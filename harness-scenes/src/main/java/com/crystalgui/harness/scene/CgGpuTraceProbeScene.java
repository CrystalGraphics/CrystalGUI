package com.crystalgui.harness.scene;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * DIAGNOSTIC, exits on its own — the T7 gate for {@link CgGpuTrace}: a frame's {@code gpuNanos} lands as
 * soon as the GPU has finished the frame, and agrees with the same work timed by waiting on the GPU.
 *
 * <p>The load is the paint context's own frame — the {@code ui} GPU zone — filled with translucent
 * full-screen quads, batched so submission is a few draws. Three phases:</p>
 * <ul>
 *   <li>LATENCY: a light frame painted without waiting. Each frame gets a fence at the start of the
 *       next, covering everything it issued; the fence says when the GPU finished it, and the figure must
 *       land within {@link #MAX_BEHIND_GPU} boundaries of that. How far behind the CPU the GPU runs is the
 *       driver's queue, printed but not gated.</li>
 *   <li>SATURATED: the heavy frame without waiting. Reported only.</li>
 *   <li>CONTROL: frames bracketed by fence waits, cycling heavy, light and empty. A bracket costs a fixed
 *       round trip whatever it holds, so the gate compares heavy against empty: the difference in the GPU
 *       figure against the difference in time waited.</li>
 * </ul>
 *
 * <p>Prints {@code [gpu-trace-probe]} lines, the last one {@code PASS} or {@code FAIL}.</p>
 */
public class CgGpuTraceProbeScene implements InteractiveSceneLifecycle {

    private static final int WARM = 30;
    private static final int PHASE = 120;
    /** Boundaries past the frame's GPU completion a figure may land: query availability trails a fence. */
    private static final int MAX_BEHIND_GPU = 1;
    /** Boundaries given to the last figures after the last frame is painted. */
    private static final int DRAIN = 8;
    private static final double TOLERANCE = 0.05;
    /** Full-screen translucent quads in a heavy frame — fill-bound. */
    private static final int HEAVY = 300;
    /** And in a light one — well under a frame of GPU time. */
    private static final int LIGHT = 20;

    private static final int GL_TIMEOUT_EXPIRED = 0x911B;

    private static final int[] KIND_QUADS = {HEAVY, LIGHT, 1};
    private static final String[] KIND_NAMES = {"heavy", "light", "empty"};

    private int painted;
    private boolean running = true;

    /** LATENCY frames whose figure has not landed yet. */
    private final Set<Long> awaiting = new HashSet<>();
    private int landed;
    private int worstSinceIssue;
    /** LATENCY frame to its fence, until the fence signals. */
    private final Map<Long, Long> fences = new HashMap<>();
    /** LATENCY frame to the boundary that first saw its fence signalled. */
    private final Map<Long, Long> gpuDoneAt = new HashMap<>();
    private int worstGpuDone;
    private int worstBehindGpu;

    private final Set<Long> saturated = new HashSet<>();
    private int worstSaturated;

    private final Map<Long, Long> waited = new HashMap<>();

    @Override
    public void init(HarnessContext ctx) {
        CgTrace.disableAll();
        CgTrace.clear();
        CgTrace.enable(CgGpuTrace.GPU.name());
        log("timer queries: " + CgGpuTrace.probe());
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!running) return;
        long previous = painted - 1L;
        if (previous >= WARM && previous < WARM + PHASE) {
            fences.put(previous, CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
        }
        // BEFORE the boundary: a fence signalled here means the frame's queries had finished when the
        // boundary collected.
        pollFences(painted);
        CgTrace.frameBegin();
        long index = CgTrace.currentFrameIndex();
        checkLanded(index);

        boolean latency = painted >= WARM && painted < WARM + PHASE;
        boolean heavy = painted >= WARM + PHASE && painted < WARM + 3 * PHASE;
        boolean control = painted >= WARM + 2 * PHASE && painted < WARM + 3 * PHASE;
        long t0 = 0L;
        if (control) {
            waitForGpu();
            t0 = System.nanoTime();
        }
        int quads = control ? KIND_QUADS[(int) (index % 3)] : heavy ? HEAVY : LIGHT;
        paint(ctx, quads);
        if (control) {
            waitForGpu();
            waited.put(index, System.nanoTime() - t0);
        }
        if (latency) awaiting.add(index);
        if (heavy && !control) saturated.add(index);
        painted++;

        if (painted >= WARM + 3 * PHASE + DRAIN) finish();
    }

    private static void paint(HarnessContext ctx, int quads) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        CgUiPaintContext paint = paintContext();
        paint.beginFrame(w, h);
        paint.fillRect(0, 0, w, h, 0x08FF8040); // binds the white pixel the batch below draws with
        for (int i = quads; i > 1; i--) {
            paint.quad().at(0, 0).size(w, h).color((i & 1) == 0 ? 0x08FF8040 : 0x084080FF).submit();
        }
        paint.flush();
        paint.endFrame();
    }

    private void pollFences(long boundary) {
        for (Iterator<Map.Entry<Long, Long>> it = fences.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> fence = it.next();
            if (!signalled(CgGL.glClientWaitSync(fence.getValue(), 0, 0L))) continue;
            worstGpuDone = Math.max(worstGpuDone, (int) (boundary - fence.getKey()));
            gpuDoneAt.put(fence.getKey(), boundary);
            CgGL.glDeleteSync(fence.getValue());
            it.remove();
        }
    }

    private void checkLanded(long current) {
        if (awaiting.isEmpty() && saturated.isEmpty()) return;
        for (CgFrameRecord record : CgTrace.frames()) {
            if (!record.hasGpu()) continue;
            if (awaiting.remove(record.index())) {
                landed++;
                worstSinceIssue = Math.max(worstSinceIssue, (int) (current - record.index()));
                Long done = gpuDoneAt.get(record.index());
                // A figure that landed before its fence was seen is not behind the GPU at all.
                worstBehindGpu = Math.max(worstBehindGpu, done == null ? 0 : (int) (current - done));
            } else if (saturated.remove(record.index())) {
                worstSaturated = Math.max(worstSaturated, (int) (current - record.index()));
            }
        }
    }

    private void finish() {
        running = false;
        double[] gpuMs = new double[3];
        double[] waitMs = new double[3];
        int[] counts = new int[3];
        double lightGpu = 0d;
        int lightFrames = 0;
        for (CgFrameRecord record : CgTrace.frames()) {
            if (!record.hasGpu()) continue;
            long i = record.index();
            if (i >= WARM && i < WARM + PHASE) {
                lightGpu += record.gpuMillis();
                lightFrames++;
            }
            Long wait = waited.get(i);
            if (wait == null) continue;
            int kind = (int) (i % 3);
            gpuMs[kind] += record.gpuMillis();
            waitMs[kind] += wait / 1e6;
            counts[kind]++;
        }
        for (int kind = 0; kind < 3; kind++) {
            if (counts[kind] == 0) continue;
            gpuMs[kind] /= counts[kind];
            waitMs[kind] /= counts[kind];
            log(String.format("control %s: %d frames, GPU %.3f ms, waited %.3f ms a frame",
                    KIND_NAMES[kind], counts[kind], gpuMs[kind], waitMs[kind]));
        }
        double gpuDelta = gpuMs[0] - gpuMs[2];
        double waitDelta = waitMs[0] - waitMs[2];
        double ratio = waitDelta == 0d ? 0d : gpuDelta / waitDelta;

        log(String.format("latency (light, %.3f ms GPU a frame): %d of %d landed, %d never; worst %d frames after "
                        + "issue, GPU itself finished at worst %d frames after issue",
                lightFrames == 0 ? 0d : lightGpu / lightFrames, landed, PHASE, awaiting.size(), worstSinceIssue,
                worstGpuDone));
        log(String.format("latency gate: landed at worst %d boundaries after the GPU finished (gate <= %d)",
                worstBehindGpu, MAX_BEHIND_GPU));
        log(String.format("saturated (heavy, not gated): worst %d frames after issue; %d never landed",
                worstSaturated, saturated.size()));
        log(String.format("accuracy gate: heavy minus empty is GPU %.3f ms against %.3f ms waited, ratio %.4f "
                        + "(gate within %.0f%%); a bracket alone costs %.3f ms",
                gpuDelta, waitDelta, ratio, TOLERANCE * 100, waitMs[2] - gpuMs[2]));

        boolean pass = CgGpuTrace.support() == CgGpuTrace.Support.SUPPORTED
                && awaiting.isEmpty() && worstBehindGpu <= MAX_BEHIND_GPU
                && counts[0] > 0 && counts[2] > 0 && Math.abs(1d - ratio) <= TOLERANCE;
        log(pass ? "PASS" : "FAIL");
    }

    /**
     * glFinish, spelled with what the facade has: a fence, flushed, then SPUN on. A blocking wait may
     * sleep, and a sleep on Windows wakes on a timer tick — up to 15.6 ms late.
     */
    private static void waitForGpu() {
        long sync = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        int flags = CgGL.GL_SYNC_FLUSH_COMMANDS_BIT;
        while (!signalled(CgGL.glClientWaitSync(sync, flags, 0L))) flags = 0;
        CgGL.glDeleteSync(sync);
    }

    private static boolean signalled(int waitResult) {
        return waitResult != GL_TIMEOUT_EXPIRED;
    }

    private static void log(String line) {
        System.out.println("[gpu-trace-probe] " + line);
    }

    @Override
    public void dispose() {
        for (long fence : fences.values()) CgGL.glDeleteSync(fence);
        CgGpuTrace.dispose();
        CgTrace.disableAll();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }

    /** This scene's paint context: it has no document to ask for one. */
    private static CgUiPaintContext paintContext;

    private static CgUiPaintContext paintContext() {
        if (paintContext == null) paintContext = CgUiPaintContext.create();
        return paintContext;
    }
}
