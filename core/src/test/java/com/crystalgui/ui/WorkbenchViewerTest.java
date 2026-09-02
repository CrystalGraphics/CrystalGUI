package com.crystalgui.ui;

import com.crystalgui.support.OldEngineSessions;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceRegistry;
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
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

import dev.vfyjxf.taffy.style.FlexDirection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>The viewer lane — a tab for something the workspace does not contain.</b>
 *
 * <p>Ctrl+B into {@code ArrayList} used to do nothing, and {@code Workbench} was the second of the two
 * reasons: the editor announced the jump, and the handler read
 * {@code if (!site.resource().isProject()) return;}. The engine's half is
 * {@code JavaDocBodyTest}'s; this is the half that opens something.</p>
 *
 * <h3>Driven through a stub provider, deliberately</h3>
 *
 * <p>The real provider is in {@code language/}, which {@code core/} cannot depend on and must not: the
 * whole point of {@code ResourceRegistry} is that the shell opens a scheme without knowing what fills
 * it. Registering a stub here tests exactly the contract the shell relies on, and would keep passing if
 * the Java engine were replaced tomorrow.</p>
 */
public class WorkbenchViewerTest extends UiTestBase {

    private static final String SOURCE = ""
            + "package java.util;\n"
            + "\n"
            + "public class ArrayList<E> {\n"
            + "    public boolean add(E e) { return true; }\n"
            + "}\n";

    private static final Resource ARRAY_LIST =
            Resource.of(Resource.SCHEME_LIBRARY, "java.util.ArrayList");

    private UIWindow window;
    private Workbench workbench;
    private JobScheduler scheduler;
    private final AtomicInteger reads = new AtomicInteger();

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem().seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<UIElement, Object> server =
                OldEngineSessions.serve(1, new UIElement(), pair[0]);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(OldEngineSessions.view(pair[1]), PlainOps.INSTANCE);
    }

    @Before
    public void setUp() {
        ResourceRegistry.resetForTesting();
        ResourceRegistry.register(Resource.SCHEME_LIBRARY, resource -> {
            reads.incrementAndGet();
            return "java.util.ArrayList".equals(resource.path())
                    ? SOURCE.getBytes(StandardCharsets.UTF_8) : new byte[0];
        });

        // ITS OWN SCHEDULER, RUNNING ON THIS THREAD. The shared pool is a static, so a job this test
        // submits can complete during the NEXT test's drain -- which is what made this class pass alone
        // and fail as a whole three separate times. `JobScheduler`'s own note prescribes exactly this.
        scheduler = new JobScheduler(Runnable::run, System::currentTimeMillis, 1);
        workbench = new Workbench(client()).setJobScheduler(scheduler);
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        settle();
    }

    @After
    public void tearDown() {
        ResourceRegistry.resetForTesting();
    }

    /**
     * The read is scheduled, so the frames have to include a drain.
     *
     * <p>{@code JobScheduler.onDone} runs on the UI thread inside {@code drain()} — that is the whole
     * reason the read may leave the UI thread at all — so a test that only paints frames would watch an
     * empty editor forever and conclude the provider was never asked.</p>
     */
    private void settle() {
        for (int i = 0; i < 12; i++) {
            scheduler.drain();
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    /** <b>A library resource opens, in a tab, with the provider's text in it.</b> */
    @Test
    public void aLibraryResourceOpensWithItsContent() {
        workbench.openResource(ARRAY_LIST, null);
        settle();

        TextEditor viewer = viewer();
        assertNotNull("no viewer tab was opened at all", viewer);
        assertTrue("the viewer is empty -- the provider was not asked, or its answer was dropped",
                viewer.getText().contains("public boolean add"));
    }

    /**
     * <b>It cannot be typed into.</b>
     *
     * <p>The point of the whole feature, and the one thing that must not regress quietly: an editable
     * viewer looks correct until somebody edits a JDK class and wonders where it saved to.</p>
     */
    @Test
    public void theViewerRefusesEdits() {
        workbench.openResource(ARRAY_LIST, null);
        settle();

        TextEditor viewer = viewer();
        assertNotNull(viewer);
        assertTrue("a viewer must be read-only", viewer.isReadOnly());
        String before = viewer.getText();
        viewer.insertAtCaret("nonsense");
        assertEquals("the viewer accepted an edit", before, viewer.getText());
    }

    /**
     * <b>Opening the same resource twice reuses the tab, and does not re-read.</b>
     *
     * <p>Reusing is what every other tab does. Not re-reading matters more than it looks: the same call
     * is a decompile later, and a viewer that re-decompiled on every activation would stutter on a tab
     * click.</p>
     */
    @Test
    public void openingTwiceReusesTheTabAndTheContent() {
        workbench.openResource(ARRAY_LIST, null);
        settle();
        int afterFirst = reads.get();
        assertTrue("the provider was never asked", afterFirst >= 1);

        workbench.openResource(ARRAY_LIST, null);
        settle();
        assertEquals("the second open re-read the resource", afterFirst, reads.get());
        assertEquals("a second tab was opened for the same resource", 1, viewerTabs());
    }

    /**
     * <b>The continuation runs after the text has landed, not before.</b>
     *
     * <p>What go-to-declaration hangs on: revealing a row in an editor that has not been filled scrolls
     * an empty document and the caret ends up at the top of the file. Asserted by checking the buffer
     * from inside the callback, which is the only place the ordering is observable.</p>
     */
    @Test
    public void theCallbackRunsOnceThereIsSomethingToRevealIn() {
        AtomicInteger textAtCallback = new AtomicInteger(-1);
        workbench.openResource(ARRAY_LIST,
                () -> textAtCallback.set(viewer() == null ? -1 : viewer().getText().length()));
        settle();

        assertTrue("the callback never ran", textAtCallback.get() >= 0);
        assertEquals("the callback ran before the text arrived", SOURCE.length(), textAtCallback.get());
    }

    /**
     * <b>A declaration site announced by an editor opens the viewer.</b>
     *
     * <p>The end-to-end path, and the line that used to return: {@code onDefinitionChosen} with a
     * non-project resource. Driven by emitting the signal rather than by resolving a symbol, because the
     * engine's half has its own test and {@code core/} has no engine to resolve with.</p>
     */
    @Test
    public void aNonProjectDeclarationSiteOpensAViewer() {
        TextEditor editor = workbench.editorFor(com.crystalgui.fs.CgPath.parse("mymod.proj:src/Main.java"));
        editor.onDefinitionChosen.emit(DeclarationSite.inLibrary(
                "java.util.ArrayList", new TextPoint(3, 19), new TextPoint(3, 22)));
        settle();

        TextEditor viewer = viewer();
        assertNotNull("a non-project site was dropped instead of opening a viewer", viewer);
        assertTrue(viewer.getText().contains("public boolean add"));
    }

    /** <b>An unclaimed scheme opens nothing</b>, which is what a host with no engine does. */
    @Test
    public void anUnclaimedSchemeOpensNothing() {
        workbench.openResource(Resource.of("nobody-claims-this", "x.y.Z"), null);
        settle();
        assertEquals("a scheme nothing registered opened a tab anyway", 0, viewerTabs());
    }

    /** <b>A tab title is the simple name</b>, or one tab pushes every other off the strip. */
    @Test
    public void theTabIsTitledWithTheSimpleName() {
        assertEquals("ArrayList",
                workbench.refForResource(ARRAY_LIST).state(
                        com.crystalgui.ui.elements.dock.DockPanelRef.TITLE, ""));
    }

    private TextEditor viewer() {
        return find(workbench, Workbench.VIEWER_CLASS);
    }

    private int viewerTabs() {
        int count = 0;
        for (DockLeaf leaf : workbench.dock().layout().leaves()) {
            for (int i = 0; i < leaf.panels().size(); i++) {
                if (Workbench.VIEWER_TYPE.equals(leaf.panels().get(i).typeId())) count++;
            }
        }
        return count;
    }

    /** The first descendant carrying {@code styleClass}, or null. */
    private static TextEditor find(UIElement root, String styleClass) {
        if (root instanceof TextEditor && root.getClasses().contains(styleClass)) {
            return (TextEditor) root;
        }
        for (UIElement child : root.getChildren()) {
            TextEditor found = find(child, styleClass);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * <b>A jump FROM inside a viewer opens another viewer.</b>
     *
     * <p>The gap this closes: {@code viewerFor} built its editor and never connected
     * {@code onDefinitionChosen}, so Ctrl+B inside a library class emitted into a signal nobody was
     * listening to — and so did the documentation popup's Jump to Source, which is the same call one
     * layer up. Both read as resolution failing, while a hover in the very same file was drawing the
     * symbol's full documentation: the engine had the answer throughout and nothing was carrying it.</p>
     *
     * <p>Drilling onward is not an edge case — it is what reading a library IS. From {@code ArrayList}
     * into {@code List}, from {@code List} into {@code Collection}. A viewer that can be entered and not
     * left is a dead end.</p>
     */
    @Test
    public void aJumpFromInsideAViewerOpensAnotherViewer() {
        workbench.openResource(ARRAY_LIST, null);
        settle();
        TextEditor viewer = viewer();
        assertNotNull("the first viewer did not open", viewer);

        Resource other = Resource.of(Resource.SCHEME_LIBRARY, "java.util.List");
        viewer.onDefinitionChosen.emit(DeclarationSite.inLibrary(
                "java.util.List", new TextPoint(0, 0), new TextPoint(0, 4)));
        settle();

        assertEquals("the jump out of a viewer went nowhere", 2, viewerTabs());
        assertTrue("the second viewer is not the one that was asked for",
                workbench.dock().layout().leaves().stream().anyMatch(
                        leaf -> leaf.indexOf(workbench.refForResource(other)) >= 0));
    }
}
