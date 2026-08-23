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
import com.crystalgui.ui.UIElement;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * CrystalOS <b>W11</b> — reconnect-on-restore ({@code plan_windowing.md}).
 *
 * <h3>Why this exists at all</h3>
 *
 * <p>Hiding a window is <em>detaching</em> it, which is what makes retention real — and a window can stay
 * detached across a disconnect and a rejoin. The plan's risk list names it outright: W11 exists because
 * retention makes the stale-client defect reachable again. Before retention, a client and its connection
 * died together, so nothing ever held one across a reconnect.</p>
 *
 * <h3>The half that fails loudly, and the half that does not</h3>
 *
 * <p>A client still pointed at a dead router is obvious: every call waits out its timeout. The one worth
 * a test is quieter — {@code watched} is a client-side memo meaning "I have already asked the server to
 * watch this", so after a reconnect it records promises the new peer never made. The client never
 * re-asks, change notifications stop for exactly the files that were open, and nothing reports it.</p>
 *
 * <h3>What the fixture rebuilds, and what it does not</h3>
 *
 * <p>A rejoin: the transport pair, the session and the {@code WorkspaceRpc} are all rebuilt, so the new
 * peer's watcher genuinely starts empty — which is the whole point — while the files underneath are the
 * ones that were there before. One test deliberately rejoins a <em>different</em> filesystem, because the
 * content cache can only go wrong across two worlds and not within one.</p>
 */
public class WorkspaceReconnectTest {

    private static final CgPath FILE = CgPath.parse("mymod.proj:README.md");

    private InMemoryFileSystem files;
    private WorkspaceClient<Object> client;
    private final List<WorkspaceClient.FileChanged> seen = new ArrayList<>();

    /** Everything that dies with one connection, so a reconnect is one call. */
    private static final class Wire {
        ServerUiSession<Object> server;
        ClientUiSession<Object> session;
        WorkspaceRpc<Object> rpc;
        InMemoryTransport<Object> a;
        InMemoryTransport<Object> b;
    }

    private Wire wire;

    @Before
    public void setUp() {
        files = new InMemoryFileSystem().seed("mymod.proj:README.md", "original");
        wire = connect();
        client = new WorkspaceClient<>(wire.session, PlainOps.INSTANCE);
        client.onFileChanged(seen::add);
        pump();
    }

    private Wire connect() {
        return connect(files);
    }

    private Wire connect(InMemoryFileSystem over) {
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "Proj", Paths.get("/srv/proj"))));
        WorkspaceService service =
                new WorkspaceService(registry, over, WorkspacePermission.ALLOW_ALL);

        Wire fresh = new Wire();
        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        fresh.a = pair[0];
        fresh.b = pair[1];
        fresh.server = new ServerUiSession<>(1, new UIElement(), fresh.a, PlainOps.INSTANCE);
        fresh.rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);
        fresh.rpc.installOn(fresh.server::onCall);
        fresh.server.open();
        fresh.session = new ClientUiSession<>(fresh.b, PlainOps.INSTANCE);
        return fresh;
    }

    private void pump() {
        for (int i = 0; i < 8; i++) {
            int moved = wire.a.deliver() + wire.b.deliver();
            wire.session.tick();
            wire.server.tick();
            if (moved == 0) break;
        }
    }

    /** What a host's tick does: poll the watcher, push, deliver. @return how many changes were sent */
    private int tickServer() {
        int sent = wire.rpc.pollAndNotify(
                (method, args) -> wire.server.call(method, args, null, null), PlainOps.INSTANCE);
        pump();
        return sent;
    }

    private String read(CgPath path) {
        StringBuilder got = new StringBuilder();
        client.read(path, document -> got.append(document.text()),
                failure -> org.junit.Assert.fail("read: " + failure.code()));
        pump();
        return got.toString();
    }

    private void edit(String content) {
        files.write(FILE, content.getBytes(StandardCharsets.UTF_8), false, true);
    }

    /** Drops the wire and stands a new one up over the same files, as a rejoin does. */
    private void reconnect() {
        wire.server.close("disconnected");
        wire = connect();
        client.rebind(wire.session);
        pump();
    }

    /**
     * <b>A rebound client can still talk.</b>
     *
     * <p>The loud half. Without the rebind every call goes to a router whose peer is gone, so a restored
     * window is not merely stale — it is inert, and the only symptom is that nothing ever answers.</p>
     */
    @Test
    public void aReboundClientCanStillRead() {
        assertEquals("original", read(FILE));

        reconnect();

        assertEquals("the client cannot read after a reconnect", "original", read(FILE));
    }

    /**
     * <b>...and it is still watching what it had open, which is the half that fails in silence.</b>
     *
     * <p>Without the re-issue, {@code watched} survives as a record of promises the new peer never made:
     * a later read sees the path already present and never asks again. The file goes on changing and the
     * editor goes on showing what it had, with no error, no log line and nothing to search for.</p>
     */
    @Test
    public void aReboundClientIsStillWatchingWhatItHadOpen() {
        read(FILE);
        edit("edited once");
        assertEquals("the fixture is not delivering notifications at all", 1, tickServer());
        assertEquals(1, seen.size());

        reconnect();
        seen.clear();

        edit("edited again");
        tickServer();

        assertFalse("the watch was never re-established, so edits go unnoticed for good", seen.isEmpty());
        assertEquals(FILE, seen.get(0).path());
    }

    /**
     * <b>Bytes read over the old connection are not served to a DIFFERENT one.</b>
     *
     * <p>Worth being exact about, because the obvious version of this test proves nothing. A read is
     * answered from the content cache only when the <em>server</em> says {@code unchanged}, so on a
     * rejoin to the same world a file that moved while nobody was watching comes back with a new etag and
     * the cache is bypassed — correct with or without any of this.</p>
     *
     * <p>The hazard is rejoining somewhere <b>else</b>. An etag is {@code mtime + size} and nothing about
     * it is server-scoped, so two worlds can hand out the same one for entirely different content — as
     * they do here, both files being the same length and each the first thing written to its own
     * filesystem. The condition then matches, the new server truthfully says nothing has changed since
     * that etag, and a client still holding the old bytes shows the previous server's file under the new
     * server's name. Dropping the cache on a rebind makes the first read after one unconditional.</p>
     *
     * <p>The etags are deliberately kept, which is why this is a cache test and not an etag one: they are
     * what a {@link WorkspaceClient#save} quotes, the server re-stats before writing, and a genuinely
     * stale write comes back as a conflict a user can act on.</p>
     */
    @Test
    public void aReconnectDoesNotServeCachedContentToADifferentServer() {
        assertEquals("original", read(FILE));

        // SAME LENGTH, and the first write to its own filesystem, so it lands on the same monotonic
        // mtime -- an etag collision between two worlds, which is the whole scenario.
        InMemoryFileSystem elsewhere = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", "replaced");
        wire.server.close("disconnected");
        wire = connect(elsewhere);
        client.rebind(wire.session);
        pump();

        assertEquals("the client showed the previous server's bytes under this server's file",
                "replaced", read(FILE));
    }

    /**
     * <b>Rebinding to the wire already in use does nothing, and must not throw.</b>
     *
     * <p>Not a nicety — it is what lets a caller ask on every frame rather than tracking the wire itself,
     * which is exactly how {@code Mc1710Workspace.pump} reaches it. And the failure mode is loud rather
     * than stale: {@code bind} re-registers this client's push handlers, and {@code MessageRouter} refuses
     * a duplicate registration outright, so a client that did not know what it was already bound to would
     * throw on the first frame after it was built.</p>
     *
     * <p>Which is why both constructors record it. The guard was originally only in the caller, so the
     * client itself would happily re-register on the wire it was constructed with.</p>
     */
    @Test
    public void rebindingToTheWireAlreadyInUseIsANoOp() {
        AtomicInteger rebounds = new AtomicInteger();
        client.onRebound(rebounds::incrementAndGet);

        assertFalse("the client did not recognise the wire it was constructed on",
                client.rebind(wire.session));
        assertEquals("a no-op rebind announced itself", 0, rebounds.get());

        // ...and it is still usable, which is what would break if the handlers had been re-registered.
        assertEquals("original", read(FILE));
    }

    /**
     * <b>A view is told, because the client cannot know what a view is showing.</b>
     *
     * <p>The client restores what the <em>protocol</em> needs — watches, capabilities, presence. A file
     * tree holding a directory listing from the previous server is showing something only the tree knows
     * about, so it gets a signal rather than being guessed at.</p>
     */
    @Test
    public void aRebindAnnouncesItself() {
        AtomicInteger rebounds = new AtomicInteger();
        client.onRebound(rebounds::incrementAndGet);

        reconnect();

        assertEquals("nothing was told the wire had moved", 1, rebounds.get());
    }
}
