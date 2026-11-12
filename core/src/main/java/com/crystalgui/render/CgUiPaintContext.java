package com.crystalgui.render;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgTextureAbstract;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgCanvasBackdrop;
import com.crystalgraphics.render.canvas.CgCanvasGlass;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.canvas.CgCurveBatch;
import com.crystalgraphics.render.canvas.CgQuadBatch;
import com.crystalgraphics.render.canvas.CgShapeTable;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgReplay;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.property.CgPropertyValues;
import com.crystalgraphics.render.property.CgSpatialTree;
import com.crystalgraphics.text.render.CgTextDraw;
import com.crystalgraphics.text.render.CgTextGamma;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.render.texture.CgUiRect;
import com.crystalgui.render.texture.svg.SvgDocument;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.api.material.CgMaterialInterface;

/**
 * A document's paint context: it records a frame of the document onto a {@link CgCanvas}, touching no GPU, and hands
 * back a sealed {@link UiFrame} that a {@link UiPresenter} shows at a GUI stage. One per {@link CgUiRenderer.View}, which a
 * {@code CgUiViewport} owns for its document and opens for its content.
 *
 * <pre>{@code
 * CgUiPaintContext ctx = view.paintContext();
 * ctx.recordFrame(w, h);                     // any thread the document is framed on
 * document.paint(ctx);
 * UiFrame frame = ctx.seal();
 *
 * view.presenter().present(stage, frame);    // render thread, at CgRenderStage.SCREEN or HUD
 * }</pre>
 *
 * <p>Everything drawn is a canvas item stamped with the context's state as it is built: the pose on
 * {@link #getPoseStack()}, the clip ({@link #pushScissor}, {@link #pushRoundedClip}), the nodes ({@link #enterNode})
 * and the layer opacity ({@link #pushLayerOpacity}). Changing any of them flushes nothing. Each item names its own
 * material, so a drawable with a shader of its own draws {@code ctx.quad(material)}:</p>
 *
 * <pre>{@code
 * ctx.fillRect(x, y, w, h, argb);                                                    // a flat box
 * ctx.quad().texture(0, icon).at(x, y).size(16, 16).color(tint).submit();             // an image, on the box material
 * ctx.rect().at(x, y).size(w, h).radius(6f, 6f).border(1f, edge).fillColor(bg).submit();   // a shaped box
 * ctx.quad(gradient.material()).at(x, y).size(w, h).submit();                          // a material of its own
 * ctx.curve().line(x0, y0, x1, y1).width(1f).color(argb).submit();
 * ctx.text().text("Hello").font(font).at(x, y).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Build and submit an item in one expression: {@code quad()}, {@code curve()}, {@code rect()} and {@code text()}
 *       hand out scratch builders the next call resets.</li>
 *   <li>Never call {@code .pose}, {@code .clip}, {@code .node} or {@code .opacity} on what they answer: each replaces
 *       the context's.</li>
 *   <li>Order is the order of submission, across kinds. {@link #flush} is for a caller about to read what was drawn,
 *       never for order.</li>
 *   <li>A material passed to {@code quad(m)} carries values the caller set; the layer opacity is per item, so no
 *       property of it is ever written here.</li>
 * </ul>
 */
public final class CgUiPaintContext {

    // ── What it draws with ───────────────────────────────────────────────────

    static final CgFormat LAYER_FORMAT = CgFormat.RGBA8_UNORM;

    private final CgUiBackdrop backdrop;
    private final SvgRasterCache svgRaster;
    private final PaintSegments segments;
    private final PaintSurfaces surfaces;

    @Getter
    private final PoseStack poseStack = new PoseStack();

    @Getter
    private final CgFont font = UiPaintWarmup.defaultFont();

    /** The tint a drawable multiplies its own colours by: set around its draw by whoever paints it. */
    @Getter @Setter
    private int color = 0xFFFFFFFF;

    /**
     * Multiplies the colours a picture names itself, as {@link #getColor()} resolves a mark's {@code currentColor}: the
     * cascade's {@code overlay-tint}, set around an overlay's draw. White draws a picture as authored; anything else
     * must be set back to white after.
     */
    @Getter @Setter
    private int pictureTint = 0xFFFFFFFF;

    /** Where the pointer is, in the document's pixels: for a drawable that answers hover itself. */
    public int mouseX, mouseY;

    /**
     * Every box: fills, textures, shapes, icons, composites. CrystalGraphics' shared instance, taken with the context
     * rather than at class load (precache loads this class before any graphics); no property is ever set on it.
     */
    private final CgMaterial box = CgCanvasMaterials.SCREEN.box();
    /** Every Bézier stroke, triangle and cell, unless {@link #withCurveMaterial} names another. */
    private final CgMaterial curve = CgCanvasMaterials.SCREEN.curve();
    /** The curve material in force: {@link #curve}, or what {@link #withCurveMaterial} set. */
    private CgMaterialInterface curveMaterial = curve;

    /** The opacity every item drawn now is stamped with: the folded layers' product, 1 outside one. */
    private float layerOpacity = 1f;

    private CgTextGamma textGamma = CgTextGamma.initial();

    /** {@link #box}'s state with the alpha multiply a mask composite draws under, and the state it was made from. */
    @Nullable
    private CgRenderState maskState, maskStateBase;

    // ── The frame as recorded ────────────────────────────────────────────────
    //
    // Every target's draws are chunks in passes of ONE recording. A layer is a pass on a texture the executor lends;
    // the target around it ends its pass at the layer and continues in another after, so passes are created in the
    // order they run and every read follows the write it sees.

    private final CgRecording recording = new CgRecording();
    /** What this context draws into: the passes of {@link #recording}. */
    private final CgCanvas recorder = new CgCanvas();
    /** The pass block of the target being drawn into: the frame's time, the target's ortho and size. */
    private final CgCamera camera = new CgCamera();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();

    /**
     * The frame's own target: the whole tree records into it and it is composited onto the host's once, so the finished
     * picture is a texture this engine owns. Not multisampled: every UI material antialiases its own edges. Remade when
     * the surface changes size; null before the first frame.
     */
    @Nullable
    CgGraphTexture frameTarget;

    @Getter
    boolean frameActive;
    long frameId;
    int screenWidth, screenHeight;

    @Getter
    private final ScissorStack scissorStack = new ScissorStack(recorder);

    /** Requested textures released between frames: the next frame records their release first. */
    private final List<CgGraphTexture> pendingReleases = new ArrayList<>();

    /** {@link CgUiRenderer.View#paintContext()} makes one per view. */
    CgUiPaintContext() {
        this.backdrop = new CgUiBackdrop(this);
        this.svgRaster = new SvgRasterCache(this);
        this.segments = new PaintSegments(this, recorder, recording);
        this.surfaces = new PaintSurfaces(this, recording);
    }

    /** Points the pass constants at a {@code width x height} target, top-left origin; chunks from now take them. */
    private void targetConstants(int width, int height) {
        recorder.camera(camera.screenLens(width, height));
    }

    // ── Frame lifecycle ──────────────────────────────────────────────────────

    /** Increments once a frame: a token for work a drawable rate-limits to once a frame. */
    public long frameId() {
        return frameId;
    }

    private final SvgDocument.LodBudget svgLodBudget = new SvgDocument.LodBudget();

    /** What this paint has spent building icon mesh tiers this frame. */
    public SvgDocument.LodBudget svgLodBudget() {
        return svgLodBudget;
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
     * Starts recording a frame of {@code screenWidth x screenHeight} into the frame's own target. Touches no GPU. Pair
     * with {@link #seal}, which hands back what a {@link UiPresenter} shows.
     */
    public void recordFrame(int screenWidth, int screenHeight) {
        if (frameActive) throw new IllegalStateException("recordFrame() called without matching seal()");
        frameId++;
        segments.beginFrame();
        layerOriginX = 0;
        layerOriginY = 0;
        clipEntry = 0;
        layerOpacity = 1f;
        curveMaterial = curve;
        nodesSuspended = 0;
        enterNode(0);
        effectNode = 0;
        viewOwner = 0;
        recorder.view(0, 0f, 0f);
        backdrop.beginFrame();
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        scissorStack.reset();
        recorder.noScissor();
        poseStack.pushPose();

        camera.time(CgFrameClock.seconds());   // what a material reading CG_TIME animates by
        targetConstants(screenWidth, screenHeight);
        for (CgGraphTexture released : pendingReleases) recording.release(released);
        pendingReleases.clear();
        int w = Math.max(1, screenWidth), h = Math.max(1, screenHeight);
        if (frameTarget == null || frameTarget.getWidth() != w || frameTarget.getHeight() != h) {
            if (frameTarget != null) recording.release(frameTarget);
            frameTarget = requestTexture("cgui_frame", w, h, LAYER_FORMAT);
        }
        recorder.recordInto(recording, frameTarget, CgLoad.CLEAR, camera);
        frameActive = true;
    }

    /**
     * Ends the frame {@link #recordFrame} started and builds it: ordered, batched and packed, with nothing left that
     * refers to this context, which may record its next frame at once. Touches no GPU.
     */
    public UiFrame seal() {
        if (!frameActive) throw new IllegalStateException("seal() without recordFrame()");
        surfaces.sweep();
        long timed = CgTrace.stamp(UiTrace.FRAME);
        recorder.stop();
        CgTrace.zoneDone(UiTrace.FRAME, "glend:flush", timed);

        timed = CgTrace.stamp(UiTrace.FRAME);
        UiFrame frame;
        try {
            CgPropertyValues values = new CgPropertyValues();
            CgFrame drawn = build(recording, values);
            CgTrace.add(UiTrace.FRAME, "drawcalls", drawn.batches());   // after batching: the real count
            frame = new UiFrame(drawn, builder, frameTarget, values, frameId, screenWidth, screenHeight);
        } finally {
            recording.reset();
        }
        CgTrace.zoneDone(UiTrace.FRAME, "glend:build", timed);

        frameActive = false;
        poseStack.popPose();
        if (!poseStack.clear()) throw new IllegalStateException("Unpopped stack(s) in UI frame");
        if (layerDepth != 0) throw new IllegalStateException(layerDepth + " layer(s) left open in UI frame");
        return frame;
    }

    private CgFrame build(CgRecording what, @Nullable CgPropertyValues values) {
        try {
            graph.add(what.seal(), values);
            return builder.build(graph);
        } finally {
            graph.clear();
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
        if (!frameActive) return;
        svgRaster.abandoned();
        recorder.abandon();
        recording.reset();
        surfaces.abort();
        Arrays.fill(layers, 0, layerDepth, null);
        layerDepth = 0;
        while (!poseStack.clear()) poseStack.popPose();   // what the frame and the throw left pushed
        frameActive = false;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    /** A flat box in {@code argb}, which already includes any tint. */
    public void fillRect(float x, float y, float width, float height, int argb) {
        quad().at(x, y).size(width, height).color(argb).submit();
    }

    /**
     * {@code texture}'s {@code (u0, v0)-(u1, v1)} drawn over a box, tinted by {@code argb}. A texture that failed to
     * load draws its whole checkerboard: a crop of it would read as a flat colour rather than as missing.
     */
    public void drawImage(CgTexture texture, float x, float y, float width, float height,
                          float u0, float v0, float u1, float v1, int argb) {
        CgQuadBatch.Quad quad = quad().texture(0, texture).at(x, y).size(width, height).color(argb);
        (texture instanceof CgTextureAbstract file && file.isMissing() ? quad : quad.uv(u0, v0, u1, v1)).submit();
    }

    /**
     * A quad on the box material, under this context's pose, clip, nodes and opacity: an image with
     * {@code .texture(0, t)}, a shape with {@code .custom2(shapes().begin(...)...end())}, a flat colour with neither.
     *
     * <pre>{@code
     * ctx.quad().texture(0, atlas).at(x, y).size(w, h).uv(u0, v0, u1, v1).color(argb).submit();
     * }</pre>
     *
     * <p>Build and submit in one expression, and never call {@code .pose}, {@code .clip}, {@code .node} or
     * {@code .opacity} on it: each replaces the context's.</p>
     */
    public CgQuadBatch.Quad quad() {
        return quad(box);
    }

    /**
     * {@link #quad()} drawn with {@code material}: a drawable's own shader. The material's values are as the caller last
     * set them; set them before this call, since the quad takes them when it is submitted.
     *
     * <pre>{@code
     * material.set1f("_Angle", angle);   // its own instance
     * ctx.quad(material).at(x, y).size(w, h).color(ctx.getColor()).submit();
     * }</pre>
     *
     * Its shader reads {@code CG_QUAD_OPACITY} and {@code CG_CLIP_QUAD_COVERAGE}, or the layer opacity and the
     * rounded clips do not reach it.
     */
    public CgQuadBatch.Quad quad(CgMaterialInterface material) {
        requireFrame();
        return recorder.quad(material).pose(poseStack.last().pose()).clip(clipEntry).node(spatialNode, effectNode)
                .opacity(layerOpacity);
    }

    /** The shapes this frame's quads name, for {@code quad().custom2(index)}. Inside a frame. @see CgShapeTable */
    public CgShapeTable shapes() {
        return recording.shapes();
    }

    private final CgUiRect.Draw rectScratch = new CgUiRect.Draw();

    /**
     * A rounded and/or bordered rect: {@link #quad()} naming a shape, or the plain quad when it has neither. The fill
     * tint is {@link #getColor()} at submit time.
     *
     * <pre>{@code
     * ctx.rect().at(x, y).size(w, h).fillColor(argb).submit();
     * ctx.rect().at(x, y).size(w, h).radius(6f, 6f).border(1f, edge).fillColor(bg).submit();
     * }</pre>
     *
     * <p>Build and submit in one expression: it is this context's one scratch, reset by the next {@code rect()}.</p>
     */
    public CgUiRect.Draw rect() {
        return rectScratch.begin(this);
    }

    /**
     * A Bézier stroke under this context's pose, clip, nodes and opacity. Widths scale with the pose, so a 2px stroke
     * stays 2 logical px at any {@code uiScale}, as a border does.
     *
     * <pre>{@code
     * ctx.curve().line(x0, y0, x1, y1).width(2f).color(argb).submit();
     * ctx.curve().from(x0, y0).via(cx, cy).to(x1, y1).width(4f, 1f).colors(a, b).submit();
     * }</pre>
     *
     * <p>The same rules as {@link #quad()}. Curves and quads interleave freely; neighbours of one kind batch.</p>
     */
    public CgCurveBatch.Curve curve() {
        requireFrame();
        return recorder.curve(curveMaterial).pose(poseStack.last().pose()).clip(clipEntry).node(spatialNode, effectNode)
                .opacity(layerOpacity);
    }

    /** A filled triangle, as {@link #curve()}: its corner radius and feather scale with the pose. */
    public CgCurveBatch.Triangle triangle() {
        requireFrame();
        return recorder.triangle(curveMaterial).pose(poseStack.last().pose()).clip(clipEntry)
                .node(spatialNode, effectNode).opacity(layerOpacity);
    }

    /**
     * A cell of a tessellated fill, as {@link #curve()}: coverage is exact area in device pixels on the edges it marks
     * soft.
     *
     * <pre>{@code
     * ctx.filledCell().points(x0, y0, x1, y1, x2, y2, x3, y3)
     *         .softEdges(CgCurveBatch.CELL_LEFT | CgCurveBatch.CELL_RIGHT).color(argb).submit();
     * }</pre>
     */
    public CgCurveBatch.Cell filledCell() {
        requireFrame();
        return recorder.cell(curveMaterial).pose(poseStack.last().pose()).clip(clipEntry).node(spatialNode, effectNode)
                .opacity(layerOpacity);
    }

    /**
     * Runs {@code body} with {@link #curve()}, {@link #triangle()} and {@link #filledCell()} drawing through
     * {@code material}: how the icon raster accumulates coverage through the same pose and clip as everything else.
     */
    void withCurveMaterial(CgMaterialInterface material, Runnable body) {
        CgMaterialInterface saved = curveMaterial;
        curveMaterial = material;
        try {
            body.run();
        } finally {
            curveMaterial = saved;
        }
    }

    /**
     * A text item under this context's pose, clip, nodes and text gamma: build and submit in one expression. It lands in
     * order with the boxes and curves around it. Never call {@code .pose}, {@code .clip} or {@code .node} on it.
     *
     * <pre>{@code
     * ctx.text().text("Hello world").font(font).at(20f, 40f).color(0xFFFFFFFF).submit();
     * ctx.text().layout(layout).family(family).at(x, y).style(OUTLINED).submit();     // a CgTextStyle
     * }</pre>
     *
     * Text is not faded by the layer opacity: its colour fades it.
     */
    public CgTextDraw text() {
        CgTrace.add(UiTrace.FRAME, "text-draws", 1);
        return recorder.text().pose(poseStack).clip(clipEntry).node(spatialNode, effectNode).gamma(textGamma);
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
        return recorder.textDegradedDraws();
    }

    /**
     * The coverage correction every label is drawn with. A change paints every kept segment and surface again, since
     * their text keeps the correction it was recorded with; setting the same one each frame costs nothing.
     */
    public void textGamma(CgTextGamma gamma) {
        if (gamma.equals(textGamma)) return;
        textGamma = gamma;
        segments.invalidate("replay-epoch-gamma");
    }

    /** Where a small icon's fills are rasterised once and drawn from. */
    public SvgRasterCache svgRaster() {
        return svgRaster;
    }

    /**
     * Lands everything drawn so far: into the recording while a frame records, else drawn now. For a caller about to
     * read what was drawn; order never needs it.
     */
    public void flush() {
        recorder.flush();
    }

    /** {@link #flush}, then ends the target's open pass, so what it holds is what a pass recorded next reads. */
    void drain() {
        flush();
        recorder.endPass();
    }

    private void requireFrame() {
        if (!frameActive) throw new IllegalStateException("drawing outside beginFrame()/endFrame()");
    }

    // ── Layer opacity ────────────────────────────────────────────────────────

    /** The opacity every item drawn now is stamped with; 1 outside a folded layer. @see #pushLayerOpacity */
    public float layerOpacity() {
        return layerOpacity;
    }

    /**
     * Fades what is drawn until {@link #popLayerOpacity}, each item on its own: composed with the opacity already in
     * force, never replacing it. Distinct from {@link #getColor()}'s alpha, which a box applies to its fill alone. Costs
     * nothing: it is stamped on each item.
     *
     * <pre>{@code
     * float previous = ctx.pushLayerOpacity(0.5f);
     * try { ... } finally { ctx.popLayerOpacity(previous); }
     * }</pre>
     *
     * Pass the returned value back to {@code popLayerOpacity}: it is what was in force before. Text does not fade.
     */
    public float pushLayerOpacity(float opacity) {
        float previous = layerOpacity;
        layerOpacity = previous * opacity;
        return previous;
    }

    /** Restores what {@link #pushLayerOpacity} returned. */
    public void popLayerOpacity(float previous) {
        layerOpacity = previous;
    }

    /** {@link #pushLayerOpacity} around {@code body}, for a caller off the per-element path. */
    public void withLayerOpacity(float opacity, Runnable body) {
        float previous = pushLayerOpacity(opacity);
        try {
            body.run();
        } finally {
            popLayerOpacity(previous);
        }
    }

    // ── Pixels ───────────────────────────────────────────────────────────────

    /**
     * The pose's scale: how many device pixels one logical unit covers. What a level-of-detail decision keys on, since
     * one logical size is twice the pixels at {@code uiScale} 2.
     */
    public float deviceScale() {
        Matrix4f m = targetPose();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
        float sy = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11());
        return Math.max(sx, sy);
    }

    /** Whether the pose has no rotation or skew: the precondition for {@link #snapXToDevicePixel}. */
    public boolean isPoseAxisAligned() {
        Matrix4f m = targetPose();
        return Math.abs(m.m01()) < 1e-5f && Math.abs(m.m10()) < 1e-5f;
    }

    /**
     * The logical X that lands on the nearest whole device pixel. Hinted icon art has its edges on whole coordinates, so
     * at 1:1 each edge falls on a pixel boundary; half a pixel off, every edge is antialiased. The rounding is after the
     * pose, which carries translations of its own. Answers {@code logicalX} when the pose is not axis-aligned.
     */
    public float snapXToDevicePixel(float logicalX) {
        Matrix4f m = targetPose();
        if (!isPoseAxisAligned() || Math.abs(m.m00()) < 1e-6f) return logicalX;
        float device = m.m00() * logicalX + m.m30();
        return (Math.round(device) - m.m30()) / m.m00();
    }

    /** The Y-axis twin of {@link #snapXToDevicePixel}. */
    public float snapYToDevicePixel(float logicalY) {
        Matrix4f m = targetPose();
        if (!isPoseAxisAligned() || Math.abs(m.m11()) < 1e-6f) return logicalY;
        float device = m.m11() * logicalY + m.m31();
        return (Math.round(device) - m.m31()) / m.m11();
    }

    /**
     * Whether a logical box could put anything on screen: its four corners through the pose against the scissor or the
     * target. Conservative: a false positive costs an empty draw, a false negative a missing icon.
     */
    public boolean isVisible(float x, float y, float w, float h) {
        Matrix4f m = targetPose();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float cx = (corner & 1) == 0 ? x : x + w, cy = (corner & 2) == 0 ? y : y + h;
            float px = m.m00() * cx + m.m10() * cy + m.m30(), py = m.m01() * cx + m.m11() * cy + m.m31();
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        float clipX0 = 0f, clipY0 = 0f, clipX1 = targetWidth(), clipY1 = targetHeight();
        if (scissorStack.hasScissor()) {   // top-left rects in the target's pixels: the space the pose produced
            clipX0 = scissorStack.currentX();
            clipX1 = clipX0 + scissorStack.currentW();
            clipY0 = scissorStack.currentY();
            clipY1 = clipY0 + scissorStack.currentH();
        }
        return maxX >= clipX0 && minX <= clipX1 && maxY >= clipY0 && minY <= clipY1;
    }

    /**
     * The width of the target being drawn into: the screen, or the innermost layer. A scissor rect and a cull bound are
     * in this target's pixels, which a window's snapshot sizes to the window rather than the screen.
     */
    public int targetWidth() {
        return layerDepth == 0 ? screenWidth : layers[layerDepth - 1].target.getWidth();
    }

    /** @see #targetWidth() */
    public int targetHeight() {
        return layerDepth == 0 ? screenHeight : layers[layerDepth - 1].target.getHeight();
    }

    // ── Clips ────────────────────────────────────────────────────────────────

    /** The {@link CgClipTable} entry every item is stamped with; 0 when no clip is active. */
    private int clipEntry;

    /** Off with {@code -Dcrystalgui.paint.squareClips=false}: every {@link #pushScissor} is a scissor. */
    private static final boolean SQUARE_CLIPS = !"false".equals(System.getProperty("crystalgui.paint.squareClips"));

    /**
     * Clips what is drawn until {@link #popScissor} to a rect, intersected with the clip in force. The rect is in the
     * pose's space, like everything this context draws, and rounded outward to whole pixels of the target.
     *
     * <ul>
     *   <li>Usually a clip entry stamped on each item: no batch break.</li>
     *   <li>A real scissor inside a target with a space of its own, under a node that is not a whole-pixel translation,
     *       or past the clip chain's depth.</li>
     * </ul>
     */
    public void pushScissor(float x, float y, float w, float h) {
        Matrix4f m = targetPose();
        float physX0 = m.m00() * x + m.m10() * y + m.m30(), physY0 = m.m01() * x + m.m11() * y + m.m31();
        float physX1 = m.m00() * (x + w) + m.m10() * (y + h) + m.m30();
        float physY1 = m.m01() * (x + w) + m.m11() * (y + h) + m.m31();
        int physX = (int) Math.floor(Math.min(physX0, physX1));
        int physY = (int) Math.floor(Math.min(physY0, physY1));
        int physW = Math.max(0, (int) Math.ceil(Math.max(physX0, physX1)) - physX);
        int physH = Math.max(0, (int) Math.ceil(Math.max(physY0, physY1)) - physY);
        int clip = squareClip(physX, physY, physW, physH);
        if (clip > 0) {
            CgTrace.add(UiTrace.FRAME, "clips-square", 1);
            scissorStack.pushClip(physX, physY, physW, physH, clip, clipEntry);
            clipEntry = clip;
            return;
        }
        CgTrace.add(UiTrace.FRAME, "scissors", 1);
        if (spatialNode == 0) {
            scissorStack.pushScissor(physX, physY, physW, physH);
        } else {
            // In the node's space as well, which is what moves with it.
            Matrix4f p = poseStack.last().pose();
            float ax = p.m00() * x + p.m10() * y + p.m30(), ay = p.m01() * x + p.m11() * y + p.m31();
            float bx = p.m00() * (x + w) + p.m10() * (y + h) + p.m30(), by = p.m01() * (x + w) + p.m11() * (y + h) + p.m31();
            scissorStack.pushScissor(physX, physY, physW, physH, spatialNode,
                    Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
        }
        scissorStack.applyScissorIfNeeded(targetHeight());
    }

    /** Pops the innermost {@link #pushScissor}. */
    public void popScissor() {
        if (scissorStack.topClip() != 0) {
            clipEntry = scissorStack.topRestore();
            scissorStack.popScissor();
            return;
        }
        scissorStack.popScissor();
        reapplyScissor();
    }

    /** Re-applies the clip stack to the target bound now: for a caller that changed the target or the stack itself. */
    public void reapplyScissor() {
        reapplyScissorFor(targetHeight());
    }

    private void reapplyScissorFor(int targetHeight) {
        if (scissorStack.hasScissor()) scissorStack.applyScissorIfNeeded(targetHeight);
        else scissorStack.clearScissorIfNeeded();
    }

    /** Lifts every clip rect until {@link #resumeScissor}: for a draw into a target the clips do not describe. */
    ScissorStack.Saved suspendScissor() {
        ScissorStack.Saved saved = scissorStack.suspend();
        reapplyScissor();
        return saved;
    }

    void resumeScissor(ScissorStack.Saved saved) {
        scissorStack.resume(saved);
        reapplyScissor();
    }

    /**
     * {@code (x, y, w, h)}, whole pixels of the bound target, as a clip entry: no flush and no batch break. 0 where a
     * scissor must do: inside a target with a space of its own, whose materials read no clip; under a node that is not
     * a whole-pixel translation; past the chain's depth.
     */
    private int squareClip(int x, int y, int w, int h) {
        if (!SQUARE_CLIPS || !frameActive || nodesSuspended > 0) return 0;
        int tx = 0, ty = 0;
        if (spatialNode != 0) {
            Matrix4f world = drawToTarget();
            if (world.m00() != 1f || world.m01() != 0f || world.m10() != 0f || world.m11() != 1f) return 0;
            float fx = world.m30(), fy = world.m31();
            if (fx != (int) fx || fy != (int) fy) return 0;
            tx = (int) fx;
            ty = (int) fy;
        }
        int entry = recording.clips().addPixelRect(clipEntry, spatialNode, x - tx, y - ty, x - tx + w, y - ty + h,
                targetHeight());
        return Math.max(entry, 0);
    }

    /**
     * Clips everything drawn until {@link #popRoundedClip} to a rounded rectangle, antialiased like a rect's own edge,
     * with no layer: a {@link CgClipTable} entry stamped on every item. What a rounded {@code overflow: hidden} uses.
     * Clips nest: a draw inside two is clipped by both.
     *
     * <pre>{@code
     * if (ctx.pushRoundedClip(0, 0, w, h, rx, ry, border)) {
     *     paintChildren();
     *     ctx.popRoundedClip();
     * } else {
     *     // a mask layer
     * }
     * }</pre>
     *
     * <ul>
     *   <li>False, and nothing pushed, past {@link CgClipTable#MAX_DEPTH} or where the pose collapses the rect.</li>
     *   <li>A layer begun inside it draws unclipped, and is clipped when composited back.</li>
     *   <li>A material that does not read {@code CG_CLIP_*_COVERAGE} ignores it; every UI material reads it.</li>
     * </ul>
     *
     * @param rx     horizontal corner radii in the pose's units: top-left, top-right, bottom-right, bottom-left
     * @param ry     vertical corner radii, same order
     * @param border edge widths left, top, right, bottom, cut away as a mask's transparent border is; null for none
     */
    public boolean pushRoundedClip(float x, float y, float w, float h, float[] rx, float[] ry, @Nullable float[] border) {
        int entry = recording.clips().add(clipEntry, spatialNode, poseStack.last().pose(), targetHeight(), x, y,
                x + w, y + h, rx, ry, border);
        if (entry < 0) return false;
        CgTrace.add(UiTrace.FRAME, "clips-rounded", 1);
        clipEntry = entry;
        return true;
    }

    /** Ends the innermost {@link #pushRoundedClip} that answered true. */
    public void popRoundedClip() {
        clipEntry = recording.clips().parent(clipEntry);
    }

    int clipEntry() {
        return clipEntry;
    }

    /** Nesting depth of the clip stack; 0 when nothing is clipped. */
    public int getScissorDepth() {
        return scissorStack.depth();
    }

    /** The live clip, as {@link #resolveClip} found it, in the current target's pixels. */
    private float clipX0, clipY0, clipX1, clipY1;

    /**
     * What the current target can show: the scissor where one is set, else the enclosing layer's region rather than its
     * texture, whose bucketed slack the composite never reads. A layer opened without a region (a snapshot, a capture)
     * is all target.
     */
    private void resolveClip() {
        if (scissorStack.hasScissor()) {
            clipX0 = scissorStack.currentX();
            clipY0 = scissorStack.currentY();
            clipX1 = clipX0 + scissorStack.currentW();
            clipY1 = clipY0 + scissorStack.currentH();
            return;
        }
        LayerRegion region = layerDepth == 0 ? null : layers[layerDepth - 1].region;
        clipX0 = 0f;
        clipY0 = 0f;
        clipX1 = region != null ? region.width() : targetWidth();
        clipY1 = region != null ? region.height() : targetHeight();
    }

    /** The live clip in the bound target's pixels, outward-rounded, into {@code out}: x0, y0, x1, y1. */
    void clipRect(int[] out) {
        resolveClip();
        out[0] = (int) Math.floor(clipX0);
        out[1] = (int) Math.floor(clipY0);
        out[2] = (int) Math.ceil(clipX1);
        out[3] = (int) Math.ceil(clipY1);
    }

    /**
     * The live clip in the current draw space, {@code x0, y0, x1, y1} into {@code out}; false, and nothing written, when
     * the draw space is not a translation of the target's pixels.
     */
    public boolean clipInDraw(float[] out) {
        resolveClip();
        Matrix4f m = drawToTarget();
        if (m.m00() != 1f || m.m01() != 0f || m.m10() != 0f || m.m11() != 1f) return false;
        out[0] = clipX0 - m.m30();
        out[1] = clipY0 - m.m31();
        out[2] = clipX1 - m.m30();
        out[3] = clipY1 - m.m31();
        return true;
    }

    /** Whether the rect, in the current target's pixels, misses the live clip entirely. Allocates nothing. */
    public boolean outsideClip(float x0, float y0, float x1, float y1) {
        if (LEGACY_LAYERS) return false;
        resolveClip();
        return x1 <= clipX0 || x0 >= clipX1 || y1 <= clipY0 || y0 >= clipY1;
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

    /**
     * Starts a box's own paint: what is drawn until {@link #endSegment} is that box's and nothing else's. Segments nest,
     * since a widget may paint another subtree from its own content, as a window thumbnail does.
     *
     * <pre>{@code
     * ctx.beginSegment();
     * paintSelf(box);
     * node.paintContent(ctx, box);
     * ctx.endSegment();
     * paintChildren(box);
     * }</pre>
     */
    public void beginSegment() {
        segments.begin();
    }

    /** Ends the innermost {@link #beginSegment}. */
    public void endSegment() {
        segments.end(null);
    }

    /**
     * {@link #endSegment()}, keeping what the segment recorded in {@code keep} for {@link #replay}: answers whether it
     * did. Not kept: a segment that recorded anything but draws (a request, a layer, a capture), changed pass or
     * scissor, drew in another node, binds a texture made for this frame alone, or drew text below the glyph tier it
     * asked for. With replay off, nothing is kept.
     */
    public boolean endSegment(@Nullable CgReplay keep) {
        return segments.end(keep);
    }

    /**
     * Records what {@code kept} holds in place of drawing it again, under the clip entry and nodes current now; false,
     * having recorded nothing, when it cannot. Only for a segment that would come out the same, under a
     * {@link #replayEpoch} that has not moved since it was kept.
     *
     * <pre>{@code
     * if (sameKey && ctx.replay(stretch)) return;
     * ctx.beginSegment();
     * ... paint ...
     * kept = ctx.endSegment(stretch);
     * }</pre>
     */
    public boolean replay(CgReplay kept) {
        return segments.replay(kept);
    }

    /**
     * Where the last {@link #endSegment} or {@link #replay} drew, {@code x0, y0, x1, y1} in the target's pixels: empty
     * ({@code x1 <= x0}) when it drew nothing. False when a draw covers everything or lies under another node, where its
     * box's ink must speak for it. What a surface's damage is made of.
     */
    public boolean segmentDrawn(float[] out) {
        return segments.drawn(out);
    }

    /**
     * What every kept segment was recorded under: moved when a glyph atlas evicts a page, the icon raster clears its
     * atlas, the text gamma changes or the kept snapshots are dropped.
     */
    public long replayEpoch() {
        return segments.epoch(svgRaster.epoch());
    }

    // ── Spatial and effect nodes ─────────────────────────────────────────────

    /** Off with {@code -Dcrystalgui.paint.nodes=false}: every draw baked into its target's pixels. */
    public static final boolean NODES = !"false".equals(System.getProperty("crystalgui.paint.nodes"));

    /** The spatial and effect nodes items are recorded in: 0 and 0 for the bound target's own space. */
    private int spatialNode, effectNode;
    /** Open targets with a space of their own (a snapshot, a capture), inside which no node is made. */
    private int nodesSuspended;
    /** Per spatial node of this frame, its world in the frame target's pixels as recorded: a b c d tx ty. */
    private float[] nodeWorlds = new float[6 * 64];
    private final Matrix4f drawToTarget = new Matrix4f(), targetToDraw = new Matrix4f(), targetPose = new Matrix4f();
    private boolean drawToTargetValid;
    /** The view owner of the passes being recorded. @see CgCanvas#view */
    private int viewOwner;

    /**
     * A spatial node inside the current one, for content whose origin is {@code origin} in the current draw space; 0
     * where none can be made (inside a target with a space of its own, past the tree's size, or with nodes off). A node
     * moves by the values a compositor gives it, with nothing recorded again.
     *
     * <pre>{@code
     * Matrix4f origin = new Matrix4f(base).mul(box.localToWorld()).translate(-scrollLeft, -scrollTop, 0f);
     * int content = ctx.addNode(origin, true);
     * if (content != 0) {
     *     int outer = ctx.enterNode(content);
     *     paintChildren(new Matrix4f(origin).invert().mul(base));
     *     ctx.enterNode(outer);
     * }
     * }</pre>
     *
     * @param movable whether a compositor moves it: draws under it never batch past draws outside it
     */
    public int addNode(Matrix4f origin, boolean movable) {
        if (!NODES || nodesSuspended > 0 || !frameActive) return 0;
        CgSpatialTree tree = recording.spatial();
        if (tree.count() >= CgSpatialTree.MAX_NODES) return 0;
        float a = origin.m00(), b = origin.m01(), c = origin.m10(), d = origin.m11(), tx = origin.m30(), ty = origin.m31();
        // In a node, the origin is the local affine; at 0 it is in the bound target, which sits at the layer origin.
        if (spatialNode == 0) {
            tx += layerOriginX;
            ty += layerOriginY;
        }
        int node = tree.add(spatialNode, a, b, c, d, tx, ty, movable);
        if (nodeWorlds.length < (node + 1) * 6) nodeWorlds = Arrays.copyOf(nodeWorlds, nodeWorlds.length * 2);
        int o = node * 6;
        if (spatialNode == 0) {
            nodeWorlds[o] = a;
            nodeWorlds[o + 1] = b;
            nodeWorlds[o + 2] = c;
            nodeWorlds[o + 3] = d;
            nodeWorlds[o + 4] = tx;
            nodeWorlds[o + 5] = ty;
        } else {
            int p = spatialNode * 6;
            float pa = nodeWorlds[p], pb = nodeWorlds[p + 1], pc = nodeWorlds[p + 2], pd = nodeWorlds[p + 3];
            nodeWorlds[o] = pa * a + pc * b;
            nodeWorlds[o + 1] = pb * a + pd * b;
            nodeWorlds[o + 2] = pa * c + pc * d;
            nodeWorlds[o + 3] = pb * c + pd * d;
            nodeWorlds[o + 4] = pa * tx + pc * ty + nodeWorlds[p + 4];
            nodeWorlds[o + 5] = pb * tx + pd * ty + nodeWorlds[p + 5];
        }
        return node;
    }

    /**
     * Records what is drawn from now on in spatial node {@code node}, one {@link #addNode} made this frame, or 0; the
     * pose is then in its space. Answers the node it replaces, to enter again after. Stamped on each item: no flush.
     */
    public int enterNode(int node) {
        int previous = spatialNode;
        spatialNode = node;
        drawToTargetValid = false;
        return previous;
    }

    /** The spatial node items are recorded in; 0 for the bound target's own space. */
    public int spatialNode() {
        return spatialNode;
    }

    /** The effect node items are grouped under; 0 for none. */
    public int effectNode() {
        return effectNode;
    }

    /** The current draw space into the bound target's pixels: identity at node 0. Read at once; the matrix is this context's. */
    public Matrix4f drawToTarget() {
        validateDrawToTarget();
        return drawToTarget;
    }

    /** The bound target's pixels into the current draw space: what a pose set to "the target's pixels" is. */
    public Matrix4f targetToDraw() {
        validateDrawToTarget();
        return targetToDraw;
    }

    /**
     * The pose on the stack carried into the bound target's pixels: the pose itself at node 0. What a decision about
     * pixels (snapping, culling, a scissor) asks. Read at once.
     */
    public Matrix4f targetPose() {
        Matrix4f pose = poseStack.last().pose();
        return spatialNode == 0 ? pose : targetPose.set(drawToTarget()).mul(pose);
    }

    private void validateDrawToTarget() {
        if (drawToTargetValid) return;
        drawToTargetValid = true;
        drawToTarget.identity();
        if (spatialNode == 0) {
            targetToDraw.identity();
            return;
        }
        int o = spatialNode * 6;
        drawToTarget.m00(nodeWorlds[o]).m01(nodeWorlds[o + 1]).m10(nodeWorlds[o + 2]).m11(nodeWorlds[o + 3])
                .m30(nodeWorlds[o + 4] - layerOriginX).m31(nodeWorlds[o + 5] - layerOriginY);
        drawToTarget.invert(targetToDraw);
    }

    // ── Layers ───────────────────────────────────────────────────────────────

    /**
     * {@code -Dcrystalgui.layers.legacy=true}: layers the size of the target, never elided, never kept. For telling a
     * rendering fault apart from a compositing one in a single run.
     */
    public static final boolean LEGACY_LAYERS = Boolean.getBoolean("crystalgui.layers.legacy");

    /** Skips a subtree whose ink lies wholly outside what the target can show (Blink's cull rect); off with {@code -Dcrystalgui.paint.cull=false}. */
    public static final boolean CULL = !"false".equals(System.getProperty("crystalgui.paint.cull"));

    /**
     * Clips a rounded {@code overflow: hidden} with {@link #pushRoundedClip} where its mask is the box's own shape,
     * instead of a children layer and a mask layer. Off with {@code -Dcrystalgui.paint.roundedClip=false}.
     */
    public static final boolean ROUNDED_CLIP = !"false".equals(System.getProperty("crystalgui.paint.roundedClip"))
            && !LEGACY_LAYERS;

    /** One open layer: what it draws into and what drawing goes back to after it. Pooled, one per depth. */
    static final class LayerFrame {
        CgGraphTexture target;
        int enclosingWidth, enclosingHeight;
        ScissorStack.Saved savedScissor;
        /** What of the target is in use and where it goes back; null when its whole extent is the layer. */
        @Nullable
        LayerRegion region;
        int savedClip, savedView, savedSpatial, savedEffect;

        CgGraphTexture target() {
            return target;
        }

        @Nullable
        LayerRegion region() {
            return region;
        }
    }

    private LayerFrame[] layers = new LayerFrame[8];
    private int layerDepth;

    /** Where the bound target's pixel (0,0) is on the screen: every enclosing bounded layer's origin, summed. */
    int layerOriginX, layerOriginY;

    /** Open layers, outermost first: {@code layer(0)} is the one opened first. */
    int layerDepth() {
        return layerDepth;
    }

    LayerFrame layer(int i) {
        return layers[i];
    }

    /** The target being drawn into: the innermost layer, or the frame's own target when none is open. */
    CgGraphTexture currentTarget() {
        return layerDepth == 0 ? frameTarget : layers[layerDepth - 1].target;
    }

    /**
     * The region a layer covering {@code (x0, y0)-(x1, y1)} needs, in the current target's pixels: those bounds cut to
     * the live clip and the target, rounded outward. Everything a layer costs is sized from it, so a layer nobody can
     * see costs nothing: an empty region means the subtree is clipped away.
     */
    public LayerRegion layerRegion(float x0, float y0, float x1, float y1) {
        if (LEGACY_LAYERS) return new LayerRegion(0, 0, targetWidth(), targetHeight());
        resolveClip();
        int left = (int) Math.floor(Math.max(x0, clipX0));
        int top = (int) Math.floor(Math.max(y0, clipY0));
        int right = (int) Math.ceil(Math.min(x1, clipX1));
        int bottom = (int) Math.ceil(Math.min(y1, clipY1));
        return new LayerRegion(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    /**
     * A layer's texture: each side a power of two from 32 up, never past the screen, so a layer resizing by a pixel
     * keeps its size class and the executor's pool hands back the same texture next frame.
     */
    CgTextureDesc layerDesc(int width, int height) {
        return new CgTextureDesc(sizeClass(width, screenWidth), sizeClass(height, screenHeight), LAYER_FORMAT);
    }

    private static int sizeClass(int size, int screen) {
        int bucket = 32;
        while (bucket < size && bucket < 32 << 8) bucket <<= 1;
        return Math.max(1, Math.min(Math.max(1, screen), bucket));
    }

    /**
     * Pushes an offscreen target the size of {@code region} and draws into it, cleared transparent. Nests. Pair with
     * {@link #endLayerFbo}, then composite with {@link #blitLayer(CgGraphTexture, float, LayerRegion)} giving the same
     * region.
     *
     * <pre>{@code
     * LayerRegion region = ctx.layerRegion(x0, y0, x1, y1);
     * if (region.isEmpty()) return;                    // wholly clipped: nothing to draw
     * CgGraphTexture layer = ctx.beginLayerFbo(region);
     * // ...draw, the caller's own transform translated by (-region.x(), -region.y())
     * ctx.endLayerFbo();
     * ctx.blitLayer(layer, opacity, region);
     * }</pre>
     *
     * <p>The layer's pixel (0,0) is the region's corner, so a caller drawing at absolute coordinates translates its own
     * transform by the region's negated origin: the pose is rebuilt per element from a base this class never sees. The
     * clip stack is shifted here.</p>
     *
     * @return a texture the executor lends for the frame, perhaps larger than the region: a size class, of which only
     *         the region's corner is drawn or composited
     */
    public CgGraphTexture beginLayerFbo(LayerRegion region) {
        CgTrace.add(UiTrace.FRAME, "layers", 1);
        CgTrace.add(UiTrace.FRAME, "layers-d" + layerDepth, 1);
        CgTextureDesc desc = layerDesc(Math.max(1, region.width()), Math.max(1, region.height()));
        return beginLayer(CgGraphTexture.transientTexture("cgui_layer", desc), true, region, true);
    }

    /** As {@link #beginLayerFbo(LayerRegion)}, into a texture that outlives the frame. */
    public CgGraphTexture beginLayerFbo(CgGraphTexture target, LayerRegion region) {
        return beginLayer(target, true, region, true);
    }

    /**
     * As {@link #beginLayerFbo(LayerRegion)}, into a {@link #requestLayer requested} texture whole (a window's
     * photograph, with its own ortho), able to keep what it holds: the backdrop seeds its capture with the scene before
     * drawing the UI over it.
     */
    public CgGraphTexture beginLayerFbo(CgGraphTexture target, boolean clear) {
        return beginLayer(target, clear, null, true);
    }

    /**
     * @param region      what of the target is in use, and where it goes back; null when its whole extent is the
     *                    layer. A region shifts the clip stack into the layer's origin
     * @param inheritClip the clip around it applies inside, shifted; false starts unclipped
     */
    CgGraphTexture beginLayer(CgGraphTexture target, boolean clear, @Nullable LayerRegion region, boolean inheritClip) {
        // The enclosing target's pass ends here and continues in another after, so the composite runs after the layer.
        drain();
        if (layerDepth == layers.length) layers = Arrays.copyOf(layers, layerDepth * 2);
        LayerFrame frame = layers[layerDepth];
        if (frame == null) frame = layers[layerDepth] = new LayerFrame();
        frame.target = target;
        frame.enclosingWidth = targetWidth();
        frame.enclosingHeight = targetHeight();
        frame.savedScissor = scissorStack.suspend();
        frame.region = region;
        frame.savedClip = clipEntry;
        frame.savedView = viewOwner;
        frame.savedSpatial = spatialNode;
        frame.savedEffect = effectNode;
        layerDepth++;
        if (region != null) {
            layerOriginX += region.x();
            layerOriginY += region.y();
        }
        // A rounded clip is in the enclosing target's pixels; it applies when this layer is composited back.
        clipEntry = 0;
        // A target with a space of its own (a snapshot, a capture) draws in its own pixels, at node 0. A bounded layer is
        // a piece of the frame: drawing stays in the current node, and the layer moves with it.
        if (region == null) {
            nodesSuspended++;
            effectNode = 0;
            enterNode(0);
        }
        viewOwner = spatialNode;
        recorder.view(viewOwner, layerOriginX, layerOriginY);
        drawToTargetValid = false;
        int width = target.getWidth(), height = target.getHeight();
        // The inherited clip, in this target's pixels: rects are flipped against its height, and shifted by its origin.
        if (inheritClip) {
            scissorStack.resume(region == null ? frame.savedScissor
                    : frame.savedScissor.shifted(-region.x(), -region.y()));
        }
        reapplyScissorFor(height);
        targetConstants(width, height);
        if (clear) {
            CgTrace.add(UiTrace.FRAME, "layer-clear-kpx",
                    (region == null ? width * height : region.width() * region.height()) / 1000);
        }
        recorder.recordInto(recording, target, clear ? CgLoad.CLEAR : CgLoad.load(), camera);
        return target;
    }

    /**
     * Pops the innermost {@link #beginLayerFbo}: what it drew is complete, and drawing goes on in the target around it,
     * with its projection and clip. Composites nothing: see {@link #blitLayer} and {@link #compositeMask}.
     */
    public void endLayerFbo() {
        drain();
        LayerFrame frame = layers[--layerDepth];
        if (frame.region != null) {
            layerOriginX -= frame.region.x();
            layerOriginY -= frame.region.y();
        } else {
            nodesSuspended--;
            effectNode = frame.savedEffect;
            enterNode(frame.savedSpatial);
        }
        viewOwner = frame.savedView;
        recorder.view(viewOwner, layerOriginX, layerOriginY);
        drawToTargetValid = false;
        targetConstants(frame.enclosingWidth, frame.enclosingHeight);
        scissorStack.resume(frame.savedScissor);
        clipEntry = frame.savedClip;
        reapplyScissor();
        recorder.recordInto(recording, currentTarget(), CgLoad.load(), camera);
        frame.target = null;
        frame.savedScissor = null;
        frame.region = null;
    }

    /**
     * Composites {@code region}'s corner of a finished layer back at {@code region}'s own place in the target being
     * drawn into, at {@code opacity}. Give it the region the layer was opened with: past it, a pooled texture holds
     * whatever its last user left.
     *
     * <ul>
     *   <li>Premultiplied: a layer was drawn over a transparent clear.</li>
     *   <li>Never tinted: {@link #getColor()} does not reach it, so {@code opacity} is the only way to fade one.</li>
     *   <li>Below full opacity it is drawn under an effect node holding {@code opacity}, which a compositor may change.
     *       Answers that node, or 0.</li>
     * </ul>
     */
    public int blitLayer(CgGraphTexture layer, float opacity, LayerRegion region) {
        return blitLayer(layer, opacity, region, false);
    }

    /** {@link #blitLayer(CgGraphTexture, float, LayerRegion)}; {@code fades} makes the effect node at full opacity too. */
    public int blitLayer(CgGraphTexture layer, float opacity, LayerRegion region, boolean fades) {
        return blitLayer(layer, opacity, region, fades, null);
    }

    /**
     * {@link #blitLayer(CgGraphTexture, float, LayerRegion, boolean)} through {@code turn}, a matrix in the current draw
     * space: a surface drawn at rest, composited with its box's own transform. Resampled, so filtered.
     */
    public int blitLayer(CgGraphTexture layer, float opacity, LayerRegion region, boolean fades,
                         @Nullable Matrix4f turn) {
        if (region.isEmpty()) return 0;
        long timed = CgTrace.stamp(UiTrace.FRAME);
        CgTrace.add(UiTrace.FRAME, "layer-blit-kpx", region.width() * region.height() / 1000);
        float u1 = Math.min(1f, (float) region.width() / layer.getWidth());
        float v1 = Math.max(0f, 1f - (float) region.height() / layer.getHeight());   // a target's row 0 is its bottom
        int effect = 0;
        if (NODES && (opacity < 1f || fades) && nodesSuspended == 0 && recording.effects().count() < CgSpatialTree.MAX_NODES) {
            effect = recording.effects().add(effectNode, opacity);
        }
        int outerEffect = effectNode;
        if (effect != 0) effectNode = effect;
        poseStack.pushPose();
        Matrix4f pose = poseStack.last().pose();
        if (turn != null) pose.set(turn).mul(targetToDraw());
        else pose.set(targetToDraw());
        try {
            quad().texture(0, layer, turn != null ? CgSampler.BILINEAR_CLAMP : null).custom2(CgShapeTable.PREMULTIPLIED)
                    .at(region.x(), region.y()).size(region.width(), region.height()).uv(0f, 1f, u1, v1)
                    .opacity(layerOpacity * (effect != 0 ? 1f : opacity)).submit();
        } finally {
            poseStack.popPose();
            effectNode = outerEffect;
        }
        CgTrace.zoneDone(UiTrace.FRAME, "layer:blit", timed);
        return effect;
    }

    /**
     * Draws a finished target into an arbitrary rect, through the pose: a captured layer drawn elsewhere and at another
     * size, a window's snapshot in a taskbar preview. Tinted by {@link #getColor()}; faded by
     * {@link #pushLayerOpacity}, never by the tint's alpha, which on a premultiplied picture brightens it.
     */
    public void drawLayer(CgGraphTexture layer, float x, float y, float width, float height) {
        drawLayer(layer, x, y, width, height, 0f, 1f, 1f, 0f);   // v flipped, as blitLayer
    }

    /**
     * {@link #drawLayer(CgGraphTexture, float, float, float, float)} for the part of {@code layer} between
     * {@code (u0, v0)} and {@code (u1, v1)}, v counted from the texture's bottom: a window's border box in its surface.
     */
    public void drawLayer(CgGraphTexture layer, float x, float y, float width, float height, float u0, float v0,
                          float u1, float v1) {
        quad().texture(0, layer).custom2(CgShapeTable.PREMULTIPLIED).at(x, y).size(width, height).uv(u0, v0, u1, v1)
                .color(color).submit();
    }

    /**
     * Multiplies {@code subtree}'s colour and alpha by {@code mask}'s alpha over {@code region}
     * ({@link CgBlendState#MASK_ALPHA_MULTIPLY}): wherever the mask is transparent the subtree is zeroed. Both are layers
     * of one size class, so the region means the same in each. The clip and the layer opacity do not apply.
     */
    public void compositeMask(CgGraphTexture subtree, CgGraphTexture mask, LayerRegion region) {
        if (region.isEmpty()) return;
        long timed = CgTrace.stamp(UiTrace.FRAME);
        boolean elsewhere = subtree != currentTarget();
        if (elsewhere) recorder.recordInto(recording, subtree, CgLoad.load(), camera);
        ScissorStack.Saved suspended = scissorStack.suspend();
        scissorStack.clearScissorIfNeeded();
        int outerNode = enterNode(0);
        int outerClip = clipEntry;
        clipEntry = 0;
        // The subtree's own projection: a mask quad drawn through another target's ortho misses what it must zero.
        int enclosingW = targetWidth(), enclosingH = targetHeight();
        targetConstants(subtree.getWidth(), subtree.getHeight());
        poseStack.pushPose();
        poseStack.setIdentity();
        try {
            quad().state(maskState()).texture(0, mask).at(0, 0).size(region.width(), region.height())
                    .uv(0f, 1f, Math.min(1f, (float) region.width() / mask.getWidth()),
                            Math.max(0f, 1f - (float) region.height() / mask.getHeight()))
                    .opacity(1f).submit();
        } finally {
            poseStack.popPose();
            clipEntry = outerClip;
            scissorStack.resume(suspended);
            targetConstants(enclosingW, enclosingH);
            enterNode(outerNode);
        }
        if (elsewhere) recorder.recordInto(recording, currentTarget(), CgLoad.load(), camera);
        reapplyScissor();
        CgTrace.zoneDone(UiTrace.FRAME, "layer:mask", timed);
    }

    /** The box's pass state under the mask's multiply: the blend rides on the draw's pipeline, as its own state. */
    private CgRenderState maskState() {
        CgRenderState base = CgMaterialProxy.of(box).getPassRenderState(CgRenderPassVariant.FORWARD);
        if (base != maskStateBase) {
            maskStateBase = base;
            maskState = base.withBlend(CgBlendState.MASK_ALPHA_MULTIPLY);
        }
        return maskState;
    }

    /**
     * Draws a transparent quad into a requested texture this context will draw into later, as its first draw: on at
     * least one NVIDIA driver a program's first draw into a never-drawn framebuffer, sampled the same frame, came out
     * empty. A pooled layer needs none.
     */
    public void warmUpLayer(CgGraphTexture target) {
        beginLayer(target, true, null, true);
        poseStack.pushPose();
        poseStack.setIdentity();
        try {
            quad().at(0, 0).size(target.getWidth(), target.getHeight()).color(0).opacity(1f).submit();
        } finally {
            poseStack.popPose();
            endLayerFbo();
        }
    }

    // ── Surfaces ─────────────────────────────────────────────────────────────

    /** Off with {@code -Dcrystalgui.paint.surfaces=false}: a box the compositor moves draws into the frame's target. */
    public static final boolean SURFACES = NODES && !"false".equals(System.getProperty("crystalgui.paint.surfaces"));

    /** Off with {@code -Dcrystalgui.paint.damage=false}: a surface drawn again executes all of itself. */
    public static final boolean DAMAGE = !"false".equals(System.getProperty("crystalgui.paint.damage"));

    /**
     * {@code -Dcrystalgui.paint.damageCheck=true}: every surface is walked, a kept one included, and its box painted
     * again whole into a texture beside it; the two are compared and a difference named in the log. Diagnostics only.
     */
    public static final boolean DAMAGE_CHECK = DAMAGE && Boolean.getBoolean("crystalgui.paint.damageCheck");

    /**
     * The texture a box the compositor moves is drawn into, made on first use and kept; null where it draws into the
     * target as any box: inside a copy drawn elsewhere, with surfaces off, or with nothing to draw. @see Surface
     *
     * @param key    what the surface is kept under, by identity: the box
     * @param region what the box's subtree covers in the target, as {@link #surfaceRegion} answers
     */
    @Nullable
    public Surface surface(Object key, LayerRegion region) {
        return surfaces.surface(key, region);
    }

    /**
     * Where a surface for the box inked over {@code (x0, y0)-(x1, y1)} goes, in the target's pixels: all of it, not cut
     * to the clip, which cuts its composite instead, so a window hanging off the screen keeps its whole picture. Cut
     * like {@link #layerRegion} past 4096 pixels a side.
     */
    public LayerRegion surfaceRegion(float x0, float y0, float x1, float y1) {
        return surfaces.region(x0, y0, x1, y1);
    }

    /**
     * Opens {@code surface} over {@code region} to draw its box again, every piece of drawing placing itself in it;
     * {@code full} when nothing of its last picture stands. Pair with {@link #endSurface}.
     */
    public void beginSurface(Surface surface, LayerRegion region, boolean full) {
        surfaces.begin(surface, region, full);
    }

    /**
     * Closes the innermost {@link #beginSurface}, and cuts every pass it drew into to what the walk damaged: a surface
     * nothing changed in executes nothing. Answers whether anything was damaged.
     */
    public boolean endSurface() {
        return surfaces.end();
    }

    /** The surface being walked, when what is drawn now lands in it rather than in a layer inside it; null otherwise. */
    @Nullable
    public Surface walkingSurface() {
        return surfaces.walking();
    }

    /** Check mode: the texture {@code surface}'s box is painted again into, whole. Made with the surface's size, freed with it. */
    public CgGraphTexture damageCheckTarget(Surface surface) {
        return surfaces.checkTarget(surface);
    }

    /** Opens {@code surface}'s {@link #damageCheckTarget} over {@code region} as a surface opens: unclipped. */
    public void beginDamageCheck(Surface surface, LayerRegion region) {
        beginLayer(surfaces.checkTarget(surface), true, region, false);
    }

    /** Check mode: compares {@code region} of {@code surface} with its check target once both are drawn, naming {@code label} where they differ. */
    public void compareSurface(Surface surface, LayerRegion region, String label) {
        surfaces.compare(surface, region, label);
    }

    /** Composites {@code inner} into the surface being walked at {@code region}, so a box that leaves takes its picture with it. */
    public void placeSurface(Surface inner, LayerRegion region) {
        surfaces.place(inner, region);
    }

    /** Composites a layer into the surface being walked at {@code region}, as {@link #placeSurface} a surface. */
    public void placeLayer(Surface.Placement placement, LayerRegion region) {
        surfaces.place(placement, region);
    }

    /**
     * Runs {@code body} with {@link #surface} answering null: for a pass drawing a subtree a second time (a copy, a
     * check), whose drawing must not become the picture a surface holds.
     */
    public void withoutRetention(Runnable body) {
        surfaces.withoutRetention(body);
    }

    /** Drawing a box's picture beside its frame: glass inside it is flat, with nothing behind it to filter. */
    private boolean snapshotting;

    /** @see #snapshotting */
    public void setSnapshotting(boolean on) {
        snapshotting = on;
    }

    boolean isSnapshotting() {
        return snapshotting;
    }

    // ── Kept pictures and passes of the caller's own ─────────────────────────

    /** A texture made when the frame executes and kept until {@link #releaseTexture}: what a recording may not make itself. */
    CgGraphTexture requestTexture(String name, int width, int height, CgFormat format) {
        return CgGraphTexture.requested(name, CgTextureDesc.of(Math.max(1, width), Math.max(1, height), format));
    }

    /**
     * A layer-format texture made when a frame executes and kept until {@link #releaseTexture}: what paint draws into
     * and keeps across frames.
     *
     * <pre>{@code
     * CgGraphTexture picture = ctx.requestLayer("my_picture", w, h);   // in paint, once
     * ctx.beginLayerFbo(picture, true);
     * // ... draw ...
     * ctx.endLayerFbo();
     * ctx.drawLayer(picture, x, y, w, h);                               // this frame or any later one
     * ctx.releaseTexture(picture);                                      // in a frame or between frames
     * }</pre>
     */
    public CgGraphTexture requestLayer(String name, int width, int height) {
        return requestTexture(name, width, height, LAYER_FORMAT);
    }

    /**
     * Runs {@code body} on the render thread once everything recorded so far that writes {@code after} has executed:
     * how a cell built in this frame is told to the other documents.
     */
    void afterExecuted(CgGraphTexture after, Runnable body) {
        requireFrame();
        drain();
        recording.callback("cgui.svg.ready", null, body, after);
    }

    /** Frees a requested texture's storage once what was recorded before has executed; between frames, at the next. */
    public void releaseTexture(CgGraphTexture requested) {
        if (!frameActive) {
            pendingReleases.add(requested);
            return;
        }
        drain();
        recording.release(requested);
    }

    /**
     * Writes RGBA8 pixels into a {@code w x h} region at {@code (x, y)} of a {@link #requestLayer requested} texture,
     * before anything recorded after reads it. Row 0 of {@code rgba} lands in the texture's row 0, which UV row 0
     * samples: a picture stored top row first draws upright. Copied now; inside a frame.
     *
     * <pre>{@code
     * ctx.update(picture, 0, 0, w, h, rgba);
     * ctx.drawImage(picture, x, y, w, h, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
     * }</pre>
     */
    public void update(CgGraphTexture target, int x, int y, int w, int h, ByteBuffer rgba) {
        requireFrame();
        drain();
        recording.update(target, 0, x, y, w, h, rgba);
    }

    /**
     * Lends the frame's recording for passes of the caller's own (into requested textures, as a shader preview renders)
     * until {@link #endPasses()}, which goes on drawing where paint was. Every pass recorded in between must have ended.
     *
     * <pre>{@code
     * CgRecording recording = ctx.beginPasses();
     * renderer.renderPending(graph, recording);
     * ctx.endPasses();
     * ctx.drawImage(renderer.textureOf(nodeId), x, y, w, h, 0f, 1f, 1f, 0f, 0xFFFFFFFF);
     * }</pre>
     */
    public CgRecording beginPasses() {
        requireFrame();
        drain();
        return recording;
    }

    /** Ends what {@link #beginPasses()} lent: paint continues on the target it was drawing into. */
    public void endPasses() {
        recorder.recordInto(recording, currentTarget(), CgLoad.load(), camera);
    }

    /** The frame's recording after everything drawn so far, the open pass ended: for a copy or a compute pass. */
    CgRecording breakPass() {
        drain();
        return recording;
    }

    /** A compute pass in the frame's recording after everything drawn so far; what is drawn after is ordered after it. */
    CgComputePass compute(String name) {
        return breakPass().compute(name);
    }

    // ── Backdrop, for glass ──────────────────────────────────────────────────

    /**
     * What is behind an element, as {@link #backdropFor} found it: the frame's captured and blurred backdrop, where the
     * bound target's pixel (0, 0) is in its texels, and the rect of them the element may sample (its bounds padded by
     * the blur's and the lens's reach, cut to its clip), bottom-left. {@link CgCanvasGlass#backdrop(CgCanvasBackdrop,
     * float, float, int, int, int, int)} takes it as it is. The context's own: valid until the next {@code backdropFor}.
     */
    public static final class Backdrop {
        CgCanvasBackdrop source;
        float offsetX, offsetY;
        int x0, y0, x1, y1;

        Backdrop() {}

        public CgCanvasBackdrop source() {
            return source;
        }

        public float offsetX() {
            return offsetX;
        }

        public float offsetY() {
            return offsetY;
        }

        public int x0() {
            return x0;
        }

        public int y0() {
            return y0;
        }

        public int x1() {
            return x1;
        }

        public int y1() {
            return y1;
        }
    }

    /**
     * Captures what is behind {@code (x, y, w, h)} and blurs it: the primitive under {@code backdrop-filter}. One capture
     * a frame serves every element, retaken only where something was painted over what one samples.
     *
     * <pre>{@code
     * CgUiPaintContext.Backdrop b = ctx.backdropFor(x, y, w, h, blurPx, pane.reach());
     * if (b == null) { ctx.fillRect(x, y, w, h, fallback); return; }
     * pane.backdrop(b.source(), b.offsetX(), b.offsetY(), b.x0(), b.y0(), b.x1(), b.y1()).apply();
     * }</pre>
     *
     * @param blurRadiusPx how far the blur reaches, in surface pixels; 0 hands back the capture itself
     * @param reach        how far past the rect a tap may land, in the rect's own units: a lens bending what is beyond
     * @return the backdrop, or null with nothing to capture: draw a fallback colour rather than nothing
     */
    @Nullable
    public Backdrop backdropFor(float x, float y, float width, float height, float blurRadiusPx, float reach) {
        return backdrop.forRect(x, y, width, height, blurRadiusPx, reach);
    }

    /** {@link #backdropFor(float, float, float, float, float, float)} with no reach past the rect. */
    @Nullable
    public Backdrop backdropFor(float x, float y, float width, float height, float blurRadiusPx) {
        return backdropFor(x, y, width, height, blurRadiusPx, 0f);
    }

    /**
     * Records that a box painted over {@code (left, top)-(right, bottom)} of its own space, drawn through {@code drawn}:
     * what lets a later glass element reuse the frame's capture unless something was painted over what it samples.
     */
    public void notePainted(Matrix4f drawn, float left, float top, float right, float bottom) {
        Matrix4f pose = spatialNode == 0 ? drawn : notedScratch.set(drawToTarget()).mul(drawn);
        float m00 = pose.m00(), m10 = pose.m10(), m30 = pose.m30() + layerOriginX;
        float m01 = pose.m01(), m11 = pose.m11(), m31 = pose.m31() + layerOriginY;
        float ax = m00 * left + m10 * top + m30, ay = m01 * left + m11 * top + m31;
        float bx = m00 * right + m10 * top + m30, by = m01 * right + m11 * top + m31;
        float cx = m00 * right + m10 * bottom + m30, cy = m01 * right + m11 * bottom + m31;
        float dx = m00 * left + m10 * bottom + m30, dy = m01 * left + m11 * bottom + m31;
        backdrop.painted(Math.min(Math.min(ax, bx), Math.min(cx, dx)), Math.min(Math.min(ay, by), Math.min(cy, dy)),
                Math.max(Math.max(ax, bx), Math.max(cx, dx)), Math.max(Math.max(ay, by), Math.max(cy, dy)));
    }

    private final Matrix4f notedScratch = new Matrix4f();

    // ── Teardown ─────────────────────────────────────────────────────────────

    /**
     * Frees what this context made: its surfaces, frame target, and the backdrop's and icon raster's textures. Its view
     * calls it at close and at graphics context destruction; the next frame makes them again. Materials, fonts and the
     * fallback textures are CrystalGraphics' registries', swept there; freeing them here would be a double free.
     */
    void release() {
        Arrays.fill(layers, 0, layerDepth, null);
        layerDepth = 0;
        recording.reset();
        surfaces.release();
        for (CgGraphTexture released : pendingReleases) CgExecutor.freeStorage(released);
        pendingReleases.clear();
        if (frameTarget != null) CgExecutor.freeStorage(frameTarget);
        frameTarget = null;
        backdrop.delete();
        svgRaster.delete();
        frameActive = false;
    }
}
