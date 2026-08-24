package com.crystalgui.ui;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The workbench's project index, reached the way an ENGINE reaches it — M15 S4.
 *
 * <h3>The one link nothing covered, and it shipped broken</h3>
 *
 * <p>{@code ProjectSourceResolutionTest} proves the engine half: give the registry a provider and a Java
 * file resolves a type declared in another. It registers that provider itself, so it says nothing about
 * whether a real workbench ever registers one — and a real workbench <b>did not</b>. The contribution sat
 * in {@code registerCommands}, which {@code UIElement} runs from its instance initialiser, so it executed
 * before the constructor assigned {@code projectIndex} and passed {@code null} to a method whose first
 * line is {@code if (provider == null) return}.</p>
 *
 * <p>Nothing threw and nothing logged. Every cross-file reference in the workspace reported "cannot be
 * resolved to a type" while the registry, the name environment, the project tier and 2,533 tests were all
 * correct — because "no provider" and "a provider that knows nothing" are the same answer from outside.
 * These tests assert across the seam rather than either side of it, which is the only place that shows.</p>
 */
public class WorkbenchProjectSourcesTest extends UiTestBase {

    private static final CgPath GREETER =
            CgPath.parse("mymod.proj:src/main/java/com/example/util/Greeter.java");
    private static final String GREETER_TEXT =
            "package com.example.util;\npublic class Greeter { public String greet() { return \"hi\"; } }\n";

    private InMemoryTransport<Object>[] pair;
    private ServerUiSession<Object> server;
    private ClientUiSession<Object> session;

    private UIWindow window;
    private Workbench workbench;

    @Before
    public void setUp() {
        // A CLEAN REGISTRY. It is a static list and every Workbench any earlier test built is still in it.
        ProjectSourcesRegistry.resetForTesting();

        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:src/main/java/com/example/Main.java",
                        "package com.example;\nclass Main {}\n")
                .seed(GREETER.toString(), GREETER_TEXT)
                .seed("mymod.proj:README.md", "# hello\n");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        pair = InMemoryTransport.pair();
        server = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();

        session = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
        workbench = new Workbench(new WorkspaceClient<>(session, PlainOps.INSTANCE));
        workbench.layout(l -> l.widthPercent(100f).heightPercent(100f));

        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1400, 900);
        settle();
        workbench.fileTree().loadProjects();
        settle();
    }

    @After
    public void clearRegistry() {
        ProjectSourcesRegistry.resetForTesting();
    }

    /** Frames AND both mailboxes — the crawl is a round trip, so a settle that only ticks walks nothing. */
    private void settle() {
        for (int i = 0; i < 40; i++) {
            pair[0].deliver();
            pair[1].deliver();
            session.tick();
            server.tick();
            window.updateWithoutPainting();
        }
    }

    /** What an engine sees. Deliberately the static view and not {@code workbench.projectSources()}. */
    private static ProjectSources seam() {
        return ProjectSourcesRegistry.view();
    }

    // ── The headline ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A workbench registers its index, and the registry answers about the workspace.</b>
     *
     * <p>The assertion the null defeated. Asked as {@code declaresPackage} rather than
     * {@code hasProvider()} on purpose: a provider that is present and empty resolves exactly as badly as
     * none at all, and only one of those two is what the compiler experiences.</p>
     */
    @Test
    public void theWorkspaceIndexIsReachableThroughTheEngineSeam() {
        assertTrue("the workbench never registered its project index -- an engine sees no workspace at all",
                seam().declaresPackage("com.example.util"));
    }

    /**
     * <b>Intermediate packages resolve too, including ones declaring nothing.</b>
     *
     * <p>ECJ asks about every segment of a qualified name before it looks the type up, so {@code com} —
     * which contains no file of its own — has to answer yes or {@code com.example.util.Greeter} never
     * resolves. This is the half that presents as "The import com.example.util cannot be resolved".</p>
     */
    @Test
    public void everyAncestorPackageAnswers() {
        assertTrue("com", seam().declaresPackage("com"));
        assertTrue("com.example", seam().declaresPackage("com.example"));
        assertTrue("com.example.util", seam().declaresPackage("com.example.util"));
        assertFalse("a package nothing declares must answer no",
                seam().declaresPackage("com.example.absent"));
    }

    /**
     * <b>A file nobody has open is answered from disk — eventually.</b>
     *
     * <p>Two-part on purpose, because the first part is the trap. {@code sourceOf} runs on the analysis
     * thread inside a compile and may not block on a round trip, so a miss returns null and schedules a
     * read. A test that only asserted the eventual answer would pass against an index that read the file
     * <em>synchronously</em>, which is the thing this must never do.</p>
     */
    @Test
    public void anUnopenedProjectFileIsReadAndThenAnswered() {
        assertNull("sourceOf must not block on a round trip -- the first ask answers null",
                seam().sourceOf("com.example.util.Greeter"));

        settle();

        String source = seam().sourceOf("com.example.util.Greeter");
        assertNotNull("the scheduled read never landed in the index", source);
        assertTrue("the wrong file's text: " + source, source.contains("class Greeter"));
    }

    /**
     * <b>An open document's text beats the file on disk.</b>
     *
     * <p>The property that makes cross-file resolution honest while typing: a compiler resolving against
     * saved text reports errors about code the author has already fixed, in the one place they are
     * looking. Nothing is saved here — the buffer is edited and the file left alone.</p>
     */
    @Test
    public void anOpenBufferOutranksTheSavedFile() {
        TextEditor editor = workbench.editorFor(GREETER);
        assertNotNull("no editor for the project file", editor);
        editor.setText("package com.example.util;\npublic class Greeter { /* UNSAVED */ }\n");
        settle();

        String source = seam().sourceOf("com.example.util.Greeter");
        assertNotNull(source);
        assertTrue("the saved file won over the live buffer: " + source, source.contains("UNSAVED"));
    }

    /**
     * <b>An editor is TOLD when the index fills, or the first analysis of a file stands wrong.</b>
     *
     * <p>{@code sourceOf} answers null for a file nobody has open, so the first analysis after opening a
     * file that names a sibling resolves nothing. Without an announcement the error stands until the
     * author happens to type — a cross-file reference that is broken on open and fixes itself on the next
     * keystroke, which reads as flakiness rather than as a missing signal.</p>
     *
     * <p>Counted rather than asserted-once, and the count is only checked for being non-zero: this is a
     * debounced hint that may legitimately over-fire, and pinning an exact number would fail the first
     * time the crawl landed a file in a different frame.</p>
     */
    @Test
    public void anEditorIsToldWhenTheProjectIndexFills() {
        TextEditor editor = workbench.editorFor(CgPath.parse("mymod.proj:src/main/java/com/example/Main.java"));
        assertNotNull(editor);
        Counting services = new Counting();
        editor.setLanguageServices(services);
        services.calls = 0;

        // A miss, which is what schedules the read whose landing is the thing being announced.
        seam().sourceOf("com.example.util.Greeter");
        settle();

        assertTrue("nothing told the editor its world had moved -- the first analysis of a file that "
                + "names a sibling would stay wrong until the author typed", services.calls > 0);
    }


    /** A services object that records only the one call this test is about. */
    private static final class Counting implements LanguageServices {
        int calls;

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public void environmentChanged() {
            calls++;
        }
    }
}
