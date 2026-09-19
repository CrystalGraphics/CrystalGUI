package com.crystalgui.workbench;

import static org.junit.Assert.assertSame;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.desktop.Desktop;
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
import com.crystalgui.workbench.dock.DockWindow;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;

/** <b>A file opens in the group you were last working in</b>, a split or a torn-out window alike. */
public class OpeningLandsInTheActiveGroupTest extends UiDocumentTestBase {

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
}
