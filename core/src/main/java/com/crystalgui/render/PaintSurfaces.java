package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTargetCompare;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.trace.UiTrace;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A paint context's surfaces (render-graph G7): kept textures a box the compositor moves draws its subtree into, and
 * the damage that limits what a walk of one executes. {@link CgUiPaintContext}'s {@code surface}, {@code beginSurface}
 * and {@code endSurface} are its doors; {@link Surface} is the picture itself.
 */
final class PaintSurfaces {

    /** Frames a surface may go unasked-for before it is freed: a window hidden for a while. */
    private static final long IDLE_FRAMES = 300L;

    /** Past this many pixels a side a surface holds only what its clip shows. */
    private static final int MAX_SIDE = 4096;

    private final CgUiPaintContext ctx;
    private final CgRecording recording;
    private final Map<Object, Surface> surfaces = new HashMap<>();
    private int created;
    private boolean retentionSuspended;

    /** Surfaces being walked, innermost last, with the recording's operation count when each began. */
    private Surface[] walking = new Surface[4];
    private int[] walkingMarks = new int[4];
    private int walkingDepth;
    private final float[] damage = new float[4];

    PaintSurfaces(CgUiPaintContext ctx, CgRecording recording) {
        this.ctx = ctx;
        this.recording = recording;
    }

    @Nullable
    Surface surface(Object key, LayerRegion region) {
        if (!CgUiPaintContext.SURFACES || retentionSuspended || CgUiPaintContext.LEGACY_LAYERS || region.isEmpty()) {
            return null;
        }
        Surface surface = surfaces.get(key);
        if (surface != null) {
            int wide = surface.target().getWidth(), tall = surface.target().getHeight();
            if (wide < region.width() || tall < region.height()
                    || wide > Math.max(64, region.width() * 2) || tall > Math.max(64, region.height() * 2)) {
                CgTrace.add(UiTrace.FRAME, "surfaces-resized", 1);
                surfaces.remove(key);
                retire(surface);
                surface = null;
            }
        }
        if (surface == null) {
            CgGraphTexture target = ctx.requestTexture("cgui_surface_" + created++, bucket(region.width()),
                    bucket(region.height()), CgUiPaintContext.LAYER_FORMAT);
            ctx.warmUpLayer(target);
            surface = new Surface(target);
            surfaces.put(key, surface);
            CgTrace.add(UiTrace.FRAME, "surfaces-new", 1);
        }
        surface.lastFrame = ctx.frameId();
        if (key instanceof Surface.Owner owner) owner.surface(surface);
        return surface;
    }

    LayerRegion region(float x0, float y0, float x1, float y1) {
        if (CgUiPaintContext.LEGACY_LAYERS) return ctx.layerRegion(x0, y0, x1, y1);
        // A hair inside: a transform taken out and put back is not exact, and a region a pixel larger would miss.
        int left = (int) Math.floor(x0 + 1e-3f), top = (int) Math.floor(y0 + 1e-3f);
        int right = (int) Math.ceil(x1 - 1e-3f), bottom = (int) Math.ceil(y1 - 1e-3f);
        if (right - left > MAX_SIDE || bottom - top > MAX_SIDE) return ctx.layerRegion(x0, y0, x1, y1);
        return new LayerRegion(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    void withoutRetention(Runnable body) {
        boolean was = retentionSuspended;
        retentionSuspended = true;
        try {
            body.run();
        } finally {
            retentionSuspended = was;
        }
    }

    void begin(Surface surface, LayerRegion region, boolean full) {
        if (walkingDepth == walking.length) {
            walking = Arrays.copyOf(walking, walkingDepth * 2);
            walkingMarks = Arrays.copyOf(walkingMarks, walkingDepth * 2);
        }
        walkingMarks[walkingDepth] = recording.operations();
        walking[walkingDepth++] = surface;
        // Its own clip only: the window's whole picture, which the clip around it cuts as it is composited.
        ctx.beginLayer(surface.target(), true, region, false);
        surface.beginWalk(ctx.frameId(), full || !CgUiPaintContext.DAMAGE);
    }

    boolean end() {
        Surface surface = walking[--walkingDepth];
        walking[walkingDepth] = null;
        surface.endWalk();
        ctx.endLayerFbo();
        if (surface.fullyDamaged()) return true;
        CgGraphTexture target = surface.target();
        int width = target.getWidth(), height = target.getHeight();
        int x = 0, y = 0, w = 0, h = 0;
        if (surface.damage(damage)) {
            // A pixel either side for edge coverage, then the bottom-left rect a pass takes.
            int x0 = Math.max(0, (int) Math.floor(damage[0]) - 1);
            int y0 = Math.max(0, (int) Math.floor(damage[1]) - 1);
            int x1 = Math.min(width, (int) Math.ceil(damage[2]) + 1);
            int y1 = Math.min(height, (int) Math.ceil(damage[3]) + 1);
            if (x1 > x0 && y1 > y0) {
                x = x0;
                y = height - y1;
                w = x1 - x0;
                h = y1 - y0;
            }
        }
        CgTrace.add(UiTrace.FRAME, w == 0 ? "surfaces-undamaged" : "surfaces-damaged", 1);
        CgTrace.add(UiTrace.FRAME, "surface-damage-kpx", (long) w * h / 1000L);
        for (int i = walkingMarks[walkingDepth], n = recording.operations(); i < n; i++) {
            if (recording.pass(i) instanceof CgRasterPass raster && raster.target() == target) raster.damage(x, y, w, h);
        }
        return w != 0;
    }

    @Nullable
    Surface walking() {
        if (walkingDepth == 0) return null;
        Surface surface = walking[walkingDepth - 1];
        return ctx.currentTarget() == surface.target() ? surface : null;
    }

    CgGraphTexture checkTarget(Surface surface) {
        if (surface.checkTarget == null) {
            surface.checkTarget = ctx.requestTexture("cgui_surface_check_" + created++, surface.target().getWidth(),
                    surface.target().getHeight(), CgUiPaintContext.LAYER_FORMAT);
            ctx.warmUpLayer(surface.checkTarget);
        }
        return surface.checkTarget;
    }

    void compare(Surface surface, LayerRegion region, String label) {
        CgGraphTexture limited = surface.target(), whole = surface.checkTarget;
        int width = region.width(), height = region.height(), y = limited.getHeight() - height;
        ctx.flush();
        recording.callback("damage-check " + label, null, () -> {
            CgTargetCompare.Difference d = CgTargetCompare.compare(limited, whole, 0, y, width, height);
            CgTrace.add(UiTrace.FRAME, "surface-checks", 1);
            if (d == null) return;
            CgTrace.add(UiTrace.FRAME, "surface-check-differs", 1);
            CrystalGuiCore.LOGGER.warn("[damage-check] {}: {} pixels differ by up to {} in ({},{})-({},{}), bottom-left",
                    label, d.pixels(), d.worst(), d.x0(), d.y0(), d.x1(), d.y1());
        }, limited, whole);
    }

    void place(Surface inner, LayerRegion region) {
        Surface surface = walking();
        if (surface != null) {
            inner.placeIn(surface, region.x(), region.y(), region.x() + region.width(), region.y() + region.height(),
                    ctx.frameId());
        }
    }

    void place(Surface.Placement placement, LayerRegion region) {
        Surface surface = walking();
        if (surface != null) {
            placement.place(surface, region.x(), region.y(), region.x() + region.width(), region.y() + region.height(),
                    ctx.frameId());
        }
    }

    /** Frees the surfaces nothing has asked for in a while; every 16th frame. */
    void sweep() {
        long frame = ctx.frameId();
        if (surfaces.isEmpty() || (frame & 15L) != 0L) return;
        Iterator<Map.Entry<Object, Surface>> each = surfaces.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<Object, Surface> entry = each.next();
            Surface surface = entry.getValue();
            if (frame - surface.lastFrame < IDLE_FRAMES) continue;
            if (entry.getKey() instanceof Surface.Owner owner && owner.keepsSurface()) continue;
            retire(surface);
            each.remove();
        }
    }

    /** A frame that threw part-way: what its walks noted never reached the textures, so each is drawn whole next. */
    void abort() {
        for (Surface surface : surfaces.values()) surface.invalidate();
        Arrays.fill(walking, 0, walkingDepth, null);
        walkingDepth = 0;
    }

    /** Teardown, with no frame to record a release into. */
    void release() {
        for (Surface surface : surfaces.values()) {
            surface.released = true;
            CgExecutor.freeStorage(surface.target());
            if (surface.checkTarget != null) CgExecutor.freeStorage(surface.checkTarget);
        }
        surfaces.clear();
    }

    private void retire(Surface surface) {
        surface.released = true;
        surface.releaseThumbnail(ctx);
        ctx.releaseTexture(surface.target());
        if (surface.checkTarget != null) ctx.releaseTexture(surface.checkTarget);
    }

    /** Rounded up, so an element resizing by a pixel a frame does not reallocate its texture every frame. */
    private static int bucket(int size) {
        return Math.max(64, (Math.max(1, size) + 63) & ~63);
    }
}
