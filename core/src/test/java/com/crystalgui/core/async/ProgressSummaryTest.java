package com.crystalgui.core.async;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The readout beside the bar — {@code "12.4 MB of 110 MB · 40s left"}.
 *
 * <p>The state <b>formats</b> the estimate and never computes one: the measurement is a sliding
 * {@link RateEstimator} and the refresh throttle are both {@link JobContext}'s, because only it sees
 * consecutive readings. Three estimators were invented here before anybody read how curl, wget and
 * Chromium do it, and every one failed within a minute of a user watching it — so what is asserted below
 * is the split, the formatting, and the throttle, not an algorithm of our own.</p>
 */
public class ProgressSummaryTest {

    private static final long START = 1_000_000L;

    /** A reading with no estimate on it — the shape before there is anything to measure. */
    private static ProgressState bytes(long done, long total) {
        return new ProgressState("Downloading", "", done, total, START, START + 5_000,
                Progress.Unit.BYTES);
    }

    /** A reading carrying the estimate {@link JobContext} decided. */
    private static ProgressState bytes(long done, long total, long secondsLeft) {
        return new ProgressState("Downloading", "", done, total, START, START + 5_000,
                Progress.Unit.BYTES, secondsLeft);
    }

    /** A count of files is what the bar already says; repeating it as a size would be a lie. */
    @Test
    public void countingJobsGetNoReadout() {
        ProgressState items = new ProgressState("Indexing", "", 412, 1178, START);
        assertEquals("the default unit is ITEMS", Progress.Unit.ITEMS, items.unit());
        assertNull(items.summary());
    }

    @Test
    public void aTransferReadsAsSizeOfSize() {
        assertEquals("12.4 MB of 48.9 MB", bytes(13_000_000L, 51_300_000L).summary());
        assertEquals("12.4 MB of 48.9 MB · 40s left", bytes(13_000_000L, 51_300_000L, 40).summary());
    }

    /**
     * <b>Binary multiples</b>, because that is what every file manager on the platforms this runs on
     * reports — a download saying 48.9 MB against a file the OS calls 46.6 MB reads as two files.
     */
    @Test
    public void sizesAreBinaryAndRoundedToWhatSomebodyCanRead() {
        assertEquals("512 B", ProgressState.bytes(512));
        assertEquals("1.0 KB", ProgressState.bytes(1024));
        assertEquals("1.5 KB", ProgressState.bytes(1536));
        assertEquals("4.3 MB", ProgressState.bytes(4_500_000));
        // ONE DECIMAL BELOW A HUNDRED, so a 49 MB download visibly moves; none above, where it would be
        // three digits nobody reads.
        assertEquals("512 MB", ProgressState.bytes(512L * 1024 * 1024));
        assertEquals("1.0 GB", ProgressState.bytes(1024L * 1024 * 1024));
    }

    /** {@code "4m 0s left"} reads as a precision a windowed estimate has not got. */
    @Test
    public void aZeroSmallerUnitIsDropped() {
        assertTrue(bytes(1, 100, 240).summary().endsWith("· 4m left"));
        assertTrue(bytes(1, 100, 990).summary().endsWith("· 16m 30s left"));
        assertTrue(bytes(1, 100, 7200).summary().endsWith("· 2h left"));
        assertTrue(bytes(1, 100, 17_940).summary().endsWith("· 4h 59m left"));
    }

    /** No estimate, no claim — and a finished transfer has nothing left to say about time. */
    @Test
    public void anAbsentEstimateIsSimplyNotShown() {
        assertNull("nothing has arrived", bytes(0, 1000, 30).summary());
        assertEquals("9.8 KB of 9.8 KB", bytes(10_000, 10_000, 30).summary());
        assertEquals("2.0 MB", bytes(2L * 1024 * 1024, -1, 30).summary());
    }

    /**
     * <b>The readout does not change because a frame went past.</b>
     *
     * <p>Reported from a client as "39s, 40s, 39s, 38s". A summary computed against {@code now} had a
     * frozen {@code done} over a growing elapsed, so it drifted up between reports and snapped back at
     * each one. It is a pure function of the reading now, which is what makes that impossible.</p>
     */
    @Test
    public void theReadoutIsAPropertyOfTheReadingAndNotOfTheFrame() {
        ProgressState reading = bytes(5L * 1024 * 1024, 10L * 1024 * 1024, 10);
        String first = reading.summary();
        for (int frame = 0; frame < 60; frame++) {
            assertEquals(first, reading.summary());
        }
        assertNotEquals(first, bytes(6L * 1024 * 1024, 10L * 1024 * 1024, 8).summary());
    }

    /**
     * <b>The estimate changes about once a second, however often work is reported.</b>
     *
     * <p>wget's {@code ETA_REFRESH_INTERVAL}, and the half that was missing while the measurement was
     * being smoothed three different ways: <i>"Don't refresh the ETA too often to avoid jerkiness in
     * predictions."</i> Reports here fire per 64 KB — hundreds a second on a fast link — and an estimate
     * recomputed on each is a number nobody can read however good the underlying rate is.</p>
     */
    @Test
    public void theEstimateIsRefreshedAboutOncePerSecond() {
        AtomicLong clock = new AtomicLong(START);
        JobContext context = new JobContext(clock::get);
        Progress progress = context.progress();
        progress.begin("Downloading", 100_000_000L, Progress.Unit.BYTES);

        // A hundred reports inside one second must not move the estimate.
        long done = 0;
        for (int report = 0; report < 100; report++) {
            clock.addAndGet(5);
            done += 65_536;
            progress.advance(done);
        }
        assertEquals("no estimate before the first refresh is due", -1L,
                context.progressState().secondsRemaining());

        clock.addAndGet(1000);
        done += 65_536;
        progress.advance(done);
        long first = context.progressState().secondsRemaining();
        assertTrue("an estimate exists once a second has passed: " + first, first > 0);

        for (int report = 0; report < 50; report++) {
            clock.addAndGet(5);
            done += 65_536;
            progress.advance(done);
        }
        assertEquals("and it is held steady until the next refresh is due",
                first, context.progressState().secondsRemaining());
    }
}
