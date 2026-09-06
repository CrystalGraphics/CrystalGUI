package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;

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

/**
 * <b>The explorer watches its project roots — and did not, on any host, ever.</b>
 *
 * <p>{@code Workbench}'s constructor took one recursive watch per project root, with a comment saying
 * exactly why it matters: <i>"the server pushes fs.changed for anything watched; a create or delete
 * elsewhere shows up here without the tree knowing who did it"</i>. The roots come from the project
 * listing, which is asked for from {@code tickFrame} — after attach, and after a session has opened,
 * because the server discards a call addressed to a window that does not exist yet. So at construction
 * the list is empty, on every host, always: the loop ran over nothing and subscribed to nothing.</p>
 *
 * <p>Nothing failed. Per-document watches are a separate subscription that {@code WorkspaceDocuments}
 * takes for each open file, so a change to a file <em>in a tab</em> still arrived — which is why this
 * reads as "the tree is a bit stale sometimes" rather than as a subscription that was never made.</p>
 *
 * <p>The assertion is on the watch's own listener count rather than on a change arriving, because
 * {@code Workspace.watch} answers the same object to every caller: this test holds one handle on the
 * root, and what it counts is whether the workbench added itself to it.</p>
 */
public class WorkbenchWatchesProjectRootsTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath ROOT = CgPath.of(PROJECT, "");

    private Workbench workbench;
    private Workspace workspace;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();

        InMemoryFileSystem files = new InMemoryFileSystem().seed(PROJECT + ":Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        workspace = Workspace.of(clientEnd);
        workbench = new Workbench(workspace);
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    @After
    public void closeWorkbench() {
        Protocols.resetForTesting();
    }

    /** A frame, and one tick of the wire — the harness's own loop. */
    private void frameAndPump() {
        frame();
        link[0].deliver();
        link[1].deliver();
        serverEnd.tick();
        clientEnd.tick();
    }

    @Test
    public void aWorkbenchWatchesEveryProjectRootOnceTheListingLands() {
        // OUR OWN HANDLE ON THE SAME SUBSCRIPTION. Repeating a watch answers the object that already
        // exists, so this is the very Watch the workbench will find -- and holding it before anything
        // else does is what makes the "before" reading meaningful.
        Workspace.Watch root = workspace.watch(Resource.of(ROOT), true);
        assertEquals("nothing has listened to the root before the listing lands",
                0, root.onChanged.connectionCount());

        for (int i = 0; i < 8; i++) frameAndPump();

        assertEquals("the listing landed and the tree has its root",
                List.of(ROOT), workbench.projects().roots());
        assertEquals("the workbench watches the project root it was just given -- taking the watch in "
                        + "the constructor watched an empty list",
                1, root.onChanged.connectionCount());

        // AND LETS GO OF IT. A Watch is shared by everything that asked for the same resource, so the
        // subscription itself survives here -- this test is still holding one -- and what must not
        // survive is the workbench's listener on it, which is the end that keeps the workbench, its
        // dock and its documents reachable.
        workbench.dispose();
        assertEquals("a disposed workbench has let go of the root it was watching",
                0, root.onChanged.connectionCount());
    }
}
