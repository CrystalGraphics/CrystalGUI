package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspaceOperation;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceProtocol;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 5 <b>5.4</b> — {@code enabledWhen} runs on the client, so the client has to be told.
 *
 * <p>A command's enablement predicate is evaluated locally while a menu is being built. It cannot ask the
 * server <i>may I?</i>, so {@code explorer.delete} looked perfectly available to a non-operator and the
 * refusal arrived as a {@code NO_PERMISSIONS} failure after a round trip. Asking per menu open is worse:
 * that is a round trip inside a UI gesture.</p>
 *
 * <p>So the answer is <b>cached and pushed</b> — VS Code's context-key model, where the far side
 * volunteers what it knows and the near side reads it synchronously.</p>
 *
 * <h3>The interesting assertions are about being wrong</h3>
 *
 * <p>The cache is per project while {@link WorkspacePermission} is per path, and it can be stale or not
 * yet arrived. So the tests that matter are the ones pinning <b>which way</b> it is wrong: unknown is
 * available, a failed refresh stays available, and nothing here relaxes the server-side check.</p>
 */
public class WorkspaceCapabilitiesTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;

    private WorkspaceService service;
    private WorkspaceRpc<Object> rpc;
    private WorkspaceClient<Object> client;

    /** Flipped by a test to simulate an operator being promoted or demoted mid-session. */
    private volatile boolean writesAllowed;

    private static final CgPath IN_PROJECT = CgPath.parse("mymod.proj:src/Thing.java");

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        writesAllowed = false;

        ProjectRegistry projects = new ProjectRegistry()
                .register(() -> List.of(
                        new WorkspaceProject("mymod.proj", "My Mod", Paths.get("/srv/mymod"))));

        WorkspacePermission permission = (actor, project, path, operation) ->
                operation == WorkspaceOperation.READ || writesAllowed;

        service = new WorkspaceService(projects, new InMemoryFileSystem(), permission);
        rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        rpc.installOn(serverSide::onRequest);
        client = new WorkspaceClient<>(clientSide);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    /**
     * A server-to-client push, in the shape this codebase actually uses.
     *
     * <p><b>A {@code call}, not a {@code notify}</b>, and this is the trap. {@code WorkspaceClient}
     * registers its inbound methods through {@code WorkspaceRpc.Registrar}, which is
     * {@code ProtocolConnection::onRequest} — so every server-to-client message on this subsystem is a
     * REQUEST that the client answers with {@code respond.ok(null)}. {@code fs.changed} has always worked
     * this way; the first draft of these tests used {@code serverSide::notify} and the two push tests
     * failed while everything else passed, because the router keys request and notification handlers
     * separately and a notification simply found nobody home.</p>
     */
    private void push() {
        rpc.notifyCapabilities((method, args) -> serverSide.call(method, args, null, null),
                PlainOps.INSTANCE);
    }

    private void settle() {
        for (int i = 0; i < 24; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    // ── The feature ─────────────────────────────────────────────────────────────────────────────

    /** The client learns it may read and may not write, without attempting anything. */
    @Test
    public void theClientLearnsWhatItMayDo() {
        client.refreshCapabilities();
        settle();

        assertTrue("reads are allowed", client.mayRead(IN_PROJECT));
        assertFalse("writes are not, and the client knows before it tries",
                client.mayWrite(IN_PROJECT));
    }

    /**
     * A change is <b>pushed</b>, because the client cannot know.
     *
     * <p>An operator promoted mid-session is not something a file listing reveals, so a client that only
     * asked at connect would go on drawing a greyed-out Delete for the rest of the session.</p>
     */
    @Test
    public void aChangeIsPushedAndTheCacheFollows() {
        client.refreshCapabilities();
        settle();
        assertFalse(client.mayWrite(IN_PROJECT));

        writesAllowed = true;
        push();
        settle();

        assertTrue("the promotion must reach the client without it asking",
                client.mayWrite(IN_PROJECT));

        writesAllowed = false;
        push();
        settle();
        assertFalse("and so must the demotion", client.mayWrite(IN_PROJECT));
    }

    /** Anything watching is told, so a menu bar can re-evaluate what it draws. */
    @Test
    public void aChangeNotifiesItsListener() {
        AtomicInteger changes = new AtomicInteger();
        client.onCapabilitiesChanged(changes::incrementAndGet);

        client.refreshCapabilities();
        settle();
        assertEquals("the first answer counts", 1, changes.get());

        writesAllowed = true;
        push();
        settle();
        assertEquals(2, changes.get());
    }

    // ── Which way it is wrong ───────────────────────────────────────────────────────────────────

    /**
     * <b>Unknown is available.</b>
     *
     * <p>The decision the whole design turns on. A wrongly-<em>greyed</em> command is a thing the user
     * cannot do and cannot explain — no message, no dialog, nothing to search for. A wrongly-<em>live</em>
     * one fails with a reason the server wrote. Being wrong in the second direction is recoverable and
     * being wrong in the first is not.</p>
     */
    @Test
    public void anUnknownProjectIsAssumedWritable() {
        client.refreshCapabilities();
        settle();

        assertTrue("a project the server said nothing about",
                client.mayWrite(CgPath.parse("someone.else:file.txt")));
        assertTrue("and a null path, which is every not-yet-selected menu target",
                client.mayWrite(null));
    }

    /** Before the first answer arrives, everything is available. */
    @Test
    public void nothingIsGreyedBeforeTheFirstAnswer() {
        assertTrue(client.mayWrite(IN_PROJECT));
        assertTrue(client.mayRead(IN_PROJECT));
    }

    /**
     * A server that does not know the method leaves the client optimistic.
     *
     * <p>Greying out every write against an otherwise perfectly working workspace, because one method is
     * missing, is a far worse answer than offering a write that fails.</p>
     */
    @Test
    public void aServerThatCannotAnswerLeavesEverythingAvailable() {
        InMemoryTransport<Object>[] bare = InMemoryTransport.pair();
        ProtocolConnection<Object> oldServer =
                Protocols.open(bare[0], PlainOps.INSTANCE, () -> { }, "bob");
        ProtocolConnection<Object> newClient =
                Protocols.open(bare[1], PlainOps.INSTANCE, () -> { }, null);
        // Deliberately no WorkspaceRpc on the server end: fs.capabilities is unknown there.
        WorkspaceClient<Object> hopeful = new WorkspaceClient<>(newClient);

        hopeful.refreshCapabilities();
        for (int i = 0; i < 24; i++) {
            bare[0].deliver();
            bare[1].deliver();
            oldServer.tick();
            newClient.tick();
        }

        assertTrue(hopeful.mayWrite(IN_PROJECT));
    }

    /**
     * The hint is a hint. <b>The server still refuses.</b>
     *
     * <p>The failure this must never become is a client-side check being mistaken for the real one — at
     * which point a peer that simply ignores the hint has full access. Every operation is still
     * authorised server-side on its own path, and this is what says so.</p>
     */
    @Test
    public void theServerStillRefusesWhateverTheClientBelieves() {
        client.refreshCapabilities();
        settle();

        // The client is told it may not write, and asks anyway -- which is exactly what a peer that
        // ignores the hint does.
        AtomicInteger refusals = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        client.create(CgPath.parse("mymod.proj:Sneaky.java"), "x".getBytes(),
                etag -> successes.incrementAndGet(),
                failure -> refusals.incrementAndGet());
        settle();

        assertEquals("the write must not have happened", 0, successes.get());
        assertEquals("and must have been refused by the server, not by the hint", 1, refusals.get());
    }

    /** A project the actor cannot read is omitted entirely, matching how the listing behaves. */
    @Test
    public void anUnreadableProjectIsNotReported() {
        ProjectRegistry projects = new ProjectRegistry()
                .register(() -> List.of(
                        new WorkspaceProject("secret.proj", "Secret", Paths.get("/srv/secret"))));
        WorkspaceService closed =
                new WorkspaceService(projects, new InMemoryFileSystem(), WorkspacePermission.DENY_ALL);

        assertTrue("\"may not read\" and \"is not there\" must look identical",
                closed.capabilities(WorkspaceActor.LOCAL).isEmpty());
    }

    /** And the vocabulary is one name in both directions, which is the point of naming it once. */
    @Test
    public void theMethodNameIsSharedByBothDirections() {
        assertEquals("fs.capabilities", WorkspaceProtocol.CAPABILITIES);
    }
}
