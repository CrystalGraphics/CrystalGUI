package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.CgPath;
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
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.DockCommands;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;

/**
 * <b>Editor tabs</b>: their menu acts on the tab it was opened on, not the one in front, and a tab keeps the keyboard
 * when the document behind it finishes loading.
 */
public class EditorTabsTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath TOP = CgPath.of(PROJECT, "top.txt");
    private static final CgPath BOTTOM = CgPath.of(PROJECT, "bottom.txt");
    private static final CgPath NEXT = CgPath.of(PROJECT, "next.txt");
    private static final CgPath LAST = CgPath.of(PROJECT, "last.txt");

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem();
        for (CgPath path : List.of(TOP, BOTTOM, NEXT, LAST)) files.seed(path.toString(), path.toString());
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

    private DockLeaf leafOf(CgPath path) {
        DockPanelRef ref = workbench.refFor(path);
        return workbench.dock().areaHolding(ref).layout().leafContaining(ref);
    }

    /** The tab of {@code path}, as a right-click on it would be the command's source. */
    private CommandContext onTab(CgPath path) {
        DockPanelRef ref = workbench.refFor(path);
        DockArea area = workbench.dock().areaHolding(ref);
        return CommandContext.of(area.groupFor(area.layout().leafContaining(ref)).tabFor(ref));
    }

    private void openThree() {
        workbench.openFile(TOP);
        workbench.openFile(BOTTOM);
        workbench.openFile(NEXT);
        frames(12);
    }

    private List<DockPanelRef> open() {
        return workbench.dock().allPanels();
    }

    @Test
    public void closeOtherTabsKeepsTheOneRightClicked() {
        openThree();
        CommandRegistry.global().run(DockCommands.CLOSE_OTHERS, onTab(BOTTOM));
        frames(4);
        assertEquals(List.of(workbench.refFor(BOTTOM)), open());
    }

    @Test
    public void closeTabsToTheRightStopsAtTheOneRightClicked() {
        openThree();
        CommandRegistry.global().run(DockCommands.CLOSE_TO_THE_RIGHT, onTab(TOP));
        frames(4);
        assertEquals(List.of(workbench.refFor(TOP)), open());
    }

    @Test
    public void splitAndMoveTakesTheTabOutOfItsGroup() {
        openThree();
        DockLeaf before = leafOf(BOTTOM);
        CommandRegistry.global().run(DockCommands.SPLIT_AND_MOVE_RIGHT, onTab(BOTTOM));
        frames(8);
        assertTrue("it did not leave", before.indexOf(workbench.refFor(BOTTOM)) < 0);
        assertNotSame(leafOf(TOP), leafOf(BOTTOM));
        assertEquals(3, open().size());
    }

    /**
     * <b>A split puts the keyboard in the new pane</b>, as both references do. The rebuild it asks for detaches
     * whatever held focus, so without this neither pane had it.
     */
    @Test
    public void splittingFocusesTheNewPane() {
        openThree();
        DockPanelRef ref = workbench.refFor(BOTTOM);
        DockArea area = workbench.dock().areaHolding(ref);
        DockLeaf before = leafOf(BOTTOM);
        document.focus().requestPointerFocus(document.focus().firstFocusableIn(area.groupFor(before).tabFor(ref).content()));

        CommandRegistry.global().run(DockCommands.SPLIT_RIGHT, onTab(BOTTOM));
        frames(8);

        DockLeaf split = null;
        for (DockLeaf leaf : area.layout().leaves()) {
            if (leaf != before && leaf.indexOf(ref) >= 0) split = leaf;
        }
        assertNotNull("the split made a pane showing the file", split);
        UIElement focused = document.focus().focused();
        assertNotNull("something holds the keyboard", focused);
        UIElement group = area.groupFor(split);
        boolean inside = false;
        for (UIElement at = focused; at != null; at = at.parentElement()) {
            if (at == group) inside = true;
        }
        assertTrue("and it is the new pane, not the one split from", inside);
    }

    @Test
    public void moveToTheOppositeGroupAndUnsplitComeBack() {
        openThree();
        CommandRegistry.global().run(DockCommands.SPLIT_AND_MOVE_RIGHT, onTab(BOTTOM));
        frames(8);
        CommandRegistry.global().run(DockCommands.MOVE_TO_OPPOSITE, onTab(NEXT));
        frames(8);
        assertSame("the opposite group of the one it was in is the split", leafOf(BOTTOM), leafOf(NEXT));

        CommandRegistry.global().run(DockCommands.UNSPLIT, onTab(TOP));
        frames(8);
        assertEquals(1, workbench.dock().layout().leaves().size());
        assertEquals(3, open().size());
    }

    @Test
    public void openTabInNewWindowTearsItOut() {
        openThree();
        CommandRegistry.global().run(DockCommands.OPEN_IN_NEW_WINDOW, onTab(TOP));
        frames(8);
        assertEquals(1, workbench.dock().windows().size());
        assertSame(workbench.dock().windows().get(0).area(), workbench.dock().areaHolding(workbench.refFor(TOP)));
    }

    /** From the keyboard or a menu, where no tab was pressed, a tab command acts on the front tab. */
    @Test
    public void withNoTabPressedTheFrontTabIsTheSubject() {
        openThree();
        CommandRegistry.global().run(DockCommands.CLOSE_OTHERS, CommandContext.of(workbench.dock()));
        frames(4);
        assertEquals(List.of(workbench.refFor(NEXT)), open());
    }

    /** A document's view landing swaps the panel's content; the tab just clicked keeps the keyboard through it. */
    @Test
    public void aFocusedTabKeepsTheKeyboardWhenItsDocumentLands() {
        openThree();
        DockPanelRef ref = workbench.refFor(NEXT);
        UIElement tab = workbench.dock().groupFor(leafOf(NEXT)).tabFor(ref);
        document.focus().requestPointerFocus(tab);
        frames(2);
        assertSame(tab, document.focus().focused());

        workbench.dock().rebuildPanel(ref);
        frames(6);
        assertSame("rebuilding one panel's content detached the tab that held the keyboard",
                tab, document.focus().focused());
    }
}
