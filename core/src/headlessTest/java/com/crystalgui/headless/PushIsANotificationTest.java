package com.crystalgui.headless;

import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.provider.CgFileEventSource;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A push is a notification: it carries information and expects no answer.
 *
 * <p>{@code fs/changed}, {@code fs/presence} and {@code fs/capabilities} are sent with
 * {@code notify}, so they cost the sender nothing beyond the message. Sent as requests they would each
 * occupy a pending slot and a timeout until the far side replied with nothing — one per watched file
 * per change per peer.</p>
 *
 * <p>Asserted on {@link com.crystalgui.net.protocol.MessageRouter#pendingRequests()}, because the
 * payload arrives either way: a test that only checks the client heard the change passes whichever
 * shape was used.</p>
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
    private static final Object PEER = new Object();

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private WorkspaceService service;
    private WatchHub hub;
    private WorkspaceBinding<Object> binding;
    private Workspace workspace;
    private final List<FsMessages.FileChange> heard = new ArrayList<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem().seed("p:a.txt", "one");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("p", "P", Paths.get("/srv/p"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        service.attachEvents(new Scripted());
        hub = new WatchHub(service);

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        binding = new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, PEER, PlainOps.INSTANCE);
        binding.installOn(serverSide::onRequest);
        workspace = Workspace.of(clientSide);
        workspace.watch(Resource.of(FILE), false).onChanged.connect(heard::addAll);
        // SETTLED BEFORE ANY TEST LOOKS, or the greeting and the watch are still outstanding and every
        // pending-request count here starts at two.
        pump();
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

    /** Sends whatever the hub found, the way the host does. */
    private void poll() {
        Map<Object, List<FsMessages.FileChange>> byPeer = hub.poll(WorkspaceActor.LOCAL);
        List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
        if (mine.isEmpty()) return;
        serverSide.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                        new FsMessages.ChangedNotification(mine))));
    }

    /** The change reaches the client and leaves nothing pending on the server. */
    @Test
    public void aPushIsANotification() {
        workspace.files().read(Resource.of(FILE));
        pump();
        assertEquals("the read settled", 0, serverSide.router().pendingRequests());

        service.write(WorkspaceActor.LOCAL, FILE, "two".getBytes(), null);
        poll();
        pump();

        assertTrue("the change must still reach the client",
                heard.stream().anyMatch(change -> FILE.toString().equals(change.path())));
        assertEquals("and must leave nothing waiting for an answer",
                0, serverSide.router().pendingRequests());
    }

    /**
     * The counter-control: without it the assertion above passes against a wire that delivers nothing
     * at all, because a pending count of zero is also what "the push was never sent" looks like.
     */
    @Test
    public void aRequestDoesLeaveOnePendingUntilItIsAnswered() {
        clientSide.call(FsMethods.PROJECTS, null, ok -> { }, error -> { });

        assertEquals("a real request is outstanding before it is delivered",
                1, clientSide.router().pendingRequests());

        pump();

        assertEquals("and settles once it is answered", 0, clientSide.router().pendingRequests());
    }

    /** Presence rides the same channel and must not open a call either. */
    @Test
    public void presenceIsANotificationToo() {
        binding.setEditing(FILE, true);
        serverSide.notify(FsMethods.PRESENCE, new StateMap<>(PlainOps.INSTANCE,
                FsMessages.presenceNotification().encode(PlainOps.INSTANCE,
                        new FsMessages.PresenceNotification(List.of(
                                new FsMessages.PresenceEntry(FILE.toString(), "alice", true))))));
        pump();

        assertEquals(0, serverSide.router().pendingRequests());
        assertEquals("and it reached the client",
                List.of("alice"), workspace.presence().whoIsEditing(Resource.of(FILE)));
    }
}
