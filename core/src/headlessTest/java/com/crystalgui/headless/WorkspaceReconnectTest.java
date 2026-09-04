package com.crystalgui.headless;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Rejoining a server: the workspace object survives, and what it had told the old peer is re-issued.
 *
 * <p>A window kept across a disconnect holds this workspace in a field, so a reconnect swaps the wire
 * underneath it rather than replacing it — every callback registered on it stays live.</p>
 *
 * <p>The half that has to be re-issued is the subscriptions. "I have already asked the server to watch
 * this" is a fact about a <em>peer</em>, so after a reconnect it records promises the new peer never
 * made: change notifications then stop permanently for exactly the files that were open, with no error
 * and no log line.</p>
 */
public class WorkspaceReconnectTest {

    private static final CgPath FILE = CgPath.parse("p:a.txt");
    private static final Object PEER = new Object();

    private Workspace workspace;
    private final List<FsMessages.FileChange> heard = new ArrayList<>();

    /** One server end, rebuildable — a reconnect is a second one of these. */
    private static final class Server {
        final InMemoryTransport<Object>[] link;
        final ProtocolConnection<Object> serverSide;
        final ProtocolConnection<Object> clientSide;
        final WorkspaceService service;
        final WatchHub hub;
        final WorkspaceBinding<Object> binding;

        Server(InMemoryFileSystem files) {
            ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                    new WorkspaceProject("p", "P", Paths.get("/srv/p"))));
            service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
            hub = new WatchHub(service);
            link = InMemoryTransport.pair();
            serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
            clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
            binding = new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, PEER,
                    PlainOps.INSTANCE);
            binding.installOn(serverSide::onRequest);
        }

        void pump() {
            for (int i = 0; i < 8; i++) {
                link[0].deliver();
                link[1].deliver();
                serverSide.tick();
                clientSide.tick();
            }
        }

        /** Sends whatever the hub found, the way a host's tick does. */
        void poll() {
            Map<Object, List<FsMessages.FileChange>> byPeer = hub.poll(WorkspaceActor.LOCAL);
            List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
            if (mine.isEmpty()) return;
            serverSide.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                    FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                            new FsMessages.ChangedNotification(mine))));
        }
    }

    private Server first;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        first = new Server(new InMemoryFileSystem().seed("p:a.txt", "one"));
        workspace = Workspace.of(first.clientSide);
        workspace.watch(Resource.of(FILE), false).onChanged.connect(heard::addAll);
        first.pump();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    /** Moves this workspace onto a fresh server end and settles both. */
    private Server reconnect(InMemoryFileSystem files) {
        Protocols.resetForTesting();
        Server next = new Server(files);
        workspace.rebind(next.clientSide);
        next.pump();
        return next;
    }

    @Test
    public void aReboundWorkspaceCanStillRead() {
        Server next = reconnect(new InMemoryFileSystem().seed("p:a.txt", "from the new server"));

        String[] got = {null};
        workspace.files().read(Resource.of(FILE))
                .then(answer -> got[0] = new String(answer.content(), StandardCharsets.UTF_8));
        next.pump();

        assertEquals("from the new server", got[0]);
    }

    /**
     * <b>The one that fails silently.</b> A watch the client believes it has already asked for is never
     * re-asked, so the file it was watching stops reporting changes for the rest of the session.
     */
    @Test
    public void aReboundWorkspaceIsStillWatchingWhatItHadOpen() {
        Server next = reconnect(new InMemoryFileSystem().seed("p:a.txt", "one"));
        heard.clear();

        next.service.write(WorkspaceActor.LOCAL, FILE, "two".getBytes(StandardCharsets.UTF_8), null);
        next.poll();
        next.pump();

        assertTrue("the watch must be re-issued to the new peer",
                heard.stream().anyMatch(change -> FILE.toString().equals(change.path())));
    }

    /**
     * The greeting is re-asked, so the new server's own facts replace the old one's.
     *
     * <p>Case sensitivity decides whether {@code Main.java} and {@code main.java} are one open document,
     * and it is a property of the host rather than of the protocol — carrying the previous server's
     * answer across a rejoin would make that decision from the wrong machine.</p>
     */
    @Test
    public void aReconnectReAsksTheGreeting() {
        boolean[] greeted = {false};
        workspace.onDidGreet.connect(hello -> greeted[0] = true);

        Server next = reconnect(new InMemoryFileSystem().seed("p:a.txt", "one"));
        next.pump();

        assertTrue("the new server states its own facts", greeted[0]);
    }

    /** Pushed state describes a server nobody is talking to any more, so it goes. */
    @Test
    public void aReconnectForgetsWhatTheOldServerPushed() {
        first.serverSide.notify(FsMethods.PRESENCE, new StateMap<>(PlainOps.INSTANCE,
                FsMessages.presenceNotification().encode(PlainOps.INSTANCE,
                        new FsMessages.PresenceNotification(List.of(
                                new FsMessages.PresenceEntry(FILE.toString(), "bob", true))))));
        first.pump();
        assertEquals("somebody was editing it on the old server",
                List.of("bob"), workspace.presence().whoIsEditing(Resource.of(FILE)));

        reconnect(new InMemoryFileSystem().seed("p:a.txt", "one"));

        assertTrue("and must not be shown as editing it on this one",
                workspace.presence().whoIsEditing(Resource.of(FILE)).isEmpty());
    }

    /** It announces, so anything holding a stale listing knows to re-fetch. */
    @Test
    public void aRebindAnnouncesItself() {
        boolean[] announced = {false};
        workspace.onDidReconnect.connect(() -> announced[0] = true);
        assertFalse(announced[0]);

        reconnect(new InMemoryFileSystem().seed("p:a.txt", "one"));

        assertTrue(announced[0]);
    }
}
