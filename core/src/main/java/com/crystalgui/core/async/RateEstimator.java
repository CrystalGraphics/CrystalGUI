package com.crystalgui.core.async;

/**
 * <b>How fast work is arriving</b> — a sliding window of recent activity, ported from Chromium.
 *
 * <p>Source: {@code components/download/public/common/rate_estimator.h} and its implementation in
 * {@code components/download/internal/common/rate_estimator.cc}, Copyright The Chromium Authors,
 * BSD-3-Clause. Chromium's own summary: <i>"RateEstimator generates rate estimates based on recent
 * activity. Internally it uses a fixed-size ring buffer, and develops estimates based on a small sliding
 * window of activity."</i> Recorded in {@code THIRD-PARTY.md}.</p>
 *
 * <h3>Why this is ported and not written</h3>
 *
 * <p>Three estimators were invented here before anybody looked this up, and every one failed in a way a
 * user reported within a minute of watching it: a cumulative average that could not track a changing
 * throughput ("stuck at 14s from 60% to 80%"), and then an exponential moving average that tracked it
 * far too well ("drops five seconds every second, then jumps right back"). That is precisely the class of
 * problem {@code AGENTS.md} says not to derive from first principles — it has been solved thousands of
 * times and the answers agree with each other.</p>
 *
 * <p>Chromium and wget independently land on the same shape: <b>a window over the last several seconds,
 * not a decay and not a whole-run average</b>. A window is what makes a rate legible when the underlying
 * throughput swings — which it does violently here, because the consumer this feeds reads a stream only
 * as fast as it can process, skipping whole archive regions at tens of MB/s and then stripping and
 * deflating others at under one.</p>
 *
 * <h3>The partial window is the detail worth not losing</h3>
 *
 * <p>The denominator is the number of buckets that have actually been <em>populated</em>, not the window
 * size. Divide by the full ten seconds a second into a transfer and the rate reads a tenth of the truth,
 * which is an ETA ten times too long on exactly the reading somebody sees first.</p>
 *
 * <p>Not thread-safe, and does not need to be: one job is one unit of work on one thread, and only that
 * worker touches this — the same rule {@link JobContext}'s state swap already relies on.</p>
 */
final class RateEstimator {

    /** Chromium's {@code kDefaultBucketTimeSeconds}. */
    static final long DEFAULT_BUCKET_MILLIS = 1000L;

    /** Chromium's {@code kDefaultNumBuckets} — ten one-second buckets, a ten-second window. */
    static final int DEFAULT_BUCKETS = 10;

    private final long bucketMillis;
    private final long[] history;

    /** Where the oldest live bucket sits in the ring. */
    private int oldestIndex;

    /** How many buckets hold real time — grows to capacity, and is the divisor. */
    private int bucketCount;

    /** When the oldest live bucket began. */
    private long oldestMillis;

    RateEstimator(long nowMillis) {
        this(DEFAULT_BUCKET_MILLIS, DEFAULT_BUCKETS, nowMillis);
    }

    RateEstimator(long bucketMillis, int buckets, long nowMillis) {
        this.bucketMillis = Math.max(1L, bucketMillis);
        this.history = new long[Math.max(1, buckets)];
        reset(nowMillis);
    }

    /** Records {@code count} units as having arrived now. */
    void increment(long count, long nowMillis) {
        if (count <= 0) return;
        slide(nowMillis);
        history[(oldestIndex + bucketCount - 1) % history.length] += count;
    }

    /** Units per second over the window, or zero before anything has arrived. */
    long countPerSecond(long nowMillis) {
        slide(nowMillis);
        long total = 0;
        for (int at = 0; at < bucketCount; at++) {
            total += history[(oldestIndex + at) % history.length];
        }
        // POPULATED buckets, not the window — see the header.
        return total * 1000L / (bucketCount * bucketMillis);
    }

    /**
     * Advances the window to cover {@code nowMillis}, retiring buckets that have fallen out of it.
     *
     * <p>A clock that went backwards resets rather than throwing: this is fed by
     * {@code System.currentTimeMillis}, which an NTP correction can move either way, and an estimate is
     * not worth an exception.</p>
     */
    private void slide(long nowMillis) {
        if (nowMillis < oldestMillis) {
            reset(nowMillis);
            return;
        }
        // The bucket containing `now`, counted from the oldest live one.
        long wanted = (nowMillis - oldestMillis) / bucketMillis + 1;
        if (wanted <= bucketCount) return;

        if (wanted > history.length) {
            long retire = wanted - history.length;
            if (retire >= history.length) {
                // Nothing in the window is still recent. A pause longer than the window is a fresh start.
                reset(nowMillis);
                return;
            }
            for (long dropped = 0; dropped < retire; dropped++) {
                history[(int) ((oldestIndex + dropped) % history.length)] = 0L;
            }
            oldestIndex = (int) ((oldestIndex + retire) % history.length);
            oldestMillis += retire * bucketMillis;
            bucketCount = history.length;
            return;
        }
        // Still filling: the newly covered buckets are already zero.
        bucketCount = (int) wanted;
    }

    private void reset(long nowMillis) {
        java.util.Arrays.fill(history, 0L);
        oldestIndex = 0;
        bucketCount = 1;
        oldestMillis = nowMillis;
    }
}
