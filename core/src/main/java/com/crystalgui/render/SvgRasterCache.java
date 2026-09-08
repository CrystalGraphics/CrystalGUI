package com.crystalgui.render;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.api.PoseStack;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.render.texture.svg.SvgDocument;

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
 * axis-aligned pose, at a whole-pixel origin. Larger draws are artwork rather than icons — a zoomed
 * canvas — and there a cell is bigger than a pixel, so the direct path is exact enough and a texture
 * would be the size of the screen. A fractional origin is an icon mid-animation and gets the direct
 * path too; snapping it here would make it judder. Strokes are cached as well, combined by MAX rather
 * than by sum since their segments overlap at every joint — so a cached icon is textured quads and
 * nothing else, whatever the file is made of.</p>
 *
 * <p>One atlas, {@value #ATLAS_SIZE} square, filled shelf by shelf. When it is full everything is
 * dropped and rasterised again on demand — simpler than an eviction policy, and a working set that
 * fills it is thousands of icons.</p>
 */
public final class SvgRasterCache {

    /** Draws taller than this, in device pixels, take the direct path. */
    public static final int MAX_DEVICE_PX = 128;

    private static final int ATLAS_SIZE = 1024;

    /** Pixels of empty atlas around a raster, so its antialiased edge and the sampling never meet a neighbour. */
    private static final int MARGIN = 1;

    /**
     * Half floats: a cell's contribution is often a fiftieth of a level, and eight bits would round every
     * one of them to zero. Colour is premultiplied by construction — every cell adds {@code colour × area}.
     */
    private static final CgFrameBufferFormat ATLAS_FORMAT =
            CgFrameBufferFormat.builder("cgui_svg_raster").color(0, CgTextureType.RGBA16F).build();

    private record Key(SvgDocument document, int op, int deviceScaleBits, boolean flat, int halfWidthBits) {
    }

    /** One rasterised fill: its atlas rect, and where the document's origin sits inside it, in device pixels. */
    private static final class Entry {
        int x, y, width, height;
        float originX, originY;
        /** The fill's own colours are in the raster, premultiplied; otherwise it is straight-alpha white coverage. */
        boolean baked;
    }

    /** {@code -Dcrystalgui.svg.raster=false} draws every icon through the direct path — for comparing the two. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("crystalgui.svg.raster"));

    private final CgUiPaintContext ctx;
    private final Map<Key, Entry> entries = new HashMap<>();
    private CgFrameBuffer atlas;
    private CgMaterial coverage;
    private CgMaterial coverageMax;
    private CgMaterial accumulate;
    private int shelfX, shelfY, shelfHeight;
    private int generation;

    SvgRasterCache(CgUiPaintContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Whether a draw of {@code document} at logical {@code (x, y)} and {@code scale} is one this cache
     * serves. When it is not, the caller draws the cells directly.
     */
    public boolean accepts(SvgDocument document, float x, float y, float scale) {
        if (!ENABLED || !ctx.isPoseAxisAligned()) return false;
        Matrix4f m = ctx.getPoseStack().last().pose();
        // Uniform and unflipped: the raster is built at one scale and drawn 1:1.
        if (m.m00() <= 0f || m.m00() != m.m11()) return false;
        float device = ctx.deviceScale();
        if (Math.max(document.width(), document.height()) * scale * device > MAX_DEVICE_PX) return false;
        // The raster is aligned to the pixel grid, so the origin has to be on it -- CgUiSvg snaps it
        // there for a settled icon, and an icon mid-zoom is exactly the case that must not be cached.
        float px = m.m00() * x + m.m30(), py = m.m11() * y + m.m31();
        return Math.abs(px - Math.round(px)) < 1e-3f && Math.abs(py - Math.round(py)) < 1e-3f;
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
        float device = ctx.deviceScale();
        float rasterScale = scale * device;
        boolean stroke = !document.ops().get(op).fill();
        float override = stroke && halfWidth > 0f ? halfWidth * device : 0f;
        Key key = new Key(document, op, Float.floatToIntBits(rasterScale), flat, Float.floatToIntBits(override));
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = rasterise(document, op, rasterScale, flat, override);
            entries.put(key, entry);
        }

        float left = x + entry.originX / device, top = y + entry.originY / device;
        float width = entry.width / device, height = entry.height / device;
        float u0 = (float) entry.x / ATLAS_SIZE, u1 = (float) (entry.x + entry.width) / ATLAS_SIZE;
        // V flipped: the atlas is drawn into under the same inverted ortho as the frame, so its row 0 is
        // at the bottom of the texture -- the convention drawLayer already documents.
        float v0 = 1f - (float) entry.y / ATLAS_SIZE, v1 = 1f - (float) (entry.y + entry.height) / ATLAS_SIZE;
        CgTexture2D texture = (CgTexture2D) atlas.getColorTexture(0);
        FrameProfile.count("svg-raster-draws", 1);

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
     * @param halfWidth a stroke's overriding half-width in DEVICE pixels, or 0 for the file's own;
     *                  the slot grows by it, since the bounds were measured from the file's
     */
    private Entry rasterise(SvgDocument document, int op, float rasterScale, boolean flat, float halfWidth) {
        float[] bounds = document.bounds();
        int pad = MARGIN + (int) Math.ceil(halfWidth);
        int width = (int) Math.ceil((bounds[2] - bounds[0]) * rasterScale) + 2 * pad;
        int height = (int) Math.ceil((bounds[3] - bounds[1]) * rasterScale) + 2 * pad;
        Entry entry = allocate(Math.max(1, width), Math.max(1, height));
        // The document origin lands at bounds' corner, which floors so the artwork's own integer
        // coordinates stay on the atlas's pixel grid -- the alignment the whole raster is for.
        float cornerX = (float) Math.floor(bounds[0] * rasterScale), cornerY = (float) Math.floor(bounds[1] * rasterScale);
        entry.originX = cornerX - pad;
        entry.originY = cornerY - pad;
        float atlasX = entry.x + pad - cornerX, atlasY = entry.y + pad - cornerY;
        SvgDocument.DrawOp drawn = document.ops().get(op);
        entry.baked = drawn.fill() && !flat && drawn.colours() != null;

        FrameProfile.count("svg-raster-builds", 1);
        long timed = FrameProfile.begin();
        ctx.beginLayerFbo(atlas, false);
        int[] scissor = ctx.suspendScissor();
        PoseStack pose = ctx.getPoseStack();
        pose.pushPose();
        pose.setIdentity();
        try {
            if (drawn.fill()) {
                ctx.withCurveMaterial(entry.baked ? accumulate() : coverage(), () ->
                        document.accumulateFill(ctx, op, atlasX, atlasY, rasterScale, flat));
            } else {
                ctx.withCurveMaterial(coverageMax(), () ->
                        document.accumulateStroke(ctx, op, atlasX, atlasY, rasterScale, halfWidth));
            }
        } finally {
            pose.popPose();
            ctx.resumeScissor(scissor);
            ctx.endLayerFbo();
        }
        FrameProfile.end(timed, "svg-raster:build");
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

    private void ensureAtlas() {
        if (atlas != null) return;
        atlas = CgFrameBuffer.createOwned("cgui_svg_raster_" + generation++, ATLAS_SIZE, ATLAS_SIZE, ATLAS_FORMAT);
        clear();
    }

    private void reset() {
        entries.clear();
        shelfX = shelfY = shelfHeight = 0;
        clear();
        FrameProfile.count("svg-raster-resets", 1);
    }

    private void clear() {
        ctx.beginLayerFbo(atlas, true);
        ctx.endLayerFbo();
    }

    private CgMaterial accumulate() {
        if (accumulate == null) accumulate = CgMaterial.load("crystalgui:shaders/gui_curve_accumulate.shader");
        return accumulate;
    }

    private CgMaterial coverage() {
        if (coverage == null) coverage = CgMaterial.load("crystalgui:shaders/gui_curve_coverage.shader");
        return coverage;
    }

    private CgMaterial coverageMax() {
        if (coverageMax == null) coverageMax = CgMaterial.load("crystalgui:shaders/gui_curve_coverage_max.shader");
        return coverageMax;
    }

    void delete() {
        entries.clear();
        if (atlas != null) {
            atlas.delete();
            atlas = null;
        }
        shelfX = shelfY = shelfHeight = 0;
    }
}
