package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspacePresence;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 5 <b>5.6</b> — who else has this file open.
 *
 * <p>The data has existed since Phase 4 and nothing showed it. {@code fs.watch} is sent for every file a
 * client reads and cleared when it closes one, so the server has always known — what was missing was a
 * view <b>across</b> peers, since a {@code WorkspaceWatcher} belongs to one connection and can only ever
 * answer about itself.</p>
 *
 * <p>The tests that matter here are the ones about <b>not lying</b>: an empty list means "nothing has
 * been said" rather than "nobody is there", a peer that vanishes without unwatching must not linger, and
 * a client must never be told about a file it cannot read.</p>
 */
public class WorkspacePresenceTest {

    private static final CgPath SHARED = CgPath.parse("mymod.proj:src/Shared.java");
    private static final CgPath OTHER = CgPath.parse("mymod.proj:src/Other.java");

    private static final WorkspaceActor ALICE = actor("alice", "Alice");
    private static final WorkspaceActor BOB = actor("bob", "Bob");
    private static final WorkspaceActor CAROL = actor("carol", "Carol");

    private static WorkspaceActor actor(String id, String name) {
        return new WorkspaceActor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return name;
            }
        };
    }

    private WorkspaceService service;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ProjectRegistry projects = new ProjectRegistry()
                .register(() -> List.of(
                        new WorkspaceProject("mymod.proj", "My Mod", Paths.get("/srv/mymod"))));
        service = new WorkspaceService(
                projects,
                new InMemoryFileSystem()
                        .seed("mymod.proj:src/Shared.java", "class Shared {}")
                        .seed("mymod.proj:src/Other.java", "class Other {}"),
                WorkspacePermission.ALLOW_ALL);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    // ── The registry ────────────────────────────────────────────────────────────────────────────

    @Test
    public void itKnowsWhoElseHasAFileOpen() {
        WorkspacePresence presence = service.presence();
        presence.opened(ALICE, SHARED);
        presence.opened(BOB, SHARED);
        presence.opened(CAROL, OTHER);

        assertEquals(List.of("Bob"), presence.whoElseHasOpen(ALICE, SHARED));
        assertEquals(List.of("Alice"), presence.whoElseHasOpen(BOB, SHARED));
        assertEquals("a file only you have open has nobody else on it",
                List.of(), presence.whoElseHasOpen(CAROL, OTHER));
    }

    /** Display names, never ids — an id is what permissions are decided on, not something to show. */
    @Test
    public void itCarriesDisplayNames() {
        service.presence().opened(ALICE, SHARED);
        assertEquals(List.of("Alice"), service.presence().whoHasOpen(SHARED));
    }

    @Test
    public void closingRemovesSomebody() {
        WorkspacePresence presence = service.presence();
        presence.opened(ALICE, SHARED);
        presence.opened(BOB, SHARED);

        presence.closed(BOB, SHARED);

        assertEquals(List.of("Alice"), presence.whoHasOpen(SHARED));
        assertTrue("and the path goes away entirely once nobody is left",
                presence.whoElseHasOpen(ALICE, SHARED).isEmpty());
    }

    /**
     * <b>A peer that vanishes is forgotten.</b>
     *
     * <p>The failure this prevents is indefinite: a player who crashed or logged out never sends the
     * {@code fs.unwatch} that would clear them, so without this they are shown as holding the file for
     * the rest of the server's life. A presence list nobody prunes describes the past.</p>
     */
    @Test
    public void aPeerThatLeavesIsForgottenEverywhere() {
        WorkspacePresence presence = service.presence();
        presence.opened(BOB, SHARED);
        presence.opened(BOB, OTHER);
        presence.opened(ALICE, SHARED);

        presence.left(BOB);

        assertEquals(List.of("Alice"), presence.whoHasOpen(SHARED));
        assertTrue("every path, not just the one", presence.whoHasOpen(OTHER).isEmpty());
        assertFalse("and the emptied path is gone", presence.paths().contains(OTHER));
    }

    /** The version moves only on a real change, since a poll uses it to decide whether to send. */
    @Test
    public void theVersionMovesOnlyWhenSomethingChanges() {
        WorkspacePresence presence = service.presence();
        int start = presence.version();

        presence.opened(ALICE, SHARED);
        int afterOpen = presence.version();
        assertTrue(afterOpen > start);

        presence.opened(ALICE, SHARED);
        assertEquals("opening the same file twice is not a change", afterOpen, presence.version());

        presence.closed(BOB, SHARED);
        assertEquals("nor is closing something nobody had open", afterOpen, presence.version());
    }

    // ── Over the wire ───────────────────────────────────────────────────────────────────────────

    /** Two peers on one server: each learns the other is there, without asking. */
    @Test
    public void presenceReachesTheOtherClient() {
        Peer alice = new Peer(ALICE);
        Peer bob = new Peer(BOB);

        alice.watch(SHARED);
        bob.watch(SHARED);
        settle(alice, bob);

        assertEquals("Alice sees Bob", List.of("Bob"), alice.client.whoElseHasOpen(SHARED));
        assertEquals("and Bob sees Alice", List.of("Alice"), bob.client.whoElseHasOpen(SHARED));
    }

    /**
     * A client is told only about files it is watching.
     *
     * <p>Sending the whole server's presence would tell a client which files it cannot read are open and
     * by whom — the same leak {@code fs.watch} is authorised against, arriving through a different
     * door.</p>
     */
    @Test
    public void aClientHearsNothingAboutFilesItIsNotWatching() {
        Peer alice = new Peer(ALICE);
        Peer bob = new Peer(BOB);

        alice.watch(SHARED);
        bob.watch(OTHER);
        settle(alice, bob);

        assertTrue("Alice is not watching Other and must not be told who is",
                alice.client.whoElseHasOpen(OTHER).isEmpty());
        assertFalse("nor may it appear in her view at all",
                alice.client.pathsOthersHaveOpen().contains(OTHER));
    }

    /** Nothing is sent while nothing changes — the poll runs every tick and must stay silent. */
    @Test
    public void aQuietServerSendsNoPresence() {
        Peer alice = new Peer(ALICE);
        alice.watch(SHARED);
        settle(alice);

        int before = alice.pushes;
        settle(alice);
        settle(alice);

        assertEquals("a poll with nothing to say must say nothing", before, alice.pushes);
    }

    // ── Harness ─────────────────────────────────────────────────────────────────────────────────

    private final class Peer {
        final InMemoryTransport<Object>[] link;
        final ProtocolConnection<Object> server;
        final ProtocolConnection<Object> clientSide;
        final WorkspaceRpc<Object> rpc;
        final WorkspaceClient<Object> client;
        int pushes;

        @SuppressWarnings("unchecked")
        Peer(WorkspaceActor actor) {
            link = InMemoryTransport.pair();
            server = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, actor.id());
            clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
            rpc = new WorkspaceRpc<>(service, actor);
            rpc.installOn(server::onRequest);
            client = new WorkspaceClient<>(clientSide);
        }

        /**
         * Opens a file, which is what establishes presence.
         *
         * <p>Through {@code read} rather than a bare watch, because that is the real path: the client
         * sends {@code fs.watch} as part of finishing a read, and presence rides the watch. A test that
         * watched directly would exercise a route no editor takes.</p>
         */
        void watch(CgPath path) {
            client.read(path, document -> { }, failure -> { });
        }

        void tick() {
            link[0].deliver();
            link[1].deliver();
            server.tick();
            clientSide.tick();
            // The push rides the poll the host runs every tick -- see WorkspaceRpc.pollPresence.
            rpc.pollAndNotify((method, args) -> {
                pushes++;
                server.call(method, args, null, null);
            }, PlainOps.INSTANCE);
        }
    }

    private void settle(Peer... peers) {
        for (int i = 0; i < 24; i++) {
            for (Peer peer : peers) peer.tick();
        }
    }
}
