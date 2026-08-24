package com.crystalgui.render;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The BACKDROP PRIMITIVE: what is behind an element, captured and blurred, once per frame.
 *
 * <p>This is the whole of what {@code glass()} stands on, and it sits beside {@link CgUiPaintContext}
 * rather than inside it for the reason {@code TextEditor}'s view parts do: it is a piece of the paint
 * context rather than a client of it, so it reaches back through package-private members instead of
 * through a public API. Keeping it here also keeps the context's own job legible — the context draws
 * things, and this reads the framebuffer back.</p>
 *
 * <h3>What it does, in order</h3>
 * <pre>
 *   capture   scene blit -&gt; MSAA resolve -&gt; composite msaaFbo and every enclosing layer
 *   blur      separable Gaussian, horizontal then vertical, at 1/BLUR_SCALE resolution
 *   hand out  the sharp texture, the blurred one, and the element's UVs into both
 * </pre>
 *
 * <h3>Two rules carry the whole design</h3>
 *
 * <p><b>The capture is a REGION, not the surface.</b> One capture per frame shared by every consumer is
 * right; taking "shared" to mean "everything" is what made glass a frame killer. See {@link #capX0}.</p>
 *
 * <p><b>The targets never change size.</b> They are screen-sized and only a corner is used, because the
 * capture happens mid-paint and rebuilding a framebuffer there is the incomplete-attachment hazard
 * {@link #prepareFrame} exists to avoid.</p>
 */
final class CgUiBackdrop {

    private final CgUiPaintContext ctx;

    CgUiBackdrop(CgUiPaintContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Records the draw target that was bound when the frame began, and invalidates last frame's capture.
     *
     * <p>Must run BEFORE the MSAA redirect, because the redirect is what hides it. @see #sceneFboId</p>
     */
    void captureSceneTarget() {
        sceneFboId = CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
        captureFrame = -1L;
    }

    void prepareFrame() {
        if (PROBE) probeFrame();
        int w = Math.max(1, Math.max(1, ctx.screenWidth) / BLUR_SCALE);
        int h = Math.max(1, Math.max(1, ctx.screenHeight) / BLUR_SCALE);
        for (CgFrameBuffer fbo : new CgFrameBuffer[] { blurA, blurB }) {
            if (fbo.getWidth() != w || fbo.getHeight() != h) {
                fbo.resize(w, h);
                ctx.warmUpLayer(fbo);
            }
        }
    }

    /**
     * The draw target that was bound when {@link #beginFrame} ran — <b>the scene behind the UI</b>.
     *
     * <p><b>{@code ctx.msaaFbo} is not the backdrop, and reaching for it is the mistake this field exists
     * to prevent.</b> {@code beginFrame} binds it and clears it <em>fully transparent</em>, so the UI's
     * own target holds the UI and nothing else. The world, the HUD and the hotbar are all in whatever
     * was bound before that — which is what {@code endFrame} composites back onto. A backdrop grab that
     * read {@code ctx.msaaFbo} would capture an empty buffer and look exactly like the effect not working.</p>
     *
     * <p>Read from the binding rather than handed in by the host: it needs no loader change, it is true
     * by construction in game and in the harness alike, and it stays correct when {@code framebufferMc}
     * is off — the id is then {@code 0} and a blit reads the back buffer perfectly well, where an
     * explicitly-registered MC framebuffer id would be wrong for exactly those players.</p>
     */
    private int sceneFboId;

    /** Scene plus whatever the UI had painted when the first glass element of the frame drew. */
    private final CgFrameBuffer captureFbo =
            CgFrameBuffer.createOwned("cgui_backdrop", 1, 1, CgUiPaintContext.LAYER_FORMAT);

    /** The frame {@link #captureFbo} was captured on — the whole of the "once per frame" rule. */
    private long captureFrame = -1L;
    private int captureDepth = -1;
    private int captureTarget = -1;

    /**
     * THE REGION THE CURRENT CAPTURE COVERS, in surface pixels, occupying the target's top-left corner.
     *
     * <p>The capture used to be the whole surface, and that is what made glass a frame killer: a taskbar
     * island is about a fortieth of the screen, and it was paying for a full-surface scene blit, a
     * full-surface MSAA resolve and a full-surface composite per enclosing layer, every frame. Sharing
     * one capture between consumers is right; taking "shared" to mean "everything" was not.</p>
     *
     * <p><b>The targets stay screen-sized and only a SUB-RECTANGLE is used.</b> Resizing them to the
     * region would be the obvious move and is the one that reintroduces the incomplete-framebuffer
     * hazard {@link #prepareFrame} exists to avoid — the capture happens mid-paint, which is exactly
     * where an FBO must not be rebuilt. Using a corner costs nothing: the sub-rect is the same FRACTION
     * of every target in the chain, so a normalised UV means the same thing in all of them and the blur's
     * step maths needs no change at all.</p>
     */
    private int capX0, capY0, capW, capH;

    /**
     * The union of the regions asked for this frame and last, in surface pixels.
     *
     * <p>Seeding a frame's capture with the PREVIOUS frame's union is what keeps this at one capture for
     * more than one consumer: on a UI that is not moving, last frame's union covers this frame's requests
     * exactly. A consumer that is not covered forces a recapture of the widened union rather than
     * accepting a stale answer, so the result is exact and only the COST is speculative — an extra
     * capture on the frame something moves or appears.</p>
     */
    private int reqX0, reqY0, reqX1, reqY1;
    private int lastX0, lastY0, lastX1, lastY1;
    private long reqFrame = -1L;

    /**
     * The two targets of the separable blur: horizontal into {@code blurA}, vertical into {@code blurB}.
     *
     * <p>Both are the surface divided by {@link #BLUR_SCALE}. Blurring at reduced resolution is not only
     * cheaper, it is what keeps the kernel well sampled: nine taps spread over a large radius leave
     * visible gaps at full resolution, and the same nine taps over a quarter-size source land barely
     * more than a texel apart. The reduction is free quality here rather than a compromise, because the
     * output is by definition an image with no high frequencies left in it.</p>
     */
    private final CgFrameBuffer blurA = CgFrameBuffer.createOwned("cgui_blur_h", 1, 1, CgUiPaintContext.LAYER_FORMAT);
    private final CgFrameBuffer blurB = CgFrameBuffer.createOwned("cgui_blur_v", 1, 1, CgUiPaintContext.LAYER_FORMAT);

    /** How much smaller than the surface the blur runs at. */
    private static final int BLUR_SCALE = 4;

    private long blurFrame = -1L;
    private float blurRadiusPx = Float.NaN;

    /**
     * Captures what is behind {@code (x, y, w, h)} and blurs it — the primitive under {@code glass()}.
     *
     * <h3>One capture per frame, shared</h3>
     * <p>The full-surface grab happens on the first call of a frame and every later caller crops out of
     * it, so this is <b>one render-target switch per frame however many consumers there are</b> rather
     * than one each. That is what makes glass on more than a single element a measurement rather than a
     * flat no.</p>
     *
     * <p><b>The caveat, which is a default rather than a limitation:</b> because the capture is taken
     * once, a consumer drawn later does not see a consumer drawn earlier. With one taskbar that is
     * exact. Two overlapping glass surfaces would need a per-consumer grab, which costs a switch.</p>
     *
     * @param blurRadiusPx how far the blur reaches, in surface pixels. Zero hands back the capture
     *                     itself, so {@code blur 0} costs nothing beyond the grab every consumer shares
     * @return the two textures, or {@code null} when there is nothing to capture — a caller must fall
     *         back to a solid colour rather than draw nothing
     */
    @Nullable
    CgUiPaintContext.Backdrop forRect(float x, float y, float width, float height, float blurRadiusPx) {
        if (!ctx.frameActive || width <= 0f || height <= 0f) return null;

        // SURFACE PIXELS, so from the TRANSFORM chain — the opposite of the rule for placing a popup
        // ("position from getWindowX/Y, never from localToWorld"), and it inverts for the same reason
        // that rule exists. A popup's left/top are logical and get scaled again, so surface pixels are
        // wrong there; a framebuffer region is already surface pixels, so they are the only right
        // answer here.
        Matrix4f pose = ctx.poseStack.last().pose();
        Vector3f min = pose.transformPosition(new Vector3f(x, y, 0f));
        Vector3f max = pose.transformPosition(new Vector3f(x + width, y + height, 0f));
        int px0 = Math.round(Math.min(min.x, max.x));
        int py0 = Math.round(Math.min(min.y, max.y));
        int px1 = Math.round(Math.max(min.x, max.x));
        int py1 = Math.round(Math.max(min.y, max.y));
        int rw = Math.max(1, px1 - px0);
        int rh = Math.max(1, py1 - py0);

        // PAD BY THE BLUR'S REACH. A tap that leaves the captured region is clamped to its edge, which
        // is what every backdrop-filter implementation does and is invisible a few pixels in - but only
        // if there ARE a few pixels. Capturing the element's own rect exactly would clamp from the very
        // first tap and smear the edge inward.
        int pad = (int) Math.ceil(blurRadiusPx) + 4;
        int w = Math.max(1, ctx.screenWidth), h = Math.max(1, ctx.screenHeight);
        int wantX0 = Math.max(0, px0 - pad), wantY0 = Math.max(0, py0 - pad);
        int wantX1 = Math.min(w, px1 + pad), wantY1 = Math.min(h, py1 + pad);

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

        // Y IS FLIPPED. GL framebuffers are bottom-left origin and the UI is top-left, so the region's
        // v runs the other way. blitLayer carries the same flip spelled uv(0, 1, 1, 0).
        // Relative to the CAPTURE's origin, since the capture is a region rather than the whole surface.
        float u0 = (px0 - capX0) / (float) w;
        float u1 = (px1 - capX0) / (float) w;
        float vBottom = 1f - (py1 - capY0) / (float) h;
        float vTop = 1f - (py0 - capY0) / (float) h;

        CgTexture2D sharp = (CgTexture2D) captureFbo.getColorTexture(0);
        CgTexture2D blurred = blurredBackdrop(blurRadiusPx);
        if (sharp == null || blurred == null) return null;
        return new CgUiPaintContext.Backdrop(sharp, blurred, u0, vBottom, u1, vTop);
    }

    /**
     * Grabs the scene, then everything the UI has painted over it so far.
     *
     * <p><b>"So far" includes the LAYER STACK, and missing that is what made the first version draw
     * nothing.</b> A masked or faded subtree paints into a layer FBO of its own and is not composited
     * back until it finishes, so at the moment a glass element inside one draws, its siblings are in
     * that layer — not in {@code ctx.msaaFbo}, and certainly not in the scene. Capturing only those two
     * produced a flat, empty backdrop: on screen, a panel with nothing but its own rim, which reads as
     * the blur being broken rather than as the capture looking in the wrong place.</p>
     *
     * <p>So every live target is composited in paint order — scene, then {@code ctx.msaaFbo}, then each
     * enclosing layer from outermost inward. That is the definition of "behind this element", and the
     * stack is at most a few deep because it is nesting depth rather than element count.</p>
     */
    private boolean ensureCaptured(int needX0, int needY0, int needX1, int needY1) {
        int depth = ctx.layerStack.size();
        CgFrameBuffer innermost = ctx.layerStack.isEmpty() ? ctx.msaaFbo : ctx.layerStack.peek().fbo();
        boolean sameTarget = captureFrame == ctx.frameId
                && captureDepth == depth && captureTarget == innermost.getId();
        if (sameTarget && needX0 >= capX0 && needY0 >= capY0
                && needX1 <= capX0 + capW && needY1 <= capY0 + capH) {
            return true;
        }
        long probeT0 = PROBE ? System.nanoTime() : 0L;
        int w = Math.max(1, ctx.screenWidth), h = Math.max(1, ctx.screenHeight);
        if (captureFbo.getWidth() != w || captureFbo.getHeight() != h) captureFbo.resize(w, h);

        // Seed from the previous frame's union so a settled UI captures ONCE for every consumer, and
        // widen to whatever is being asked for now so the answer is never stale.
        int x0 = Math.max(0, Math.min(needX0, lastX0 == 0 && lastX1 == 0 ? needX0 : lastX0));
        int y0 = Math.max(0, Math.min(needY0, lastX1 == 0 && lastY1 == 0 ? needY0 : lastY0));
        int x1 = Math.min(w, Math.max(needX1, lastX1));
        int y1 = Math.min(h, Math.max(needY1, lastY1));
        if (sameTarget) {   // widening mid-frame: keep what the earlier consumers already needed
            x0 = Math.min(x0, capX0); y0 = Math.min(y0, capY0);
            x1 = Math.max(x1, capX0 + capW); y1 = Math.max(y1, capY0 + capH);
        }
        capX0 = x0; capY0 = y0;
        capW = Math.max(1, Math.min(w - x0, x1 - x0));
        capH = Math.max(1, Math.min(h - y0, y1 - y0));

        // The capture occupies the target's TOP-LEFT corner in UI terms, which is the TOP-left in GL
        // terms too - hence h - capH as the GL y origin. Everything downstream reads the same sub-rect.
        int glY0 = h - (capY0 + capH), glY1 = h - capY0;

        // 1. The scene, region only.
        CgFrameBuffer.blitFrom(sceneFboId, captureFbo.getId(),
                capX0, glY0, capX0 + capW, glY1,
                0, h - capH, capW, h, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_NEAREST);

        // 2. ctx.msaaFbo, resolved - it is a multisampled RENDERBUFFER and cannot be sampled, which is the
        // same reason endFrame resolves it and the reason this feature is possible at all. RESOLVED IN
        // PLACE and region-only: on a multisampled surface this is the single most expensive thing the
        // capture does, because it touches every sample of every pixel it covers.
        CgFrameBuffer.blitFrom(ctx.msaaFbo.getId(), ctx.msaaResolveFbo.getId(),
                capX0, glY0, capX0 + capW, glY1,
                capX0, glY0, capX0 + capW, glY1, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_NEAREST);

        // SNAPSHOT FIRST. beginLayerFbo below pushes onto the very stack being read.
        List<CgFrameBuffer> enclosing = new ArrayList<>();
        for (Iterator<CgUiPaintContext.LayerFrame> it = ctx.layerStack.descendingIterator(); it.hasNext(); ) {
            enclosing.add(it.next().fbo());
        }

        // KEEP the scene blit above -- see ctx.beginLayerFbo(fbo, clear).
        final int fx0 = capX0, fy0 = capY0, fw = capW, fh = capH;
        ctx.beginLayerFbo(captureFbo, false);
        try {
            withoutScissor(() -> {
                drawOver((CgTexture2D) ctx.msaaResolveFbo.getColorTexture(0), fx0, fy0, fw, fh, w, h);
                for (CgFrameBuffer layer : enclosing) {
                    drawOver((CgTexture2D) layer.getColorTexture(0), fx0, fy0, fw, fh, w, h);
                }
            });
        } finally {
            ctx.endLayerFbo();
        }

        captureFrame = ctx.frameId;
        captureDepth = depth;
        captureTarget = innermost.getId();
        blurFrame = -1L;   // the capture moved, so whatever was blurred describes somewhere else
        if (PROBE) {
            pCapture += System.nanoTime() - probeT0;
            pRecaptures++;
            pCapW = capW; pCapH = capH; pLayerDepth = depth;
        }
        return true;
    }

    /**
     * Runs {@code body} with the scissor test OFF, restoring it afterwards.
     *
     * <p><b>A SCISSOR RECT IS IN SCREEN PIXELS, AND A BACKDROP TARGET IS NOT IN SCREEN PIXELS.</b>
     * That is the whole of it. Every ordinary layer FBO is screen-sized, so the ambient clip rect means
     * the same thing there and {@link CgUiPaintContext#beginLayerFbo} is right not to touch it -- an element inside an
     * {@code overflow: hidden} subtree must stay clipped when it is promoted to a layer. The backdrop
     * capture and the blur targets are the first things in this engine to render into a target with its
     * OWN coordinate space, and for them the inherited rect is not a clip, it is a coordinate error.</p>
     *
     * <p>What it looked like: glass drawn inside a clipped subtree produced blur targets whose left
     * strip was never written -- the stage's clip rect started ~180px into a 1920px screen, and the same
     * 180 landed 180px into a 480px target, so 37% of it stayed at the clear instead of 9%. Panels over
     * that strip sampled transparent black and came out dark, with a hard edge exactly where the rect
     * began. It reads as a sampling or clamping fault and survived six rounds of looking at the sampling,
     * because the arithmetic downstream was correct throughout: it was being handed a target with a hole
     * in it. A readback of the target is what finally showed it, and would have on day one.</p>
     */
    private void withoutScissor(Runnable body) {
        try (CgGlScope ignored = CgGlState.save(CgGlSlot.SCISSOR)) {
            CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
            body.run();
        }
    }

    /**
     * Composites the region {@code (rx, ry, rw, rh)} of a SCREEN-SIZED {@code tex} into the bound
     * target's top-left corner, premultiplied.
     *
     * <p>{@code w}/{@code h} are the source's dimensions, which is what the region has to be normalised
     * against - the sources here are the resolve target and the layer pool's FBOs, all screen-sized,
     * while the destination is a region-sized corner of the capture.</p>
     */
    private void drawOver(@Nullable CgTexture2D tex, int rx, int ry, int rw, int rh, int w, int h) {
        if (tex == null) return;
        float u0 = rx / (float) w, u1 = (rx + rw) / (float) w;
        float vTop = 1f - ry / (float) h, vBottom = 1f - (ry + rh) / (float) h;
        ctx.withMaterial(ctx.layerBlitMaterial, () -> {
            ctx.bindTexture(tex);
            ctx.poseStack.pushPose();
            ctx.poseStack.setIdentity();
            ctx.quad().at(0, 0).size(rw, rh).uv(u0, vTop, u1, vBottom).color(0xFFFFFFFF).submit();
            ctx.flush();
            ctx.poseStack.popPose();
        });
    }

    /**
     * Builds and resizes the blur targets - from {@link #beginFrame}, never from a draw.
     *
     * <p><b>Never created mid-paint.</b> An FBO built inside a draw came back INCOMPLETE (0x8CD7,
     * missing attachment) on the very first frame, while the identical call from
     * {@code acquireLayerFbo} has always worked - and the difference is only ever <em>when</em>: the
     * pool builds its slots from {@code beginFrame}, this was building them with our own framebuffer
     * bindings in flight. That is the shape {@code CgGlStateManager} warns about, where an elided bind
     * means an attachment lands somewhere other than the framebuffer being built, so the symptom is a
     * missing call rather than a failing one.</p>
     */
    /**
     * Blurs the whole captured backdrop, once per frame, and hands back the result.
     *
     * <p><b>The blur is SHARED, like the capture.</b> Every consumer samples its own region out of one
     * blurred surface, so this runs once however many glass elements there are rather than once each.
     * The trade is that they share a radius - exactly right while the parameters come from a
     * stylesheet, and it would need a pass per distinct radius if that stops being true.</p>
     *
     * <p>Two passes, horizontal then vertical, which is the whole of it. This deliberately replaced a
     * dual-Kawase pyramid; {@code gui_blur.shader} records why at length, and the short version is that
     * a pyramid's correctness is spread across every level's viewport, texel size and quad, and when
     * those disagreed the symptom was darkness bleeding inward from outside the capture - which reads
     * as a sampling bug anywhere except where it was.</p>
     */
    @Nullable
    private CgTexture2D blurredBackdrop(float radiusPx) {
        if (radiusPx < 0.5f) return (CgTexture2D) captureFbo.getColorTexture(0);
        if (blurFrame == ctx.frameId && blurRadiusPx == radiusPx) {
            if (PROBE) probeBlurSkipped++;
            return (CgTexture2D) blurB.getColorTexture(0);
        }
        long probeB0 = PROBE ? System.nanoTime() : 0L;

        // The kernel reaches four steps either side, so a step is a quarter of the asked-for radius.
        // In UV, and therefore independent of what resolution the passes actually run at - which is what
        // lets BLUR_SCALE change without retuning anything, and what lets the capture be a REGION
        // without retuning anything either: every target is screen-scaled, so a normalised step means
        // the same number of surface pixels in all of them.
        float stepU = radiusPx / 4f / Math.max(1, ctx.screenWidth);
        float stepV = radiusPx / 4f / Math.max(1, ctx.screenHeight);

        // The fraction of each target the capture actually occupies. Identical in all of them, because
        // they are all the same fraction of the screen. @see #capX0
        float fracW = capW / (float) Math.max(1, ctx.screenWidth);
        float fracH = capH / (float) Math.max(1, ctx.screenHeight);

        CgTexture2D captured = (CgTexture2D) captureFbo.getColorTexture(0);
        if (captured == null) return null;
        blurPass(captured, blurA, stepU, 0f, fracW, fracH);
        CgTexture2D horizontal = (CgTexture2D) blurA.getColorTexture(0);
        if (horizontal == null) return null;
        blurPass(horizontal, blurB, 0f, stepV, fracW, fracH);

        blurFrame = ctx.frameId;
        blurRadiusPx = radiusPx;
        if (PROBE) pBlur += System.nanoTime() - probeB0;
        return (CgTexture2D) blurB.getColorTexture(0);
    }

    /**
     * One axis of the separable blur, {@code source} to {@code target}.
     *
     * <p>The quad is drawn {@code uv(0, 1, 1, 0)} - the SAME flip {@link #drawOver} uses, and the
     * agreement is load-bearing rather than incidental. A layer FBO is bottom-left origin while the UI
     * is top-left, so every full-surface blit into one carries the flip. The pyramid this replaced drew
     * its passes unflipped and got away with it only because an equal number of down and up passes
     * cancelled: correct output from two errors, which is the kind of thing that stays true right up
     * until somebody changes the level count.</p>
     */
    private void blurPass(CgTexture2D source, CgFrameBuffer target,
                          float stepU, float stepV, float fracW, float fracH) {
        // PROPERTIES BEFORE THE BIND, and this is not style. withMaterial binds the material, and
        // binding VALIDATES the samplers it currently holds - which, on the frame after a resize, are
        // the textures the resize deleted. Maximising the window crashed with "CgTexture2D has been
        // deleted" from inside useMaterial, before a single line of this pass had run.
        //
        // applyProperties is buffered and replayed at bind time, so setting them first is both legal
        // and the only order that cannot hand a bind a dead texture.
        float halfU = 0.5f / Math.max(1, source.getWidth());
        float halfV = 0.5f / Math.max(1, source.getHeight());
        ctx.blurMaterial.applyProperties(b -> {
            b.sampler("_MainTex", 0, source);
            b.vec2("_Step", stepU, stepV);
            // The CAPTURED SUB-RECT, half a texel in. A tap landing outside it reads whatever the
            // sampler's clamp gives back, and outside the sub-rect that is the target's clear -
            // transparent black. Which is precisely how darkness gets dragged into a panel that had
            // colour behind it, with a hard boundary where the taps stop reaching, and is why this is
            // the sub-rect rather than the whole texture now that the capture is a region.
            b.vec4("_Bounds", halfU, 1f - fracH + halfV, fracW - halfU, 1f - halfV);
        });
        int qw = Math.max(1, Math.round(target.getWidth() * fracW));
        int qh = Math.max(1, Math.round(target.getHeight() * fracH));
        ctx.beginLayerFbo(target);
        try {
            withoutScissor(() -> ctx.withMaterial(ctx.blurMaterial, () -> {
                ctx.poseStack.pushPose();
                ctx.poseStack.setIdentity();
                ctx.quad().at(0, 0).size(qw, qh)
                      .uv(0f, 1f, fracW, 1f - fracH).color(0xFFFFFFFF).submit();
                ctx.flush();
                ctx.poseStack.popPose();
            }));
        } finally {
            ctx.endLayerFbo();
        }
    }

    // ---- GLASS PROBE -------------------------------------------------------------------------
    // -Dcrystalgui.glass.probe=true. Off by default and one boolean read per frame when off.
    //
    // CPU-side timing only: CgGL exposes neither glFinish nor a timer query, so GPU work started here
    // may well be paid for later in the frame. That makes the STAGE figures a lower bound and the FRAME
    // PERIOD the honest number -- which is the one that matters, since the question is what opening the
    // desktop costs. Read them together: if the stages barely move and the period doubles, the cost is
    // GPU-side in the very work they enqueue.
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

    /** Frees the framebuffers this owns. They are {@code createOwned}, so no registry sweeps them. */
    void delete() {
        captureFbo.delete();
        blurA.delete();
        blurB.delete();
    }
}
