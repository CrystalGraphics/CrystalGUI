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
import com.crystalgui.ui.elements.dock.DockCommands;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Opening a file, and acting on the tab it opened in.
 *
 * <p>Two defects, reported together and unrelated except in how they present: a file opens, the status
 * line says so, and the tab is <b>empty</b>; and {@code Ctrl+W} closes nothing unless you first click the
 * tab's own header.</p>
 *
 * <h3>The read is deliberately not exercised here</h3>
 *
 * <p>These drive the editor through {@link Workbench#editorFor} and {@link Workbench#openPanel} rather
 * than through {@link Workbench#openFile}, so no RPC round-trip is involved. Not a shortcut: in the
 * harness the read plainly <em>worked</em> — the status line read "opened README.md" — and the pane was
 * blank anyway. Routing these through the network would put a second, slower, flakier thing between the
 * assertion and the defect, and the defect is not in the network. {@code WorkspaceClientTest} covers the
 * transport end to end, headlessly, where that belongs.</p>
 */
public class WorkbenchFileTabTest extends UiTestBase {

    private static final String README = "# hello\nsecond line\nthird line\n";

    private UIWindow window;
    private Workbench workbench;

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", README)
                .seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> server =
                new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(new ClientUiSession<>(pair[1], PlainOps.INSTANCE), PlainOps.INSTANCE);
    }

    @Before
    public void setUp() {
        workbench = new Workbench(client());
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        DockCommands.install(window);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    /** A file tab, with content, and no network in the way. */
    private TextEditor openWithContent(CgPath path) {
        TextEditor editor = workbench.editorFor(path);
        editor.setText(README);
        workbench.openPanel(Workbench.refFor(path));
        settle();
        return editor;
    }

    /**
     * <b>The tab is not empty.</b>
     *
     * <p>A {@link TextEditor}'s lines are absolutely positioned inside its text viewport, so the widget
     * contributes <b>nothing</b> to its own intrinsic height — the same shape as {@code CanvasView} and its
     * transformed plane. Dropped into a dock pane with no rule sizing it, it resolved to zero and painted a
     * blank pane while holding the file perfectly well: the read succeeded, the status line said so, and
     * there was nothing on screen.</p>
     *
     * <p>Asserted on the laid-out box, never on {@code getText()} — the text was never what was missing,
     * and a test that checked it would have gone green over a blank pane.</p>
     */
    @Test
    public void anOpenedFileHasAVisibleEditor() {
        TextEditor editor = openWithContent(CgPath.parse("mymod.proj:README.md"));

        assertNotNull("the editor is not in the tree", editor.getParent());
        assertTrue("the editor laid out ZERO pixels tall -- the tab renders empty",
                editor.getRuntimeCache().getHeight() > 0f);
        assertTrue("the editor laid out ZERO pixels wide",
                editor.getRuntimeCache().getWidth() > 0f);
    }

    /**
     * <b>Focusing something inside a group makes that group the active one.</b>
     *
     * <p>Every dock command acts on {@code DockArea.activeGroup()}, and that was set only by the tab
     * strip's own selection listener — so focus could sit in an editor while the "active" group was
     * whichever <em>header</em> was last clicked. {@code Ctrl+W} then closed a panel in another pane, or
     * nothing at all, and the workaround looked like "click the tab first".</p>
     *
     * <p>The active group is pointed elsewhere first, so this cannot pass on whatever {@code openPanel}
     * happened to leave behind.</p>
     */
    @Test
    public void focusingAFileEditorMakesItsGroupActive() {
        CgPath path = CgPath.parse("mymod.proj:README.md");
        TextEditor editor = openWithContent(path);

        workbench.dock().setActiveGroup(workbench.dock().groupFor(
                workbench.dock().layout().leaves().get(0)));
        settle();

        window.getInputHandler().requestPointerFocus(editor);
        settle();

        assertNotNull(workbench.dock().activeGroup());
        assertEquals("focus is inside the editor but the active group is elsewhere -- "
                        + "every dock command acts on the wrong pane",
                path.toString(),
                workbench.dock().activeGroup().leaf().activePanel().state(Workbench.PATH_STATE, ""));
    }
}
