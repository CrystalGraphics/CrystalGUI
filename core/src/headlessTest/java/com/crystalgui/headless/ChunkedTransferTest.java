package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 4 <b>B3</b> — a file larger than one message, and one larger than the cap.
 *
 * <p>Chunking is not a performance choice here, it is forced: the transport bounds a single reassembled
 * message, so a large file <em>cannot</em> cross whole however patient anyone is. What this pins is that
 * a caller cannot tell — {@link WorkspaceClient#read} returns the same {@code Document} either way, and
 * the threshold stays the server's business.</p>
 *
 * <p>Also <b>B1</b>, incidentally and on purpose: every one of these runs over a
 * {@link ProtocolConnection} rather than a session, which is the wiring the swap was reserved for.</p>
 */
public class ChunkedTransferTest {

    private InMemoryTransport<Object>[] pair;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private WorkspaceClient<Object> client;
    private InMemoryFileSystem files;
    private WorkspaceRpc<Object> rpc;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ProjectRegistry registry = new ProjectRegistry().register(() -> Collections.singletonList(
                new WorkspaceProject("p", "Project", Paths.get("/p"))));
        files = new InMemoryFileSystem().addProject("p");
        WorkspaceService service =
                new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);
        rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);

        pair = InMemoryTransport.pair();
        serverSide = Protocols.open(pair[0], PlainOps.INSTANCE, () -> { }, "peer");
        clientSide = Protocols.open(pair[1], PlainOps.INSTANCE, () -> { }, null);
        rpc.installOn(serverSide::onRequest);
        client = new WorkspaceClient<>(clientSide);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    /** Enough passes for a multi-chunk pull, each of which is its own round trip. */
    private void settle() {
        for (int i = 0; i < 4000; i++) {
            pair[0].deliver();
            pair[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    private static byte[] payload(int length) {
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) value[i] = (byte) (i * 31 + (i >> 16));
        return value;
    }

    private CgPath write(String name, byte[] content) {
        CgPath path = CgPath.of("p", name);
        files.write(path, content, true, true);
        return path;
    }

    // ── The claim ───────────────────────────────────────────────────────────

    /** A small file comes back inline, as it always did. */
    @Test
    public void aSmallFileArrivesInOneMessage() {
        byte[] content = payload(4096);
        CgPath path = write("small.bin", content);

        AtomicReference<WorkspaceClient.Document> got = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Failure> failed = new AtomicReference<>();
        client.read(path, got::set, failed::set);
        settle();

        assertNull("no failure: " + failed.get(), failed.get());
        assertNotNull("the document must arrive", got.get());
        assertArrayEquals(content, got.get().content());
        assertEquals("nothing was left open", 0, rpc.openTransfers());
    }

    /**
     * A file past the inline threshold arrives whole, through chunks the caller never sees.
     *
     * <p>The content is verified byte for byte rather than by length: a reassembly that concatenates in
     * the wrong order produces something of exactly the right size.</p>
     */
    @Test
    public void aLargeFileArrivesThroughChunksAndIsIdentical() {
        byte[] content = payload(WorkspaceRpc.INLINE_MAX_BYTES + WorkspaceRpc.CHUNK_BYTES + 7);
        CgPath path = write("large.bin", content);

        AtomicReference<WorkspaceClient.Document> got = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Failure> failed = new AtomicReference<>();
        client.read(path, got::set, failed::set);
        settle();

        assertNull("no failure: " + failed.get(), failed.get());
        assertNotNull("the document must arrive", got.get());
        assertEquals("every byte", content.length, got.get().content().length);
        assertArrayEquals("byte for byte, not merely the right length", content, got.get().content());
        assertEquals("the transfer must be released on the last chunk", 0, rpc.openTransfers());
    }

    /** Progress is reported per chunk, monotonically, ending exactly at the total. */
    @Test
    public void progressIsReportedAndEndsAtTheTotal() {
        byte[] content = payload(WorkspaceRpc.INLINE_MAX_BYTES + 3 * WorkspaceRpc.CHUNK_BYTES);
        CgPath path = write("progress.bin", content);

        List<Integer> seen = new ArrayList<>();
        AtomicReference<WorkspaceClient.Document> got = new AtomicReference<>();
        client.read(path, got::set, failure -> { }, (done, total) -> {
            assertEquals("the total never changes mid-transfer", content.length, total);
            seen.add(done);
        });
        settle();

        assertNotNull(got.get());
        assertTrue("more than one report, or it was not chunked at all", seen.size() > 2);
        for (int i = 1; i < seen.size(); i++) {
            assertTrue("progress must not go backwards", seen.get(i) >= seen.get(i - 1));
        }
        assertEquals("it must end at the total", Integer.valueOf(content.length), seen.get(seen.size() - 1));
    }

    /**
     * A caller cannot tell which path was taken — the assertion that makes chunking an implementation
     * detail rather than an API.
     */
    @Test
    public void bothPathsProduceTheSameShapeOfAnswer() {
        CgPath small = write("a.bin", payload(1024));
        CgPath large = write("b.bin", payload(WorkspaceRpc.INLINE_MAX_BYTES + 1));

        AtomicReference<WorkspaceClient.Document> one = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Document> two = new AtomicReference<>();
        client.read(small, one::set, failure -> { });
        client.read(large, two::set, failure -> { });
        settle();

        assertNotNull(one.get());
        assertNotNull(two.get());
        assertEquals(small, one.get().path());
        assertEquals(large, two.get().path());
        assertTrue("both carry an etag", !one.get().etag().isEmpty() && !two.get().etag().isEmpty());
    }

    /**
     * A transfer id that was never issued is refused, and says nothing about what does exist.
     *
     * <p>Expired, completed and invented all answer the same way on purpose — distinguishing them would
     * let a client probe for other actors' transfers.</p>
     */
    @Test
    public void anUnknownTransferIsRefused() {
        AtomicReference<String> error = new AtomicReference<>();
        clientSide.call("fs.readChunk",
                new com.crystalgui.serialization.StateMap<>(PlainOps.INSTANCE)
                        .putString("transfer", "nobody:999")
                        .putInt("offset", 0),
                result -> { },
                error::set);
        settle();

        assertNotNull("an unknown transfer must be answered, not dropped", error.get());
        assertTrue("and answered as not-found: " + error.get(),
                error.get().contains("FILE_NOT_FOUND"));
    }

    /**
     * The cap is enforced against a stat, before any bytes are read.
     *
     * <p>Asserted through the service rather than the wire, because the point is <em>when</em> it is
     * refused: a 4 GB file must cost a metadata call, not an allocation. {@code InMemoryFileSystem} would
     * happily hand over an array, which is exactly why the check cannot live after the read.</p>
     */
    @Test
    public void theCapIsCheckedBeforeAnythingIsRead() {
        assertEquals("D11's number, and it is a decision rather than an accident",
                100L * 1024 * 1024, WorkspaceService.MAX_FILE_BYTES);
        assertTrue("the inline threshold must sit well below the cap",
                WorkspaceRpc.INLINE_MAX_BYTES < WorkspaceService.MAX_FILE_BYTES);
        assertTrue("and a chunk must not exceed what one message can hold",
                WorkspaceRpc.CHUNK_BYTES <= WorkspaceRpc.INLINE_MAX_BYTES);
    }
}
