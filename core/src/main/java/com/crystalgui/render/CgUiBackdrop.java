package com.crystalgui.render;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.canvas.CgCanvasBackdrop;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * What is behind an element, captured and blurred once a frame: the primitive under {@code backdrop-filter}. Part of the
 * paint context, reaching into it through package-private members. The textures and the blur are CrystalGraphics'
 * {@link CgCanvasBackdrop}; what is the UI's is here:
 * <pre>
 *   capture   the scene, then the frame target and every enclosing layer laid over it, over the region asked for
 *   share     one capture a frame for every consumer, seeded from last frame's, retaken when painted over
 *   hand out  the backdrop, where the bound layer sits in it, and the element's sampleable rect
 * </pre>
 */
final class CgUiBackdrop {

    private final CgUiPaintContext ctx;
    private final CgCanvasBackdrop backdrop = new CgCanvasBackdrop("cgui.backdrop");

    CgUiBackdrop(CgUiPaintContext ctx) {
        this.ctx = ctx;
    }

    /** A new frame: last frame's capture describes nothing in it. */
    void beginFrame() {
        if (PROBE) probeFrame();
        captureFrame = -1L;
    }

    /** The frame the capture was taken on: the whole of the "once a frame" rule. */
    private long captureFrame = -1L;
    private int captureDepth = -1;
    @Nullable
    private CgGraphTexture captureTarget;

    /** The region the current capture covers, in screen pixels, top-down. */
    private int capX0, capY0, capW, capH;

    /**
     * The union of the regions asked for this frame and last, in screen pixels. Seeding a capture with last frame's
     * keeps a settled UI at one capture; a consumer outside it forces a recapture.
     */
    private int reqX0, reqY0, reqX1, reqY1;
    private int lastX0, lastY0, lastX1, lastY1;
    private long reqFrame = -1L;

    private long blurFrame = -1L;
    private float blurRadiusPx = Float.NaN;

    /**
     * Captures what is behind {@code (x, y, w, h)} and blurs it: the primitive under {@code backdrop-filter}. One capture
     * a frame, shared by every consumer, retaken only when something was painted over what one samples
     * ({@link CgUiPaintContext#notePainted}).
     *
     * @param blurRadiusPx how far the blur reaches, in surface pixels; 0 hands back the capture itself
     * @param reach        how far a lens samples past the rect, in element units
     * @return the backdrop and the element's place in it, or {@code null} with nothing to capture: draw the fallback
     */
    @Nullable
    CgUiPaintContext.Backdrop forRect(float x, float y, float width, float height, float blurRadiusPx, float reach) {
        if (!ctx.frameActive || width <= 0f || height <= 0f || ctx.isSnapshotting()) return null;

        // All four corners through the transform chain: two bound a rotated quad too tightly. In screen pixels: a
        // bounded layer's (0,0) is its corner.
        Matrix4f pose = new Matrix4f(ctx.targetPose());
        Vector3f tl = pose.transformPosition(new Vector3f(x, y, 0f));
        Vector3f tr = pose.transformPosition(new Vector3f(x + width, y, 0f));
        Vector3f bl = pose.transformPosition(new Vector3f(x, y + height, 0f));
        Vector3f br = pose.transformPosition(new Vector3f(x + width, y + height, 0f));
        int[] origin = targetOrigin();
        int px0 = Math.round(Math.min(Math.min(tl.x, tr.x), Math.min(bl.x, br.x))) + origin[0];
        int py0 = Math.round(Math.min(Math.min(tl.y, tr.y), Math.min(bl.y, br.y))) + origin[1];
        int px1 = Math.round(Math.max(Math.max(tl.x, tr.x), Math.max(bl.x, br.x))) + origin[0];
        int py1 = Math.round(Math.max(Math.max(tl.y, tr.y), Math.max(bl.y, br.y))) + origin[1];

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
        ensureCaptured(wantX0, wantY0, wantX1, wantY1);
        blur(blurRadiusPx);

        // The bound target's pixel (0,0) in the backdrop's bottom-left texels: its top-left corner is origin, top-down.
        int boundHeight = ctx.currentTarget().getHeight();
        return new CgUiPaintContext.Backdrop(backdrop, origin[0], h - origin[1] - boundHeight,
                wantX0, h - wantY1, wantX1, h - wantY0);
    }

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

    private void ensureCaptured(int needX0, int needY0, int needX1, int needY1) {
        int depth = ctx.layerStack.size();
        CgGraphTexture innermost = ctx.currentTarget();
        // Retaken when something was painted over this consumer's area since: a capture is the picture as it was.
        boolean sameTarget = captureFrame == ctx.frameId
                && captureDepth == depth && captureTarget == innermost
                && !ctx.paintedOver(needX0, needY0, needX1, needY1);
        if (sameTarget && needX0 >= capX0 && needY0 >= capY0
                && needX1 <= capX0 + capW && needY1 <= capY0 + capH) {
            return;
        }
        long probeT0 = PROBE ? System.nanoTime() : CgTrace.stamp(UiTrace.FRAME);
        int w = Math.max(1, ctx.screenWidth), h = Math.max(1, ctx.screenHeight);

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

        // The layers, snapshotted first, since beginLayerFbo pushes onto the stack being read: each with where it sits
        // and how much of its pooled texture is its own.
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

        // The scene behind the UI, the host's target as the frame began, at its place in the backdrop's texels.
        CgGraphTexture capture = backdrop.capture(ctx.breakPass(), CgGraphTexture.current(), w, h, CgTextureType.RGBA8,
                capX0, h - (capY0 + capH), capX0 + capW, h - capY0);

        // Then what the UI drew, premultiplied `over`. The capture's top-down rows start (grid - screen) above the
        // screen's, the grid rounding the screen up.
        int dy = backdrop.gridHeight() - h;
        final int fx0 = capX0, fy0 = capY0, fw = capW, fh = capH;
        ctx.beginLayerFbo(capture, false);
        ScissorStack.Saved saved = ctx.suspendScissor();
        try {
            // A scissor is in screen pixels and the capture is not: an inherited one left part of it unwritten.
            drawOver(ctx.frameTarget, 0, 0, w, h, fx0, fy0, fw, fh, dy);
            for (int i = 0; i < enclosing.size(); i++) {
                int[] at = placed.get(i);
                drawOver(enclosing.get(i), at[0], at[1], at[2], at[3], fx0, fy0, fw, fh, dy);
            }
        } finally {
            ctx.resumeScissor(saved);
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
    }

    /**
     * Composites what of {@code tex} covers the screen rect {@code (cx, cy, cw, ch)} into the same place in the bound
     * capture, {@code dy} rows lower, premultiplied.
     *
     * @param ox where the texture's pixel (0,0) is on the screen
     * @param lw how much of the texture, from that corner, holds the layer: its region, not the bucket
     */
    private void drawOver(CgTexture tex, int ox, int oy, int lw, int lh, int cx, int cy, int cw, int ch, int dy) {
        int x0 = Math.max(cx, ox), y0 = Math.max(cy, oy);
        int x1 = Math.min(cx + cw, ox + lw), y1 = Math.min(cy + ch, oy + lh);
        if (x1 <= x0 || y1 <= y0) return;
        float tw = tex.getWidth(), th = tex.getHeight();
        float u0 = (x0 - ox) / tw, u1 = (x1 - ox) / tw;
        float vTop = 1f - (y0 - oy) / th, vBottom = 1f - (y1 - oy) / th;
        // Declared, not bound by hand. @see CgUiPaintContext#blitLayer
        ctx.layerBlitMaterial.applyProperties(b -> b.sampler("_MainTex", 0, tex));
        ctx.withMaterial(ctx.layerBlitMaterial, () -> {
            ctx.poseStack.pushPose();
            ctx.poseStack.setIdentity();
            ctx.quad().at(x0, y0 + dy).size(x1 - x0, y1 - y0).uv(u0, vTop, u1, vBottom).color(0xFFFFFFFF).submit();
            ctx.flush();
            ctx.poseStack.popPose();
        });
    }

    /** Blurs the capture once a frame per radius, shared by every consumer at it. */
    private void blur(float radiusPx) {
        if (blurFrame == ctx.frameId && blurRadiusPx == radiusPx) {
            if (PROBE) probeBlurSkipped++;
            return;
        }
        long probeB0 = PROBE ? System.nanoTime() : CgTrace.stamp(UiTrace.FRAME);
        backdrop.blur(ctx.breakPass(), radiusPx);
        blurFrame = ctx.frameId;
        blurRadiusPx = radiusPx;
        CgTrace.zoneDone(UiTrace.FRAME, "backdrop:blur", probeB0);
        if (PROBE) pBlur += System.nanoTime() - probeB0;
    }

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
                System.out.printf(Locale.ROOT, "[GLASS] %.0ff  frame=%.2fms (%.1f fps)  glass=OFF (no consumers)%n",
                        f, periodMs, 1000.0 / Math.max(0.001, periodMs));
            } else {
                System.out.printf(Locale.ROOT,
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

    /** Frees the storage of its textures: teardown, with no frame to record a release into. */
    void delete() {
        backdrop.delete();
    }
}
