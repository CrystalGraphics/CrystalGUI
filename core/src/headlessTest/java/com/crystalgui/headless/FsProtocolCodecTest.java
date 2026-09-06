package com.crystalgui.headless;

import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsHello;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.PlainOps;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan/fs-rewrite.md} F2.2, D14 — <b>every payload round-trips, over both ops</b>.
 *
 * <p>What it replaces is 230 lines of hand-packed {@code StateMap} puts at one end and a hand-written
 * reader of forty string keys at the other, with nothing checking that a field written on one side was
 * read on the other. That failure is the quietest kind: the value crosses correctly and is dropped on
 * arrival, and every observable on both sides looks right — which is how identity deltas came to be
 * encoded and never applied (N27's sibling, one layer up).</p>
 *
 * <p>Both {@code JsonOps} and {@code PlainOps}, because the protocol is used over the binary transport
 * and read in a log, and a codec that only works over one of them is a codec that fails whenever
 * somebody looks at it.</p>
 */
public class FsProtocolCodecTest {

    /** Encodes and decodes through both ops, asserting the value survives each. */
    private static <A> void roundTrips(Codec<A> codec, A value) {
        assertEquals("over PlainOps", value, through(codec, PlainOps.INSTANCE, value));
        assertEquals("over JsonOps", value, through(codec, JsonOps.INSTANCE, value));
    }

    private static <A, T> A through(Codec<A> codec, DynamicOps<T> ops, A value) {
        return codec.decode(ops, codec.encode(ops, value));
    }

    // ── Requests ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aPathRequestRoundTrips() {
        roundTrips(FsMessages.pathRequest(), new FsMessages.PathRequest("proj:src/Main.java", "op-7"));
        roundTrips(FsMessages.pathRequest(), new FsMessages.PathRequest("proj:src/Main.java"));
    }

    @Test
    public void aMoveRequestRoundTrips() {
        roundTrips(FsMessages.moveRequest(),
                new FsMessages.MoveRequest("proj:a.txt", "proj:b.txt", true, "op-1"));
        roundTrips(FsMessages.moveRequest(),
                new FsMessages.MoveRequest("proj:a.txt", "proj:b.txt", false, ""));
    }

    @Test
    public void aReadRequestRoundTripsWithAndWithoutItsCondition() {
        roundTrips(FsMessages.readRequest(), new FsMessages.ReadRequest("proj:a.txt", "12:34"));
        roundTrips(FsMessages.readRequest(), new FsMessages.ReadRequest("proj:a.txt", ""));
    }

    @Test
    public void aWriteRequestRoundTripsIncludingItsBytes() {
        byte[] content = "class Main {}\n".getBytes(StandardCharsets.UTF_8);
        FsMessages.WriteRequest sent =
                new FsMessages.WriteRequest("proj:a.java", content, "12:34", false, true, "op-2");
        FsMessages.WriteRequest back = through(FsMessages.writeRequest(), PlainOps.INSTANCE, sent);

        assertEquals(sent.path(), back.path());
        assertArrayEquals(content, back.content());
        assertEquals("12:34", back.etag());
        assertEquals("op-2", back.op());
        assertTrue(back.overwrite());
        assertFalse(back.create());
    }

    /** Bytes with no text encoding at all — the case a naive String round trip corrupts. */
    @Test
    public void arbitraryBytesSurvive() {
        byte[] content = new byte[256];
        for (int i = 0; i < 256; i++) content[i] = (byte) i;
        FsMessages.ChunkResponse back = through(FsMessages.chunkResponse(), JsonOps.INSTANCE,
                new FsMessages.ChunkResponse(content, true));

        assertArrayEquals(content, back.content());
        assertTrue(back.eof());
    }

    @Test
    public void aReadResponseRoundTripsInAllThreeOfItsShapes() {
        byte[] content = "hi".getBytes(StandardCharsets.UTF_8);

        FsMessages.ReadResponse inline = through(FsMessages.readResponse(), PlainOps.INSTANCE,
                new FsMessages.ReadResponse("12:2", content, false, "", 2));
        assertArrayEquals(content, inline.content());
        assertEquals("12:2", inline.etag());

        FsMessages.ReadResponse unchanged = through(FsMessages.readResponse(), PlainOps.INSTANCE,
                new FsMessages.ReadResponse("12:2", new byte[0], true, "", 2));
        assertTrue(unchanged.unchanged());
        assertEquals(0, unchanged.content().length);

        FsMessages.ReadResponse chunked = through(FsMessages.readResponse(), PlainOps.INSTANCE,
                new FsMessages.ReadResponse("12:900", new byte[0], false, "t-1", 900));
        assertEquals("t-1", chunked.transfer());
        assertEquals(900L, chunked.size());
    }

    @Test
    public void aChunkRequestAndAnEtagResponseRoundTrip() {
        roundTrips(FsMessages.chunkRequest(), new FsMessages.ChunkRequest("t-1", 4096L, 2048));
        roundTrips(FsMessages.etagResponse(), new FsMessages.EtagResponse("99:1"));
    }

    // ── Listings and projects ───────────────────────────────────────────────────────────────────

    @Test
    public void aListingRoundTripsWithItsCursor() {
        FsMessages.ListResponse sent = new FsMessages.ListResponse(List.of(
                new FsMessages.Entry("src", true, 0, 0),
                new FsMessages.Entry("Main.java", false, 42, 1700000000000L)), "page-2");
        roundTrips(FsMessages.listResponse(), sent);
        roundTrips(FsMessages.listRequest(), new FsMessages.ListRequest("proj:src", "page-2"));
    }

    @Test
    public void anEmptyListingRoundTrips() {
        roundTrips(FsMessages.listResponse(), new FsMessages.ListResponse(List.of()));
    }

    /** The ignore rules travel, which is what lets the crawl and the tree agree with the server. */
    @Test
    public void aProjectCarriesItsSourceRootsAndItsExcludes() {
        FsMessages.ProjectsResponse sent = new FsMessages.ProjectsResponse(List.of(
                new FsMessages.ProjectEntry("mymod.scripts", "Scripts",
                        List.of("src/main/java"), List.of(".git", "build", "*.class"))));
        FsMessages.ProjectsResponse back =
                through(FsMessages.projectsResponse(), JsonOps.INSTANCE, sent);

        assertEquals(1, back.projects().size());
        assertEquals(List.of(".git", "build", "*.class"), back.projects().get(0).excludes());
        assertEquals(List.of("src/main/java"), back.projects().get(0).sourceRoots());
    }

    // ── Notifications ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>A rename is one event carrying both ends.</b> The wire had {@code modified} and
     * {@code deleted} only, so an external rename arrived as a deletion and the client closed the tab.
     */
    @Test
    public void everyChangeKindRoundTripsIncludingRenamed() {
        FsMessages.ChangedNotification sent = new FsMessages.ChangedNotification(List.of(
                new FsMessages.FileChange("proj:new.txt", FsMessages.ChangeKind.CREATED, "1:0"),
                new FsMessages.FileChange("proj:a.txt", FsMessages.ChangeKind.MODIFIED, "2:9"),
                new FsMessages.FileChange("proj:gone.txt", FsMessages.ChangeKind.DELETED, ""),
                new FsMessages.FileChange("proj:to.txt", FsMessages.ChangeKind.RENAMED, "3:1",
                        "proj:from.txt")));
        FsMessages.ChangedNotification back =
                through(FsMessages.changedNotification(), PlainOps.INSTANCE, sent);

        assertEquals(4, back.changes().size());
        assertEquals(FsMessages.ChangeKind.RENAMED, back.changes().get(3).kind());
        assertEquals("proj:from.txt", back.changes().get(3).from());
        assertEquals("", back.changes().get(0).from());
    }

    /** Presence carries whether the other peer is EDITING, not merely whether it has the file open. */
    @Test
    public void presenceCarriesTheEditingFlag() {
        FsMessages.PresenceNotification back = through(FsMessages.presenceNotification(),
                JsonOps.INSTANCE, new FsMessages.PresenceNotification(List.of(
                        new FsMessages.PresenceEntry("proj:a.txt", "steve", true),
                        new FsMessages.PresenceEntry("proj:a.txt", "alex", false))));

        assertTrue(back.entries().get(0).editing());
        assertFalse(back.entries().get(1).editing());
    }

    @Test
    public void capabilitiesRoundTrip() {
        roundTrips(FsMessages.capabilitiesNotification(), new FsMessages.CapabilitiesNotification(
                List.of(new FsMessages.ProjectCapability("mymod.scripts", true, false))));
    }

    // ── Tolerance ───────────────────────────────────────────────────────────────────────────────

    /**
     * A field a client has never heard of costs it nothing.
     *
     * <p>Which is the version policy: {@link FsHello#VERSION} bumps only for a change a tolerant reader
     * cannot absorb, so an additive field needs no negotiation at all.
     */
    @Test
    public void anUnknownFieldIsIgnored() {
        // Built through the ops rather than parsed from text, because the property under test is the
        // READER's tolerance and not the parser's: a map with a field the record has no slot for.
        java.util.Map<Object, Object> raw = new java.util.LinkedHashMap<>();
        raw.put(PlainOps.INSTANCE.createString("path"), PlainOps.INSTANCE.createString("proj:a.txt"));
        raw.put(PlainOps.INSTANCE.createString("op"), PlainOps.INSTANCE.createString("op-1"));
        raw.put(PlainOps.INSTANCE.createString("somethingNewer"), PlainOps.INSTANCE.createNumber(42));
        FsMessages.PathRequest back = FsMessages.pathRequest()
                .decode(PlainOps.INSTANCE, PlainOps.INSTANCE.createMap(raw));

        assertEquals("proj:a.txt", back.path());
        assertEquals("op-1", back.op());
    }

    // ── The greeting ────────────────────────────────────────────────────────────────────────────

    @Test
    public void theGreetingRoundTrips() {
        roundTrips(FsHello.CODEC, new FsHello(1, false, FsHello.WINDOWS_RESERVED, 255,
                FsHello.DEFAULT_SERVICES_TIER, FsHello.DEFAULT_READ_ONLY_TIER, 100L * 1024 * 1024));
    }

    @Test
    public void theNameRuleRefusesWhatAHostRefuses() {
        FsHello hello = FsHello.unknown();

        assertTrue(hello.isValidName("Main.java"));
        assertTrue(hello.isValidName(".gitignore"));
        assertFalse("reserved, and on the stem so an extension does not rescue it",
                hello.isValidName("CON"));
        assertFalse(hello.isValidName("con.txt"));
        assertFalse("Windows strips a trailing dot, creating a file nobody named",
                hello.isValidName("name."));
        assertFalse(hello.isValidName("name "));
        assertFalse(hello.isValidName(""));
        assertFalse(hello.isValidName(".."));
        assertFalse(hello.isValidName("a/b"));
        assertFalse(hello.isValidName("a b"));
    }

    @Test
    public void theSizeTiersAreOrdered() {
        FsHello hello = FsHello.unknown();

        assertEquals(FsHello.SizeTier.ORDINARY, hello.tierOf(1024));
        assertEquals(FsHello.SizeTier.NO_SERVICES, hello.tierOf(10L * 1024 * 1024));
        assertEquals(FsHello.SizeTier.READ_ONLY, hello.tierOf(60L * 1024 * 1024));
        assertEquals(FsHello.SizeTier.REFUSED, hello.tierOf(200L * 1024 * 1024));
    }

    /** Case-sensitive is the conservative default: the failure it produces is one the etag catches. */
    @Test
    public void anUnknownServerIsAssumedCaseSensitive() {
        assertTrue(FsHello.unknown().caseSensitive());
    }

    // ── Errors ──────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A conflict carries the etag as a field.</b> It travelled as {@code "CONFLICT " + etag} and was
     * recovered by splitting a sentence on a space.
     */
    @Test
    public void aConflictIsStructured() {
        FsError error = FsError.conflict("the file moved", "99:7");

        assertEquals(FsError.CONFLICT, error.code());
        assertEquals("99:7", error.actualEtag());
        assertTrue(error.is(FsError.CONFLICT));
    }

    @Test
    public void everyOtherErrorHasNoEtag() {
        assertNull(new FsError(FsError.NOT_FOUND, "no such file").actualEtag());
    }

    @Test
    public void aProviderErrorMapsToACode() {
        assertEquals(FsError.NOT_FOUND,
                FsError.of(CgFileError.FILE_NOT_FOUND, "x").code());
        assertEquals(FsError.NOT_PERMITTED,
                FsError.of(CgFileError.NO_PERMISSIONS, "x").code());
        assertEquals(FsError.TOO_LARGE,
                FsError.of(CgFileError.FILE_TOO_LARGE, "x").code());
        assertEquals(FsError.ALREADY_EXISTS,
                FsError.of(CgFileError.FILE_EXISTS, "x").code());
    }
}
