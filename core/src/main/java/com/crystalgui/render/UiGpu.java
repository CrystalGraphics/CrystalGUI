package com.crystalgui.render;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.framebuffer.CgPixelReadback;
import com.crystalgraphics.gl.texture.CgHostSamplers;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlCensus;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The GL half of a UI frame, on the render thread: the GL state a host hands over and gets back, the frame's own
 * target, executing what a {@link CgUiPaintContext} recorded, and compositing it onto the host's target. One per
 * process; a paint context calls it from {@code beginFrame}/{@code endFrame}.
 *
 * <pre>{@code
 * UiFrame frame = ctx.seal();          // recorded and built, on the document's thread
 * UiGpu.present(frame);              // render thread: the host's state saved, executed, composited, restored
 * }</pre>
 *
 * <p>A paint context used inline does the same around its recording: {@code beginFrame} saves the host's state
 * first, so GL a paint hook still issues while recording is covered too.</p>
 *
 * <ul>
 *   <li>{@link #destroy()} on GL context destruction: the frame target is {@code createOwned}, so no registry
 *       sweeps it, and neither does anything sweep the textures paint contexts made.</li>
 *   <li>Being static, the instance outlives the context that built it; nothing may hold it across a destroy.</li>
 * </ul>
 */
public final class UiGpu {

    private static final int GPU_UI = CgGpuTrace.name("ui");

    @Nullable
    private static UiGpu instance;

    /**
     * The frame's own target: the whole tree records into it and it is composited onto the host's target once, so
     * the finished picture is a texture this engine owns — the backdrop samples it, a readback sees the UI rather
     * than the world behind it, and its format is the same on every loader. Not multisampled: every UI material
     * antialiases its own edges. Starts 1x1 and follows the surface.
     */
    final CgFrameBuffer frameFbo = CgFrameBuffer.createOwned("cgui_frame", 1, 1, CgUiPaintContext.LAYER_FORMAT);
    /** {@link #frameFbo}, as a recording names it. */
    final CgGraphTexture frameTarget = CgGraphTexture.imported("cgui_frame", frameFbo);

    @Nullable
    private CgGlScope glScope;
    private boolean samplersParked;
    /** The draw target bound when the frame began: the scene behind the UI, which the backdrop reads. */
    private int sceneFboId;
    /** Built on the first frame a picture is asked for; three in flight covers a GPU two frames behind. */
    @Nullable
    private CgPixelReadback frameImages;
    /** Every paint context made, for {@link #destroy()} to free what each made. */
    private final List<WeakReference<CgUiPaintContext>> contexts = new ArrayList<>();

    private UiGpu() {
    }

    /** The instance, built on first use, on the render thread. */
    static UiGpu get() {
        if (instance == null) {
            // Built before any frame's scope: the framebuffer it makes would otherwise stay bound for the host.
            try (CgGlScope ignored = CgGlState.saveAll()) {
                instance = new UiGpu();
            }
        }
        return instance;
    }

    void track(CgUiPaintContext context) {
        contexts.removeIf(ref -> ref.get() == null);
        contexts.add(new WeakReference<>(context));
    }

    /** The scene behind the UI: what was bound when the frame began. Render thread, inside a frame's execution. */
    int sceneTarget() {
        return sceneFboId;
    }

    /** Saves the host's GL state and sizes the frame target. Render thread. */
    void beginFrame(int width, int height) {
        // What the host handed us, off unless -Dcrystalgraphics.host.census. Before the scope reads anything.
        CgGlCensus.at("gui");
        // Everything the UI asks of the GPU this frame, the composite included.
        CgGpuTrace.begin(GPU_UI);
        glScope = CgGlState.save(
                CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND,
                CgGlSlot.DEPTH, CgGlSlot.CULL, CgGlSlot.VIEWPORT, CgGlSlot.ALPHA_TEST,
                CgGlSlot.SCISSOR, CgGlSlot.COLOR_MASK,
                // The host's VAO back: Minecraft 1.17+ skips its own bind while it believes its VAO is
                // still current, so leaving ours bound failed its next GUI draw ("Array object is not active").
                CgGlSlot.VERTEX_INPUT);
        disableFixedFunctionAlphaTest();
        // Every channel, alpha included: a host that leaves alpha writes off (1.21.6+ does) leaves the frame's alpha
        // at 0, and the premultiplied composite then ADDS the UI to the scene.
        CgGL.glColorMask(true, true, true, true);
        // A frame owns the whole surface; a clip the host left on would cut every pass. Saved above, so it goes back.
        CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
        // The host's sampler objects override our textures' filtering and wrapping on the units they hold
        // (Minecraft 1.21.5+ leaves three bound). Off until the composite, which samples too.
        CgHostSamplers.park();
        samplersParked = true;
        sceneFboId = CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
        int w = Math.max(1, width), h = Math.max(1, height);
        if (frameFbo.getWidth() != w || frameFbo.getHeight() != h) frameFbo.resize(w, h);
    }

    /** Executes a sealed frame onto the host's bound target, saving and restoring the host's GL state around it. */
    public static void present(UiFrame frame) {
        UiGpu gpu = get();
        gpu.beginFrame(frame.width(), frame.height());
        gpu.endFrame(frame);
    }

    /**
     * Executes the frame, photographs the result for the trace when due, gives the host its state back, and executes
     * the composite onto the host's target, which the restored scope has bound again. Hands the frame's buffers back.
     */
    void endFrame(UiFrame frame) {
        try {
            execute(frame);
        } finally {
            frame.builder.recycle(frame.frame);
            frame.builder.recycle(frame.present);
        }
    }

    private void execute(UiFrame frame) {
        long timed = CgTrace.stamp(UiTrace.FRAME);
        CgExecutor.execute(frame.frame);
        CgTrace.zoneDone(UiTrace.FRAME, "glend:execute", timed);

        timed = CgTrace.stamp(UiTrace.FRAME);
        captureFrameImage();
        CgTrace.zoneDone(UiTrace.FRAME, "glend:captureImage", timed);

        if (glScope != null) {
            // Timed: the first GL call after a long frame's submission is where a driver whose queue is full makes
            // the CPU wait for the GPU.
            timed = CgTrace.stamp(UiTrace.FRAME);
            glScope.close();
            glScope = null;
            CgTrace.zoneDone(UiTrace.FRAME, "glend:restoreState", timed);
        }
        // SCOPED, because the composite runs after the frame's own restore and would otherwise leave our program and
        // render state bound for the host. Minecraft's final present is fixed-function and never unbinds a program,
        // so its blit of its own framebuffer ran through our vertex shader and the window showed a flat fill while
        // the framebuffer held the whole UI; the depth state left behind stopped terrain occluding itself. The slots
        // are everything a material bind can write.
        try (CgGlScope ignored = CgGlState.save(CgGlSlot.PROGRAM, CgGlSlot.TEXTURES,
                CgGlSlot.BLEND, CgGlSlot.DEPTH, CgGlSlot.CULL,
                CgGlSlot.STENCIL, CgGlSlot.COLOR_MASK, CgGlSlot.ALPHA_TEST,
                CgGlSlot.VERTEX_INPUT)) {
            // Again: the restore just handed the host's alpha test back, and the composite is clipped by the
            // picture's ACCUMULATED alpha -- a 7% panel would be discarded whole on the way to the screen.
            disableFixedFunctionAlphaTest();
            CgExecutor.execute(frame.present);
        }
        unparkSamplers();
        CgGpuTrace.end();
        // The other half of the frame the document opened: reports and clears. A no-op when none was opened.
        UiTrace.frameEnd();
    }

    /** Gives a frame that threw part-way its GL state back. */
    void abortFrame() {
        unparkSamplers();
        if (glScope != null) {
            glScope.close();
            glScope = null;
        }
    }

    private void unparkSamplers() {
        if (!samplersParked) return;
        samplersParked = false;
        CgHostSamplers.unpark();
    }

    /**
     * Turns off the host's fixed-function alpha test for a UI pass.
     *
     * <p>Minecraft 1.7.10 leaves {@code GL_ALPHA_TEST} on with {@code glAlphaFunc(GL_GREATER, 0.1)} through GUI
     * rendering, and a compatibility profile applies it to programmable draws too. Nothing in a material's render
     * state models it, so every fragment the UI writes at 10% alpha or less was discarded — a gradient's shoulder,
     * a 6% fill, the outer sliver of every antialiased edge — read as "the soft parts are missing", never as one GL
     * flag. The harness cannot see it: its context has no fixed-function test to leave on.</p>
     *
     * <p>Guarded on the profile: on a core profile {@code glDisable(GL_ALPHA_TEST)} is {@code GL_INVALID_ENUM}.</p>
     */
    private static void disableFixedFunctionAlphaTest() {
        if (CgCapabilities.detect().isCoreProfile()) return;
        CgGL.glDisable(CgGL.GL_ALPHA_TEST);
    }

    /**
     * Photographs the finished frame for the trace when one is due, and files whatever earlier requests have read
     * back. Never waits on the GPU. @see CgFrameImages
     */
    private void captureFrameImage() {
        long frame = CgTrace.currentFrameIndex();
        boolean due = CgFrameImages.isDue(frame);
        if (!due && (frameImages == null || !frameImages.isPending())) return;
        long timed = CgTrace.stamp(UiTrace.FRAME);
        if (frameImages == null) frameImages = new CgPixelReadback(3);
        frameImages.poll(pixels -> CgFrameImages.put(pixels.tag(), pixels.width(), pixels.height(), pixels.rgb()));
        CgTrace.zoneDone(UiTrace.FRAME, "glend:image:poll", timed);
        if (due) {
            timed = CgTrace.stamp(UiTrace.FRAME);
            frameImages.request(frameFbo.getId(), frameFbo.getWidth(), frameFbo.getHeight(),
                    CgFrameImages.width(), frame);
            CgTrace.zoneDone(UiTrace.FRAME, "glend:image:request", timed);
        }
    }

    /**
     * Frees the frame target, the readback, and everything each paint context made, and drops the instance. On GL
     * context destruction — game shutdown — so it protects no later context; it is complete teardown of what
     * nothing else sweeps. Idempotent.
     */
    public static void destroy() {
        UiGpu gpu = instance;
        if (gpu == null) return;
        for (WeakReference<CgUiPaintContext> ref : gpu.contexts) {
            CgUiPaintContext context = ref.get();
            if (context != null) context.release();
        }
        gpu.contexts.clear();
        gpu.glScope = null;
        gpu.frameFbo.delete();
        if (gpu.frameImages != null) gpu.frameImages.delete();
        instance = null;
    }

    /** Whether an instance exists, without making one. */
    public static boolean hasInstance() {
        return instance != null;
    }
}
