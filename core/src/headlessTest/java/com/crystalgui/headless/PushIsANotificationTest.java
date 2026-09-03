package com.crystalgui.headless;

import com.crystalgui.fs.CgFileEvent;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.CgFileEventSource;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan_fs_rewrite.md} F2, N24 — <b>a push is a notification, and there is nothing to answer.</b>
 *
 * <p>{@code fs.changed}, {@code fs.presence} and {@code fs.capabilities} were sent as requests. The
 * client had registered them through {@code onRequest}, so a {@code notify} found nobody home — and the
 * fix went in on the sending side, with a javadoc on {@code notifyCapabilities} instructing every
 * caller to send a request. Every push then cost the server a pending entry and a ten-second timeout
 * slot, waiting for an answer that carried no information. For ten open files that is a pending request
 * per file per change, per peer.</p>
 *
 * <p><b>Asserted on {@code MessageRouter.pendingRequests()}</b>, because the payload arrives either
 * way: a test that checks the client got the change passes against both versions, which is exactly why
 * the request shape survived five suites that all exercise the push.</p>
 */
public class PushIsANotificationTest {

    /** An event source a test drives by hand. */
    private static final class Scripted implements CgFileEventSource {
        private final List<CgFileEvent> queued = new ArrayList<>();

        @Override
        public List<CgFileEvent> drain() {
            List<CgFileEvent> out = new ArrayList<>(queued);
            queued.clear();
            return out;
        }

        @Override
        public void close() {
        }
    }

    private static final CgPath FILE = CgPath.parse("p:a.txt");

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private WorkspaceService service;
    private WorkspaceRpc<Object> rpc;
    private WorkspaceClient<Object> client;
    private final List<WorkspaceClient.FileChanged> heard = new ArrayList<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem().seed("p:a.txt", "one");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("p", "P", Paths.get("/srv/p"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        service.attachEvents(new Scripted());

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);
        rpc.installOn(serverSide::onRequest);
        client = WorkspaceClient.forConnection(clientSide);
        client.onFileChanged(heard::add);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void pump() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    /** <b>The acceptance.</b> The change reaches the client and leaves nothing pending on the server. */
    @Test
    public void aPushIsANotification() {
        client.read(FILE, doc -> { }, failure -> { });
        pump();
        assertEquals("the read settled", 0, serverSide.router().pendingRequests());

        service.write(WorkspaceActor.LOCAL, FILE, "two".getBytes(), null);
        rpc.pollAndNotify((method, args) -> serverSide.notify(method, args), PlainOps.INSTANCE);
        pump();

        assertTrue("the change must still reach the client",
                heard.stream().anyMatch(change -> change.path().equals(FILE)));
        assertEquals("and must leave nothing waiting for an answer",
                0, serverSide.router().pendingRequests());
    }

    /**
     * The counter-control.
     *
     * <p>Without it the assertion above passes against a wire that delivers nothing at all — a pending
     * count of zero is what "the push was never sent" also looks like.
     */
    @Test
    public void aRequestDoesLeaveOnePendingUntilItIsAnswered() {
        clientSide.call("fs.projects", null, ok -> { }, error -> { });

        assertEquals("a real request is outstanding before it is delivered",
                1, clientSide.router().pendingRequests());

        pump();

        assertEquals("and settles once it is answered", 0, clientSide.router().pendingRequests());
    }

    /** Presence rides the same channel and must not open a call either. */
    @Test
    public void presenceIsANotificationToo() {
        client.read(FILE, doc -> { }, failure -> { });
        pump();

        rpc.pollAndNotify((method, args) -> serverSide.notify(method, args), PlainOps.INSTANCE);
        pump();

        assertEquals(0, serverSide.router().pendingRequests());
    }
}
