package com.crystalgui.render;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgCanvasFrame;
import com.crystalgraphics.api.canvas.CgCanvasPresenter;
import com.crystalgraphics.api.canvas.CgCanvasRecorder;
import com.crystalgraphics.api.canvas.CgPaintChunk;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.text.render.CgTextGamma;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.trace.UiTrace;
import lombok.Getter;
import javax.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A document's paint context: it records a frame of the document onto a {@link CgCanvas}, touching no GPU, and hands
 * back a sealed {@link CgCanvasFrame} that a {@link CgCanvasPresenter} shows at a GUI stage. One per
 * {@link CgUiRenderer.View}, which a {@code CgUiViewport} owns for its document and opens for its content.
 *
 * <pre>{@code
 * CgUiPaintContext ctx = view.paintContext();
 * ctx.recordFrame(w, h);                     // any thread the document is framed on
 * document.paint(ctx);
 * CgCanvasFrame frame = ctx.seal();
 *
 * view.presenter().present(stage, frame);    // render thread, at CgRenderStage.SCREEN or HUD
 * }</pre>
 *
 * <p>Everything is drawn on {@link #canvas()}, under its state: transform, clip, opacity, nodes and layers. The
 * context adds what a box tree needs around the drawing: segments it can replay, surfaces a compositor moves, and
 * textures and passes of a painter's own.</p>
 *
 * <pre>{@code
 * CgCanvas canvas = ctx.canvas();
 * canvas.box(x, y, w, h).radius(6f).border(1f, edge).fill(bg).draw();
 * canvas.textDraw().text("Hello").font(ctx.getFont()).at(x, y).color(argb).submit();
 * canvas.save().clip(0, 0, w, h);
 * canvas.image(icon, 4, 4, 16, 16).restore();
 * }</pre>
 *
 * <ul>
 *   <li>Every {@code save()} on the canvas needs its {@code restore()} before the painter returns.</li>
 *   <li>A frame begun with {@link #recordFrame} ends with {@link #seal} or {@link #abortFrame} on the same thread.</li>
 * </ul>
 */
public final class CgUiPaintContext {

    /** How long {@link #release} waits for a frame recording on another thread before freeing under it. */
    private static final long RELEASE_WAIT_MILLIS = 2000L;

    /** The UI's default font. */
    @Getter
    private final CgFont font = UiPaintWarmup.defaultFont();

    private CgTextGamma textGamma = CgTextGamma.initial();

    /** The frame: its recording, its picture, sealing and requested textures; its canvas takes every draw. */
    private final CgCanvasRecorder frames = new CgCanvasRecorder();
    /** The open frame's canvas. */
    private CgCanvas canvas;
    /** The open frame's recording. */
    private CgRecording recording;
    /** Held by the recording thread from {@link #recordFrame} to {@link #seal} or {@link #abortFrame}. */
    private final ReentrantLock frameLock = new ReentrantLock();

    @Getter
    volatile boolean frameActive;
    long frameId;

    private final PaintSegments segments = new PaintSegments();

    /** {@link CgUiRenderer.View#paintContext()} makes one per view. */
    CgUiPaintContext() {
    }

    // ── Frame lifecycle ──────────────────────────────────────────────────────

    /** Increments once a frame: a token for work a drawable rate-limits to once a frame. */
    public long frameId() {
        return frameId;
    }

    /**
     * Starts recording a frame of {@code screenWidth x screenHeight} into the frame's own picture. Touches no GPU. Pair
     * with {@link #seal}, which hands back what a {@link CgCanvasPresenter} shows.
     */
    public void recordFrame(int screenWidth, int screenHeight) {
        if (frameActive) throw new IllegalStateException("recordFrame() called without matching seal()");
        frameLock.lock();
        try {
            canvas = frames.begin(screenWidth, screenHeight);
            canvas.pixelSnap(false);   // the UI's edges sit where layout puts them
            canvas.textGamma(textGamma);
            recording = frames.recording();
            frameId = frames.frameId();
            segments.beginFrame();
        } catch (RuntimeException | Error failed) {
            frameLock.unlock();
            throw failed;
        }
        frameActive = true;
    }

    /**
     * Ends the frame {@link #recordFrame} started and builds it: ordered, batched and packed, with nothing left that
     * refers to this context, which may record its next frame at once. Touches no GPU.
     */
    public CgCanvasFrame seal() {
        try {
            if (!frameActive) throw new IllegalStateException("seal() without recordFrame()");
            sweepSurfaces();
            long timed = CgTrace.stamp(UiTrace.FRAME);
            CgCanvasFrame frame = frames.seal();
            CgTrace.add(UiTrace.FRAME, "drawcalls", frame.batches());   // after batching: the real count
            CgTrace.zoneDone(UiTrace.FRAME, "glend:build", timed);
            return frame;
        } finally {
            endFrame();
        }
    }

    /**
     * Leaves a frame that threw part-way: what it drew discarded, and no frame open, so the next {@link #recordFrame}
     * starts clean. A no-op with no frame open.
     *
     * <pre>{@code
     * ctx.recordFrame(w, h);
     * try {
     *     document.paint(ctx);
     * } catch (RuntimeException failed) {
     *     ctx.abortFrame();
     *     throw failed;
     * }
     * return ctx.seal();
     * }</pre>
     */
    public void abortFrame() {
        try {
            if (!frameActive) return;
            frames.abort();
            // What its walks drew never reached the pictures: each is drawn whole next
            for (Surface surface : surfaces.values()) surface.invalidate();
        } finally {
            endFrame();
        }
    }

    /** No frame open, and the lock let go if this thread holds it: a {@link #release} may be waiting on it. */
    private void endFrame() {
        frameActive = false;
        while (frameLock.isHeldByCurrentThread()) frameLock.unlock();
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    /**
     * The frame's canvas: what everything is drawn on, and the state it is drawn under. Inside a frame.
     *
     * <pre>{@code
     * ctx.canvas().box(x, y, w, h).fill(texture).region(u0, v0, u1, v1).tint(argb).draw();
     * }</pre>
     */
    public CgCanvas canvas() {
        if (!frameActive) throw new IllegalStateException("drawing outside recordFrame()/seal()");
        return canvas;
    }

    /**
     * The coverage correction every label is drawn with. A change paints every kept surface again, since their text
     * keeps the correction it was drawn with; setting the same one each frame costs nothing.
     */
    public void textGamma(CgTextGamma gamma) {
        if (gamma.equals(textGamma)) return;
        textGamma = gamma;
        if (frameActive) canvas.textGamma(gamma);
        segments.invalidate("replay-epoch-gamma");
    }

    // ── Segments ─────────────────────────────────────────────────────────────

    /**
     * Starts a box's own paint: what is drawn until {@link #endSegment} is that box's and nothing else's, taken as one
     * canvas chunk. One opened inside another takes nothing of its own: chunks do not nest.
     *
     * <pre>{@code
     * ctx.beginSegment();
     * paintSelf(box);
     * node.paintContent(ctx, box);
     * kept = ctx.endSegment(chunk);
     * paintChildren(box);
     * }</pre>
     */
    public void beginSegment() {
        segments.begin(canvas());
    }

    /** Ends the innermost {@link #beginSegment}, keeping nothing. */
    public void endSegment() {
        segments.end(canvas(), null);
    }

    /**
     * {@link #endSegment()}, keeping what the segment drew in {@code keep} for {@link #replay}: answers whether it did.
     * Not kept: a segment that opened a pass (a layer, glass), drew in a node from outside itself, binds a texture made
     * for this frame alone, or drew text below the glyph tier it asked for. With replay off, nothing is kept.
     */
    public boolean endSegment(@Nullable CgPaintChunk keep) {
        return segments.end(canvas(), keep);
    }

    /**
     * Adds what {@code kept} holds in place of drawing it again, under the clip and nodes current now; false, having
     * added nothing, when it cannot. Only for a segment that would come out the same.
     *
     * <pre>{@code
     * if (sameKey && ctx.replay(chunk)) return;
     * ctx.beginSegment();
     * ... paint ...
     * ctx.endSegment(chunk);
     * }</pre>
     */
    public boolean replay(CgPaintChunk kept) {
        return segments.replay(canvas(), kept);
    }

    /**
     * Where the last {@link #endSegment} or {@link #replay} drew, {@code x0, y0, x1, y1} in the device pixels of the
     * node drawn in: empty ({@code x1 <= x0}) when it drew nothing. False when that is not known, where its box's ink
     * must speak for it. What a surface's damage is made of.
     */
    public boolean segmentDrawn(float[] out) {
        return segments.drawn(out);
    }

    /** What every kept surface was drawn under: moved when a glyph atlas evicts a page or the text gamma changes. */
    public long replayEpoch() {
        return segments.epoch();
    }

    // ── Surfaces ─────────────────────────────────────────────────────────────

    /** Frames a surface may go unasked for before it is forgotten: a window hidden for a while. */
    private static final long IDLE_FRAMES = 300L;
    /** A kept picture's largest side in device pixels: past it, the box draws into the frame. */
    private static final float MAX_SIDE = 4096f;

    private final Map<Object, Surface> surfaces = new HashMap<>();
    private boolean retentionSuspended;

    /**
     * The surface a box the compositor moves is drawn into, made on first use and kept, for a subtree inked over
     * {@code (x0, y0)-(x1, y1)} in device pixels; null where it draws into the frame as any box: inside a copy drawn
     * elsewhere, with surfaces off, empty, or past 4096 pixels a side. @see Surface
     *
     * @param key what the surface is kept under, by identity: what the box is for
     */
    @Nullable
    public Surface surface(Object key, float x0, float y0, float x1, float y1) {
        if (!PaintFlags.SURFACES || retentionSuspended || !(x1 > x0 && y1 > y0) || x1 - x0 > MAX_SIDE
                || y1 - y0 > MAX_SIDE) {
            return null;
        }
        Surface surface = surfaces.get(key);
        if (surface == null) {
            surfaces.put(key, surface = new Surface());
            CgTrace.add(UiTrace.FRAME, "surfaces-new", 1);
        }
        surface.lastFrame = frameId;
        if (key instanceof Surface.Owner owner) {
            surface.layer.keep(owner.keepsSurface()).reserve(owner.reserveWidth(), owner.reserveHeight());
            owner.surface(surface);
        }
        return surface;
    }

    /**
     * Runs {@code body} with {@link #surface} answering null: for a pass drawing a subtree a second time (a copy, a
     * check), whose drawing must not become the picture a surface holds.
     */
    public void withoutRetention(Runnable body) {
        boolean was = retentionSuspended;
        retentionSuspended = true;
        try {
            body.run();
        } finally {
            retentionSuspended = was;
        }
    }

    /** Forgets the surfaces nothing asked for in a while, every 16th frame; the canvas frees their pictures. */
    private void sweepSurfaces() {
        if (surfaces.isEmpty() || (frameId & 15L) != 0L) return;
        Iterator<Map.Entry<Object, Surface>> each = surfaces.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<Object, Surface> entry = each.next();
            if (frameId - entry.getValue().lastFrame < IDLE_FRAMES) continue;
            if (entry.getKey() instanceof Surface.Owner owner && owner.keepsSurface()) continue;
            entry.getValue().layer.keep(false);
            each.remove();
        }
    }

    // ── Kept textures and passes of the caller's own ─────────────────────────

    /**
     * An RGBA8 texture made when a frame executes and kept until {@link #releaseTexture}: what a painter writes pixels
     * into and draws across frames.
     *
     * <pre>{@code
     * CgGraphTexture picture = ctx.requestTexture("my_picture", w, h);   // in paint, once
     * ctx.update(picture, 0, 0, w, h, rgba);
     * ctx.canvas().box(x, y, w, h).fill(picture).draw();                  // this frame or any later one
     * ctx.releaseTexture(picture);                                       // in a frame or between frames
     * }</pre>
     */
    public CgGraphTexture requestTexture(String name, int width, int height) {
        return frames.requestTexture(name, width, height, CgFormat.RGBA8_UNORM);
    }

    /** Frees a requested texture's storage once what was recorded before has executed; between frames, at the next. */
    public void releaseTexture(CgGraphTexture requested) {
        if (frameActive) canvas.endPass();
        frames.releaseTexture(requested);
    }

    /**
     * Writes RGBA8 pixels into a {@code w x h} region at {@code (x, y)} of a {@link #requestTexture requested} texture,
     * before anything drawn after reads it. Row 0 of {@code rgba} lands in the texture's row 0, which UV row 0 samples:
     * a picture stored top row first draws upright. Copied now; inside a frame.
     */
    public void update(CgGraphTexture target, int x, int y, int w, int h, ByteBuffer rgba) {
        canvas().endPass();
        recording.update(target, 0, x, y, w, h, rgba);
    }

    /**
     * Lends the frame's recording to {@code body} for passes of the caller's own (into requested textures, as a shader
     * preview renders); the canvas's next draw opens a pass after them. Every pass {@code body} records must end in
     * it.
     *
     * <pre>{@code
     * ctx.passes(recording -> renderer.renderPending(graph, recording));
     * ctx.canvas().box(x, y, w, h).fill(renderer.textureOf(nodeId)).region(0f, 1f, 1f, 0f).draw();
     * }</pre>
     */
    public void passes(Consumer<CgRecording> body) {
        canvas().endPass();
        body.accept(recording);
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    /**
     * Frees what this context made: its surfaces' pictures and the frame's textures. Its view calls it at close and at
     * graphics context destruction, from the render thread; a frame recording on another thread is waited for, for
     * two seconds at most. The next frame makes them again.
     */
    void release() {
        boolean locked = false;
        try {
            locked = frameLock.tryLock(RELEASE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            CrystalGuiCore.LOGGER.warn("[cgui] a frame was still recording after {} ms: its paint context is freed"
                    + " under it, and that frame fails", RELEASE_WAIT_MILLIS);
        }
        try {
            for (Surface surface : surfaces.values()) surface.layer.delete();
            surfaces.clear();
            frames.release();
            frameActive = false;
        } finally {
            if (locked) frameLock.unlock();
        }
    }
}
