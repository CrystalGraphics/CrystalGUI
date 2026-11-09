package com.crystalgui.render;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What is behind an element, captured and blurred once a frame: the primitive under {@code backdrop-filter}. Part of the
 * paint context, reaching into it through package-private members.
 * <pre>
 *   capture   the scene, then the frame target and every enclosing layer, over the region asked for
 *   blur      Skia's scale-then-blur on CrystalGraphics' compute (CgGpuOps.blurScaled)
 *   hand out  the sharp texture, the blurred one, and the element's place in both
 * </pre>
 */
final class CgUiBackdrop {

    private final CgUiPaintContext ctx;

    CgUiBackdrop(CgUiPaintContext ctx) {
        this.ctx = ctx;
    }

    /** A new frame: last frame's capture describes nothing in it. */
    void beginFrame() {
        captureFrame = -1L;
    }

    void prepareFrame() {
        if (PROBE) probeFrame();
        // From the radius last frame asked for, so a target is built here and never mid-paint.
        blurScale = scaleFor(blurRadiusPx);
        targetFor(blurScale);
    }


    /** Scene plus whatever the UI had painted when the first glass element of the frame drew. */
    @Nullable
    private CgGraphTexture capture;

    /** The capture's texture at the surface's size: made when a frame executes, replaced when the surface resizes. */
    private CgGraphTexture captureTexture(int w, int h) {
        if (capture != null && (capture.getWidth() != w || capture.getHeight() != h)) {
            ctx.releaseTexture(capture);
            capture = null;
        }
        if (capture == null) capture = ctx.requestTexture("cgui_backdrop_filter", w, h, CgUiPaintContext.LAYER_FORMAT);
        return capture;
    }

    /** The frame {@link #capture} was captured on — the whole of the "once per frame" rule. */
    private long captureFrame = -1L;
    private int captureDepth = -1;
    @Nullable
    private CgGraphTexture captureTarget;

    /**
     * The region the current capture covers, in surface pixels, at its target's top-left. A region, since a taskbar is a
     * fortieth of the screen and capturing it all made glass a frame killer; in screen-sized targets, which mid-paint
     * must never be rebuilt, the region the same fraction of each.
     */
    private int capX0, capY0, capW, capH;

    /**
     * The union of the regions asked for this frame and last, in surface pixels. Seeding a capture with last frame's
     * keeps a settled UI at one capture; a consumer outside it forces a recapture, so the cost is speculative, not the
     * answer.
     */
    private int reqX0, reqY0, reqX1, reqY1;
    private int lastX0, lastY0, lastX1, lastY1;
    private long reqFrame = -1L;

    /**
     * The blurred backdrop, one per working scale, each the grid over its scale and built once: a radius change selects
     * another rather than rebuilding one under a live frame, which flickered. A surface resize rebuilds them.
     */
    private final Map<Integer, CgGraphTexture> blurTargets = new HashMap<>();

    /** The target for {@code scale}, built on first use; {@link #prepareFrame} asks first, so paint never builds one. */
    private CgGraphTexture targetFor(int scale) {
        int w = gridWidth() / scale;
        int h = gridHeight() / scale;
        CgGraphTexture target = blurTargets.get(scale);
        if (target != null && (target.getWidth() != w || target.getHeight() != h)) {
            ctx.releaseTexture(target);
            target = null;
        }
        if (target == null) {
            target = ctx.requestTexture("cgui_blur_" + scale, w, h, CgUiPaintContext.LAYER_FORMAT);
            blurTargets.put(scale, target);
            ctx.warmUpLayer(target);
        }
        return target;
    }

    /** How much smaller than the surface the blur runs: {@link CgGpuOps#blurScale}, from last frame's radius. */
    private int blurScale = 4;

    /** The blur's REACH is three sigma: a tap beyond that carries under half a percent of the weight. */
    private static final float REACH_PER_SIGMA = 3f;

    /** The largest working scale, which the grid is rounded to. */
    private static final int MAX_SCALE = 4;

    /**
     * The grid the capture and every blur target share: the surface rounded up to {@link #MAX_SCALE}, so a blur texel
     * covers exactly {@code scale} capture texels. Off it, a maximised window's glass moved the taskbar's backdrop.
     */
    private int gridWidth() {
        return roundUp(Math.max(1, ctx.screenWidth));
    }

    /** @see #gridWidth */
    private int gridHeight() {
        return roundUp(Math.max(1, ctx.screenHeight));
    }

    private static int roundUp(int px) {
        return (px + MAX_SCALE - 1) / MAX_SCALE * MAX_SCALE;
    }

    private static int scaleFor(float radiusPx) {
        return CgGpuOps.blurScale(radiusPx / REACH_PER_SIGMA);
    }

    /** The finished blur of {@link #blurFrame}. */
    @Nullable
    private CgGraphTexture blurResult;

    private long blurFrame = -1L;
    private float blurRadiusPx = Float.NaN;

    /**
     * Captures what is behind {@code (x, y, w, h)} and blurs it: the primitive under {@code backdrop-filter}. One capture
     * a frame, shared by every consumer, retaken only when something was painted over what one samples
     * ({@link CgUiPaintContext#notePainted}).
     *
     * @param blurRadiusPx how far the blur reaches, in surface pixels; 0 hands back the capture itself
     * @param reach        how far a lens samples past the rect, in element units
     * @return the two textures and the rect's place in them, or {@code null} with nothing to capture: draw the fallback
     */
    @Nullable
    CgUiPaintContext.Backdrop forRect(float x, float y, float width, float height, float blurRadiusPx, float reach) {
        if (!ctx.frameActive || width <= 0f || height <= 0f || ctx.isSnapshotting()) return null;

        // Surface pixels, from the transform chain: a framebuffer region is already in them.
        Matrix4f pose = new Matrix4f(ctx.targetPose());
        Vector3f tl = pose.transformPosition(new Vector3f(x, y, 0f));
        Vector3f tr = pose.transformPosition(new Vector3f(x + width, y, 0f));
        Vector3f bl = pose.transformPosition(new Vector3f(x, y + height, 0f));
        Vector3f br = pose.transformPosition(new Vector3f(x + width, y + height, 0f));
        // All four corners: two bound a rotated quad too tightly. In screen pixels: a bounded layer's (0,0) is its corner.
        int[] origin = targetOrigin();
        int px0 = Math.round(Math.min(Math.min(tl.x, tr.x), Math.min(bl.x, br.x))) + origin[0];
        int py0 = Math.round(Math.min(Math.min(tl.y, tr.y), Math.min(bl.y, br.y))) + origin[1];
        int px1 = Math.round(Math.max(Math.max(tl.x, tr.x), Math.max(bl.x, br.x))) + origin[0];
        int py1 = Math.round(Math.max(Math.max(tl.y, tr.y), Math.max(bl.y, br.y))) + origin[1];
        int rw = Math.max(1, px1 - px0);
        int rh = Math.max(1, py1 - py0);

        // Padded by the blur's and the lens's reach (the lens in element units, so through the pose), or the edge clamps
        // from the first tap and smears inward.
        float poseScale = (float) Math.hypot(pose.m00(), pose.m01());
        int pad = (int) Math.ceil(blurRadiusPx + reach * poseScale) + 4;
        int w = Math.max(1, ctx.screenWidth), h = Math.max(1, ctx.screenHeight);
        // Never past the clip, the web's backdrop root: a lens in a zoomed canvas bent the whole editor in.
        int[] clip = visibleArea(origin);
        int wantX0 = Math.max(Math.max(0, clip[0]), px0 - pad);
        int wantY0 = Math.max(Math.max(0, clip[1]), py0 - pad);
        int wantX1 = Math.min(Math.min(w, clip[2]), px1 + pad);
        int wantY1 = Math.min(Math.min(h, clip[3]), py1 + pad);
        if (wantX1 <= wantX0 || wantY1 <= wantY0) return null;

        if (reqFrame != ctx.frameId) {
            lastX0 = reqX0; lastY0 = reqY0; lastX1 = reqX1; lastY1 = reqY1;
            reqX0 = wantX0; reqY0 = wantY0; reqX1 = wantX1; reqY1 = wantY1;
            reqFrame = ctx.frameId;
        } else {
            reqX0 = Math.min(reqX0, wantX0); reqY0 = Math.min(reqY0, wantY0);
            reqX1 = Math.max(reqX1, wantX1); reqY1 = Math.max(reqY1, wantY1);
        }

        if (PROBE) pConsumers++;
        if (!ensureCaptured(wantX0, wantY0, wantX1, wantY1)) return null;

        // Where the element's uv lands in the capture, through the pose: an origin and a step per axis, so a rotated
        // element reads a rotated patch. v is flipped (GL is bottom-left), relative to the capture's region, over the
        // grid the textures are.
        int gw = gridWidth(), gh = gridHeight();
        float u0 = (tl.x + origin[0] - capX0) / gw;
        float v0 = 1f - (tl.y + origin[1] - capY0) / gh;
        float ux = (tr.x - tl.x) / gw, vx = -(tr.y - tl.y) / gh;
        float uy = (bl.x - tl.x) / gw, vy = -(bl.y - tl.y) / gh;

        CgTexture sharp = capture;
        CgTexture blurred = blurredBackdrop(blurRadiusPx);
        if (sharp == null || blurred == null) return null;
        if (GEOMETRY_LOG && blurRadiusPx != loggedRadius) {
            loggedRadius = blurRadiusPx;
            logGeometry(blurRadiusPx, px0, py0, px1, py1, gw, gh, v0, v0 + vy, blurred);
        }
        // What it may sample: its padded, clipped rect, half a texel in.
        float halfU = 0.5f / gw, halfV = 0.5f / gh;
        return new CgUiPaintContext.Backdrop(sharp, blurred, u0, v0, ux, vx, uy, vy,
                (wantX0 - capX0) / (float) gw + halfU, 1f - (wantY1 - capY0) / (float) gh + halfV,
                (wantX1 - capX0) / (float) gw - halfU, 1f - (wantY0 - capY0) / (float) gh - halfV);
    }

    /**
     * Every number the backdrop's placement depends on, a few lines per radius change:
     * {@code -Dcrystalgui.glass.geometry=true}. The capture and the blur share one normalised rect, so the band must
     * cover a whole {@code scale} multiple of rows; its overshoot is under {@code scale}.
     */
    private void logGeometry(float radiusPx, int px0, int py0, int px1, int py1, int w, int h,
                             float vTop, float vBottom, CgTexture blurred) {
        CgGraphTexture target = blurTargets.get(blurScale);
        int tw = target == null ? -1 : target.getWidth();
        int th = target == null ? -1 : target.getHeight();
        float fracW = capW / (float) w, fracH = capH / (float) h;
        int qw = tw < 0 ? -1 : (capW + blurScale - 1) / blurScale;
        int qh = th < 0 ? -1 : (capH + blurScale - 1) / blurScale;
        CrystalGuiCore.LOGGER.info(String.format(
                "[glass] r=%.1f scale=%d pad=%d screen=%dx%d elem=(%d,%d)..(%d,%d)",
                radiusPx, blurScale, (int) Math.ceil(radiusPx) + 4, w, h, px0, py0, px1, py1));
        CrystalGuiCore.LOGGER.info(String.format(
                "[glass]   capture origin=(%d,%d) size=%dx%d  frac=%.6f x %.6f",
                capX0, capY0, capW, capH, fracW, fracH));
        CrystalGuiCore.LOGGER.info(String.format(
                "[glass]   blurTarget=%dx%d quad=%dx%d  bandRows=%d capH=%d overshoot=%d",
                tw, th, qw, qh, qh * blurScale, capH, qh * blurScale - capH));
        CrystalGuiCore.LOGGER.info(String.format(
                "[glass]   blurredTex=%dx%d  v: top=%.6f bottom=%.6f  elemRowsInCapture=%d..%d",
                blurred.getWidth(), blurred.getHeight(), vTop, vBottom, py0 - capY0, py1 - capY0));
    }

    /** @see #logGeometry */
    private static final boolean GEOMETRY_LOG = Boolean.getBoolean("crystalgui.glass.geometry");

    /** @see #logGeometry */
    private float loggedRadius = Float.NaN;

    /** Where the bound target's pixel (0,0) is on the screen: the sum of the enclosing bounded layers' origins. */
    private int[] targetOrigin() {
        int x = 0, y = 0;
        for (Iterator<CgUiPaintContext.LayerFrame> it = ctx.layerStack.descendingIterator(); it.hasNext(); ) {
            LayerRegion region = it.next().region();
            if (region != null) {
                x += region.x();
                y += region.y();
            }
        }
        return new int[] {x, y};
    }

    /**
     * What an element drawn now can see, in screen pixels: the scissor and every enclosing bounded layer's region (a
     * mask, an opacity or a rounded {@code overflow}, none of them a scissor). Bounded by the scissor alone, glass at
     * a rounded stage's edge sampled the dialog around it.
     */
    private int[] visibleArea(int[] origin) {
        int[] clip = ctx.clipRect();
        int x0 = clip[0] + origin[0], y0 = clip[1] + origin[1];
        int x1 = clip[2] + origin[0], y1 = clip[3] + origin[1];
        int ox = 0, oy = 0;
        for (Iterator<CgUiPaintContext.LayerFrame> it = ctx.layerStack.descendingIterator(); it.hasNext(); ) {
            LayerRegion region = it.next().region();
            if (region == null) continue;
            ox += region.x();
            oy += region.y();
            x0 = Math.max(x0, ox);
            y0 = Math.max(y0, oy);
            x1 = Math.min(x1, ox + region.width());
            y1 = Math.min(y1, oy + region.height());
        }
        return new int[] {x0, y0, x1, y1};
    }

    private boolean ensureCaptured(int needX0, int needY0, int needX1, int needY1) {
        int depth = ctx.layerStack.size();
        CgGraphTexture innermost = ctx.currentTarget();
        // Retaken when something was painted over this consumer's area since: a capture is the picture as it was.
        boolean sameTarget = captureFrame == ctx.frameId
                && captureDepth == depth && captureTarget == innermost
                && !ctx.paintedOver(needX0, needY0, needX1, needY1);
        if (sameTarget && needX0 >= capX0 && needY0 >= capY0
                && needX1 <= capX0 + capW && needY1 <= capY0 + capH) {
            return true;
        }
        long probeT0 = PROBE ? System.nanoTime() : CgTrace.stamp(UiTrace.FRAME);
        // Queued draws go first: the capture reads the target.
        ctx.drain();
        int w = Math.max(1, ctx.screenWidth), h = Math.max(1, ctx.screenHeight);
        CgGraphTexture cap = captureTexture(gridWidth(), gridHeight());

        // Seeded from last frame's union, so a settled UI captures once for every consumer; widened to what is asked.
        int x0 = Math.max(0, Math.min(needX0, lastX0 == 0 && lastX1 == 0 ? needX0 : lastX0));
        int y0 = Math.max(0, Math.min(needY0, lastY0 == 0 && lastY1 == 0 ? needY0 : lastY0));
        int x1 = Math.min(w, Math.max(needX1, lastX1));
        int y1 = Math.min(h, Math.max(needY1, lastY1));
        if (sameTarget) {   // widening mid-frame: keep what the earlier consumers already needed
            x0 = Math.min(x0, capX0); y0 = Math.min(y0, capY0);
            x1 = Math.max(x1, capX0 + capW); y1 = Math.max(y1, capY0 + capH);
        }
        capX0 = x0; capY0 = y0;
        capW = Math.max(1, Math.min(w - x0, x1 - x0));
        capH = Math.max(1, Math.min(h - y0, y1 - y0));

        // Top-left in the UI's space, which is GL's top-left too: h - capH as the GL y origin.
        int glY0 = h - (capY0 + capH);

        // 1. The scene, region only: the host's target as it stood when the frame began, copied in its place.
        ctx.recordCopy(CgGraphTexture.current(), capX0, glY0, capW, capH, cap, 0, gridHeight() - capH);

        // 1b. The scene made opaque, alpha written alone: the host's alpha is whatever its rendering left (Minecraft's
        // GUI draws blended), and cg_backdrop's un-premultiply divided a bright item by a fractional alpha into a white
        // halo. Opaque, the divide is a no-op and every stage's premultiplied assumption holds.
        //
        // The layers, snapshotted first, since beginLayerFbo pushes onto the stack being read: each with where it
        // sits and how much of its pooled texture is its own.
        List<CgGraphTexture> enclosing = new ArrayList<>();
        List<int[]> placed = new ArrayList<>();
        int originX = 0, originY = 0;
        for (Iterator<CgUiPaintContext.LayerFrame> it = ctx.layerStack.descendingIterator(); it.hasNext(); ) {
            CgUiPaintContext.LayerFrame frame = it.next();
            LayerRegion region = frame.region();
            if (region != null) {
                originX += region.x();
                originY += region.y();
            }
            enclosing.add(frame.target());
            placed.add(new int[] {originX, originY,
                    region == null ? frame.target().getWidth() : region.width(),
                    region == null ? frame.target().getHeight() : region.height()});
        }

        final int fx0 = capX0, fy0 = capY0, fw = capW, fh = capH;
        ctx.beginLayerFbo(cap, false);
        try {
            // Premultiplied `over` leaves the scene where the UI drew nothing, provided it is transparent there.
            withoutScissor(() -> {
                ctx.fillAlpha(fw, fh);
                drawOver(ctx.frameTarget, 0, 0, w, h, fx0, fy0, fw, fh);
                for (int i = 0; i < enclosing.size(); i++) {
                    int[] at = placed.get(i);
                    drawOver(enclosing.get(i), at[0], at[1], at[2], at[3], fx0, fy0, fw, fh);
                }
            });
        } finally {
            ctx.endLayerFbo();
        }

        captureFrame = ctx.frameId;
        ctx.clearPainted();
        captureDepth = depth;
        captureTarget = innermost;
        blurFrame = -1L;   // the capture moved, so whatever was blurred describes somewhere else
        CgTrace.zoneDone(UiTrace.FRAME, "backdrop:capture", probeT0);
        CgTrace.add(UiTrace.FRAME, "backdrop-capture-kpx", (capW * capH) / 1000);
        if (PROBE) {
            pCapture += System.nanoTime() - probeT0;
            pRecaptures++;
            pCapW = capW; pCapH = capH; pLayerDepth = depth;
        }
        return true;
    }

    /**
     * Runs {@code body} with the scissor off: a scissor is in screen pixels and the capture is not, so an inherited
     * one left part of it unwritten and the glass over it dark.
     */
    private void withoutScissor(Runnable body) {
        ScissorStack.Saved saved = ctx.suspendScissor();
        try {
            body.run();
        } finally {
            ctx.resumeScissor(saved);
        }
    }

    /**
     * Composites what of {@code tex} covers the screen rect {@code (cx, cy, cw, ch)} into the same place in the
     * bound capture, whose top-left corner is that rect's, premultiplied.
     *
     * @param ox where the texture's pixel (0,0) is on the screen
     * @param lw how much of the texture, from that corner, holds the layer — its region, not the bucket
     */
    private void drawOver(CgTexture tex, int ox, int oy, int lw, int lh, int cx, int cy, int cw, int ch) {
        int x0 = Math.max(cx, ox), y0 = Math.max(cy, oy);
        int x1 = Math.min(cx + cw, ox + lw), y1 = Math.min(cy + ch, oy + lh);
        if (x1 <= x0 || y1 <= y0) return;
        int rw = x1 - x0, rh = y1 - y0;
        float tw = tex.getWidth(), th = tex.getHeight();
        float u0 = (x0 - ox) / tw, u1 = (x1 - ox) / tw;
        float vTop = 1f - (y0 - oy) / th, vBottom = 1f - (y1 - oy) / th;
        // Declared, not bound by hand. @see CgUiPaintContext#blitLayer
        ctx.layerBlitMaterial.applyProperties(b -> b.sampler("_MainTex", 0, tex));
        ctx.withMaterial(ctx.layerBlitMaterial, () -> {
            ctx.poseStack.pushPose();
            ctx.poseStack.setIdentity();
            ctx.quad().at(x0 - cx, y0 - cy).size(rw, rh).uv(u0, vTop, u1, vBottom).color(0xFFFFFFFF).submit();
            ctx.flush();
            ctx.poseStack.popPose();
        });
    }

    /**
     * Blurs the capture once a frame, shared by every consumer at one radius: Skia's scale-then-blur
     * ({@link CgGpuOps#blurScaled}), sigma a third of the reach, the region box-reduced by {@link #blurScale}, its edges
     * mirrored as Chromium extends a backdrop. One compute pass over the captured region.
     */
    @Nullable
    private CgTexture blurredBackdrop(float radiusPx) {
        if (radiusPx < 0.5f) return capture;
        if (blurFrame == ctx.frameId && blurRadiusPx == radiusPx && blurResult != null) {
            if (PROBE) probeBlurSkipped++;
            return blurResult;
        }
        long probeB0 = PROBE ? System.nanoTime() : CgTrace.stamp(UiTrace.FRAME);

        CgGraphTexture captured = capture;
        if (captured == null) return null;
        // The capture holds the region at its top-left in the UI's space, which is its last rows.
        CgGraphTexture target = targetFor(blurScale);
        CgComputePass pass = ctx.compute("cgui.backdrop.blur").timed(GPU_BLUR);
        CgGpuOps.blurScaled(pass, captured, 0, gridHeight() - capH, capW, capH, target,
                radiusPx / REACH_PER_SIGMA, CgGpuOps.Edge.MIRROR);
        pass.end();

        blurFrame = ctx.frameId;
        blurRadiusPx = radiusPx;
        blurResult = target;
        CgTrace.zoneDone(UiTrace.FRAME, "backdrop:blur", probeB0);
        if (PROBE) pBlur += System.nanoTime() - probeB0;
        return target;
    }

    /** The blur's passes on the GPU, timed apart from the frame's. */
    private static final int GPU_BLUR = CgGpuTrace.name("backdrop.blur");

    // -Dcrystalgui.glass.probe=true: CPU time of the capture and the blur, and the frame period, every 120 frames. The
    // stages are a lower bound, since their GPU work runs later; the period is the honest figure.
    private static final boolean PROBE = Boolean.getBoolean("crystalgui.glass.probe");

    private long probeLastStart;
    private long pFramePeriod, pCapture, pBlur;
    private int pConsumers, pRecaptures, pFrames, pLayerDepth, pCapW, pCapH;
    private long probeBlurSkipped;

    /** One rolling line every {@code PROBE_EVERY} frames, so a live session stays readable. */
    private static final int PROBE_EVERY = 120;

    private void probeFrame() {
        long now = System.nanoTime();
        if (probeLastStart != 0L) pFramePeriod += now - probeLastStart;
        probeLastStart = now;
        if (++pFrames >= PROBE_EVERY) {
            double f = pFrames;
            double periodMs = pFramePeriod / 1e6 / f;
            double capMs = pCapture / 1e6 / f;
            double blurMs = pBlur / 1e6 / f;
            int sw = Math.max(1, ctx.screenWidth), sh = Math.max(1, ctx.screenHeight);
            double pct = 100.0 * (pCapW * (double) pCapH) / (sw * (double) sh);
            if (pConsumers == 0) {
                System.out.printf(java.util.Locale.ROOT,
                        "[GLASS] %.0ff  frame=%.2fms (%.1f fps)  glass=OFF (no consumers)%n",
                        f, periodMs, 1000.0 / Math.max(0.001, periodMs));
            } else {
                System.out.printf(java.util.Locale.ROOT,
                        "[GLASS] %.0ff  frame=%.2fms (%.1f fps)  glass=%.2fms"
                        + " [capture %.2f + blur %.2f]  consumers=%.1f/f  recaptures=%.2f/f"
                        + "  rect=%dx%d (%.1f%% of %dx%d)  layers=%d  blurReused=%d%n",
                        f, periodMs, 1000.0 / Math.max(0.001, periodMs), capMs + blurMs,
                        capMs, blurMs, pConsumers / f, pRecaptures / f,
                        pCapW, pCapH, pct, sw, sh, pLayerDepth, probeBlurSkipped);
            }
            pFrames = 0; pFramePeriod = 0; pCapture = 0; pBlur = 0;
            pConsumers = 0; pRecaptures = 0; probeBlurSkipped = 0;
        }
    }
    // ------------------------------------------------------------------------------------------

    /** Frees the storage of every texture this made: teardown, with no frame to record a release into. */
    void delete() {
        if (capture != null) CgExecutor.freeStorage(capture);
        capture = null;
        for (CgGraphTexture target : blurTargets.values()) CgExecutor.freeStorage(target);
        blurTargets.clear();
    }
}
