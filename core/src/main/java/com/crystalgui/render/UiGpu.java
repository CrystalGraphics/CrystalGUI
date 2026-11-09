package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The render thread's half of a UI frame: executing what a {@link CgUiPaintContext} recorded and compositing it onto
 * the host's target, and the last frame kept for {@link #redraw} and {@link #presentAgain}. One per process; a paint
 * context calls it from {@code beginFrame}/{@code endFrame}.
 *
 * <pre>{@code
 * UiFrame frame = ctx.seal();        // recorded and built, on the document's thread
 * UiGpu.present(frame);              // render thread, inside the host's section: executed, composited
 * }</pre>
 *
 * <ul>
 *   <li>Every call on the render thread, inside a host section (a stage's firing, {@code CgHostPaint}): the executor
 *       takes and gives back the host's state, and the section's close is the host's repair.</li>
 *   <li>{@link #destroy()} on GL context destruction: nothing sweeps the textures paint contexts requested.</li>
 *   <li>Being static, the instance outlives the context that built it; nothing may hold it across a destroy.</li>
 * </ul>
 */
public final class UiGpu {

    private static final int GPU_UI = CgGpuTrace.name("ui");

    @Nullable
    private static UiGpu instance;

    /** The last presented frame and its composite, kept for {@link #redraw} and {@link #presentAgain}. */
    @Nullable
    private CgFrame lastFrame;
    @Nullable
    private CgFrame lastPresent;
    @Nullable
    private UiFrame lastShown;
    @Nullable
    private CgFrameBuilder lastPresentBuilder;
    private int lastWidth, lastHeight;
    /** Set while {@link #warm} runs: the frame executes, then is recycled instead of composited and kept. */
    private boolean warming;
    /** Whether {@link #beginFrame} opened the GPU's time and nothing has closed it. */
    private boolean timing;
    /** Every paint context made, for {@link #destroy()} to free what each made. */
    private final List<WeakReference<CgUiPaintContext>> contexts = new ArrayList<>();

    private UiGpu() {
    }

    /** The instance, built on first use. */
    static UiGpu get() {
        if (instance == null) instance = new UiGpu();
        return instance;
    }

    void track(CgUiPaintContext context) {
        contexts.removeIf(ref -> ref.get() == null);
        contexts.add(new WeakReference<>(context));
    }

    /** Opens the GPU's time for one frame: everything the UI asks of the GPU, the composite included. Render thread. */
    void beginFrame() {
        CgGpuTrace.begin(GPU_UI);
        timing = true;
    }

    /** Executes a sealed frame and composites it onto the host's target. Render thread, inside a host section. */
    public static void present(UiFrame frame) {
        UiGpu gpu = get();
        gpu.beginFrame();
        gpu.endFrame(frame);
    }

    /**
     * Executes a sealed frame into the UI's own target and composites nothing: for a rehearsal behind the loading
     * screen, which builds the pipelines, targets and rasters a real frame will need. The frame is not kept, so
     * {@link #presentAgain} and {@link #redraw} never show it. Render thread.
     *
     * <pre>{@code
     * UiFrame frame = desktop.record(DesktopPresentation.DESKTOP, delta, width, height);
     * if (frame != null) UiGpu.warm(frame);
     * }</pre>
     */
    public static void warm(UiFrame frame) {
        UiGpu gpu = get();
        gpu.beginFrame();
        gpu.warming = true;
        try {
            gpu.endFrame(frame);
        } finally {
            gpu.warming = false;
        }
    }

    /** Executes the frame, composites it, and keeps it; a frame that threw hands its buffers back. */
    void endFrame(UiFrame frame) {
        boolean executed = false;
        try {
            execute(frame.frame, frame.present);
            executed = true;
        } finally {
            if (executed && !warming) {
                keep(frame);
            } else {
                frame.builder.recycle(frame.frame);
                frame.builder.recycle(frame.present);
                if (!executed) abortFrame();
            }
        }
    }

    /**
     * Executes the last presented frame again, with its {@link UiFrame#values()} as they stand now, and composites it:
     * a compositor's scroll, move or fade with nothing recorded or built. A no-op where {@link #presentAgain} would be
     * one.
     *
     * <pre>{@code
     * frame.values().translate(listBox.scrolledNode(frame.frameId()), 0f, -scrolledPx);
     * UiGpu.redraw(width, height, true);
     * }</pre>
     *
     * <p>What a recording decided in the target's pixels stays as recorded: what was culled, a layer's region, a
     * backdrop's capture. A move far enough to reach those wants a new frame.</p>
     *
     * @param keepRequested skip every pass that writes a requested texture -- a surface, a shader-graph
     *                      preview -- leaving it as the frame's first execution did. A move under property values
     *                      changes none of them; false paints them again
     */
    public static void redraw(int width, int height, boolean keepRequested) {
        UiGpu gpu = instance;
        if (gpu == null || gpu.lastFrame == null || width != gpu.lastWidth || height != gpu.lastHeight) return;
        gpu.beginFrame();
        boolean executed = false;
        try {
            gpu.executeAgain(gpu.lastFrame, gpu.lastPresent, keepRequested);
            executed = true;
        } finally {
            if (!executed) gpu.abortFrame();
        }
    }

    /**
     * Composites the last presented frame onto the host's target again, recording and executing nothing else: for a
     * host frame that arrives before a new frame was sealed. A no-op when nothing was presented, or when the surface
     * is not the size it was.
     *
     * <pre>{@code
     * UiFrame next = sequence.poll();
     * if (next != null) UiGpu.present(next);
     * else UiGpu.presentAgain(width, height);
     * }</pre>
     *
     * <p>What it shows is that frame's target as the last execution left it.</p>
     */
    public static void presentAgain(int width, int height) {
        UiGpu gpu = instance;
        if (gpu == null || gpu.lastPresent == null || width != gpu.lastWidth || height != gpu.lastHeight) return;
        CgExecutor.executeAgain(gpu.lastPresent, false);
    }

    private void keep(UiFrame frame) {
        if (lastFrame != null) lastPresentBuilder.recycle(lastFrame);
        if (lastPresent != null) lastPresentBuilder.recycle(lastPresent);
        lastFrame = frame.frame;
        lastPresent = frame.present;
        lastShown = frame;
        lastPresentBuilder = frame.builder;
        lastWidth = frame.width();
        lastHeight = frame.height();
    }

    private void execute(CgFrame frame, CgFrame present) {
        long timed = CgTrace.stamp(UiTrace.FRAME);
        CgExecutor.execute(frame);
        CgTrace.zoneDone(UiTrace.FRAME, "glend:execute", timed);
        if (!warming) composite(present, false);
        finish();
    }

    private void executeAgain(CgFrame frame, CgFrame present, boolean keepRequested) {
        long timed = CgTrace.stamp(UiTrace.FRAME);
        CgExecutor.executeAgain(frame, keepRequested);
        CgTrace.zoneDone(UiTrace.FRAME, "glend:execute", timed);
        composite(present, true);
        finish();
    }

    /** The finished frame target onto the host's target: one draw, and the photograph when one was recorded. */
    private static void composite(CgFrame present, boolean again) {
        long timed = CgTrace.stamp(UiTrace.FRAME);
        if (again) CgExecutor.executeAgain(present, false);
        else CgExecutor.execute(present);
        CgTrace.zoneDone(UiTrace.FRAME, "glend:composite", timed);
    }

    private void finish() {
        endTiming();
        // The other half of the frame the document opened: reports and clears. A no-op when none was opened.
        UiTrace.frameEnd();
    }

    /** Closes the GPU's time of a frame that threw part-way. */
    void abortFrame() {
        endTiming();
    }

    private void endTiming() {
        if (!timing) return;
        timing = false;
        CgGpuTrace.end();
    }

    /**
     * Frees everything each paint context made and drops the instance. On GL context destruction — game shutdown — so
     * it protects no later context; it is complete teardown of what nothing else sweeps. Idempotent.
     */
    public static void destroy() {
        UiGpu gpu = instance;
        if (gpu == null) return;
        for (WeakReference<CgUiPaintContext> ref : gpu.contexts) {
            CgUiPaintContext context = ref.get();
            if (context != null) context.release();
        }
        gpu.contexts.clear();
        gpu.lastFrame = null;
        gpu.lastPresent = null;
        gpu.lastShown = null;
        instance = null;
    }

    /** The frame last presented, whose values {@link #redraw} draws with; null before the first. Render thread. */
    @Nullable
    public static UiFrame presented() {
        return instance == null ? null : instance.lastShown;
    }

    /** Whether an instance exists, without making one. */
    public static boolean hasInstance() {
        return instance != null;
    }
}
