package com.crystalgui.headless;

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
import com.crystalgui.ui.dom.UIElement;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code fs.changed} — a client is told when a file it has open moves underneath it.
 *
 * <p><b>Promptness, not correctness.</b> A stale write was already refused by the re-stat in
 * {@code WorkspaceService.write} before any of this existed; the point here is that a client finds out
 * <em>before</em> it tries to save.</p>
 */
public class WorkspaceChangeNotificationTest {

    private static final CgPath FILE = CgPath.parse("mymod.proj:README.md");
    private static final CgPath OTHER = CgPath.parse("mymod.proj:other.txt");

    private InMemoryFileSystem files;
    private ServerUiSession<UIElement, Object> server;
    private ClientUiSession<UIElement, Object> session;
    private WorkspaceClient<Object> client;
    private WorkspaceRpc<Object> rpc;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;
    private final List<WorkspaceClient.FileChanged> seen = new ArrayList<>();

    @Before
    public void setUp() {
        files = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", "original")
                .seed("mymod.proj:other.txt", "untouched");
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "Proj", Paths.get("/srv/proj"))));
        WorkspaceService service =
                new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        a = pair[0];
        b = pair[1];
        server = Sessions.serve(1, new UIElement(), a);
        rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);
        rpc.installOn(server::onCall);
        server.open();

        session = Sessions.view(b);
        client = new WorkspaceClient<>(session, PlainOps.INSTANCE);
        client.onFileChanged(seen::add);
        pump();
    }

    private void pump() {
        for (int i = 0; i < 8; i++) {
            int moved = a.deliver() + b.deliver();
            session.tick();
            server.tick();
            if (moved == 0) break;
        }
    }

    /** What a host's tick does: poll, push, deliver. */
    private int tickServer() {
        int sent = rpc.pollAndNotify(
                (method, args) -> server.call(method, args, null, null), PlainOps.INSTANCE);
        pump();
        return sent;
    }

    private void open(CgPath path) {
        client.read(path, d -> { }, f -> org.junit.Assert.fail("open: " + f.code()));
        pump();
    }

    // ── The mechanism ───────────────────────────────────────────────────────────────────────────

    /** <b>An out-of-band edit reaches the client without it asking.</b> */
    @Test
    public void aWatchedFileThatMovesIsReported() {
        open(FILE);
        assertEquals("nothing has moved yet", 0, tickServer());

        files.write(FILE, "edited elsewhere".getBytes(StandardCharsets.UTF_8), false, true);
        assertEquals(1, tickServer());

        assertEquals(1, seen.size());
        assertEquals(FILE, seen.get(0).path());
        assertEquals(false, seen.get(0).isDeleted());
        assertNotNull("the new etag comes with it", seen.get(0).etag());
    }

    /**
     * <b>Reported once, not on every poll.</b>
     *
     * <p>A watcher that re-announced the same change each tick would put a client into a reload prompt it
     * could not dismiss.</p>
     */
    @Test
    public void aChangeIsAnnouncedOnlyOnce() {
        open(FILE);
        files.write(FILE, "one".getBytes(StandardCharsets.UTF_8), false, true);

        assertEquals(1, tickServer());
        assertEquals(0, tickServer());
        assertEquals(0, tickServer());
        assertEquals(1, seen.size());
    }

    /**
     * <b>Opening a file does not immediately report it as changed.</b>
     *
     * <p>The watch is seeded with the current etag. An unseeded entry would look like a change on the
     * first poll, and every file would announce itself as stale the moment it was opened.</p>
     */
    @Test
    public void openingAFileDoesNotReportAChange() {
        open(FILE);
        tickServer();
        tickServer();
        assertTrue("a freshly opened file has not moved", seen.isEmpty());
    }

    /** Only what the client is watching is polled. */
    @Test
    public void anUnwatchedFileIsNotReported() {
        open(FILE);
        files.write(OTHER, "changed".getBytes(StandardCharsets.UTF_8), false, true);

        assertEquals(0, tickServer());
        assertTrue(seen.isEmpty());
    }

    /** Closing a document stops the notifications. */
    @Test
    public void forgettingStopsTheWatch() {
        open(FILE);
        client.forget(FILE);
        pump();

        files.write(FILE, "after closing".getBytes(StandardCharsets.UTF_8), false, true);
        assertEquals(0, tickServer());
        assertTrue(seen.isEmpty());
    }

    /** Deletion is its own kind, and carries no etag. */
    @Test
    public void aDeletedFileIsReportedAsDeleted() {
        open(FILE);
        files.delete(FILE, false);

        assertEquals(1, tickServer());
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).isDeleted());
        org.junit.Assert.assertNull("there is no etag for a file that is gone", seen.get(0).etag());
    }

    /**
     * <b>A client's own save does not come back as a change.</b>
     *
     * <p>The write updates the client's etag <em>and</em> the watcher's, so the next poll sees no
     * difference. Without that, saving would immediately prompt the user to reload their own work.</p>
     */
    @Test
    public void yourOwnSaveIsNotReportedBackToYou() {
        open(FILE);
        client.save(FILE, "my edit".getBytes(StandardCharsets.UTF_8), e -> { },
                f -> org.junit.Assert.fail("save: " + f.code()));
        pump();

        assertEquals("a save must not echo back as somebody else's change", 0, tickServer());
        assertTrue(seen.isEmpty());
    }

    /** Watching a file the actor may not read is refused, or it leaks existence and every later change. */
    @Test
    public void watchingIsAuthorisedLikeARead() {
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "Proj", Paths.get("/srv/proj"))));
        WorkspaceService denied =
                new WorkspaceService(registry, files, WorkspacePermission.DENY_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<UIElement, Object> s2 =
                Sessions.serve(2, new UIElement(), pair[0]);
        WorkspaceRpc<Object> rpc2 = new WorkspaceRpc<>(denied, WorkspaceActor.LOCAL);
        rpc2.installOn(s2::onCall);
        s2.open();

        assertEquals("a refused watch registers nothing", 0, rpc2.watcher().size());
    }
}
