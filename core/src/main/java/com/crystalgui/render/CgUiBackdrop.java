package com.crystalgui.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
 *   blur      scale down until sigma is small, box-prefiltered; separable Gaussian, horizontal then
 *             vertical, with the tap count derived from sigma -- Skia's scale-then-blur, see blurredBackdrop
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
        // From the radius the PREVIOUS frame asked for, so a pair is built here and never mid-paint.
        // The first frame a new radius appears therefore runs at the scale the old one chose: a correct
        // blur at a slightly different quality, for one frame.
        blurScale = scaleFor(blurRadiusPx);
        targetsFor(blurScale);
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
     * The two targets of the separable blur, ping-ponged: the box prefilter, then one axis, then the
     * other, so which of the two holds the result depends on how many passes ran (see
     * {@link #blurredBackdrop}, which records it in {@link #blurResult}).
     *
     * <p>Both are the surface divided by {@link #blurScale}. Blurring at reduced resolution is not only
     * cheaper, it is what keeps the kernel well sampled — provided the source was REDUCED rather than
     * merely read at a stride, which is what the prefilter is for. The reduction is free quality here
     * rather than a compromise, because the output is by definition an image with no high frequencies
     * left in it.</p>
     */
    /**
     * One ping-pong pair per working scale, each built once and never rebuilt for a scale change.
     *
     * <p>A pair used to be resized whenever {@link #scaleFor} stepped, which is at a blur radius of
     * exactly 12 and 24 -- and a target rebuilt under a live frame flickers the whole surface for a few
     * frames, wherever the rebuild is done from. {@link #prepareFrame} moving it to frame start was not
     * enough. Keeping a pair per scale means a radius change SELECTS a target instead of rebuilding one,
     * and only a surface resize rebuilds anything.</p>
     */
    private final Map<Integer, CgFrameBuffer[]> blurTargets = new HashMap<>();

    /**
     * The pair for {@code scale}, built on first use at the surface's own fraction of that scale.
     *
     * <p>{@link #prepareFrame} calls this for the scale the passes will use, so the mid-paint call from
     * {@link #blurredBackdrop} only ever reads back what is already there -- a pair is never built with
     * our own bindings in flight.</p>
     */
    private CgFrameBuffer[] targetsFor(int scale) {
        int w = Math.max(1, Math.max(1, ctx.screenWidth) / scale);
        int h = Math.max(1, Math.max(1, ctx.screenHeight) / scale);
        CgFrameBuffer[] pair = blurTargets.get(scale);
        if (pair == null) {
            pair = new CgFrameBuffer[] {
                    CgFrameBuffer.createOwned("cgui_blur_a" + scale, w, h, CgUiPaintContext.LAYER_FORMAT),
                    CgFrameBuffer.createOwned("cgui_blur_b" + scale, w, h, CgUiPaintContext.LAYER_FORMAT),
            };
            blurTargets.put(scale, pair);
            for (CgFrameBuffer fbo : pair) ctx.warmUpLayer(fbo);
        } else if (pair[0].getWidth() != w || pair[0].getHeight() != h) {
            // Only a SURFACE resize reaches here; a radius change picks a different pair instead.
            for (CgFrameBuffer fbo : pair) {
                fbo.resize(w, h);
                ctx.warmUpLayer(fbo);
            }
        }
        return pair;
    }

    /**
     * How much smaller than the surface the blur runs at this frame: 1, 2 or 4.
     *
     * <p><b>Chosen from sigma, which is Skia's rule and the whole of what makes a large blur affordable
     * and a small one sharp.</b> A Gaussian is only well sampled while its taps sit about a texel apart,
     * so a big sigma at full resolution means a big kernel; Skia ({@code GrBlurUtils}) instead scales
     * the input down by powers of two until sigma is at most {@link #MAX_WORKING_SIGMA}, blurs there,
     * and scales back up. A small sigma stays at full resolution, where a quarter-res path would have
     * blurred it more than was asked by the reduction alone.</p>
     */
    private int blurScale = 4;

    /** The largest sigma, in working-resolution texels, a pass is asked to blur. Skia's is 4. */
    private static final float MAX_WORKING_SIGMA = 4f;

    /** Taps either side of the centre, at most — bounds the shader's loop. {@code 3 * MAX_WORKING_SIGMA} rounded up, with headroom. */
    static final int MAX_KERNEL_RADIUS = 16;

    /** The blur's REACH is three sigma: a tap beyond that carries under half a percent of the weight. */
    private static final float REACH_PER_SIGMA = 3f;

    /** The scale a radius wants, capped at the largest the 4-tap box prefilter reduces cleanly. */
    private static int scaleFor(float radiusPx) {
        float sigma = radiusPx / REACH_PER_SIGMA;
        int scale = 1;
        while (sigma / scale > MAX_WORKING_SIGMA && scale < 4) scale *= 2;
        return scale;
    }

    /** Which of the working pair holds the finished blur for {@link #blurFrame}. */
    @Nullable
    private CgFrameBuffer blurResult;

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

        // 1b. THE SCENE IS OPAQUE, AND SAYING SO IS WHAT MAKES THE REST OF THIS PIPELINE TRUE.
        //
        // Everything downstream is premultiplied: gui_blur carries alpha through its taps ("PREMULTIPLIED
        // ALPHA IS CARRIED THROUGH, NOT DISCARDED"), and gui_glass's cg_backdrop un-premultiplies with
        // `rgb /= max(a, 1/255)`. That contract is right for the LAYERS composited below, which really
        // are premultiplied. It is not right for the blit above, which is a raw glBlitFramebuffer of the
        // HOST's colour buffer -- and a host's alpha channel holds whatever its own rendering left there.
        // Minecraft's is not 1: its GUI layer draws blended, so an item sprite lands in the framebuffer
        // carrying a fractional alpha next to slot art carrying another.
        //
        // DIVIDE A BRIGHT PIXEL BY A FRACTIONAL ALPHA AND IT SATURATES. A gold nugget in the hotbar at
        // rgb(0.80, 0.65, 0.15) with a = 0.25 comes back (3.2, 2.6, 0.6), clips to near-white, and the
        // blur then spreads that over its neighbours -- so the item wears a white halo while the dark
        // slots beside it do not, because small numbers divided by the same alpha stay small. On screen
        // it reads as bloom, or as an HDR or a saturation fault, and it is none of them: a Gaussian
        // cannot brighten anything, so ANY local amplification in this path is arithmetic, and the only
        // division in the path is the un-premultiply.
        //
        // ONE ALPHA-ONLY CLEAR fixes it at the source instead of special-casing the consumer. An opaque
        // image is trivially premultiplied (rgb * 1 == rgb), so cg_backdrop's divide becomes a no-op that
        // still guards genuinely premultiplied content, and the layers composited below -- premultiplied
        // `over` onto an opaque destination -- leave alpha at 1 rather than at src_a^2 + (1 - src_a),
        // which is what straight-alpha blending would have left. Every stage's assumption is then true,
        // rather than one of them being true by luck about the host.
        //
        // IT CANNOT BE SEEN IN THE HARNESS, which is why it shipped. There the "scene" is our own clear
        // colour, so the capture's alpha is whatever we chose it to be and the divide is near enough to
        // harmless; gui_blur's own note about a dark fringe is the same channel read the other way. The
        // capture is the one place in the engine that samples a buffer we did not write.
        withoutScissor(() -> {
            try (CgGlScope ignored = CgGlState.save(CgGlSlot.COLOR_MASK)) {
                CgGL.glColorMask(false, false, false, true);
                captureFbo.clearColor(0f, 0f, 0f, 1f);
            }
        });

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
            // WHAT THE UI-SO-FAR ACTUALLY HOLDS OVER THE CAPTURED REGION, read before it is composited.
            // The whole pipeline rests on it being TRANSPARENT wherever nothing has drawn: premultiplied
            // `over` is dst = src + dst*(1 - src.a), so a transparent source leaves the scene alone and
            // an OPAQUE BLACK one erases it completely. Those two produce an identical-looking flat
            // panel downstream, and only the alpha channel tells them apart.
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
        // Declared, not bound by hand. @see CgUiPaintContext#blitLayer
        ctx.layerBlitMaterial.applyProperties(b -> b.sampler("_MainTex", 0, tex));
        ctx.withMaterial(ctx.layerBlitMaterial, () -> {
            ctx.poseStack.pushPose();
            ctx.poseStack.setIdentity();
            ctx.quad().at(0, 0).size(rw, rh).uv(u0, vTop, u1, vBottom).color(0xFFFFFFFF).submit();
            ctx.flush();
            // AND AGAIN AFTER THE FLUSH, because submit() only QUEUES. The material's render state is
            // uploaded by the bind, and the reading that matters is the one in force when the geometry
            // is actually drawn -- which is here. Measuring at submit time answers a question nobody
            // asked and answers it reassuringly.
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
     * Blurs the captured backdrop, once per frame, and hands back the result.
     *
     * <p><b>The blur is SHARED, like the capture.</b> Every consumer samples its own region out of one
     * blurred surface, so this runs once however many glass elements there are rather than once each.
     * The trade is that they share a radius - exactly right while the parameters come from a
     * stylesheet, and it would need a pass per distinct radius if that stops being true.</p>
     *
     * <h4>Scale, then blur — Skia's Gaussian, and why the previous version was not one</h4>
     *
     * <p>{@code radiusPx} is the REACH the sheet asks for; sigma is a third of it. The working scale
     * ({@link #blurScale}, decided in {@link #prepareFrame}) is the power of two that brings sigma to at
     * most {@link #MAX_WORKING_SIGMA} texels, and the kernel's radius in taps is {@code ceil(3 * sigma)}
     * at that scale with the taps ONE TEXEL APART — the two things that together define a well-sampled
     * Gaussian. The weights are the Gaussian evaluated at each tap and normalised over the taps
     * actually used, computed in the shader by the incremental recurrence (GPU Gems 3, ch. 40), so no
     * table is uploaded and a truncated tail cannot darken the result.</p>
     *
     * <p>The version this replaced had nine fixed taps and stretched the STEP to reach the radius —
     * six pixels apart at the taskbar's radius of 24, over a full-resolution source. Nine taps that skip
     * five pixels between them are a comb, not a kernel: a glyph stem survives wherever a tap happens to
     * land on it, so the horizontal pass let text through nearly intact and the vertical pass — which
     * happened to read the quarter-res intermediate and so WAS well sampled — smeared the survivors into
     * vertical streaks. Reported as "smudgy, not a proper blur" over the one backdrop with text in it.
     * The shader's own note said the taps were meant to land a texel apart over a reduced source; the
     * first pass had simply never been given one. {@code gui_downsample.shader} is the prefilter that
     * gives it one, and the kernel no longer stretches to compensate for anything.</p>
     *
     * <p>Three passes at a reduced scale (box prefilter, horizontal, vertical) and two at full scale,
     * where a prefilter would be a copy. All of them touch only the captured sub-rect — a 34px bar at
     * scale 4 is a few thousand texels per pass.</p>
     */
    @Nullable
    private CgTexture2D blurredBackdrop(float radiusPx) {
        if (radiusPx < 0.5f) return (CgTexture2D) captureFbo.getColorTexture(0);
        if (blurFrame == ctx.frameId && blurRadiusPx == radiusPx && blurResult != null) {
            if (PROBE) probeBlurSkipped++;
            return (CgTexture2D) blurResult.getColorTexture(0);
        }
        long probeB0 = PROBE ? System.nanoTime() : 0L;

        // The fraction of each target the capture actually occupies. Identical in all of them, because
        // they are all the same fraction of the screen. @see #capX0
        float fracW = capW / (float) Math.max(1, ctx.screenWidth);
        float fracH = capH / (float) Math.max(1, ctx.screenHeight);

        CgTexture2D captured = (CgTexture2D) captureFbo.getColorTexture(0);
        if (captured == null) return null;

        // Sigma at the working scale, and the taps that reach three of it. The radius is CLAMPED rather
        // than the scale raised past 4 (the box prefilter reduces 4x cleanly and no further), so a blur
        // asked to reach beyond 3 * MAX_KERNEL_RADIUS * 4 surface pixels comes back slightly narrower
        // than asked and still correctly normalised -- never broken, merely saturated.
        int scale = blurScale;
        float sigma = radiusPx / REACH_PER_SIGMA / scale;
        int taps = Math.max(1, Math.min(MAX_KERNEL_RADIUS, (int) Math.ceil(REACH_PER_SIGMA * sigma)));

        CgFrameBuffer[] pair = targetsFor(scale);
        CgFrameBuffer blurA = pair[0], blurB = pair[1];

        CgFrameBuffer result;
        if (scale > 1) {
            downsamplePass(captured, blurA, scale, fracW, fracH);
            CgTexture2D reduced = (CgTexture2D) blurA.getColorTexture(0);
            if (reduced == null) return null;
            blurPass(reduced, blurB, 1f, 0f, sigma, taps, fracW, fracH);
            CgTexture2D horizontal = (CgTexture2D) blurB.getColorTexture(0);
            if (horizontal == null) return null;
            blurPass(horizontal, blurA, 0f, 1f, sigma, taps, fracW, fracH);
            result = blurA;
        } else {
            blurPass(captured, blurA, 1f, 0f, sigma, taps, fracW, fracH);
            CgTexture2D horizontal = (CgTexture2D) blurA.getColorTexture(0);
            if (horizontal == null) return null;
            blurPass(horizontal, blurB, 0f, 1f, sigma, taps, fracW, fracH);
            result = blurB;
        }

        // EACH STAGE, because "the glass is a flat fill" is equally consistent with a capture that is
        // flat, a downsample that lost it, and a blur that produced nothing -- and they are three
        // different bugs. The capture is read at the region it actually wrote into; a whole-target read
        // would sample the untouched remainder and report every stage empty.

        blurFrame = ctx.frameId;
        blurRadiusPx = radiusPx;
        blurResult = result;
        if (PROBE) pBlur += System.nanoTime() - probeB0;
        return (CgTexture2D) result.getColorTexture(0);
    }

    /**
     * The box prefilter: the captured sub-rect reduced {@code scale} times into {@code target}.
     *
     * <p>Four bilinear taps at {@code +-scale/4} source texels from the output texel's centre. Each
     * bilinear fetch already averages a 2x2 block, so at scale 4 the four together average the whole
     * 4x4 block behind the output texel; at scale 2 they land on the four texel centres of its 2x2
     * block. A source decimated without this aliases, and an aliased source blurred is what a comb looks
     * like. @see gui_downsample.shader</p>
     */
    private void downsamplePass(CgTexture2D source, CgFrameBuffer target, int scale, float fracW, float fracH) {
        float halfU = 0.5f / Math.max(1, source.getWidth());
        float halfV = 0.5f / Math.max(1, source.getHeight());
        float offset = scale / 4f;
        ctx.downsampleMaterial.applyProperties(b -> {
            b.sampler("_MainTex", 0, source);
            b.vec2("_TexelSize", offset / Math.max(1, source.getWidth()), offset / Math.max(1, source.getHeight()));
            b.vec4("_Bounds", halfU, 1f - fracH + halfV, fracW - halfU, 1f - halfV);
        });
        runFilter(ctx.downsampleMaterial, target, fracW, fracH);
    }

    /**
     * One axis of the separable Gaussian, {@code source} to {@code target}, taps one source texel apart.
     *
     * <p>{@code dirU}/{@code dirV} pick the axis (one of them is 1, the other 0); the step is that many
     * SOURCE texels in UV, which is what "one texel apart" means whatever resolution the pass runs at.
     * {@code sigma} and {@code taps} are in the same texels. @see gui_blur.shader</p>
     */
    private void blurPass(CgTexture2D source, CgFrameBuffer target, float dirU, float dirV,
                          float sigma, int taps, float fracW, float fracH) {
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
            b.vec2("_Step", dirU / Math.max(1, source.getWidth()), dirV / Math.max(1, source.getHeight()));
            b.set1f("_Sigma", Math.max(0.25f, sigma));
            b.set1f("_Radius", taps);
            // The CAPTURED SUB-RECT, half a texel in. A tap landing outside it reads whatever the
            // sampler's clamp gives back, and outside the sub-rect that is the target's clear -
            // transparent black. Which is precisely how darkness gets dragged into a panel that had
            // colour behind it, with a hard boundary where the taps stop reaching, and is why this is
            // the sub-rect rather than the whole texture now that the capture is a region.
            b.vec4("_Bounds", halfU, 1f - fracH + halfV, fracW - halfU, 1f - halfV);
        });
        runFilter(ctx.blurMaterial, target, fracW, fracH);
    }

    /**
     * Draws one full-sub-rect filter pass with {@code material} into {@code target}.
     *
     * <p>The quad is drawn {@code uv(0, 1, fracW, 1 - fracH)} - the SAME flip {@link #drawOver} uses, and
     * the agreement is load-bearing rather than incidental. A layer FBO is bottom-left origin while the
     * UI is top-left, so every full-surface blit into one carries the flip. The pyramid this replaced
     * drew its passes unflipped and got away with it only because an equal number of down and up passes
     * cancelled: correct output from two errors, which is the kind of thing that stays true right up
     * until somebody changes the level count.</p>
     */
    private void runFilter(CgMaterial material, CgFrameBuffer target, float fracW, float fracH) {
        int qw = Math.max(1, Math.round(target.getWidth() * fracW));
        int qh = Math.max(1, Math.round(target.getHeight() * fracH));
        ctx.beginLayerFbo(target);
        // The ortho as well as the viewport: beginLayerFbo sets only the viewport, which is enough for a
        // screen-sized layer and not for a blur target at a working scale above 1 -- a full-size quad
        // lands on a fraction of it and the rest keeps the clear. @see CgUiPaintContext#compositeMask
        CgFrameData fd = CgRenderPipeline.getInstance().getFrameData();
        Matrix4f enclosingProj = new Matrix4f(fd.projMatrix);
        int enclosingW = fd.viewportW, enclosingH = fd.viewportH;
        fd.projMatrix.identity().ortho(0, target.getWidth(), target.getHeight(), 0, -1, 1);
        fd.viewportW = target.getWidth();
        fd.viewportH = target.getHeight();
        CgRenderPipeline.getInstance().prepareFrame();
        try {
            withoutScissor(() -> ctx.withMaterial(material, () -> {
                ctx.poseStack.pushPose();
                ctx.poseStack.setIdentity();
                ctx.quad().at(0, 0).size(qw, qh)
                      .uv(0f, 1f, fracW, 1f - fracH).color(0xFFFFFFFF).submit();
                ctx.flush();
                ctx.poseStack.popPose();
            }));
        } finally {
            fd.projMatrix.set(enclosingProj);
            fd.viewportW = enclosingW;
            fd.viewportH = enclosingH;
            CgRenderPipeline.getInstance().prepareFrame();
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
        for (CgFrameBuffer[] pair : blurTargets.values()) {
            pair[0].delete();
            pair[1].delete();
        }
        blurTargets.clear();
    }
}
