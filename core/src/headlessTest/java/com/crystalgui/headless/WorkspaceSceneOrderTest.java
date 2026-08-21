package com.crystalgui.headless;

import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectInfo;
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
import com.crystalgui.ui.UIElement;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * <b>A call made before the client has a window is DROPPED — wait for the session.</b>
 *
 * <p>{@code ClientUiSession.windowId} is {@code -1} until {@code OpenWindow} arrives, and
 * {@code ServerUiSession} discards any packet whose window id is not its own. That is correct — every
 * packet carries a window id precisely so one in flight when a GUI closes cannot land on the next — but
 * it means <b>a client must not call before it is connected</b>, and nothing says so at the call site.</p>
 *
 * <p>Written because the harness scene did exactly that: it built both sessions in {@code init()} and
 * asked for the project list immediately, with no frame yet to pump the transport. The tree came up
 * empty with no error at all. Every other test here happens to pump during setup before calling, so the
 * ordering was never covered.</p>
 */
public class WorkspaceSceneOrderTest {

    private record Rig(ServerUiSession<Object> server, ClientUiSession<Object> session,
                       WorkspaceClient<Object> client, InMemoryTransport<Object>[] pair) {

        void pump(int times) {
            for (int i = 0; i < times; i++) {
                pair[0].deliver();
                pair[1].deliver();
                session.tick();
                server.tick();
            }
        }
    }

    private static Rig rig() {
        InMemoryFileSystem files = new InMemoryFileSystem().seed("harness.scratch:README.md", "# hi");
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("harness.scratch", "Scratch", Paths.get("/srv/scratch"))));
        WorkspaceService service =
                new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> server =
                new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();

        ClientUiSession<Object> session = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
        return new Rig(server, session, new WorkspaceClient<>(session, PlainOps.INSTANCE), pair);
    }

    /**
     * The trap this class was written for is <b>gone</b>, and that is the assertion now.
     *
     * <p>It used to read: called too early, the request never arrives and nothing reports it. The cause
     * was that an RPC travelled as {@code UIPacket.RpcCall}, which carried a {@code windowId} because
     * every packet did — so before the window existed the call went out addressed to window {@code -1}
     * and the server's {@code packet.windowId() != windowId} guard silently discarded it. Nothing
     * surfaced until the RPC timeout, which is why this needed pinning.</p>
     *
     * <p>On the envelope an RPC is an ordinary REQUEST and carries no window at all, because a workspace
     * call was never a window concern — it only looked like one while it was tunnelled through a UI
     * packet. The guard still exists and still protects {@code ui/*} messages, which are genuinely
     * per-window. So the ordering trap disappears rather than being fixed: there is no longer a wrong
     * moment to call.</p>
     */
    @Test
    public void aCallBeforeTheWindowExistsNowArrives() {
        Rig rig = rig();
        assertEquals("the client has no window yet", -1, rig.session().windowId());

        AtomicReference<List<ProjectInfo>> got = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Failure> failed = new AtomicReference<>();
        rig.client().projects(got::set, failed::set);
        rig.pump(10);

        assertNotNull("an RPC no longer depends on a window — failure was " + failed.get(), got.get());
        assertEquals(1, got.get().size());
        org.junit.Assert.assertNull(failed.get());
    }

    /** Still true, and no longer the only pattern that works. */
    @Test
    public void aCallAfterTheWindowIsEstablishedArrives() {
        Rig rig = rig();
        rig.pump(4);
        assertEquals("the window is now established", 1, rig.session().windowId());

        AtomicReference<List<ProjectInfo>> got = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Failure> failed = new AtomicReference<>();
        rig.client().projects(got::set, failed::set);
        rig.pump(4);

        assertNotNull("the call must arrive — failure was " + failed.get(), got.get());
        assertEquals(1, got.get().size());
        assertEquals("harness.scratch", got.get().get(0).id());
    }
}
