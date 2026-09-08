package com.crystalgui.render;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgui.core.async.FrameProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The offscreen targets {@link CgUiPaintContext#beginLayerFbo} draws into, kept by nesting depth and
 * by size bucket.
 *
 * <p>Two things went wrong with the single screen-sized FBO per depth this replaces, and they are
 * different problems:</p>
 *
 * <ol>
 *   <li><b>Every layer cost a screen.</b> A fractional opacity on a 20×20 icon allocated, cleared and
 *       composited 1920×1080. Bucketing by size means the allocation follows the element.</li>
 *   <li><b>Two surfaces fought over one pool.</b> {@code screenWidth}/{@code Height} changed whenever
 *       painting moved between surfaces — 960×540 and 1920×1080 alternated within one session — and
 *       every pooled FBO was reallocated on each swap. A pool per surface makes that free.</li>
 * </ol>
 *
 * <p>Sizes round up to a power of two, capped at the surface's own dimension, so a full-screen layer
 * is exactly the screen and a 300px one takes a 512px slot instead of a fresh allocation on every
 * frame the element resizes by a pixel. Slot reuse is also what keeps
 * {@link CgUiPaintContext#warmUpLayer}'s guarantee: a slot is cold once, ever.</p>
 *
 * <pre>{@code
 * CgFrameBuffer fbo = pool.acquire(depth, region.width(), region.height());
 * }</pre>
 */
final class LayerPool {

    /** Nothing smaller than this gets its own slot — below it the allocation is noise and the
     * bucket count is not. */
    private static final int MIN_BUCKET = 32;

    /** Powers of two from {@link #MIN_BUCKET} up to 8192, which is past any surface we run on. */
    private static final int BUCKETS = 9;

    /** How many surfaces keep pools before the least recently used one is freed. A desktop and a
     * snapshot make two; the third is a resize in progress. */
    private static final int MAX_SURFACES = 4;

    private final Consumer<CgFrameBuffer> warmUp;
    private final Map<Long, Surface> surfaces = new LinkedHashMap<>();
    private int created;

    LayerPool(Consumer<CgFrameBuffer> warmUp) {
        this.warmUp = warmUp;
    }

    /** One surface size's worth of slots: {@code [depth][widthBucket * BUCKETS + heightBucket]}. */
    private static final class Surface {
        final int width, height;
        final List<CgFrameBuffer[]> byDepth = new ArrayList<>();

        Surface(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /**
     * The layer target for a nesting depth and a wanted size, created on first use.
     *
     * @param surfaceWidth  the surface being painted, which caps the bucket — a layer is never usefully
     *                      bigger than what it composites into
     */
    CgFrameBuffer acquire(int depth, int wantWidth, int wantHeight, int surfaceWidth, int surfaceHeight) {
        Surface surface = surfaceFor(surfaceWidth, surfaceHeight);
        while (surface.byDepth.size() <= depth) surface.byDepth.add(new CgFrameBuffer[BUCKETS * BUCKETS]);
        CgFrameBuffer[] slots = surface.byDepth.get(depth);

        int wi = bucketIndex(wantWidth);
        int hi = bucketIndex(wantHeight);
        int slot = wi * BUCKETS + hi;
        CgFrameBuffer fbo = slots[slot];
        if (fbo != null) return fbo;

        int width = bucketSize(wi, surface.width);
        int height = bucketSize(hi, surface.height);
        fbo = CgFrameBuffer.createOwned("cgui_layer_" + created++, width, height, CgUiPaintContext.LAYER_FORMAT);
        slots[slot] = fbo;
        warmUp.accept(fbo);
        FrameProfile.count("layer-fbos", 1);
        return fbo;
    }

    private Surface surfaceFor(int width, int height) {
        long key = (long) Math.max(1, width) << 32 | (Math.max(1, height) & 0xFFFFFFFFL);
        Surface surface = surfaces.remove(key);
        if (surface == null) {
            surface = new Surface(Math.max(1, width), Math.max(1, height));
            if (surfaces.size() >= MAX_SURFACES) {
                Long oldest = surfaces.keySet().iterator().next();
                delete(surfaces.remove(oldest));
            }
        }
        // Re-inserted so iteration order is least-recently-used first.
        surfaces.put(key, surface);
        return surface;
    }

    /** Which power-of-two bucket a wanted size falls in. */
    private static int bucketIndex(int size) {
        int index = 0;
        int bucket = MIN_BUCKET;
        while (bucket < size && index < BUCKETS - 1) {
            bucket <<= 1;
            index++;
        }
        return index;
    }

    /** The bucket's real size: the power of two, or the surface's own dimension when that is smaller. */
    private static int bucketSize(int index, int surfaceSize) {
        return Math.max(1, Math.min(surfaceSize, MIN_BUCKET << index));
    }

    /** Frees every target. Called from the paint context's own teardown, on the GL thread. */
    void deleteAll() {
        for (Surface surface : surfaces.values()) delete(surface);
        surfaces.clear();
    }

    private static void delete(Surface surface) {
        if (surface == null) return;
        for (CgFrameBuffer[] slots : surface.byDepth) {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null) {
                    slots[i].delete();
                    slots[i] = null;
                }
            }
        }
        surface.byDepth.clear();
    }
}
