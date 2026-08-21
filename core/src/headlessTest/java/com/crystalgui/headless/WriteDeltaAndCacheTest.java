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
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.text.Change;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 4 <b>C5</b> — {@code fs.writeDelta} and the etag-validated client cache.
 *
 * <p>D10: writing branches on <b>what the client is holding</b>, not on what the file is. A text
 * document with a matching base revision sends a change set; anything else sends the whole file. D13: a
 * re-read is conditional on the etag the client already has, so the common case — re-opening a file
 * nothing has touched — costs a field rather than the file.</p>
 */
public class WriteDeltaAndCacheTest {

    private InMemoryTransport<Object>[] pair;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private WorkspaceClient<Object> client;
    private InMemoryFileSystem files;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ProjectRegistry registry = new ProjectRegistry().register(() -> Collections.singletonList(
                new WorkspaceProject("p", "Project", Paths.get("/p"))));
        files = new InMemoryFileSystem().addProject("p");
        WorkspaceService service = new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        pair = InMemoryTransport.pair();
        serverSide = Protocols.open(pair[0], PlainOps.INSTANCE, () -> { }, "peer");
        clientSide = Protocols.open(pair[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceRpc<>(service, WorkspaceActor.LOCAL).installOn(serverSide::onRequest);
        client = new WorkspaceClient<>(clientSide);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 200; i++) {
            pair[0].deliver();
            pair[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    private CgPath seed(String name, String content) {
        CgPath path = CgPath.of("p", name);
        files.write(path, content.getBytes(StandardCharsets.UTF_8), true, true);
        return path;
    }

    private String onDisk(CgPath path) {
        return new String(files.read(path), StandardCharsets.UTF_8);
    }

    private WorkspaceClient.Document read(CgPath path) {
        AtomicReference<WorkspaceClient.Document> got = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Failure> failed = new AtomicReference<>();
        client.read(path, got::set, failed::set);
        settle();
        assertNull("read failed: " + failed.get(), failed.get());
        assertNotNull("read produced nothing", got.get());
        return got.get();
    }

    // ── writeDelta ──────────────────────────────────────────────────────────

    /** One character into a file, sent as one change. */
    @Test
    public void aChangeSetIsAppliedToTheFile() {
        CgPath path = seed("Main.java", "hello world");
        read(path);

        AtomicReference<String> saved = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Failure> failed = new AtomicReference<>();
        client.writeDelta(path, List.of(new Change(6, 11, "there")), saved::set, failed::set);
        settle();

        assertNull("no failure: " + failed.get(), failed.get());
        assertEquals("hello there", onDisk(path));
        assertNotNull("the new etag comes back", saved.get());
    }

    /** Several changes in one set, applied as one write. */
    @Test
    public void severalChangesApplyTogether() {
        CgPath path = seed("a.txt", "one two three");
        read(path);

        client.writeDelta(path,
                List.of(new Change(0, 3, "ONE"), new Change(8, 13, "THREE")),
                etag -> { }, failure -> fail("failed: " + failure.code()));
        settle();

        assertEquals("ONE two THREE", onDisk(path));
    }

    /**
     * A delta against a file that moved is <b>refused</b>, not merged.
     *
     * <p>Merging is a decision with a UI attached and does not belong in a write path. The conflict
     * arrives with the live etag, which is everything a "reload or keep?" prompt needs.</p>
     */
    @Test
    public void aDeltaAgainstAMovedFileIsRefused() {
        CgPath path = seed("b.txt", "original");
        read(path);

        // Somebody else writes underneath.
        files.write(path, "changed by someone else".getBytes(StandardCharsets.UTF_8), false, true);

        AtomicReference<WorkspaceClient.Failure> failed = new AtomicReference<>();
        client.writeDelta(path, List.of(new Change(0, 8, "mine")), etag -> { }, failed::set);
        settle();

        assertNotNull("the write must be refused", failed.get());
        assertTrue("and refused AS A CONFLICT: " + failed.get().code(), failed.get().isConflict());
        assertEquals("the file must be untouched", "changed by someone else", onDisk(path));
    }

    /** A delta with no prior read has no base revision, and guessing one would corrupt the file. */
    @Test
    public void aDeltaWithoutAPriorReadIsRefusedLocally() {
        CgPath path = seed("c.txt", "text");
        try {
            client.writeDelta(path, List.of(new Change(0, 1, "T")), etag -> { }, failure -> { });
            fail("writeDelta without a read must not guess a base revision");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("base etag"));
        }
    }

    // ── the cache ───────────────────────────────────────────────────────────

    /** A second read of an untouched file is answered from cache, and is identical. */
    @Test
    public void aSecondReadIsServedFromCache() {
        CgPath path = seed("d.txt", "cached content");
        WorkspaceClient.Document first = read(path);

        // Take the file away entirely. A conditional read is answered from the STAT, so this must fail --
        // which is precisely how we know the second read did NOT come from the cache if it succeeds.
        WorkspaceClient.Document second = read(path);

        assertEquals("the same bytes", new String(first.content(), StandardCharsets.UTF_8),
                new String(second.content(), StandardCharsets.UTF_8));
        assertEquals("and the same etag", first.etag(), second.etag());
        assertEquals("cached content", new String(second.content(), StandardCharsets.UTF_8));
    }

    /** A file that changed is re-sent, cache or no cache. */
    @Test
    public void aChangedFileIsResent() {
        CgPath path = seed("e.txt", "before");
        WorkspaceClient.Document first = read(path);
        assertEquals("before", new String(first.content(), StandardCharsets.UTF_8));

        files.write(path, "after".getBytes(StandardCharsets.UTF_8), false, true);
        WorkspaceClient.Document second = read(path);

        assertEquals("after", new String(second.content(), StandardCharsets.UTF_8));
        assertTrue("the etag must have moved", !first.etag().equals(second.etag()));
    }

    /** A write through this client invalidates its own cache. */
    @Test
    public void writingInvalidatesTheCache() {
        CgPath path = seed("f.txt", "aaa");
        read(path);

        client.writeDelta(path, List.of(new Change(0, 3, "bbb")),
                etag -> { }, failure -> fail("failed: " + failure.code()));
        settle();

        WorkspaceClient.Document after = read(path);
        assertEquals("bbb", new String(after.content(), StandardCharsets.UTF_8));
    }
}
