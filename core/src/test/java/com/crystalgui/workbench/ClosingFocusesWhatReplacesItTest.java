package com.crystalgui.workbench;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
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
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;

/**
 * <b>Closing the tab you are in hands the keyboard to the one that takes its place.</b>
 *
 * <p>Closing detaches the element holding focus and the focus service is right to forget a detached one,
 * so without an answer to "and now who has it?" {@code Ctrl+W} ends with the caret in no editor at all.
 * The dock is what knows the answer — the surviving front panel is chosen by its own rebuild — so the
 * dock is what asks for it, on the frame that decision exists.</p>
 *
 * <p>The mechanism this replaced counted twelve frames and then focused {@code activeEditor()}, which
 * carried two defects its own comment half-recorded: the countdown was a race it lost under load ("passed
 * alone and failed in the full suite"), and {@code activeEditor()} answers only for a TEXT document, so
 * closing onto a graph or an image focused nothing however long it waited.</p>
 */
public class ClosingFocusesWhatReplacesItTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed(PROJECT + ":Main.java", "class Main { }")
                .seed(PROJECT + ":Other.java", "class Other { }")
                .seed(PROJECT + ":test.shadergraph", "{}");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);
        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        workbench = new Workbench(Workspace.of(clientEnd));
        UIElement root = new UIElement().layout(l -> l.width(1400).height(900));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        viewport(1400, 900);
        for (int i = 0; i < 3; i++) frameAndPump();
    }

    @After
    public void close() {
        Protocols.resetForTesting();
    }

    private void frameAndPump() {
        frame();
        link[0].deliver();
        link[1].deliver();
        serverEnd.tick();
        clientEnd.tick();
    }

    private void settle() {
        for (int i = 0; i < 20; i++) frameAndPump();
    }

    private void open(String name) {
        workbench.openResource(Resource.of(CgPath.parse(PROJECT + ":" + name)));
        settle();
    }

    @Test
    public void closingTheFocusedTabFocusesTheEditorThatReplacesIt() {
        open("Main.java");
        open("Other.java");
        UIElement closing = document.focus().focused();
        assertTrue("the tab just opened has the keyboard", closing instanceof TextEditor);

        workbench.dock().closePanel(refFor("Other.java"));
        settle();

        UIElement focused = document.focus().focused();
        assertNotNull("the keyboard did not fall on the floor", focused);
        assertSame("the file that took its place has it", contentOf(refFor("Main.java")), focused);
    }

    /**
     * ...and the same when what replaces it is not a text editor.
     *
     * <p>The reason the answer cannot be {@code activeEditor()}: a graph is an editor with no lines, and
     * asking for a {@code TextEditor} answers null for it however long you wait.</p>
     */
    @Test
    public void closingOntoAGraphFocusesTheGraph() {
        open("test.shadergraph");
        open("Main.java");

        workbench.dock().closePanel(refFor("Main.java"));
        settle();

        UIElement focused = document.focus().focused();
        assertTrue("the graph itself took the keyboard, not its panel root: " + focused,
                focused instanceof GraphView);
        assertTrue("...and it is the one this tab holds",
                isInside(focused, contentOf(refFor("test.shadergraph"))));
    }

    /**
     * <b>Closing fills a vacancy; it never takes one.</b>
     *
     * <p>Closing a background tab from a menu, or closing one while the caret is somewhere else
     * entirely, leaves focus exactly where the user put it. This is the half that stops the fix from
     * being the auto-focus coupling the project tree had taken out of it.</p>
     */
    @Test
    public void closingABackgroundTabLeavesTheKeyboardAlone() {
        open("Main.java");
        open("Other.java");

        Button elsewhere = new Button("elsewhere");
        document.append(elsewhere);
        frame();
        document.focus().requestPointerFocus(elsewhere);
        frame();

        // NOT the one in front, and not the one holding the keyboard either.
        workbench.dock().closePanel(refFor("Main.java"));
        settle();

        assertSame("nothing was taken", elsewhere, document.focus().focused());
    }

    /** Whether {@code node} is {@code ancestor} or sits under it. */
    private static boolean isInside(UIElement node, UIElement ancestor) {
        for (UIElement at = node; at != null; at = at.composedParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }

    private DockPanelRef refFor(String name) {
        return workbench.refFor(CgPath.parse(PROJECT + ":" + name));
    }

    /** What the dock has built for {@code ref}. */
    private UIElement contentOf(DockPanelRef ref) {
        for (DockLeaf leaf : workbench.dock().layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            var group = workbench.dock().groupFor(leaf);
            return group == null ? null : group.builtContentFor(ref);
        }
        return null;
    }
}
