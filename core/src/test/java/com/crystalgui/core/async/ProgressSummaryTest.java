package com.crystalgui.core.async;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The readout beside the bar — {@code "12.4 MB of 48.9 MB · 30s left"}.
 *
 * <p>Here rather than in the chrome because it is arithmetic and formatting, and because the widget that
 * draws it has no branches at all: it appends a string the state produced. The unit is the thing under
 * test as much as the numbers are — the whole reason {@link Progress.Unit} exists is that a reader given
 * two longs cannot tell a byte count from a file count, and would render "412 of 1178 bytes".</p>
 */
public class ProgressSummaryTest {

    private static final long START = 1_000_000L;

    private static ProgressState bytes(long done, long total) {
        return new ProgressState("Downloading", "", done, total, START, Progress.Unit.BYTES);
    }

    /** A count of files is what the bar already says; repeating it as a size would be a lie. */
    @Test
    public void countingJobsGetNoReadout() {
        ProgressState items = new ProgressState("Indexing", "", 412, 1178, START);
        assertEquals("the default unit is ITEMS", Progress.Unit.ITEMS, items.unit());
        assertNull(items.summary(START + 5_000));
    }

    @Test
    public void aTransferReadsAsSizeOfSize() {
        String summary = bytes(13_000_000L, 51_300_000L).summary(START + 500);
        assertTrue(summary, summary.startsWith("12.4 MB of 48.9 MB"));
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

    /**
     * <b>The estimate is suppressed until it can be made honestly</b>, and again once it is meaningless.
     *
     * <p>An ETA from the first two chunks is a guess with a decimal point on it, and "0s left" on a
     * finished transfer is noise. Both read as the number being wrong rather than as it being withheld.
     */
    @Test
    public void theEstimateIsWithheldWhenItWouldBeInvented() {
        assertNull("nothing has moved yet", bytes(0, 1000).summary(START + 5_000));
        assertEquals("under a second of evidence is not an estimate",
                "1.0 KB of 9.8 KB", bytes(1024, 10_000).summary(START + 200));
        assertEquals("and a finished transfer has nothing left",
                "9.8 KB of 9.8 KB", bytes(10_000, 10_000).summary(START + 5_000));
    }

    /** Average rate, not instantaneous: half in ten seconds means about ten seconds to go. */
    @Test
    public void theEstimateIsTheAverageRateSoFar() {
        assertEquals("5.0 MB of 10.0 MB · 10s left",
                bytes(5L * 1024 * 1024, 10L * 1024 * 1024).summary(START + 10_000));
    }

    @Test
    public void longEstimatesReadInMinutesAndHours() {
        // 1 MB in 10s of a 100 MB file -> 990s, which is 16m 30s.
        String minutes = bytes(1024L * 1024, 100L * 1024 * 1024).summary(START + 10_000);
        assertTrue(minutes, minutes.endsWith("16m 30s left"));

        // 1 MB in 60s of a 300 MB file -> 17940s, which is 4h 59m.
        String hours = bytes(1024L * 1024, 300L * 1024 * 1024).summary(START + 60_000);
        assertTrue(hours, hours.endsWith("4h 59m left"));
    }

    /** An indeterminate transfer still says how much has arrived, which is all it honestly can. */
    @Test
    public void anIndeterminateTransferReportsWhatHasArrived() {
        assertEquals("2.0 MB", bytes(2L * 1024 * 1024, -1).summary(START + 5_000));
    }
}
