package com.crystalgui.render;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgCanvasBox;
import com.crystalgraphics.api.canvas.CgCanvasFrame;
import com.crystalgraphics.api.canvas.CgCanvasPresenter;
import com.crystalgraphics.api.canvas.CgCanvasRecorder;
import com.crystalgraphics.api.canvas.CgPaintChunk;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgTextureAbstract;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.text.render.CgTextDraw;
import com.crystalgraphics.text.render.CgTextGamma;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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
 * <p>Everything drawn lands on {@link #canvas()} under its state: its transform, clip, opacity, nodes and layers, each
 * pushed with {@code save()} and popped with {@code restore()}. The context adds what a painter of CrystalGUI's draws
 * with: text, segments and surfaces for the box tree.</p>
 *
 * <pre>{@code
 * ctx.canvas().fill(x, y, w, h, argb);                                                     // a flat box
 * ctx.canvas().box(x, y, w, h).radius(6f).border(1f, edge).fill(bg).draw();                // a shaped box
 * ctx.canvas().spline().line(x0, y0, x1, y1).width(2f).color(argb).draw();
 * ctx.text().text("Hello").font(font).at(x, y).submit();
 * ctx.canvas().save().clip(0, 0, w, h);                                                    // the canvas's own state
 * ctx.canvas().image(icon, 4, 4, 16, 16).restore();
 * }</pre>
 *
 * <ul>
 *   <li>Build and submit an item in one expression: {@code text()} hands out a scratch builder the next call
 *       resets.</li>
 *   <li>Never call {@code .pose}, {@code .clip} or {@code .node} on what {@link #text()} answers: each replaces the
 *       canvas's.</li>
 *   <li>Every {@code save()} on the canvas needs its {@code restore()} before the painter returns.</li>
 * </ul>
 */
public final class CgUiPaintContext {

    @Getter
    private final CgFont font = UiPaintWarmup.defaultFont();

    /** Where the pointer is, in the document's pixels: for a drawable that answers hover itself. */
    public int mouseX, mouseY;

    private CgTextGamma textGamma = CgTextGamma.initial();

    /** The frame: its recording, its picture, sealing and requested textures; its canvas takes every draw. */
    private final CgCanvasRecorder frames = new CgCanvasRecorder();
    /** The open frame's canvas. */
    private CgCanvas canvas;
    /** The open frame's recording. */
    private CgRecording recording;

    @Getter
    boolean frameActive;
    long frameId;
    int screenWidth, screenHeight;

    private final PaintSegments segments = new PaintSegments();
    private final float[] transform = new float[6], bounds = new float[4];

    /** {@link CgUiRenderer.View#paintContext()} makes one per view. */
    CgUiPaintContext() {
    }

    // ── Frame lifecycle ──────────────────────────────────────────────────────

    /** Increments once a frame: a token for work a drawable rate-limits to once a frame. */
    public long frameId() {
        return frameId;
    }

    /** Nested mirror passes: a counter, since a mirror may draw another (a preview of a window showing a preview). */
    private int mirrorDepth;

    /**
     * Draws {@code body} as a mirror: a second rendering of something also drawn elsewhere, as a taskbar preview draws a
     * window. Elements in it do not take the pose they are drawn under as their own, which hit-testing reads; a copy
     * drawn later would otherwise move the real window's clicks to where the copy is.
     */
    public void mirrored(Runnable body) {
        mirrorDepth++;
        try {
            body.run();
        } finally {
            mirrorDepth--;
        }
    }

    /** Whether the current draw is a mirror, and so must not update placement caches. @see #mirrored */
    public boolean mirroring() {
        return mirrorDepth > 0;
    }

    /**
     * Starts recording a frame of {@code screenWidth x screenHeight} into the frame's own picture. Touches no GPU. Pair
     * with {@link #seal}, which hands back what a {@link CgCanvasPresenter} shows.
     */
    public void recordFrame(int screenWidth, int screenHeight) {
        if (frameActive) throw new IllegalStateException("recordFrame() called without matching seal()");
        canvas = frames.begin(screenWidth, screenHeight);
        canvas.pixelSnap(false);   // the UI's edges sit where layout puts them, as they always have
        canvas.textGamma(textGamma);
        recording = frames.recording();
        frameId = frames.frameId();
        segments.beginFrame();
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        frameActive = true;
    }

    /**
     * Ends the frame {@link #recordFrame} started and builds it: ordered, batched and packed, with nothing left that
     * refers to this context, which may record its next frame at once. Touches no GPU.
     */
    public CgCanvasFrame seal() {
        if (!frameActive) throw new IllegalStateException("seal() without recordFrame()");
        sweepSurfaces();
        long timed = CgTrace.stamp(UiTrace.FRAME);
        CgCanvasFrame frame = frames.seal();
        CgTrace.add(UiTrace.FRAME, "drawcalls", frame.batches());   // after batching: the real count
        CgTrace.zoneDone(UiTrace.FRAME, "glend:build", timed);
        frameActive = false;
        return frame;
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
        if (!frameActive) return;
        frames.abort();
        // What its walks drew never reached the pictures: each is drawn whole next
        for (Surface surface : surfaces.values()) surface.invalidate();
        frameActive = false;
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
        requireFrame();
        return canvas;
    }

    /**
     * A text item on the canvas, under its transform, clip, nodes and this context's text gamma: build and submit in
     * one expression. Never call {@code .pose}, {@code .clip} or {@code .node} on it.
     *
     * <pre>{@code
     * ctx.text().text("Hello world").font(font).at(20f, 40f).color(0xFFFFFFFF).submit();
     * ctx.text().layout(layout).family(family).at(x, y).style(OUTLINED).submit();     // a CgTextStyle
     * }</pre>
     *
     * Its colour is taken as given: the canvas's opacity does not fade it.
     */
    public CgTextDraw text() {
        CgTrace.add(UiTrace.FRAME, "text-draws", 1);
        return canvas().textDraw();
    }

    /**
     * How many text draws this context has made below the glyph tier they asked for. A widget that draws text brackets
     * its paint with this and repaints when it moves: a glyph that missed the frame's budget drew degraded, and a
     * settled tree repaints on damage alone.
     *
     * <pre>{@code
     * long before = ctx.textDegradedDrawCount();
     * ctx.text()....submit();
     * if (ctx.textDegradedDrawCount() != before) repaint();
     * }</pre>
     */
    public long textDegradedDrawCount() {
        return canvas != null ? canvas.degradedDraws() : 0L;
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

    private void requireFrame() {
        if (!frameActive) throw new IllegalStateException("drawing outside recordFrame()/seal()");
    }

    // ── Pixels ───────────────────────────────────────────────────────────────

    /**
     * How many device pixels one unit covers under the canvas's transform. What a level-of-detail decision keys on,
     * since one logical size is twice the pixels at {@code uiScale} 2.
     */
    public float deviceScale() {
        float[] m = canvas().transform(transform);
        return (float) Math.max(Math.hypot(m[0], m[1]), Math.hypot(m[2], m[3]));
    }

    /** Whether the transform has no rotation or skew: the precondition for {@link #snapXToDevicePixel}. */
    public boolean isPoseAxisAligned() {
        float[] m = canvas().transform(transform);
        return Math.abs(m[1]) < 1e-5f && Math.abs(m[2]) < 1e-5f;
    }

    /**
     * The X that lands on the nearest whole device pixel. Hinted icon art has its edges on whole coordinates, so at 1:1
     * each edge falls on a pixel boundary; half a pixel off, every edge is antialiased. Answers {@code logicalX} when
     * the transform is not axis-aligned.
     */
    public float snapXToDevicePixel(float logicalX) {
        float[] m = canvas().transform(transform);
        if (Math.abs(m[1]) >= 1e-5f || Math.abs(m[2]) >= 1e-5f || Math.abs(m[0]) < 1e-6f) return logicalX;
        return (Math.round(m[0] * logicalX + m[4]) - m[4]) / m[0];
    }

    /** The Y-axis twin of {@link #snapXToDevicePixel}. */
    public float snapYToDevicePixel(float logicalY) {
        float[] m = canvas().transform(transform);
        if (Math.abs(m[1]) >= 1e-5f || Math.abs(m[2]) >= 1e-5f || Math.abs(m[3]) < 1e-6f) return logicalY;
        return (Math.round(m[3] * logicalY + m[5]) - m[5]) / m[3];
    }

    /**
     * Whether a box could put anything on screen: its corners through the transform against the clip's bounds.
     * Conservative: a false positive costs an empty draw, a false negative a missing icon.
     */
    public boolean isVisible(float x, float y, float w, float h) {
        float[] m = canvas().transform(transform);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float cx = (corner & 1) == 0 ? x : x + w, cy = (corner & 2) == 0 ? y : y + h;
            float px = m[0] * cx + m[2] * cy + m[4], py = m[1] * cx + m[3] * cy + m[5];
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        return !outsideClip(minX, minY, maxX, maxY);
    }

    /**
     * Whether {@code (x0, y0)-(x1, y1)}, in the device pixels of the node drawn in, misses what the clip can show.
     * Allocates nothing. @see CgCanvas#clipBounds
     */
    public boolean outsideClip(float x0, float y0, float x1, float y1) {
        float[] c = canvas().clipBounds(bounds);
        return x1 <= c[0] || x0 >= c[2] || y1 <= c[1] || y0 >= c[3];
    }

    /** The frame's width in device pixels. */
    public int targetWidth() {
        return screenWidth;
    }

    /** @see #targetWidth() */
    public int targetHeight() {
        return screenHeight;
    }

    // ── Segments ─────────────────────────────────────────────────────────────

    /** Off with {@code -Dcrystalgui.paint.replay=false}: every box painted every frame. Needs segments. */
    public static final boolean REPLAY = PaintSegments.SEGMENTS
            && !"false".equals(System.getProperty("crystalgui.paint.replay"));

    /**
     * {@code -Dcrystalgui.paint.replayCheck=true}: a segment whose key holds is painted anyway and compared with what it
     * kept, and a box that drew otherwise under an unchanged key is named once. Every box paints.
     */
    public static final boolean REPLAY_CHECK = REPLAY && Boolean.getBoolean("crystalgui.paint.replayCheck");

    /** Skips a subtree whose ink lies wholly outside what the clip can show (Blink's cull rect); off with {@code -Dcrystalgui.paint.cull=false}. */
    public static final boolean CULL = !"false".equals(System.getProperty("crystalgui.paint.cull"));

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

    /** Off with {@code -Dcrystalgui.paint.nodes=false}: no box gets a node a compositor moves, nor a surface. */
    public static final boolean NODES = !"false".equals(System.getProperty("crystalgui.paint.nodes"));

    /** Off with {@code -Dcrystalgui.paint.surfaces=false}: a box the compositor moves draws into the frame. */
    public static final boolean SURFACES = NODES && !"false".equals(System.getProperty("crystalgui.paint.surfaces"));

    /** Off with {@code -Dcrystalgui.paint.damage=false}: a surface walked again executes all of itself. */
    public static final boolean DAMAGE = !"false".equals(System.getProperty("crystalgui.paint.damage"));

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
        if (!SURFACES || retentionSuspended || !(x1 > x0 && y1 > y0) || x1 - x0 > MAX_SIDE || y1 - y0 > MAX_SIDE) {
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
     * Lends the frame's recording for passes of the caller's own (into requested textures, as a shader preview renders)
     * until {@link #endPasses()}. Every pass recorded in between must have ended.
     *
     * <pre>{@code
     * CgRecording recording = ctx.beginPasses();
     * renderer.renderPending(graph, recording);
     * ctx.endPasses();
     * ctx.canvas().box(x, y, w, h).fill(renderer.textureOf(nodeId)).region(0f, 1f, 1f, 0f).draw();
     * }</pre>
     */
    public CgRecording beginPasses() {
        canvas().endPass();
        return recording;
    }

    /** Ends what {@link #beginPasses()} lent: the canvas's next draw opens a pass after them. */
    public void endPasses() {
        requireFrame();
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    /**
     * Frees what this context made: its surfaces' pictures and the frame's textures. Its view calls it at close and at
     * graphics context destruction; the next frame makes them again.
     */
    void release() {
        for (Surface surface : surfaces.values()) surface.layer.delete();
        surfaces.clear();
        frames.release();
        frameActive = false;
    }
}
