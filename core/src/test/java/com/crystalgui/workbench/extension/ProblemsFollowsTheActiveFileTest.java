package com.crystalgui.workbench.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.text.diagnostic.ProblemNode;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;

/**
 * <b>The Problems panel follows the file in front.</b>
 *
 * <p>Its scope tabs were reported as dead: {@code File} was selected and the list showed every project's
 * problems, and clicking between the two tabs moved only the highlight. The panel's own logic was
 * correct — it narrows the moment it is told which file is active — and nothing ever told it.</p>
 *
 * <p>The extension subscribed to {@code onDidOpenDocument}, which fires when a file's CONTENT lands.
 * That is not a tab change: it says nothing when you click between two files that are already open, and
 * at the moment it does fire the dock may not have activated the panel yet, because groups are built a
 * frame later. So the panel was told "the file in front is nothing" and never told otherwise. The
 * comment above that subscription had described the intended behaviour all along.</p>
 */
public class ProblemsFollowsTheActiveFileTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath ONE = CgPath.of(PROJECT, "One.java");
    private static final CgPath TWO = CgPath.of(PROJECT, "Two.java");

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed(PROJECT + ":One.java", "class One { }")
                .seed(PROJECT + ":Two.java", "class Two { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        // EVERY EXTENSION, because the subject is one of them and how it is wired is the question.
        workbench = new Workbench(Workspace.of(clientEnd));
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        // SHOWN, because a tool window's view is built when its container mounts it and not before -- the
        // panel this test is about does not exist until something reveals it.
        for (int i = 0; i < 4; i++) frameAndPump();
        workbench.revealPanel(ProblemsExtension.TYPE);
        for (int i = 0; i < 8; i++) frameAndPump();
    }

    @After
    public void closeWorkbench() {
        if (workbench != null) workbench.dispose();
        if (clientEnd != null) clientEnd.close("test over");
        if (serverEnd != null) serverEnd.close("test over");
        Protocols.resetForTesting();
    }

    private void frameAndPump() {
        frame();
        link[0].deliver();
        link[1].deliver();
        serverEnd.tick();
        clientEnd.tick();
    }

    private void give(CgPath path, String message) {
        Resource resource = Resource.of(path);
        DiagnosticSet set = workbench.markers().forResource(resource);
        if (set == null) set = workbench.markers().attach(resource, new DiagnosticSet());
        set.setAll(List.of(new Diagnostic(new TextPoint(0, 0), new TextPoint(0, 1),
                DiagnosticSeverity.ERROR, message, null, null)));
    }

    /**
     * The panel, found in the tree.
     *
     * <p>Rather than through its {@code DataKey}: that resolves from the focus owner outward, and this
     * fixture focuses nothing. What is being tested is whether the panel is TOLD things, so finding the
     * instance is incidental and a walk is the fewest assumptions.</p>
     */
    private ProblemsPanel problems() {
        return find(workbench);
    }

    private static ProblemsPanel find(UIElement from) {
        if (from instanceof ProblemsPanel panel) return panel;
        for (UIElement child : from.children()) {
            ProblemsPanel found = find(child);
            if (found != null) return found;
        }
        return null;
    }

    private List<Resource> shownFiles() {
        List<Resource> out = new ArrayList<>();
        for (ProblemNode root : problems().source().roots()) out.add(root.resource());
        return out;
    }

    /**
     * Opens both files and gives each a problem.
     *
     * <p>In that order, and it matters: opening a document attaches its OWN diagnostic set under its
     * resource, replacing anything seeded beforehand — so problems written first vanish the moment the
     * file they are about arrives.</p>
     */
    private void openBothWithProblems() {
        workbench.openFile(ONE);
        for (int i = 0; i < 16; i++) frameAndPump();
        workbench.openFile(TWO);
        for (int i = 0; i < 16; i++) frameAndPump();
        give(ONE, "wrong in One");
        give(TWO, "wrong in Two");
        for (int i = 0; i < 4; i++) frameAndPump();
    }

    @Test
    public void openingAFileNarrowsTheProblemsPanelToIt() {
        openBothWithProblems();
        workbench.openFile(ONE);
        for (int i = 0; i < 16; i++) frameAndPump();

        ProblemsPanel panel = problems();
        assertNotNull("the Problems panel was never built, so this test is measuring nothing", panel);
        assertEquals("the panel does not open in file scope", true, panel.isFileScope());
        assertEquals("the panel is in file scope and showing another file's problems too",
                List.of(Resource.of(ONE)), shownFiles());
    }

    /**
     * ...and switching to another already-open file follows.
     *
     * <p>The case the missing subscription could never see: both files are open, so no content lands and
     * {@code onDidOpenDocument} says nothing at all.</p>
     */
    @Test
    public void switchingBetweenOpenFilesFollows() {
        openBothWithProblems();
        assertEquals("opening the second file did not move the panel", List.of(Resource.of(TWO)),
                shownFiles());

        workbench.openFile(ONE);
        for (int i = 0; i < 16; i++) frameAndPump();
        assertEquals("clicking back to a file that was already open left the panel on the other one",
                List.of(Resource.of(ONE)), shownFiles());
    }
}
