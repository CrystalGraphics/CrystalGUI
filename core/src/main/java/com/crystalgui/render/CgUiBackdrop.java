package com.crystalgui.render;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.canvas.CgCanvasBackdrop;
import com.crystalgraphics.render.canvas.CgShapeTable;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * What is behind an element, captured and blurred once a frame: the primitive under {@code backdrop-filter}, reached
 * through {@link CgUiPaintContext#backdropFor}. The textures and the blur are CrystalGraphics' {@link CgCanvasBackdrop};
 * what is the UI's is here:
 * <pre>
 *   capture   the scene, then the frame target and every enclosing layer laid over it, over the region asked for
 *   share     one capture a frame for every consumer, seeded from last frame's, retaken when painted over
 *   hand out  the backdrop, where the bound layer sits in it, and the element's sampleable rect
 * </pre>
 * The capture is the web's backdrop root: inside a layer, what is behind an element includes the layers around it, half
 * painted, which only the UI knows. Allocates nothing per element.
 */
final class CgUiBackdrop {

    private final CgUiPaintContext ctx;
    private final CgCanvasBackdrop backdrop = new CgCanvasBackdrop("cgui.backdrop");
    /** What {@link #forRect} hands out: the context's one, valid until the next call. */
    private final CgUiPaintContext.Backdrop answer = new CgUiPaintContext.Backdrop();
    private final int[] clip = new int[4];

    /** The frame the capture was taken on, the layer depth and innermost target it was taken under. */
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

    /** The screen area boxes painted since the capture, as one rect: a flag would be set by nearly every box. */
    private float paintedX0, paintedY0, paintedX1, paintedY1;

    CgUiBackdrop(CgUiPaintContext ctx) {
        this.ctx = ctx;
        clearPainted();
    }

    /** A new frame: last frame's capture describes nothing in it. */
    void beginFrame() {
        captureFrame = -1L;
        clearPainted();
    }

    /** Something was painted over {@code (x0, y0)-(x1, y1)} of the screen. @see CgUiPaintContext#notePainted */
    void painted(float x0, float y0, float x1, float y1) {
        paintedX0 = Math.min(paintedX0, x0);
        paintedY0 = Math.min(paintedY0, y0);
        paintedX1 = Math.max(paintedX1, x1);
        paintedY1 = Math.max(paintedY1, y1);
    }

    private void clearPainted() {
        paintedX0 = paintedY0 = Float.MAX_VALUE;
        paintedX1 = paintedY1 = -Float.MAX_VALUE;
    }

    CgUiPaintContext.@Nullable Backdrop forRect(float x, float y, float width, float height, float blurRadiusPx, float reach) {
        if (!ctx.frameActive || width <= 0f || height <= 0f || ctx.isSnapshotting()) return null;

        // All four corners through the pose: two bound a rotated quad too tightly. In screen pixels.
        Matrix4f p = ctx.targetPose();
        int ox = ctx.layerOriginX, oy = ctx.layerOriginY;
        float ax = p.m00() * x + p.m10() * y + p.m30(), ay = p.m01() * x + p.m11() * y + p.m31();
        float bx = ax + p.m00() * width, by = ay + p.m01() * width;
        float cx = ax + p.m10() * height, cy = ay + p.m11() * height;
        float dx = bx + p.m10() * height, dy = by + p.m11() * height;
        int px0 = Math.round(Math.min(Math.min(ax, bx), Math.min(cx, dx))) + ox;
        int py0 = Math.round(Math.min(Math.min(ay, by), Math.min(cy, dy))) + oy;
        int px1 = Math.round(Math.max(Math.max(ax, bx), Math.max(cx, dx))) + ox;
        int py1 = Math.round(Math.max(Math.max(ay, by), Math.max(cy, dy))) + oy;

        // Padded by the blur's and the lens's reach (the lens in element units, so through the pose), or the edge
        // clamps from the first tap and smears inward.
        float poseScale = (float) Math.hypot(p.m00(), p.m01());
        int pad = (int) Math.ceil(blurRadiusPx + reach * poseScale) + 4;
        int w = Math.max(1, ctx.screenWidth), h = Math.max(1, ctx.screenHeight);
        // Never past the clip, the web's backdrop root: a lens in a zoomed canvas bent the whole editor in.
        visibleArea(ox, oy);
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
        CgTrace.add(UiTrace.FRAME, "backdrop-consumers", 1);
        ensureCaptured(wantX0, wantY0, wantX1, wantY1);
        blur(blurRadiusPx);

        // The bound target's pixel (0,0) in the backdrop's bottom-left texels: its top-left corner is the origin, top-down.
        int boundHeight = ctx.currentTarget().getHeight();
        answer.source = backdrop;
        answer.offsetX = ox;
        answer.offsetY = h - oy - boundHeight;
        answer.x0 = wantX0;
        answer.y0 = h - wantY1;
        answer.x1 = wantX1;
        answer.y1 = h - wantY0;
        return answer;
    }

    /**
     * What an element drawn now can see, in screen pixels, into {@link #clip}: the scissor and every enclosing bounded
     * layer's region (a mask, an opacity or a rounded {@code overflow}, none a scissor). By the scissor alone, glass at a
     * rounded stage's edge sampled the dialog around it.
     */
    private void visibleArea(int ox, int oy) {
        ctx.clipRect(clip);
        int x0 = clip[0] + ox, y0 = clip[1] + oy, x1 = clip[2] + ox, y1 = clip[3] + oy;
        int lx = 0, ly = 0;
        for (int i = 0, n = ctx.layerDepth(); i < n; i++) {
            LayerRegion region = ctx.layer(i).region();
            if (region == null) continue;
            lx += region.x();
            ly += region.y();
            x0 = Math.max(x0, lx);
            y0 = Math.max(y0, ly);
            x1 = Math.min(x1, lx + region.width());
            y1 = Math.min(y1, ly + region.height());
        }
        clip[0] = x0;
        clip[1] = y0;
        clip[2] = x1;
        clip[3] = y1;
    }

    private void ensureCaptured(int needX0, int needY0, int needX1, int needY1) {
        int depth = ctx.layerDepth();
        CgGraphTexture innermost = ctx.currentTarget();
        // Retaken when something was painted over this consumer's area since: a capture is the picture as it was.
        boolean sameTarget = captureFrame == ctx.frameId && captureDepth == depth && captureTarget == innermost
                && !(paintedX1 > needX0 && paintedX0 < needX1 && paintedY1 > needY0 && paintedY0 < needY1);
        if (sameTarget && needX0 >= capX0 && needY0 >= capY0 && needX1 <= capX0 + capW && needY1 <= capY0 + capH) {
            return;
        }
        long timed = CgTrace.stamp(UiTrace.FRAME);
        int w = Math.max(1, ctx.screenWidth), h = Math.max(1, ctx.screenHeight);

        // Seeded from last frame's union, so a settled UI captures once for every consumer; widened to what is asked.
        int x0 = Math.max(0, Math.min(needX0, lastX0 == 0 && lastX1 == 0 ? needX0 : lastX0));
        int y0 = Math.max(0, Math.min(needY0, lastY0 == 0 && lastY1 == 0 ? needY0 : lastY0));
        int x1 = Math.min(w, Math.max(needX1, lastX1));
        int y1 = Math.min(h, Math.max(needY1, lastY1));
        if (sameTarget) {   // widening mid-frame: keep what the earlier consumers already needed
            x0 = Math.min(x0, capX0);
            y0 = Math.min(y0, capY0);
            x1 = Math.max(x1, capX0 + capW);
            y1 = Math.max(y1, capY0 + capH);
        }
        capX0 = x0;
        capY0 = y0;
        capW = Math.max(1, Math.min(w - x0, x1 - x0));
        capH = Math.max(1, Math.min(h - y0, y1 - y0));

        // The scene behind the UI, the host's target as the frame began, at its place in the backdrop's texels.
        CgGraphTexture capture = backdrop.capture(ctx.breakPass(), CgGraphTexture.current(), w, h, CgFormat.RGBA8_UNORM,
                capX0, h - (capY0 + capH), capX0 + capW, h - capY0);

        // Then what the UI drew, premultiplied `over`: the frame target, then each enclosing layer where it sits. Read
        // before the capture's own layer opens, which pushes onto the stack being read. The capture's top-down rows
        // start (grid - screen) above the screen's, the grid rounding the screen up.
        int dy = backdrop.gridHeight() - h;
        int layers = depth;
        ctx.beginLayerFbo(capture, false);
        ScissorStack.Saved saved = ctx.suspendScissor();   // a scissor is in screen pixels and the capture is not
        ctx.getPoseStack().pushPose();
        ctx.getPoseStack().setIdentity();
        try {
            drawOver(ctx.frameTarget, 0, 0, w, h, dy);
            int lx = 0, ly = 0;
            for (int i = 0; i < layers; i++) {
                CgUiPaintContext.LayerFrame frame = ctx.layer(i);
                LayerRegion region = frame.region();
                if (region != null) {
                    lx += region.x();
                    ly += region.y();
                }
                CgGraphTexture target = frame.target();
                drawOver(target, lx, ly, region == null ? target.getWidth() : region.width(),
                        region == null ? target.getHeight() : region.height(), dy);
            }
        } finally {
            ctx.getPoseStack().popPose();
            ctx.resumeScissor(saved);
            ctx.endLayerFbo();
        }

        captureFrame = ctx.frameId;
        captureDepth = depth;
        captureTarget = innermost;
        clearPainted();
        blurFrame = -1L;   // the capture moved, so whatever was blurred describes somewhere else
        CgTrace.zoneDone(UiTrace.FRAME, "backdrop:capture", timed);
        CgTrace.add(UiTrace.FRAME, "backdrop-captures", 1);
        CgTrace.add(UiTrace.FRAME, "backdrop-capture-kpx", (capW * capH) / 1000);
    }

    /**
     * Composites what of {@code tex} covers the capture's region into the same place in the bound capture, {@code dy}
     * rows lower, premultiplied.
     *
     * @param ox where the texture's pixel (0,0) is on the screen
     * @param lw how much of the texture, from that corner, holds the layer: its region, not its bucket
     */
    private void drawOver(CgTexture tex, int ox, int oy, int lw, int lh, int dy) {
        int x0 = Math.max(capX0, ox), y0 = Math.max(capY0, oy);
        int x1 = Math.min(capX0 + capW, ox + lw), y1 = Math.min(capY0 + capH, oy + lh);
        if (x1 <= x0 || y1 <= y0) return;
        float tw = tex.getWidth(), th = tex.getHeight();
        ctx.quad().texture(0, tex).custom2(CgShapeTable.PREMULTIPLIED).at(x0, y0 + dy).size(x1 - x0, y1 - y0)
                .uv((x0 - ox) / tw, 1f - (y0 - oy) / th, (x1 - ox) / tw, 1f - (y1 - oy) / th)
                .opacity(1f).submit();   // whole, whatever opacity the element is drawn under
    }

    /** Blurs the capture once a frame per radius, shared by every consumer at it. */
    private void blur(float radiusPx) {
        if (blurFrame == ctx.frameId && blurRadiusPx == radiusPx) {
            CgTrace.add(UiTrace.FRAME, "backdrop-blur-reused", 1);
            return;
        }
        long timed = CgTrace.stamp(UiTrace.FRAME);
        backdrop.blur(ctx.breakPass(), radiusPx);
        blurFrame = ctx.frameId;
        blurRadiusPx = radiusPx;
        CgTrace.zoneDone(UiTrace.FRAME, "backdrop:blur", timed);
    }

    /** Frees the storage of its textures: teardown, with no frame to record a release into. */
    void delete() {
        backdrop.delete();
    }
}
