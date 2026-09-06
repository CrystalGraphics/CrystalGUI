package com.crystalgui.headless;

import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Backup;
import com.crystalgui.fs.client.Health;
import com.crystalgui.fs.client.LocalHistory;
import com.crystalgui.fs.protocol.FsError;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan/fs-rewrite.md} F4.3 and F4.4 — backup, local history and connection health.
 *
 * <p>All three are client-local by construction: the server is what may have gone away, so a backup
 * that needed the wire would be unavailable in precisely the situation it exists for.</p>
 */
public class WorkspaceClientFacadesTest {

    private static Resource file(String path) {
        return Resource.of(CgPath.parse("proj:" + path));
    }

    private static final Resource MAIN = file("src/Main.java");

    // ── Backup ──────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>N32.</b> Phase 5.3 designed this and it was never built, so a crash, a quit or a dropped
     * connection lost the edit — and a save needs the server, so a workspace whose connection has gone
     * is one where nothing can be saved at all.
     */
    @Test
    public void anUnsavedDocumentSurvivesAQuit() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        new Backup(storage).save(MAIN, "half-typed".getBytes(StandardCharsets.UTF_8), "1:9");

        // The client goes away entirely and a new one is built over the same store.
        Backup afterRestart = new Backup(storage);
        List<Backup.Entry> offered = afterRestart.restorable();

        assertEquals(1, offered.size());
        assertEquals(MAIN, offered.get(0).resource());
        assertArrayEquals("half-typed".getBytes(StandardCharsets.UTF_8), offered.get(0).content());
        assertEquals("and the etag it was in step with", "1:9", offered.get(0).etag());
    }

    /** A save discards it, so the next open is not offered work that is already on disk. */
    @Test
    public void savingDiscardsTheBackup() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        Backup backup = new Backup(storage);
        backup.save(MAIN, "typed".getBytes(StandardCharsets.UTF_8), "1:9");

        backup.discard(MAIN);

        assertTrue(backup.restorable().isEmpty());
        assertNull(backup.get(MAIN));
    }

    @Test
    public void backingUpTwiceKeepsOneEntryPerDocument() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        Backup backup = new Backup(storage);

        backup.save(MAIN, "one".getBytes(StandardCharsets.UTF_8), "1:9");
        backup.save(MAIN, "two".getBytes(StandardCharsets.UTF_8), "1:9");

        assertEquals(1, backup.restorable().size());
        assertArrayEquals("two".getBytes(StandardCharsets.UTF_8),
                backup.restorable().get(0).content());
    }

    @Test
    public void severalDocumentsAreOfferedTogether() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        Backup backup = new Backup(storage);
        backup.save(MAIN, "a".getBytes(StandardCharsets.UTF_8), "");
        backup.save(file("README.md"), "b".getBytes(StandardCharsets.UTF_8), "");

        assertEquals(2, backup.restorable().size());
    }

    /** Arbitrary bytes, since a backup covers any document kind and not only text. */
    @Test
    public void arbitraryBytesSurviveABackup() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        byte[] content = new byte[256];
        for (int i = 0; i < 256; i++) content[i] = (byte) i;
        Backup backup = new Backup(storage);

        backup.save(file("a.bin"), content, "");

        assertArrayEquals(content, backup.get(file("a.bin")).content());
    }

    /** A record this build cannot read is discarded — restoring wrong bytes is worse than losing an edit. */
    @Test
    public void anUnreadableRecordIsDiscardedRatherThanGuessedAt() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        Backup backup = new Backup(storage);
        backup.save(MAIN, "typed".getBytes(StandardCharsets.UTF_8), "");
        String name = storage.list().get(0);
        storage.write(name, "v=99\nresource=proj:src/Main.java\ncontent=????");

        assertTrue(backup.restorable().isEmpty());
        assertTrue("and it is not left to be re-read for ever", storage.list().isEmpty());
    }

    // ── Local history ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>D13.</b> A conflict resolved by overwriting is currently the end of the other version, with
     * nowhere it survives.
     */
    @Test
    public void keepMineIsRecoverableFromLocalHistory() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        AtomicLong clock = new AtomicLong(1000);
        LocalHistory history = new LocalHistory(storage, clock::get, 10, Long.MAX_VALUE);

        history.record(MAIN, "theirs".getBytes(StandardCharsets.UTF_8));
        clock.addAndGet(1000);
        history.record(MAIN, "mine, which overwrote it".getBytes(StandardCharsets.UTF_8));

        List<LocalHistory.Entry> entries = history.entriesOf(MAIN);
        assertEquals(2, entries.size());
        assertArrayEquals("newest first", "mine, which overwrote it".getBytes(StandardCharsets.UTF_8),
                entries.get(0).content());
        assertArrayEquals("and the version that was overwritten is still there",
                "theirs".getBytes(StandardCharsets.UTF_8), entries.get(1).content());
    }

    /**
     * <b>N8.</b> The merge base was a comment pointing at a content cache any read could evict. The
     * newest history entry is what the file held when it was last in step with this client.
     */
    @Test
    public void theMergeBaseIsTheLastSavedVersion() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        AtomicLong clock = new AtomicLong(1000);
        LocalHistory history = new LocalHistory(storage, clock::get, 10, Long.MAX_VALUE);

        assertNull("a file with no history has no base", history.mergeBase(MAIN));

        history.record(MAIN, "as it was".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("as it was".getBytes(StandardCharsets.UTF_8), history.mergeBase(MAIN));
    }

    @Test
    public void theHistoryIsBoundedByCount() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        AtomicLong clock = new AtomicLong(1000);
        LocalHistory history = new LocalHistory(storage, clock::get, 3, Long.MAX_VALUE);

        for (int i = 0; i < 6; i++) {
            clock.addAndGet(100);
            history.record(MAIN, ("save " + i).getBytes(StandardCharsets.UTF_8));
        }

        List<LocalHistory.Entry> entries = history.entriesOf(MAIN);
        assertEquals(3, entries.size());
        assertArrayEquals("save 5".getBytes(StandardCharsets.UTF_8), entries.get(0).content());
    }

    @Test
    public void theHistoryIsBoundedByAge() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        AtomicLong clock = new AtomicLong(1000);
        LocalHistory history = new LocalHistory(storage, clock::get, 10, 5000);
        history.record(MAIN, "old".getBytes(StandardCharsets.UTF_8));

        clock.addAndGet(10_000);
        history.record(MAIN, "new".getBytes(StandardCharsets.UTF_8));

        List<LocalHistory.Entry> entries = history.entriesOf(MAIN);
        assertEquals(1, entries.size());
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8), entries.get(0).content());
    }

    /** A 50 MB log's history is a way to fill a disk. */
    @Test
    public void aLargeFileGetsNoHistory() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        LocalHistory history = new LocalHistory(storage);

        history.record(MAIN, new byte[(int) LocalHistory.MAX_FILE_BYTES + 1]);

        assertTrue(history.entriesOf(MAIN).isEmpty());
    }

    // ── Health ──────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>D24.</b> Every operation crosses a socket, and when one is slow nothing on screen says so — a
     * tab that will not open and an application that has hung look identical.
     */
    @Test
    public void healthReportsARoundTripAndWhatIsOutstanding() {
        AtomicLong clock = new AtomicLong(0);
        Health health = new Health(clock::get);

        long stamp = health.asked();
        assertEquals(1, health.inFlight());
        clock.set(58);
        health.answered(stamp);

        assertEquals(0, health.inFlight());
        assertEquals(58, health.roundTripMillis());
        assertEquals(1, health.requestsSent());
        assertEquals(1, health.answersReceived());
    }

    /** Smoothed, so one slow answer moves the estimate without defining it. */
    @Test
    public void oneSlowAnswerDoesNotDefineTheEstimate() {
        AtomicLong clock = new AtomicLong(0);
        Health health = new Health(clock::get);
        for (int i = 0; i < 10; i++) {
            long stamp = health.asked();
            clock.addAndGet(10);
            health.answered(stamp);
        }
        assertEquals(10, health.roundTripMillis());

        long stamp = health.asked();
        clock.addAndGet(1000);
        health.answered(stamp);

        assertTrue("it moved", health.roundTripMillis() > 10);
        assertTrue("but one outlier did not become the reading",
                health.roundTripMillis() < 200);
    }

    @Test
    public void healthRemembersTheLastFailure() {
        Health health = new Health(() -> 0L);
        health.asked();

        health.failed(new FsError(FsError.CONFLICT, "moved"));

        assertNotNull(health.lastError());
        assertEquals(FsError.CONFLICT, health.lastError().code());
        assertEquals("and it stopped being outstanding", 0, health.inFlight());
    }

    /** Cleared on a reconnect: the last error described a peer nobody is talking to. */
    @Test
    public void reconnectingClearsTheLastError() {
        Health health = new Health(() -> 0L);
        health.failed(new FsError(FsError.FAILED, "gone"));

        health.reset();

        assertNull(health.lastError());
        assertEquals(0, health.inFlight());
    }

    @Test
    public void aReadoutSaysSomethingBeforeAnythingHasBeenAnswered() {
        assertEquals("—", new Health(() -> 0L).toString());
    }
}
