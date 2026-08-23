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
import com.crystalgui.ui.elements.workbench.Workbench;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS <b>W11</b>, the view half — what a panel does when the wire moves under it.
 *
 * <p>{@code WorkspaceClient} repairs what the <em>protocol</em> needs the instant a reconnect happens,
 * because a change notification missed is missed for good. A directory listing is a different kind of
 * stale: it describes a server nobody is talking to any more, and no {@code fs.changed} can ever arrive
 * to say so — nothing was watching, because there was nothing to watch with.</p>
 *
 * <p><b>And it is repaired on the frame the panel comes back, not when the wire moves.</b> Re-fetching
 * listings for a window that is hidden is exactly the invisible work a detached window is supposed to
 * have stopped doing. The deferral needs no new mechanism: {@code ProjectFileTree}'s drain ticker returns
 * false when the element leaves the tree and {@code onLayoutChanged} registers it again on the way back,
 * so a restored panel re-lists on its first frame and one still put away does not.</p>
 */
public class WorkbenchReconnectTest extends UiTestBase {

    private static final CgPath ROOT_DIR = CgPath.parse("mymod.proj:");

    private UIWindow window;
    private UIElement root;
    private Workbench workbench;
    private WorkspaceClient<Object> client;

    private InMemoryTransport<Object> serverSide;
    private InMemoryTransport<Object> clientSide;
    private ClientUiSession<Object> clientSession;
    private ServerUiSession<Object> serverSession;

    @Before
    public void setUp() {
        client = connect(new InMemoryFileSystem()
                .seed("mymod.proj:README.md", "# hello")
                .seed("mymod.proj:before.txt", "was here"));

        workbench = new Workbench(client);
        root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        workbench.fileTree().loadProjects();
        settle();
    }

    /** A fresh wire over {@code files}, and the client that talks down it. */
    private WorkspaceClient<Object> connect(InMemoryFileSystem files) {
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverSide = pair[0];
        clientSide = pair[1];
        serverSession = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(serverSession::onCall);
        serverSession.open();
        clientSession = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
        return client == null ? new WorkspaceClient<>(clientSession, PlainOps.INSTANCE) : client;
    }

    private void settle() {
        for (int i = 0; i < 10; i++) {
            serverSide.deliver();
            clientSide.deliver();
            clientSession.tick();
            serverSession.tick();
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    /** Whether the tree's model currently holds a file of this name anywhere under the project root. */
    private boolean listingHas(String name) {
        return workbench.fileTree().source().listedChildren(ROOT_DIR).stream()
                .anyMatch(path -> path.name().equals(name));
    }

    /**
     * <b>A panel put away across a reconnect re-lists when it comes back — and not before.</b>
     *
     * <p>The second half is what the ordering buys and the first is what the wiring buys, so they are
     * asserted together: a tree that never re-listed keeps the old server's files for good, and one that
     * re-listed while detached did work for a window nobody was looking at.</p>
     */
    @Test
    public void aPanelRestoredAfterAReconnectRelistsOnItsFirstFrameBack() {
        assertTrue("the fixture never listed the project", listingHas("before.txt"));

        // HIDDEN: detaching is what hiding a window does, and it is what stops the drain ticker.
        root.removeChild(workbench);
        settle();

        serverSession.close("disconnected");
        client.rebind(connectElsewhere());
        settle();

        assertFalse("a hidden panel re-listed, which is work for a window nobody is looking at",
                listingHas("after.txt"));
        assertTrue(listingHas("before.txt"));

        // RESTORED.
        root.addChild(workbench);
        settle();

        assertTrue("the panel came back still showing the previous server's files",
                listingHas("after.txt"));
        assertFalse("the old server's listing survived the reconnect", listingHas("before.txt"));
    }

    /** Stands up a second world with a different file in it, and returns the session onto it. */
    private ClientUiSession<Object> connectElsewhere() {
        connect(new InMemoryFileSystem()
                .seed("mymod.proj:README.md", "# hello")
                .seed("mymod.proj:after.txt", "here now"));
        return clientSession;
    }
}
