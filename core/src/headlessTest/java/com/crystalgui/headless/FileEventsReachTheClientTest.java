package com.crystalgui.headless;

import com.crystalgui.fs.CgFileEvent;
import com.crystalgui.fs.CgFileEventSource;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase 6 <b>6.2</b> — an event on the server's disk reaches the client that has the file open.
 *
 * <p>The OS half is covered against a real directory by {@code NioFileEventSourceTest}. This is the other
 * half and needs no OS at all: given a batch of events, does the right client hear about the right files,
 * and — the part that matters more — does the wrong client hear nothing?</p>
 */
public class FileEventsReachTheClientTest {

    private static final CgPath WATCHED = CgPath.parse("mymod.proj:src/Watched.java");
    private static final CgPath UNWATCHED = CgPath.parse("mymod.proj:src/Unwatched.java");

    /** Hands over whatever a test queues, so the batch is the test's to choose. */
    private static final class Scripted implements CgFileEventSource {
        private final List<CgFileEvent> queued = new ArrayList<>();

        void queue(CgFileEvent... events) {
            queued.addAll(Arrays.asList(events));
        }

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

    private InMemoryFileSystem files;
    private WorkspaceService service;
    private Scripted source;

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private WorkspaceRpc<Object> rpc;
    private WorkspaceClient<Object> client;

    private final List<WorkspaceClient.FileChanged> heard = new ArrayList<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();

        files = new InMemoryFileSystem()
                .seed("mymod.proj:src/Watched.java", "class Watched {}")
                .seed("mymod.proj:src/Unwatched.java", "class Unwatched {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Mod", Paths.get("/srv/mymod"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        source = new Scripted();
        service.attachEvents(source);

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

    /** One drain, handed to the peer, exactly as the host does it every tick. */
    private void tick() {
        List<CgFileEvent> events = service.drainFileEvents();
        if (!events.isEmpty()) {
            rpc.notifyFileEvents(events,
                    (method, args) -> serverSide.call(method, args, null, null), PlainOps.INSTANCE);
        }
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    /** Opening a file is what starts watching it. */
    private void open(CgPath path) {
        client.read(path, document -> { }, failure -> { });
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    // ── The claim ───────────────────────────────────────────────────────────────────────────────

    /** An external change to an open file reaches the client. */
    @Test
    public void anExternalChangeReachesTheClient() {
        open(WATCHED);

        files.seed("mymod.proj:src/Watched.java", "class Watched { int changed; }");
        source.queue(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, WATCHED));
        tick();

        assertEquals("the client must be told", 1, heard.size());
        assertEquals(WATCHED, heard.get(0).path());
    }

    /**
     * A file this client does not have open produces nothing.
     *
     * <p>Not merely noise: an event is real and still none of that peer's business, and reporting it
     * would tell a client which files exist that it never asked about.</p>
     */
    @Test
    public void anEventForAnUnopenedFileIsNotReported() {
        open(WATCHED);

        files.seed("mymod.proj:src/Unwatched.java", "class Unwatched { int changed; }");
        source.queue(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, UNWATCHED));
        tick();

        assertTrue("nothing this client asked about changed", heard.isEmpty());
    }

    /**
     * An event whose bytes did not actually change reports nothing.
     *
     * <p>{@code ENTRY_MODIFY} fires for a touch, and a single save is often several events — truncate,
     * write, rename into place. The etag stays the arbiter even when an event prompted the look, or one
     * save arrives as three reload prompts.</p>
     */
    @Test
    public void anEventWithNoRealChangeIsNotReported() {
        open(WATCHED);

        source.queue(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, WATCHED));
        source.queue(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, WATCHED));
        tick();

        assertTrue("the file is untouched, so there is nothing to say", heard.isEmpty());
    }

    /** A deletion arrives as a deletion, which the client can tell apart. */
    @Test
    public void aDeletionReachesTheClientAsOne() {
        open(WATCHED);

        files.delete(WATCHED, false);
        source.queue(CgFileEvent.of(CgFileEvent.Kind.DELETED, WATCHED));
        tick();

        assertEquals(1, heard.size());
        assertTrue("and is distinguishable from a modification", heard.get(0).isDeleted());
    }

    /**
     * <b>An OVERFLOW reconciles rather than being ignored.</b>
     *
     * <p>The reason the etag poll survives a real watcher. Events are dropped by design once a key's
     * queue fills, so the change that was lost must still be found — here the file changed and its own
     * event never arrived, and only the full re-scan can notice.</p>
     */
    @Test
    public void anOverflowFallsBackToAFullRescan() {
        open(WATCHED);

        files.seed("mymod.proj:src/Watched.java", "class Watched { int lost; }");
        // The event for that write never arrives -- only the news that events were lost.
        source.queue(CgFileEvent.overflow());
        tick();

        assertEquals("the change must be found anyway", 1, heard.size());
        assertEquals(WATCHED, heard.get(0).path());
    }

    /** And a change is announced once, however many events prompted it. */
    @Test
    public void aChangeIsAnnouncedOnce() {
        open(WATCHED);

        files.seed("mymod.proj:src/Watched.java", "class Watched { int once; }");
        source.queue(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, WATCHED),
                CgFileEvent.of(CgFileEvent.Kind.MODIFIED, WATCHED),
                CgFileEvent.of(CgFileEvent.Kind.MODIFIED, WATCHED));
        tick();

        assertEquals("three events, one save, one notification", 1, heard.size());

        source.queue(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, WATCHED));
        tick();
        assertEquals("and nothing more on a later look", 1, heard.size());
    }

    /** With no source attached, nothing breaks — the poll is still the fallback. */
    @Test
    public void aServiceWithNoWatcherIsFine() {
        WorkspaceService bare = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject("mymod.proj", "My Mod", Paths.get("/srv/mymod")))),
                new InMemoryFileSystem(), WorkspacePermission.ALLOW_ALL);

        assertEquals(Collections.emptyList(), bare.drainFileEvents());
    }
}
