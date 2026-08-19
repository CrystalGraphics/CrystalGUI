package com.crystalgui.core.async;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The ported sliding-window rate — Chromium's {@code RateEstimator}.
 *
 * <p>Pinned here rather than trusted because the port is from a header read verbatim plus a description
 * of its implementation, not a line-for-line copy. What has to hold is the behaviour: a window over recent
 * activity, a denominator that counts <b>populated</b> buckets, and a long pause starting again.</p>
 */
public class RateEstimatorTest {

    private static final long START = 1_000_000L;

    @Test
    public void aSteadyRateReadsBackAsItself() {
        RateEstimator rate = new RateEstimator(START);
        for (int second = 1; second <= 10; second++) {
            rate.increment(1_000_000, START + second * 1000L);
        }
        assertEquals("1 MB in each of ten one-second buckets", 1_000_000L,
                rate.countPerSecond(START + 10_000));
    }

    /**
     * <b>The partial window divides by what has been populated, not by the window.</b>
     *
     * <p>The detail most worth not losing in the port. Divide by the full ten seconds one second into a
     * transfer and the rate reads a tenth of the truth — an ETA ten times too long, on precisely the
     * reading somebody sees first.</p>
     */
    @Test
    public void anEarlyReadingIsNotDividedByTheWholeWindow() {
        RateEstimator rate = new RateEstimator(START);
        rate.increment(5_000_000, START + 200);
        assertEquals("one populated bucket, so one second's worth", 5_000_000L,
                rate.countPerSecond(START + 900));
    }

    /**
     * A window tracks a change; that is the whole reason it is a window.
     *
     * <p>Ten seconds at 10 MB/s and then ten at 1 MB/s: by the end the old rate has fallen out entirely
     * and the estimate is the new one, where a whole-run average would still be claiming 5.5 MB/s.</p>
     */
    @Test
    public void theWindowForgetsWhatHasFallenOutOfIt() {
        RateEstimator rate = new RateEstimator(START);
        for (int second = 1; second <= 10; second++) rate.increment(10_000_000, START + second * 1000L);
        assertEquals(10_000_000L, rate.countPerSecond(START + 10_000));

        for (int second = 11; second <= 20; second++) rate.increment(1_000_000, START + second * 1000L);
        long now = rate.countPerSecond(START + 20_000);
        assertEquals("the fast half is entirely outside the window", 1_000_000L, now);
    }

    /** A pause longer than the window is a fresh start, not a rate of nearly zero for ten more seconds. */
    @Test
    public void aLongPauseStartsAgain() {
        RateEstimator rate = new RateEstimator(START);
        for (int second = 1; second <= 10; second++) rate.increment(10_000_000, START + second * 1000L);

        long afterPause = rate.countPerSecond(START + 120_000);
        assertEquals("nothing in the window is still recent", 0L, afterPause);

        rate.increment(2_000_000, START + 120_500);
        assertEquals(2_000_000L, rate.countPerSecond(START + 120_900));
    }

    /**
     * A clock that goes backwards resets rather than throwing.
     *
     * <p>This is fed by {@code System.currentTimeMillis}, which an NTP correction moves either way, and an
     * estimate is not worth an exception on a background thread.</p>
     */
    @Test
    public void aClockGoingBackwardsIsSurvived() {
        RateEstimator rate = new RateEstimator(START);
        rate.increment(1_000_000, START + 3_000);
        rate.increment(1_000_000, START - 50_000);
        assertTrue("did not throw, and reports something sane",
                rate.countPerSecond(START - 49_000) >= 0);
    }
}
