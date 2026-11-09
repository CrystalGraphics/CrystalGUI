package com.crystalgui.render;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.api.PoseStack;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.render.texture.svg.SvgScene;

import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Icon fills rasterised once, on the GPU, and drawn from a texture after that.
 *
 * <h3>Why a cache, when the cells could draw straight into the frame</h3>
 *
 * <p>A tessellated fill is a set of cells, and at icon sizes most of them are thinner than a pixel:
 * a rounded corner flattened to eight chords puts eight cells inside two pixel rows. Drawn one at a
 * time, each cell can only composite — and two cells each covering half a pixel composite to three
 * quarters of it, so whichever cell owns a pixel has to claim <em>all</em> of it and guess the rest of
 * the pixel's boundary from its own edges. Where the boundary bends inside the pixel the guess is
 * wrong, which is every corner and every small circle. <b>Exact coverage needs the cells to sum</b>,
 * and summing is additive blending into a scratch target — which, once it exists, there is no reason
 * to repeat next frame.</p>
 *
 * <p>The raster is coverage, not colour: a flat fill accumulates {@code (1, 1, 1, area)} — straight
 * alpha, so the frame composites it through the ordinary box-model material with the tint as its colour,
 * batched with every other quad — and one raster serves a selected row and an unselected one, a themed
 * {@code currentColor} mark in any colour, and the monochrome silhouette of a drag ghost. Only a fill
 * with colours of its own (a gradient) bakes them in, premultiplied, and composites through the
 * layer-blit material. This is the same trade IntelliJ makes with its icon cache, with the rasterising
 * done by the cells and the tinting left to the draw.</p>
 *
 * <h3>What is and is not cached</h3>
 *
 * <p>Fills of a document drawn at most {@value #MAX_DEVICE_PX} device pixels tall, under an
 * axis-aligned, uniform pose. Larger draws are artwork rather than icons — a zoomed canvas — and there a
 * cell is bigger than a pixel, so the direct path is exact enough and a texture would be the size of the
 * screen. Strokes are cached as well, combined by MAX rather than by sum since their segments overlap at
 * every joint — so a cached icon is textured quads and nothing else, whatever the file is made of.</p>
 *
 * <p><b>On the pixel grid a raster is drawn 1:1</b>, built at the draw's own scale. <b>Off it</b> — an icon
 * mid-animation — the atlas's linear filter places it between pixels, from its exact raster where one exists,
 * else from the next of {@value #LADDER_STEPS} sizes a doubling, drawn up to 4% smaller than it was built. So
 * an icon moving or growing looks as it does at rest, shadow included, and builds a dozen rasters rather than
 * one a frame. Before, it left for the direct path on every frame off the grid and flickered between the two.</p>
 *
 * <p>One atlas, {@value #ATLAS_SIZE} square, filled shelf by shelf. When it is full everything is
 * dropped and rasterised again on demand — simpler than an eviction policy, and a working set that
 * fills it is thousands of icons.</p>
 *
 * <h3>Drop shadows</h3>
 *
 * <p>An element's {@code filter} drop shadow ({@link SvgScene.DropShadow}) is the union of what it draws,
 * blurred, offset and flooded, painted under it: SVG's {@code feGaussianBlur} of {@code SourceAlpha}, as resvg
 * renders it. {@link #drawShadow} rasterises the group's coverage into scratch, blurs it ({@link CgGpuOps#blur})
 * into a texture of its own, and draws that as white coverage under the flood colour, like a plain icon.</p>
 */
public final class SvgRasterCache {

    /** Draws taller than this, in device pixels, take the direct path. */
    public static final int MAX_DEVICE_PX = 128;

    private static final int ATLAS_SIZE = 1024;

    /** Pixels of empty atlas around a raster, so its antialiased edge and the sampling never meet a neighbour. */
    private static final int MARGIN = 1;

    /**
     * The smallest raster this will build, across the document's longer side, in atlas pixels.
     *
     * <p><b>A raster is never built at the size it will be DRAWN at, only at or above this.</b> The scale
     * a thumbnail asks for is a fifth of life size or less, and at that scale an icon is two atlas texels:
     * there is no arrangement of two texels that is a recognisable icon, so what came back was a bright
     * blob the size of the cell -- every icon in a taskbar preview and a switcher tile, reported as
     * "broken white icons".</p>
     *
     * <p>Eight, then drawn minified, which is what every renderer does with a texture it is shrinking and
     * what the direct path cannot do at all. It also COARSENS THE KEY at small sizes -- every scale below
     * this shares one raster -- so a canvas zooming out stops building a fresh one per frame, which the
     * sub-pixel note in {@link #accepts} had flagged as the cost of accepting those scales.</p>
     */
    private static final float MIN_RASTER_PX = 8f;

    /**
     * How far past its half-width a stroke's coverage reaches, per unit of half-width: a square cap's corner, at
     * {@code sqrt(2)}. The bounds already hold one half-width. @see #rasterise
     */
    private static final float CAP_REACH = 0.4143f;

    /** A stroke's antialiased edge beyond its geometry, in device pixels, with a pixel to spare. */
    private static final int STROKE_FRINGE = 2;

    /**
     * Half floats: a cell's contribution is often a fiftieth of a level, and eight bits would round every
     * one of them to zero. Colour is premultiplied by construction — every cell adds {@code colour × area}.
     */
    private static final CgFrameBufferFormat ATLAS_FORMAT =
            CgFrameBufferFormat.builder("cgui_svg_raster").color(0, CgTextureType.RGBA16F).build();

    /**
     * A raster's identity. Mutable so every draw looks up through {@link #probe} and only a miss stores a copy:
     * an icon draw is per frame, and a key per draw was a steady stream for nothing.
     */
    private static final class Key {
        SvgDocument document;
        int op, deviceScaleBits, halfWidthBits;
        boolean flat;

        Key set(SvgDocument document, int op, int deviceScaleBits, boolean flat, int halfWidthBits) {
            this.document = document;
            this.op = op;
            this.deviceScaleBits = deviceScaleBits;
            this.flat = flat;
            this.halfWidthBits = halfWidthBits;
            return this;
        }

        Key copy() {
            return new Key().set(document, op, deviceScaleBits, flat, halfWidthBits);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.document == document && key.op == op
                    && key.deviceScaleBits == deviceScaleBits && key.flat == flat && key.halfWidthBits == halfWidthBits;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(document);
            h = 31 * h + op;
            h = 31 * h + deviceScaleBits;
            h = 31 * h + (flat ? 1 : 0);
            return 31 * h + halfWidthBits;
        }
    }

    /** @see Key */
    private final Key probe = new Key();

    /** One rasterised fill: its atlas rect, and where the document's origin sits inside it, in device pixels. */
    private static final class Entry {
        int x, y, width, height;
        float originX, originY;
        /** The fill's own colours are in the raster, premultiplied; otherwise it is straight-alpha white coverage. */
        boolean baked;
    }

    /** One blurred shadow raster, and where the document's origin sits inside it, in raster pixels. */
    private static final class Shadow {
        CgGraphTexture texture;
        int width, height;
        float originX, originY;
    }

    /** Shadows kept before they are all dropped: a canvas zooming builds one per scale, as the atlas does. */
    private static final int MAX_SHADOWS = 256;

    /** {@code -Dcrystalgui.svg.raster=false} draws every icon through the direct path — for comparing the two. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("crystalgui.svg.raster"));

    private final CgUiPaintContext ctx;
    private final Map<Key, Entry> entries = new HashMap<>();
    private final Map<Key, Shadow> shadows = new HashMap<>();
    /** A shadow's coverage, then its first axis; grown to the largest asked for. */
    private CgGraphTexture shadowCoverage;
    private int shadowsCreated;
    private boolean sigmaClampLogged;
    /** Made when the first frame that rasterises executes, and kept; cleared when it is full. */
    private CgGraphTexture atlas;
    private int shelfX, shelfY, shelfHeight;
    private int generation;
    /** Times the atlas was cleared and its icons placed again. @see #resets() */
    private int resets;

    SvgRasterCache(CgUiPaintContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Whether a draw of {@code document} at logical {@code (x, y)} and {@code scale} is one this cache
     * serves. When it is not, the caller draws the cells directly.
     */
    public boolean accepts(SvgDocument document, float x, float y, float scale) {
        boolean served = decide(document, x, y, scale);
        // THE DIRECT PATH IS THE EXPENSIVE ONE -- a draw per scanline cell against one textured quad --
        // so a frame full of them is a finding rather than a detail. Counted here and not at the two
        // call sites, because there are five ways to refuse and a caller can tell none of them apart.
        if (!served) CgTrace.add(UiTrace.FRAME, "svg-direct", 1);
        return served;
    }

    private boolean decide(SvgDocument document, float x, float y, float scale) {
        if (!ENABLED || !ctx.isPoseAxisAligned()) return false;
        Matrix4f m = ctx.targetPose();
        // Uniform and unflipped: the raster is built at one scale.
        if (m.m00() <= 0f || m.m00() != m.m11()) return false;
        // Whatever the alignment: off the pixel grid it is drawn filtered, as the class note says. Below one device
        // pixel per document unit the direct path could not draw it at all -- a cell thinner than a pixel claims the
        // whole pixel, and a thumbnail's icon came out a block.
        return Math.max(document.width(), document.height()) * scale * ctx.deviceScale() <= MAX_DEVICE_PX;
    }

    /** Whether logical {@code (x, y)} lands on a whole device pixel. */
    private boolean onPixel(float x, float y) {
        Matrix4f m = ctx.targetPose();
        float px = m.m00() * x + m.m30(), py = m.m11() * y + m.m31();
        return Math.abs(px - Math.round(px)) < 1e-3f && Math.abs(py - Math.round(py)) < 1e-3f;
    }

    /** Raster sizes off the pixel grid, per doubling: a raster drawn at most this much smaller than it was built. */
    private static final float LADDER_STEPS = 16f;

    /** {@link #probe}, set to op {@code op}'s raster at {@code rasterScale} for a draw at {@code scale}. */
    private Key rasterKey(SvgDocument document, int op, float rasterScale, float scale, boolean flat, boolean stroke,
                          float halfWidth) {
        float atlasPerLogical = scale > 0f ? rasterScale / scale : rasterScale;
        float override = stroke && halfWidth > 0f ? halfWidth * atlasPerLogical : 0f;
        return probe.set(document, op, Float.floatToIntBits(rasterScale), flat, Float.floatToIntBits(override));
    }

    /** The scale a raster of {@code document} drawn at {@code scale} is built at exactly: drawn 1:1. */
    private float exactScale(SvgDocument document, float scale) {
        float span = Math.max(1f, Math.max(document.width(), document.height()));
        // FLOORED, so the atlas always holds an icon rather than a smudge. @see #MIN_RASTER_PX
        return Math.max(scale * ctx.deviceScale(), MIN_RASTER_PX / span);
    }

    /**
     * The rung of the ladder at or above {@code exact}: what a draw off the pixel grid with no exact raster is built at,
     * drawn shrunk and filtered. An icon growing through a hundred scales then builds a dozen rasters, not one a frame.
     */
    private static float rung(float exact) {
        return (float) Math.pow(2.0, Math.ceil(Math.log(exact) / Math.log(2.0) * LADDER_STEPS) / LADDER_STEPS);
    }

    /**
     * Draws op {@code op} of {@code document} — which must be the mesh tier the caller wants
     * rasterised, not the root — at logical {@code (x, y)}, rasterising it first if this is the first
     * time it is asked for at this device scale.
     *
     * @param argb      the tint; white for a fill whose colours are baked into the raster
     * @param flat      every cell white, regardless of the file's own paint — see {@link SvgDocument#renderMonochrome}
     * @param halfWidth for a stroke, a half-width in logical pixels overriding the file's; {@code <= 0}
     *                  keeps the file's own. Ignored for a fill
     */
    public void draw(SvgDocument document, int op, float x, float y, float scale, int argb, boolean flat,
                     float halfWidth) {
        boolean stroke = !document.ops().get(op).fill();
        float rasterScale = exactScale(document, scale);
        Entry entry = entries.get(rasterKey(document, op, rasterScale, scale, flat, stroke, halfWidth));
        if (entry == null && !onPixel(x, y)) {
            rasterScale = rung(rasterScale);
            entry = entries.get(rasterKey(document, op, rasterScale, scale, flat, stroke, halfWidth));
        }
        // Atlas pixels per LOGICAL pixel, which is the device scale exactly while nothing is floored or laddered -- and
        // is what every conversion below has to read once the two can differ.
        float atlasPerLogical = scale > 0f ? rasterScale / scale : rasterScale;
        Key key = rasterKey(document, op, rasterScale, scale, flat, stroke, halfWidth);
        float override = stroke && halfWidth > 0f ? halfWidth * atlasPerLogical : 0f;
        if (entry == null) {
            entry = rasterise(document, op, rasterScale, flat, override);
            entries.put(key.copy(), entry);
        }

        // LOGICAL PIXELS PER ATLAS PIXEL. Was `/ device`, which is the same number until the raster is
        // floored and silently the wrong one after: a raster built larger than it is drawn has to be
        // scaled DOWN by the ratio it was floored by, or it lands at the size it was rasterised at.
        float perAtlasPx = 1f / atlasPerLogical;
        float left = x + entry.originX * perAtlasPx, top = y + entry.originY * perAtlasPx;
        float width = entry.width * perAtlasPx, height = entry.height * perAtlasPx;
        float u0 = (float) entry.x / ATLAS_SIZE, u1 = (float) (entry.x + entry.width) / ATLAS_SIZE;
        // V flipped: the atlas is drawn into under the same inverted ortho as the frame, so its row 0 is
        // at the bottom of the texture -- the convention drawLayer already documents.
        float v0 = 1f - (float) entry.y / ATLAS_SIZE, v1 = 1f - (float) (entry.y + entry.height) / ATLAS_SIZE;
        CgTexture texture = atlas;
        CgTrace.add(UiTrace.FRAME, "svg-raster-draws", 1);

        if (!entry.baked) {
            // Straight-alpha white coverage under the tint: an ordinary textured quad, batched with
            // whatever is around it. This is the whole of what a cached icon costs per frame.
            ctx.bindTexture(texture);
            ctx.quad().at(left, top).size(width, height).uv(u0, v0, u1, v1).color(argb).submit();
            return;
        }

        // Premultiplied, because a baked raster is: the composite material multiplies texture by colour
        // and blends ONE / ONE_MINUS_SRC_ALPHA, so a translucent tint has to arrive with its alpha
        // already folded into its channels.
        int a = argb >>> 24;
        int tint = (a << 24)
                | (((argb >> 16 & 0xFF) * a / 255) << 16)
                | (((argb >> 8 & 0xFF) * a / 255) << 8)
                | ((argb & 0xFF) * a / 255);
        ctx.withMaterial(ctx.layerBlitMaterial(), () -> {
            ctx.bindTexture(texture);
            ctx.quad().at(left, top).size(width, height).uv(u0, v0, u1, v1).color(tint).submit();
            ctx.flush();
        });
    }

    /**
     * Draws the drop shadow of the ops from {@code first} to the end of its group ({@link
     * SvgDocument#shadowGroupEnd}), under them: call it before drawing op {@code first}, with the same
     * position and scale. Blurs it the first time it is asked for at this device scale.
     */
    public void drawShadow(SvgDocument document, int first, float x, float y, float scale) {
        SvgScene.DropShadow shadow = document.ops().get(first).shadow();
        if (shadow == null || (shadow.argb() >>> 24) == 0) return;
        float rasterScale = exactScale(document, scale);
        // A negative op names the group starting at that op, so it never meets a fill or stroke's key
        Key key = probe.set(document, -1 - first, Float.floatToIntBits(rasterScale), false, 0);
        Shadow entry = shadows.get(key);
        if (entry == null && !onPixel(x, y)) {
            rasterScale = rung(rasterScale);
            key = probe.set(document, -1 - first, Float.floatToIntBits(rasterScale), false, 0);
            entry = shadows.get(key);
        }
        float atlasPerLogical = scale > 0f ? rasterScale / scale : rasterScale;
        if (entry == null) {
            if (shadows.size() >= MAX_SHADOWS) clearShadows();
            entry = rasteriseShadow(document, first, shadow, rasterScale);
            shadows.put(key.copy(), entry);
        }

        float perPx = 1f / atlasPerLogical;
        // The offset is in document units, scaled as the artwork is
        float left = x + entry.originX * perPx + shadow.dx() * scale;
        float top = y + entry.originY * perPx + shadow.dy() * scale;
        CgTrace.add(UiTrace.FRAME, "svg-shadow-draws", 1);
        ctx.bindTexture(entry.texture);
        // Its own texture, whole, and V flipped as every layer is
        ctx.quad().at(left, top).size(entry.width * perPx, entry.height * perPx)
                .uv(0f, 1f, 1f, 0f).color(shadow.argb()).submit();
    }

    private Shadow rasteriseShadow(SvgDocument document, int first, SvgScene.DropShadow shadow, float rasterScale) {
        int end = document.shadowGroupEnd(first);
        float sigmaX = clampSigma(shadow.sigmaX() * rasterScale), sigmaY = clampSigma(shadow.sigmaY() * rasterScale);
        // The blur's reach is three sigma, the backdrop's rule
        int tapsX = (int) Math.ceil(3f * sigmaX), tapsY = (int) Math.ceil(3f * sigmaY);
        int strokeReach = 0;
        for (int i = first; i < end; i++) {
            SvgDocument.DrawOp op = document.ops().get(i);
            if (op.fill()) continue;
            strokeReach = Math.max(strokeReach,
                    (int) Math.ceil(op.halfWidth() * rasterScale * CAP_REACH) + STROKE_FRINGE);
        }
        float[] bounds = document.bounds();
        int padX = MARGIN + strokeReach + tapsX, padY = MARGIN + strokeReach + tapsY;
        int width = Math.max(1, (int) Math.ceil((bounds[2] - bounds[0]) * rasterScale) + 2 * padX);
        int height = Math.max(1, (int) Math.ceil((bounds[3] - bounds[1]) * rasterScale) + 2 * padY);
        float cornerX = (float) Math.floor(bounds[0] * rasterScale), cornerY = (float) Math.floor(bounds[1] * rasterScale);
        Shadow entry = new Shadow();
        entry.width = width;
        entry.height = height;
        entry.originX = cornerX - padX;
        entry.originY = cornerY - padY;
        float originX = padX - cornerX, originY = padY - cornerY;

        CgTrace.add(UiTrace.FRAME, "svg-shadow-builds", 1);
        long timed = CgTrace.stamp(UiTrace.FRAME);
        ensureShadowScratch(width, height);
        ctx.beginLayerFbo(shadowCoverage, true);
        ScissorStack.Saved scissor = ctx.suspendScissor();
        PoseStack pose = ctx.getPoseStack();
        pose.pushPose();
        pose.setIdentity();
        ctx.pushScissor(0, 0, width, height);
        try {
            // Fills sum and strokes max, as for the icon itself; the union is the alpha the filter blurs
            ctx.withCurveMaterial(coverage(), () -> {
                for (int i = first; i < end; i++) {
                    if (document.ops().get(i).fill()) document.accumulateFill(ctx, i, originX, originY, rasterScale, true);
                }
            });
            ctx.withCurveMaterial(coverageMax(), () -> {
                for (int i = first; i < end; i++) {
                    if (!document.ops().get(i).fill()) document.accumulateStroke(ctx, i, originX, originY, rasterScale, 0f);
                }
            });
        } finally {
            ctx.popScissor();
            pose.popPose();
            ctx.resumeScissor(scissor);
            ctx.endLayerFbo();
        }

        entry.texture = ctx.requestTexture("cgui_svg_shadow_" + shadowsCreated++, width, height, ATLAS_FORMAT);
        // The coverage sits at the scratch's top-left in the UI's space, its last rows
        CgComputePass pass = ctx.compute("cgui.svg.shadow");
        CgGpuOps.blur(pass, shadowCoverage, 0, shadowCoverage.getHeight() - height, width, height, entry.texture, 0, 0,
                sigmaX, sigmaY, CgGpuOps.Edge.MIRROR);
        pass.end();
        CgTrace.zoneDone(UiTrace.FRAME, "svg-shadow:build", timed);
        return entry;
    }

    /** Within the blur's kernel, {@link CgGpuOps#MAX_BLUR_SIGMA}. */
    private float clampSigma(float sigma) {
        float most = CgGpuOps.MAX_BLUR_SIGMA;
        if (sigma <= most) return sigma;
        if (!sigmaClampLogged) {
            sigmaClampLogged = true;
            CrystalGuiCore.LOGGER.warn("SVG drop shadow of {} px deviation drawn at {} px, the blur's widest", sigma, most);
        }
        return most;
    }

    /** The two scratch targets, both at least {@code width x height}; the passes read only that corner. */
    private void ensureShadowScratch(int width, int height) {
        if (shadowCoverage != null && shadowCoverage.getWidth() >= width && shadowCoverage.getHeight() >= height) return;
        int w = Math.max(width, shadowCoverage == null ? 0 : shadowCoverage.getWidth());
        int h = Math.max(height, shadowCoverage == null ? 0 : shadowCoverage.getHeight());
        if (shadowCoverage != null) ctx.releaseTexture(shadowCoverage);
        shadowCoverage = ctx.requestTexture("cgui_svg_shadow_coverage", w, h, ATLAS_FORMAT);
    }

    private void clearShadows() {
        for (Shadow shadow : shadows.values()) ctx.releaseTexture(shadow.texture);
        shadows.clear();
    }

    /**
     * @param halfWidth a stroke's overriding half-width in DEVICE pixels, or 0 for the file's own;
     *                  the slot grows by it, since the bounds were measured from the file's
     */
    private Entry rasterise(SvgDocument document, int op, float rasterScale, boolean flat, float halfWidth) {
        float[] bounds = document.bounds();
        SvgDocument.DrawOp drawn = document.ops().get(op);
        int pad = MARGIN + (int) Math.ceil(halfWidth);
        if (!drawn.fill()) {
            float strokeHalf = halfWidth > 0f ? halfWidth : drawn.halfWidth() * rasterScale;
            pad += (int) Math.ceil(strokeHalf * CAP_REACH) + STROKE_FRINGE;
        }
        int width = (int) Math.ceil((bounds[2] - bounds[0]) * rasterScale) + 2 * pad;
        int height = (int) Math.ceil((bounds[3] - bounds[1]) * rasterScale) + 2 * pad;
        Entry entry = allocate(Math.max(1, width), Math.max(1, height));
        // The document origin lands at bounds' corner, which floors so the artwork's own integer
        // coordinates stay on the atlas's pixel grid -- the alignment the whole raster is for.
        float cornerX = (float) Math.floor(bounds[0] * rasterScale), cornerY = (float) Math.floor(bounds[1] * rasterScale);
        entry.originX = cornerX - pad;
        entry.originY = cornerY - pad;
        float atlasX = entry.x + pad - cornerX, atlasY = entry.y + pad - cornerY;
        entry.baked = drawn.fill() && !flat && drawn.colours() != null;

        CgTrace.add(UiTrace.FRAME, "svg-raster-builds", 1);
        long timed = CgTrace.stamp(UiTrace.FRAME);
        ctx.beginLayerFbo(atlas, false);
        ScissorStack.Saved scissor = ctx.suspendScissor();
        PoseStack pose = ctx.getPoseStack();
        pose.pushPose();
        pose.setIdentity();
        // CLIPPED TO ITS OWN SLOT. Rasters are packed edge to edge, so anything reaching past the pad -- a cap, a
        // feather -- lands in a neighbour and is drawn with it: a white line beside an unrelated icon, and only
        // on the runs whose first draws happened to pack those two together.
        ctx.pushScissor(entry.x, entry.y, entry.width, entry.height);
        try {
            if (drawn.fill()) {
                ctx.withCurveMaterial(entry.baked ? accumulate() : coverage(), () ->
                        document.accumulateFill(ctx, op, atlasX, atlasY, rasterScale, flat));
            } else {
                ctx.withCurveMaterial(coverageMax(), () ->
                        document.accumulateStroke(ctx, op, atlasX, atlasY, rasterScale, halfWidth));
            }
        } finally {
            ctx.popScissor();
            pose.popPose();
            ctx.resumeScissor(scissor);
            ctx.endLayerFbo();
        }
        CgTrace.zoneDone(UiTrace.FRAME, "svg-raster:build", timed);
        return entry;
    }

    /** A rect in the atlas, shelf-packed; resets the whole atlas when it is full. */
    private Entry allocate(int width, int height) {
        if (width > ATLAS_SIZE || height > ATLAS_SIZE) {
            throw new IllegalArgumentException("SVG raster " + width + "x" + height + " exceeds the atlas");
        }
        ensureAtlas();
        if (shelfX + width > ATLAS_SIZE) {
            shelfY += shelfHeight;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + height > ATLAS_SIZE) reset();
        Entry entry = new Entry();
        entry.x = shelfX;
        entry.y = shelfY;
        entry.width = width;
        entry.height = height;
        shelfX += width;
        shelfHeight = Math.max(shelfHeight, height);
        return entry;
    }

    /** How many times the atlas has been cleared: an icon drawn before a reset is elsewhere after it. */
    int resets() {
        return resets;
    }

    private void ensureAtlas() {
        if (atlas != null) return;
        atlas = ctx.requestTexture("cgui_svg_raster_" + generation++, ATLAS_SIZE, ATLAS_SIZE, ATLAS_FORMAT);
        clear();
    }

    private void reset() {
        resets++;
        entries.clear();
        shelfX = shelfY = shelfHeight = 0;
        clear();
        CgTrace.add(UiTrace.FRAME, "svg-raster-resets", 1);
    }

    private void clear() {
        ctx.beginLayerFbo(atlas, true);
        ctx.endLayerFbo();
    }

    private static CgMaterial accumulate() {
        return CgCanvasMaterials.SCREEN.colourCoverage();
    }

    private static CgMaterial coverage() {
        return CgCanvasMaterials.SCREEN.coverage();
    }

    private static CgMaterial coverageMax() {
        return CgCanvasMaterials.SCREEN.strokeCoverage();
    }

    void delete() {
        entries.clear();
        for (Shadow shadow : shadows.values()) deleteNow(shadow.texture);
        shadows.clear();
        deleteNow(shadowCoverage);
        deleteNow(atlas);
        shadowCoverage = atlas = null;
        shelfX = shelfY = shelfHeight = 0;
    }

    /** Teardown: no frame will record a release. */
    private static void deleteNow(CgGraphTexture texture) {
        if (texture != null) CgExecutor.freeStorage(texture);
    }
}
