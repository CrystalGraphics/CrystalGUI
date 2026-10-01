package com.crystalgui.harness;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceReport;

import java.util.Arrays;
import java.util.Locale;

/**
 * What moving the pointer costs a frame: the hover benchmark (plan desktop-hitches #2 and #7).
 *
 * <pre>{@code
 * HoverSweepProbe probe = new HoverSweepProbe(warmup, new HoverSweepProbe.Target() { ... });
 * long start = System.nanoTime();
 * ... the scene's frame and paint ...
 * if (probe.frame(System.nanoTime() - start)) running = false;   // true once it has printed
 * }</pre>
 *
 * <p>A block with the pointer parked, then a block moving it every frame across {@link Target#region()} in rows,
 * so the hovered element changes most frames. Prints {@code [hover-sweep]} lines: the scene's work per frame
 * (median and p95) in each block, and the per-zone comparison. Record at least {@code crystalgui.frame}.</p>
 */
public final class HoverSweepProbe {

    /** What the probe drives. */
    public interface Target {
        /** Moves the pointer to {@code (x, y)}, in the coordinates {@link #region} answers in. */
        void moveTo(float x, float y);

        /** The rect to sweep: x, y, width, height. */
        float[] region();
    }

    private static final int BLOCK = 600;
    private static final int SETTLE = 20;
    private static final float STEP_X = 11f;
    private static final float STEP_Y = 9f;

    private final int startFrame;
    private final Target target;
    private final long[][] work = new long[2][BLOCK];
    private final long[] from = new long[2];
    private final long[] to = new long[2];
    private int frame;
    private boolean done;

    public HoverSweepProbe(int startFrame, Target target) {
        this.startFrame = startFrame;
        this.target = target;
    }

    /** Feeds one frame's work time and drives the next pointer position. @return true once it has printed */
    public boolean frame(long workNanos) {
        if (done) return true;
        int at = frame++ - startFrame;
        if (at < 0) return false;
        int block = at / BLOCK;
        int slot = at % BLOCK;
        if (block >= 2) {
            to[1] = CgTrace.currentFrameIndex() - 1;
            report();
            done = true;
            return true;
        }
        if (slot == 0) {
            if (block == 1) to[0] = CgTrace.currentFrameIndex() - 1;
            from[block] = CgTrace.currentFrameIndex() + SETTLE;
        }
        work[block][slot] = slot < SETTLE ? -1L : workNanos;
        float[] r = target.region();
        if (block == 0) {
            if (slot == 0) target.moveTo(r[0] + r[2] / 2f, r[1] + r[3] / 2f);
            return false;
        }
        int columns = Math.max(1, (int) (r[2] / STEP_X));
        int rows = Math.max(1, (int) (r[3] / STEP_Y));
        int row = (slot / columns) % rows;
        int column = row % 2 == 0 ? slot % columns : columns - 1 - slot % columns;
        target.moveTo(r[0] + column * STEP_X + STEP_X / 2f, r[1] + row * STEP_Y + STEP_Y / 2f);
        return false;
    }

    private void report() {
        System.out.printf(Locale.ROOT, "[hover-sweep] work per frame, median / p95 over %d frames: parked %.3f / %.3f ms, sweeping %.3f / %.3f ms%n",
                BLOCK - SETTLE, percentile(0, 0.5), percentile(0, 0.95), percentile(1, 0.5), percentile(1, 0.95));
        String compare = CgTraceReport.of(CgTrace.snapshot()).budget(1000d / 60d).compare(from[0], to[0], from[1], to[1]);
        for (String line : compare.split("\n")) System.out.println("[hover-sweep] " + line);
    }

    private double percentile(int block, double p) {
        long[] held = Arrays.stream(work[block]).filter(v -> v >= 0L).sorted().toArray();
        return held.length == 0 ? 0d : held[Math.min(held.length - 1, (int) (held.length * p))] / 1_000_000d;
    }
}
