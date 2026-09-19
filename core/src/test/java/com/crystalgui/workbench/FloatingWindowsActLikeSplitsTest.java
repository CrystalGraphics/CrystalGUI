package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.document.DraggedResources;
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
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.DockWindow;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.editor.EditorService;

/** <b>A torn-out window is a split in another window</b>: what a group does, it does there too. */
public class FloatingWindowsActLikeSplitsTest extends UiDocumentTestBase {

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

    @Test
    public void aSplitThatWasWorkedInGetsTheNextFile() {
        workbench.openFile(TOP);
        frames(12);
        DockPanelRef bottom = workbench.refFor(BOTTOM);
        workbench.dock().layout().drop(leafOf(TOP), DockDropZone.SPLIT_DOWN, new DockLeaf(bottom));
        workbench.dock().requestRebuild();
        frames(12);
        workbench.dock().activatePanel(bottom);
        frames(4);

        workbench.openFile(NEXT);
        frames(12);
        assertSame("opened in the central group instead of the one in use", leafOf(BOTTOM), leafOf(NEXT));
    }

    @Test
    public void aTornOutWindowThatWasWorkedInGetsTheNextFile() {
        workbench.openFile(TOP);
        workbench.openFile(BOTTOM);
        frames(12);
        DockPanelRef bottom = workbench.refFor(BOTTOM);
        workbench.dock().layout().leafContaining(bottom).remove(bottom);
        workbench.dock().requestRebuild();
        DockWindow torn = new DockWindow(workbench.dock(), DockLayout.of(new DockLeaf(bottom)), "bottom.txt");
        Desktop.of(document).addWindow(torn);
        frames(12);
        workbench.dock().activatePanel(bottom);
        frames(4);

        workbench.openFile(NEXT);
        frames(12);
        assertSame("opened behind the window being worked in", leafOf(BOTTOM), leafOf(NEXT));

        // BACK IN THE MAIN WINDOW, the next file follows.
        workbench.dock().activatePanel(workbench.refFor(TOP));
        frames(4);
        workbench.openFile(LAST);
        frames(12);
        assertSame(leafOf(TOP), leafOf(LAST));
    }

    /** Tears {@code path}'s tab out of the main dock into a window of its own. */
    private DockWindow tearOut(CgPath path) {
        DockPanelRef ref = workbench.refFor(path);
        workbench.dock().layout().leafContaining(ref).remove(ref);
        workbench.dock().requestRebuild();
        DockWindow torn = new DockWindow(workbench.dock(), DockLayout.of(new DockLeaf(ref)), path.toString());
        Desktop.of(document).addWindow(torn);
        frames(12);
        return torn;
    }

    private EditorService.Tab tabOf(CgPath path) {
        return workbench.editors().tabFor(EditorInput.of(Resource.of(path)));
    }

    @Test
    public void aRestoredWindowShowsItsEditorsOnceTheyLoad() {
        workbench.openFile(TOP);
        workbench.openFile(BOTTOM);
        frames(12);
        tearOut(BOTTOM);
        WorkbenchSession session = new WorkbenchSession(workbench);
        String record = session.toJson(1200, 800);

        for (DockPanelRef panel : workbench.dock().allPanels()) workbench.dock().closePanel(panel);
        frames(8);
        assertTrue(workbench.dock().windows().isEmpty());
        assertNull(tabOf(BOTTOM));

        assertTrue(session.fromJson(record));
        session.reopenTornOutWindows();
        frames(16);

        assertEquals(1, workbench.dock().windows().size());
        EditorService.Tab tab = tabOf(BOTTOM);
        assertNotNull("the window's document was never read", tab);
        assertNotNull(tab.editor());
        assertSame("the window still shows what stood in while the read was in flight",
                tab.viewElement(), workbench.dock().builtContentFor(workbench.refFor(BOTTOM)));
    }

    @Test
    public void closingATabInAWindowReleasesItsDocument() {
        workbench.openFile(TOP);
        workbench.openFile(BOTTOM);
        frames(12);
        DockWindow torn = tearOut(BOTTOM);
        assertNotNull(tabOf(BOTTOM));

        torn.area().closePanel(workbench.refFor(BOTTOM));
        frames(8);
        assertNull("the document outlived its last tab", tabOf(BOTTOM));
        assertTrue("the emptied window stayed open", workbench.dock().windows().isEmpty());
    }

    /** Drags {@code files} from nowhere in particular and drops them at {@code (fx, fy)} of {@code leaf}'s group. */
    private void dropFiles(DockArea area, DockLeaf leaf, float fx, float fy, CgPath... files) {
        DockGroup group = area.groupFor(leaf);
        Box box = group.box();
        float x = box.worldX() + box.width() * fx;
        float y = box.worldY() + box.height() * fy;
        List<Resource> resources = new ArrayList<>();
        for (CgPath file : files) resources.add(Resource.of(file));
        // FROM OUTSIDE THE DOCK, as the Project panel is: a drag never targets its own source.
        UIElement source = new UIElement().layout(l -> l.width(4).height(4));
        document.append(source);
        frame();
        Drag.start(source, 1f, 1f, CgMouseCodes.LEFT_BUTTON, new DraggedResources(resources),
                Drag.DEFAULT_THRESHOLD_PX, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }

                    @Override
                    public void onDragEnd(float ex, float ey) {
                    }
                });
        // THROUGH THE INPUT, whose pointer is what a drag event carries.
        move(x, y);
        frame();
        release(x, y, CgMouseCodes.LEFT_BUTTON);
        source.removeSelf();
        frames(12);
    }

    @Test
    public void aFileDroppedInAGroupOpensThere() {
        workbench.openFile(TOP);
        frames(12);
        dropFiles(workbench.dock(), leafOf(TOP), 0.5f, 0.5f, NEXT);
        assertSame("the middle of a group merges into it", leafOf(TOP), leafOf(NEXT));
        assertNotNull("dropped and never read", tabOf(NEXT).editor());
    }

    @Test
    public void aFileDroppedNearAnEdgeSplitsThere() {
        workbench.openFile(TOP);
        frames(12);
        DockLeaf top = leafOf(TOP);
        dropFiles(workbench.dock(), top, 0.8f, 0.5f, NEXT);
        assertTrue("four fifths across did not split", leafOf(NEXT) != top);
        assertSame(workbench.dock(), workbench.dock().areaHolding(workbench.refFor(NEXT)));
    }

    @Test
    public void aFileDroppedOnAWindowOpensInIt() {
        workbench.openFile(TOP);
        workbench.openFile(BOTTOM);
        frames(12);
        DockWindow torn = tearOut(BOTTOM);
        dropFiles(torn.area(), leafOf(BOTTOM), 0.5f, 0.5f, NEXT);
        assertSame(leafOf(BOTTOM), leafOf(NEXT));
    }

    @Test
    public void anOpenFileDroppedElsewhereMovesItsTab() {
        workbench.openFile(TOP);
        workbench.openFile(BOTTOM);
        frames(12);
        DockWindow torn = tearOut(TOP);
        dropFiles(torn.area(), leafOf(TOP), 0.5f, 0.5f, BOTTOM);
        assertSame("a second copy, or none moved", leafOf(TOP), leafOf(BOTTOM));
        assertSame(torn.area(), workbench.dock().areaHolding(workbench.refFor(BOTTOM)));
        assertEquals(1, workbench.dock().allPanels().stream().filter(workbench.refFor(BOTTOM)::equals).count());
    }

    /** How tabs overflow is one choice for every group: the main dock's, a window's, and one split off later. */
    @Test
    public void theTabOverflowReachesEveryGroup() {
        workbench.openFile(TOP);
        workbench.openFile(BOTTOM);
        frames(12);
        DockWindow torn = tearOut(BOTTOM);
        workbench.dock().setTabOverflow(TabView.TabOverflow.WRAP);
        assertEquals(TabView.TabOverflow.WRAP, workbench.dock().groupFor(leafOf(TOP)).tabView().tabOverflow());
        assertEquals(TabView.TabOverflow.WRAP, torn.area().groupFor(leafOf(BOTTOM)).tabView().tabOverflow());

        workbench.dock().layout().drop(leafOf(TOP), DockDropZone.SPLIT_RIGHT, new DockLeaf(workbench.refFor(NEXT)));
        workbench.dock().requestRebuild();
        frames(12);
        assertTrue("a group built after the choice did not take it",
                workbench.dock().groupFor(leafOf(NEXT)).tabView().hasClass(TabView.WRAP_CLASS));
    }
}
