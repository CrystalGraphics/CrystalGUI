package com.crystalgui.workbench;

import com.crystalgui.document.EditorInput;
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
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.editor.TextEditorView;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * <b>A split shows one document in two panes, each with its own editor.</b> One buffer and one history; a caret,
 * a selection and a scroll per pane — as VS Code's editor groups and IntelliJ's split windows both do.
 */
public class SplitEditorTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath FILE = CgPath.of(PROJECT, "file.txt");

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem();
        files.seed(FILE.toString(), "one two three");
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
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        workbench.openFile(FILE);
        frames(12);
        DockPanelRef ref = workbench.refFor(FILE);
        // WHAT "Split Right" DOES: the same panel in a new group beside.
        workbench.dock().layout().drop(left(), DockDropZone.SPLIT_RIGHT, new DockLeaf(ref));
        workbench.dock().requestRebuild();
        frames(12);
    }

    @After
    public void closeWorkbench() {
        workbench.dispose();
        clientEnd.close("test over");
        serverEnd.close("test over");
        Protocols.resetForTesting();
    }

    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            frame();
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    private DockLeaf left() {
        return workbench.dock().layout().leaves().get(0);
    }

    private DockLeaf right() {
        return workbench.dock().layout().leaves().get(1);
    }

    private UIElement shownIn(DockLeaf leaf) {
        return workbench.dock().groupFor(leaf).builtContentFor(workbench.refFor(FILE));
    }

    private TextEditor editorIn(DockLeaf leaf) {
        for (EditorService.View view : tab().views()) {
            if (shownIn(leaf) == view.element() || shownIn(leaf).contains(view.element())) {
                return ((TextEditorView) view.editor()).editor();
            }
        }
        throw new AssertionError("no view is shown in " + leaf);
    }

    private EditorService.Tab tab() {
        return workbench.editors().tabFor(EditorInput.of(Resource.of(FILE)));
    }

    @Test
    public void bothPanesShowTheFileEachWithItsOwnEditor() {
        assertEquals(2, workbench.dock().layout().leaves().size());
        assertNotNull("the left pane went blank", shownIn(left()));
        assertNotNull(shownIn(right()));
        assertNotSame("both panes were handed one element", shownIn(left()), shownIn(right()));
        assertEquals(2, tab().views().size());
        assertSame("two buffers: an edit in one pane would not be in the other",
                editorIn(left()).buffer(), editorIn(right()).buffer());
    }

    @Test
    public void aCaretMovedInOnePaneStaysPutInTheOther() {
        editorIn(left()).setCaret(0);
        editorIn(right()).setCaret(6);
        assertEquals(0, editorIn(left()).getCaret());
        assertEquals(6, editorIn(right()).getCaret());
    }

    /**
     * Focus moving between the panes of a split is a different editor in front, though the panel and the document are
     * the same: what follows the active editor — the Hierarchy, the Inspector — has to move to the pane now worked in.
     */
    @Test
    public void workingInTheOtherPaneMakesItsEditorTheActiveOne() {
        List<EditorService.Tab> announced = new ArrayList<>();
        workbench.editors().onDidChangeActive.connect(announced::add);

        workbench.dock().setActiveGroup(workbench.dock().groupFor(right()));
        frames(2);
        assertSame(editorIn(right()), ((TextEditorView) tab().editor()).editor());
        assertFalse("nothing following the active editor heard it", announced.isEmpty());

        workbench.dock().setActiveGroup(workbench.dock().groupFor(left()));
        frames(2);
        assertSame(editorIn(left()), ((TextEditorView) tab().editor()).editor());
    }

    @Test
    public void closingOnePaneLeavesTheOtherOpen() {
        TextEditor survivor = editorIn(left());
        workbench.dock().closePanel(right(), workbench.refFor(FILE));
        frames(8);
        assertEquals(1, workbench.dock().layout().leaves().size());
        assertNotNull("closing one pane closed the document", tab());
        assertEquals(1, tab().views().size());
        assertSame("the remaining pane's editor was rebuilt", survivor, editorIn(left()));
    }
}
