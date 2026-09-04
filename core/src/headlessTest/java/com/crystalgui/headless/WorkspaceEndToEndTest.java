package com.crystalgui.headless;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.async.Stream;
import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsHello;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan_fs_rewrite.md} F4 — <b>the new client against the new binding, over a real transport.</b>
 *
 * <p>Both ends together, because a protocol tested from one side is a protocol tested against a mock
 * of the other. Everything asserted here is what a caller sees: a {@code Reply} that settles, a
 * structured failure, a coalesced round trip, a batch that reports per item.</p>
 */
public class WorkspaceEndToEndTest {

    private static Resource file(String path) {
        return Resource.of(CgPath.parse("proj:" + path));
    }

    private static final Resource MAIN = file("src/Main.java");

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private InMemoryFileSystem files;
    private WorkspaceService service;
    private WatchHub hub;
    private WorkspaceBinding<Object> binding;
    private Workspace workspace;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        files = new InMemoryFileSystem()
                .seed("proj:src/Main.java", "class Main {}")
                .seed("proj:src/Other.java", "class Other {}")
                .seed("proj:README.md", "# hi");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(new ProjectInfo("proj", "Proj", List.of("src")),
                        Paths.get("/srv/proj"), List.of(".git", "*.class"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        hub = new WatchHub(service);

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        binding = new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, "alice",
                PlainOps.INSTANCE);
        binding.installOn(serverSide::onRequest);
        workspace = Workspace.of(clientSide);
        pump();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void pump() {
        for (int i = 0; i < 12; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    /** Drives one server tick's worth of watch notifications to the client. */
    private void notifyChanges(List<CgFileEvent> events) {
        Map<Object, List<FsMessages.FileChange>> byPeer = hub.tick(WorkspaceActor.LOCAL, events);
        List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
        if (!mine.isEmpty()) {
            serverSide.notify(FsMethods.CHANGED, new com.crystalgui.serialization.StateMap<>(
                    PlainOps.INSTANCE,
                    FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                            new FsMessages.ChangedNotification(mine))));
        }
        pump();
    }

    // ── The greeting ────────────────────────────────────────────────────────────────────────────

    /** <b>D21.</b> The server's own facts, which the client used to guess at. */
    @Test
    public void theGreetingArrivesAndCarriesTheServersFacts() {
        FsHello hello = workspace.server();

        assertEquals(FsHello.VERSION, hello.protocolVersion());
        assertTrue("the in-memory provider is case-sensitive and says so", hello.caseSensitive());
        assertFalse("and a name this host would refuse is refused before the round trip",
                workspace.capabilities().isValidName("CON"));
        assertTrue(workspace.capabilities().isValidName("Main.java"));
    }

    @Test
    public void theSizeTiersComeFromTheServer() {
        assertEquals(FsHello.SizeTier.ORDINARY, workspace.capabilities().sizeTierOf(1024));
        assertEquals(FsHello.SizeTier.NO_SERVICES,
                workspace.capabilities().sizeTierOf(10L * 1024 * 1024));
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aReadAnswersTheBytesAndAnEtag() {
        Reply<FsMessages.ReadResponse> reply = workspace.files().readResponse(MAIN);
        pump();

        assertNotNull(reply.result());
        assertArrayEquals("class Main {}".getBytes(StandardCharsets.UTF_8), reply.result().content());
        assertFalse(reply.result().etag().isEmpty());
    }

    /** <b>N18.</b> Two callers asking the same question while one is in flight are one round trip. */
    @Test
    public void twoReadsOfOneFileAreOneRoundTrip() {
        int before = serverSide.router().pendingRequests();
        Reply<FsMessages.ReadResponse> first = workspace.files().readResponse(MAIN);
        Reply<FsMessages.ReadResponse> second = workspace.files().readResponse(MAIN);

        assertSame("the second caller joins the first's question", first, second);
        pump();
        assertNotNull(first.result());
    }

    /** And two reads of DIFFERENT files are two, which is the counter-control. */
    @Test
    public void twoReadsOfTwoFilesAreTwo() {
        Reply<FsMessages.ReadResponse> a = workspace.files().readResponse(MAIN);
        Reply<FsMessages.ReadResponse> b = workspace.files().readResponse(file("README.md"));

        assertFalse(a == b);
        pump();
        assertArrayEquals("# hi".getBytes(StandardCharsets.UTF_8), b.result().content());
    }

    /** A conditional read that matches sends no bytes — HTTP's If-None-Match, reachable at last. */
    @Test
    public void aConditionalReadThatMatchesSendsNothing() {
        Reply<FsMessages.ReadResponse> first = workspace.files().readResponse(MAIN);
        pump();
        String etag = first.result().etag();

        Reply<FsMessages.ReadResponse> again = workspace.files().readResponse(MAIN, etag);
        pump();

        assertTrue(again.result().unchanged());
        assertEquals(0, again.result().content().length);
    }

    /** A file above the inline limit is pulled through as a stream, chunk by chunk. */
    @Test
    public void aLargeFileArrivesAsAStreamOfChunks() {
        StringBuilder big = new StringBuilder();
        while (big.length() < WorkspaceBinding.INLINE_LIMIT + 5000) big.append("0123456789");
        files.seed("proj:big.txt", big.toString());

        List<byte[]> chunks = new ArrayList<>();
        Stream<byte[]> stream = workspace.files().readStream(file("big.txt")).onPartial(chunks::add);
        pump();

        assertTrue("it did not arrive in one message", chunks.size() > 1);
        assertNotNull("and it settled", stream.result());
        int total = 0;
        for (byte[] chunk : chunks) total += chunk.length;
        assertEquals(big.length(), total);
    }

    /**
     * <b>...and {@code Workspace.read} gives every byte of it, not the empty inline field.</b>
     *
     * <p>The tier above the protocol, which is where this went wrong. {@code fs/read} answers inline or
     * with a TRANSFER, and which one is the server's decision against its own limit — so a caller that
     * reads {@code content} and stops is correct for every small file and silently wrong for a large
     * one. It read {@code ReadResponse::content} and stopped: a file over 256 KB opened as an EMPTY
     * document, marked CLEAN, and the first save wrote that emptiness over it.</p>
     *
     * <p>The test above proves the chunks arrive; this proves the one caller every document goes
     * through actually joins them. Nothing between the two was checking.</p>
     */
    @Test
    public void aLargeFileIsReadWholeByTheOneCallerDocumentsUse() {
        StringBuilder big = new StringBuilder();
        while (big.length() < WorkspaceBinding.INLINE_LIMIT + 5000) big.append("0123456789");
        files.seed("proj:big.txt", big.toString());

        Reply<FileOperations.Content> reply = workspace.read(file("big.txt"));
        pump();

        assertNotNull("the read settled", reply.result());
        assertEquals("every byte, not the empty inline field",
                big.length(), reply.result().bytes().length);
    }

    /**
     * <b>A copy of a large file copies all of it.</b>
     *
     * <p>{@code copy} is a read and a create, because the server has no copy verb — so it inherited the
     * truncation whole: the destination was created EMPTY and the operation reported success. Same for
     * the explorer's copy-drop, which spells the pair out itself.</p>
     */
    @Test
    public void aCopyOfALargeFileCopiesAllOfIt() {
        String big = bigText();
        files.seed("proj:big.txt", big);

        workspace.files().copy(file("big.txt"), file("copy.txt"));
        for (int i = 0; i < 6; i++) pump();

        assertEquals(big.length(),
                files.read(CgPath.parse("proj:copy.txt")).length);
    }

    /**
     * <b>Two readers of one large file are one transfer.</b>
     *
     * <p>Not only a saving. A transfer id is handed out once and the server destroys it when a pull
     * reaches EOF, so two readers sharing one coalesced {@code readResponse} would pull the same id and
     * whichever finished first would take it out from under the other — the second settling with
     * {@code no such transfer} on a file that is perfectly readable. Two panes restoring one large file
     * is exactly that, which is why {@code readWhole} coalesces rather than leaving it to callers.</p>
     */
    @Test
    public void twoWholeReadsOfOneLargeFileAreOneTransfer() {
        String big = bigText();
        files.seed("proj:big.txt", big);

        Reply<FileOperations.Content> first = workspace.files().readWhole(file("big.txt"));
        Reply<FileOperations.Content> second = workspace.files().readWhole(file("big.txt"));
        assertSame("the second reader joins the first's transfer", first, second);
        for (int i = 0; i < 6; i++) pump();

        assertNotNull("it settled: " + first.error(), first.result());
        assertEquals(big.length(), first.result().bytes().length);
    }

    /** ...and a later read is a NEW one, which is the counter-control for the coalescing above. */
    @Test
    public void aWholeReadAfterTheFirstSettlesIsAFreshOne() {
        files.seed("proj:big.txt", bigText());

        Reply<FileOperations.Content> first = workspace.files().readWhole(file("big.txt"));
        for (int i = 0; i < 6; i++) pump();
        Reply<FileOperations.Content> later = workspace.files().readWhole(file("big.txt"));

        assertNotSame("a settled read is not answered again", first, later);
    }

    /** Comfortably over the server's inline limit, so it is answered as a transfer. */
    private static String bigText() {
        StringBuilder big = new StringBuilder();
        while (big.length() < WorkspaceBinding.INLINE_LIMIT + 5000) big.append("0123456789");
        return big.toString();
    }

    @Test
    public void aListingCarriesItsEntries() {
        Reply<FsMessages.ListResponse> reply = workspace.files().list(file("src"));
        pump();

        List<String> names = new ArrayList<>();
        for (FsMessages.Entry entry : reply.result().entries()) names.add(entry.name());
        assertTrue(names.contains("Main.java"));
        assertTrue(names.contains("Other.java"));
    }

    /** <b>D22.</b> The ignore rules travel, so the client's crawl can agree with the server. */
    @Test
    public void theProjectsIgnoreRulesReachTheClient() {
        Reply<List<FsMessages.ProjectEntry>> reply = workspace.projects();
        pump();

        FsMessages.ProjectEntry project = reply.result().get(0);
        assertEquals(List.of(".git", "*.class"), project.excludes());
        assertEquals(List.of("src"), project.sourceRoots());
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aWriteAnswersTheNewEtag() {
        Reply<FsMessages.ReadResponse> read = workspace.files().readResponse(MAIN);
        pump();

        Reply<String> write = workspace.files()
                .write(MAIN, "changed".getBytes(StandardCharsets.UTF_8), read.result().etag());
        pump();

        assertNotNull(write.result());
        assertFalse(write.result().isEmpty());
        assertEquals("changed", new String(files.read(CgPath.parse("proj:src/Main.java")),
                StandardCharsets.UTF_8));
    }

    /**
     * <b>N17.</b> A conflict is structured, and it carries the etag the file actually holds — which is
     * the only thing that makes it resolvable rather than merely reported.
     */
    @Test
    public void aConflictIsStructuredAndCarriesTheActualEtag() {
        Reply<FsMessages.ReadResponse> read = workspace.files().readResponse(MAIN);
        pump();
        String stale = read.result().etag();
        service.write(WorkspaceActor.LOCAL, CgPath.parse("proj:src/Main.java"),
                "somebody else".getBytes(StandardCharsets.UTF_8), null);

        List<ReplyError> errors = new ArrayList<>();
        workspace.files().write(MAIN, "mine".getBytes(StandardCharsets.UTF_8), stale)
                .onError(errors::add);
        pump();

        assertEquals(1, errors.size());
        assertEquals(FsError.CONFLICT, errors.get(0).code());
        assertTrue(errors.get(0) instanceof FsError);
        assertNotNull("and the etag as a FIELD, not inside a sentence",
                ((FsError) errors.get(0)).actualEtag());
    }

    /** A name the host would refuse is refused, and audited as a refusal. */
    @Test
    public void aReservedNameIsRefused() {
        List<ReplyError> errors = new ArrayList<>();
        workspace.files().create(file("CON"), "x".getBytes(StandardCharsets.UTF_8))
                .onError(errors::add);
        pump();

        assertEquals(1, errors.size());
        assertEquals(FsError.INVALID_PATH, errors.get(0).code());
    }

    /** <b>D17.</b> A save and a reload of one file must never interleave. */
    @Test
    public void twoOperationsOnOneResourceAreSerialised() {
        Reply<FsMessages.ReadResponse> read = workspace.files().readResponse(MAIN);
        pump();
        String etag = read.result().etag();

        List<String> order = new ArrayList<>();
        workspace.files().write(MAIN, "first".getBytes(StandardCharsets.UTF_8), etag)
                .then(ok -> order.add("first"));
        // Unconditional, because the first write moved the etag -- which is exactly why the second must
        // not start until the first has finished.
        workspace.files().write(MAIN, "second".getBytes(StandardCharsets.UTF_8), null)
                .then(ok -> order.add("second"));
        pump();

        assertEquals(List.of("first", "second"), order);
        assertEquals("second", new String(files.read(CgPath.parse("proj:src/Main.java")),
                StandardCharsets.UTF_8));
    }

    // ── Batches ─────────────────────────────────────────────────────────────────────────────────

    /** A batch settles when its members do, and reports failures per item rather than as one. */
    @Test
    public void aBatchReportsPerItemAndDoesNotFailWholesale() {
        Reply<FileOperations.BatchResult> batch = workspace.files().batch("Create three", work -> {
            work.create(file("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
            work.create(file("b.txt"), "b".getBytes(StandardCharsets.UTF_8));
            // Already there: this one must fail without taking the other two with it.
            work.create(file("README.md"), "clobber".getBytes(StandardCharsets.UTF_8));
        });
        pump();

        FileOperations.BatchResult result = batch.result();
        assertNotNull("the batch settled", result);
        assertEquals(2, result.succeeded());
        assertEquals(1, result.failures().size());
        assertEquals(file("README.md"), result.failures().get(0).resource());
        assertFalse(result.isCompletelySuccessful());
        assertEquals("and the two that worked stayed done", "a",
                new String(files.read(CgPath.parse("proj:a.txt")), StandardCharsets.UTF_8));
    }

    // ── Watching ────────────────────────────────────────────────────────────────────────────────

    /** A folder watch, which the old client could not express at all. */
    @Test
    public void aFolderWatchDeliversABatchOfChanges() {
        List<List<FsMessages.FileChange>> batches = new ArrayList<>();
        Workspace.Watch watch = workspace.watch(file("src"), false);
        watch.onChanged.connect(batches::add);
        pump();

        service.write(WorkspaceActor.LOCAL, CgPath.parse("proj:src/Main.java"),
                "changed".getBytes(StandardCharsets.UTF_8), null);
        service.write(WorkspaceActor.LOCAL, CgPath.parse("proj:src/Other.java"),
                "also changed".getBytes(StandardCharsets.UTF_8), null);
        notifyChanges(List.of(
                CgFileEvent.of(CgFileEvent.Kind.MODIFIED, CgPath.parse("proj:src/Main.java")),
                CgFileEvent.of(CgFileEvent.Kind.MODIFIED, CgPath.parse("proj:src/Other.java"))));

        assertEquals("one batch, not one call per file", 1, batches.size());
        assertEquals(2, batches.get(0).size());
    }

    /** <b>N16.</b> Two subscribers, and the second does not evict the first. */
    @Test
    public void twoSubscribersBothHear() {
        int[] first = {0};
        int[] second = {0};
        Workspace.Watch watch = workspace.watch(MAIN, false);
        watch.onChanged.connect(changes -> first[0]++);
        watch.onChanged.connect(changes -> second[0]++);
        pump();

        service.write(WorkspaceActor.LOCAL, CgPath.parse("proj:src/Main.java"),
                "changed".getBytes(StandardCharsets.UTF_8), null);
        notifyChanges(List.of(CgFileEvent.of(CgFileEvent.Kind.MODIFIED,
                CgPath.parse("proj:src/Main.java"))));

        assertEquals(1, first[0]);
        assertEquals("a second subscriber must not silently evict the first", 1, second[0]);
    }

    /** Two consumers of one folder cost one subscription, and the last release unwatches. */
    @Test
    public void aWatchIsSharedAndReleasedByItsLastHolder() {
        Workspace.Watch one = workspace.watch(file("src"), false);
        Workspace.Watch two = workspace.watch(file("src"), false);
        pump();
        assertSame(one, two);
        assertEquals(1, hub.subscriptionCount("alice"));

        one.dispose();
        pump();
        assertEquals("one holder left", 1, hub.subscriptionCount("alice"));

        two.dispose();
        pump();
        assertEquals(0, hub.subscriptionCount("alice"));
    }

    // ── Failures ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMissingFileIsNotFound() {
        List<ReplyError> errors = new ArrayList<>();
        workspace.files().readResponse(file("nope.txt")).onError(errors::add);
        pump();

        assertEquals(FsError.NOT_FOUND, errors.get(0).code());
    }

    @Test
    public void aFailedReadCarriesNoValue() {
        Reply<FsMessages.ReadResponse> reply = workspace.files().readResponse(file("nope.txt"));
        pump();

        assertNull(reply.result());
        assertNotNull(reply.error());
    }
}
