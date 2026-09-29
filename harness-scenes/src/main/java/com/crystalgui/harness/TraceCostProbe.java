package com.crystalgui.harness;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

import java.util.Arrays;
import java.util.Locale;

/**
 * What the trace engine costs a frame when nothing is recording — the "every channel off" gate.
 *
 * <pre>{@code
 * long start = System.nanoTime();
 * ... the scene's frame and paint ...
 * if (probe.frame(System.nanoTime() - start)) running = false;   // true once it has printed
 * }</pre>
 *
 * <p>Alternates blocks of frames with every channel off and every CPU channel on, timing the scene's own
 * work (never the swap, which vsync would clamp). A build without the engine cannot be made, so the gate
 * is derived: the on-minus-off difference divided by the events recorded gives what one recorded call
 * costs, and the calls a frame makes times the measured cost of a DISABLED call is what the engine costs
 * with everything off. Prints {@code [trace-cost]} lines.</p>
 *
 * <p>Leaves out {@code crystalgui.blame}, which walks a stack per invalidation and is no ordinary call,
 * and the GPU and image channels, which issue GL work rather than record.</p>
 */
public final class TraceCostProbe {

    private static final int BLOCK = 300;
    private static final int BLOCKS = 6;

    private final int startFrame;
    private int frame;
    private final long[][] work = new long[BLOCKS][BLOCK];
    private long events;
    private long recordedFrames;
    private boolean done;

    public TraceCostProbe(int startFrame) {
        this.startFrame = startFrame;
    }

    /** Feeds one frame's work time. @return true once the report has been printed */
    public boolean frame(long workNanos) {
        if (done) return true;
        int at = frame++ - startFrame;
        if (at < 0) return false;
        int block = at / BLOCK;
        int slot = at % BLOCK;
        if (block >= BLOCKS) {
            report();
            done = true;
            return true;
        }
        if (slot == 0) {
            if (block > 0 && on(block - 1)) countRecorded(CgTrace.currentFrameIndex() - BLOCK + 1);
            switchTo(on(block));
        }
        // The first frames after a switch carry the switch itself.
        work[block][slot] = slot < 10 ? -1L : workNanos;
        return false;
    }

    private static boolean on(int block) {
        return block % 2 == 1;
    }

    private static void switchTo(boolean on) {
        CgTrace.disableAll();
        if (!on) return;
        CgTrace.enable("crystalgraphics");
        CgTrace.enable("crystalgui");
        CgTrace.disable("crystalgui.blame");
    }

    /** Zones and counter values the frames since {@code fromIndex} recorded, less the last ten's settling. */
    private void countRecorded(long fromIndex) {
        for (CgFrameRecord record : CgTrace.frames()) {
            if (record.index() < fromIndex + 10) continue;
            events += CgTrace.zonesBetween(record.beginNanos(), record.endNanos()).size();
            events += CgTrace.countersIn(record).size();
            recordedFrames++;
        }
    }

    private void report() {
        CgTrace.disableAll();
        double off = median(0, 2, 4);
        double on = median(1, 3, 5);
        double perFrame = recordedFrames == 0 ? 0d : (double) events / recordedFrames;
        double perCall = perFrame == 0d ? 0d : (on - off) * 1_000_000d / perFrame;
        double disabled = zoneNanos(false);
        double enabled = zoneNanos(true);
        double offCost = perFrame * disabled / 1000d;
        // AN UPPER BOUND: per-call adds collapse into one recorded value, so the events undercount the
        // calls. Charging the whole on-minus-off difference to calls at an enabled zone's price over-counts
        // them instead, since a frame's commit and snapshot work is in that difference too.
        double callsAtMost = enabled <= 0d ? 0d : (on - off) * 1_000_000d / enabled;
        double offCostAtMost = callsAtMost * disabled / 1000d;
        System.out.printf(Locale.ROOT, "[trace-cost] work per frame, median of %d frames each: off %.3f ms, on %.3f ms (+%.3f ms)%n",
                3 * (BLOCK - 10), off, on, on - off);
        System.out.printf(Locale.ROOT, "[trace-cost] recorded per frame: %.0f events (zones + counter values; per-call adds collapse, so calls are at least this)%n",
                perFrame);
        System.out.printf(Locale.ROOT, "[trace-cost] a recorded event costs about %.1f ns in the scene; an enabled zone %.1f ns, a disabled one %.2f ns alone%n",
                perCall, enabled, disabled);
        System.out.printf(Locale.ROOT, "[trace-cost] every channel off: about %.2f us a frame (%.4f%% of %.3f ms); at most %.2f us (%.3f%%) if every call costs an enabled zone's price (%.0f calls)%n",
                offCost, offCost / 10d / off, off, offCostAtMost, offCostAtMost / 10d / off, callsAtMost);
    }

    /** Median work in milliseconds over the given blocks, settling frames left out. */
    private double median(int... blocks) {
        long[] all = new long[blocks.length * BLOCK];
        int n = 0;
        for (int block : blocks) {
            for (long each : work[block]) if (each >= 0L) all[n++] = each;
        }
        long[] held = Arrays.copyOf(all, n);
        Arrays.sort(held);
        return n == 0 ? 0d : held[n / 2] / 1_000_000d;
    }

    /**
     * One zone, open and close, best of three. Enabled runs a hundred thousand, inside the arena's first
     * doubling steps rather than past its ceiling, where a dropped zone would read as a cheap one.
     */
    private static double zoneNanos(boolean enabled) {
        CgTraceChannel channel = CgTrace.channel("harness.trace-cost");
        int name = CgTrace.name("probe");
        int count = enabled ? 100_000 : 10_000_000;
        CgTrace.setEnabled(channel, enabled);
        double best = Double.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                try (CgTrace.Zone ignored = CgTrace.zone(channel, name)) {
                    // measured: the mask test, and when enabled the clock reads and the arena write
                }
            }
            best = Math.min(best, (System.nanoTime() - start) / (double) count);
            if (enabled) CgTrace.clear();
        }
        CgTrace.setEnabled(channel, false);
        return best;
    }
}
