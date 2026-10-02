package com.crystalgui.ui.box;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.InkOverflow;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.LayerRegion;
import com.crystalgui.render.RetainedLayer;
import com.crystalgui.render.Surface;
import com.crystalgui.render.texture.*;
import com.crystalgui.render.texture.CgUiRect;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.BoxOrigin;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgraphics.api.PoseStack;
import dev.vfyjxf.taffy.geometry.FloatRect;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.joml.Matrix4f;

/**
 * The paint pass over a {@link BoxTree}: every box in paint order, each drawn in its OWN space with
 * the pose set from the matrix layout composed — so the picture and the hit-test read one
 * definition of where a box is, and nothing here writes a matrix anything else will read.
 *
 * <p>The old engine painted in absolute layout coordinates with the pose carrying transforms and
 * scroll, and reconciled a cached world matrix against the pose it was drawn with — which is what
 * made a subtree drawn twice corrupt hit-testing unless the pass said it was a copy. Here a box's
 * pose is {@code base × localToWorld}, where {@code base} is whatever the caller had on the stack
 * (the surface's {@code uiScale}), and a thumbnail is a second box with a second matrix (5.4).</p>
 *
 * <p>The box model is the painter's: background (fill and border together, under the children as
 * CSS stacks them), then the node's {@link UIElement#paintContent content}, the children, the node's
 * {@link UIElement#paintDecoration decoration}, {@code overlay}, and {@code outline} last. A fractional
 * {@code opacity} or a rounded {@code overflow: hidden} routes the whole box through the paint
 * context's layer-FBO path; a square clip is a scissor. The drawables, the SDF rounded rect and the
 * compositing are the backend's — this class only decides what is drawn where.</p>
 *
 * <h3>The children are painted in CSS's stacking order</h3>
 *
 * <p>A {@linkplain Box#isStackingContext stacking context} paints, after its own background, its negative
 * {@code z-index} boxes, then its normal flow, then its {@code auto}/{@code 0} and positive boxes (CSS 2.1
 * Appendix E, from its {@link StackingOrder}). A box that is not one paints its normal flow alone: its z-ordered
 * descendants are the context's to paint, clipped on the way by every box between the two. Hit-testing searches
 * the same order backwards. @see Box#hitTest</p>
 */
public final class BoxPainter {

    private static final int WHITE = 0xFFFFFFFF;

    /** One per {@link BoxTree}: the scratch below is the tree's, so two documents can paint at once. */
    BoxPainter() {
    }

    /** Paints the whole tree with the pose on the stack as the surface transform. */
    void paint(BoxTree tree, CgUiPaintContext ctx) {
        Box root = tree.root();
        if (root == null) return;
        Matrix4f base = new Matrix4f(ctx.getPoseStack().last().pose());
        paintBox(root, ctx, base, true);
    }

    /**
     * Paints ONE box and what it hosts, with the pose on the stack as the surface transform.
     *
     * <p>For a caller drawing a subtree somewhere other than where it lives — a window photographing
     * itself into an off-screen target. Nothing about the boxes is changed by it: the pose is
     * {@code base × localToWorld}, computed per box and never written back, so drawing a subtree a
     * second time cannot disturb where hit-testing thinks it is. That is the whole of what the old
     * engine's {@code CgUiPaintContext.mirrored} counter existed to protect, and why there is no
     * counterpart here.</p>
     *
     * <p>A LIVE second copy wants {@link BoxTree#mirror} instead, which lays the subtree out again and
     * gives the copy boxes of its own; this is for a one-shot into a target the caller owns.</p>
     */
    public static void paintSubtree(Box box, CgUiPaintContext ctx) {
        BoxPainter painter = box.tree().painter;
        Matrix4f base = new Matrix4f(ctx.getPoseStack().last().pose());
        // @see CgUiPaintContext#withoutRetention -- a copy drawn at other coordinates must not become
        // what the live tree thinks it last painted.
        // AS A CONTEXT whether or not it is one: a subtree drawn on its own paints everything under it.
        ctx.withoutRetention(() -> painter.paintBox(box, ctx, base, true));
    }

    /**
     * One box and what it paints. {@code asContext}: its stacking context's lists as well as its normal flow — true
     * for a stacking context and for the root of a paint.
     */
    private void paintBox(Box box, CgUiPaintContext ctx, Matrix4f base, boolean asContext) {
        float opacity = box.opacity();
        if (opacity <= 0f) return;
        // NOTHING OF IT CAN LAND: a row scrolled past its list's edge draws nothing the scissor would keep.
        if (CgUiPaintContext.CULL) {
            inkThrough(box, targetOf(ctx, base));
            if (ctx.outsideClip(ink[0], ink[1], ink[2], ink[3])) {
                CgTrace.add(UiTrace.FRAME, "culled", 1);
                return;
            }
        }
        // A BOX A COMPOSITOR MOVES (`will-change: transform`) is recorded in its own space, under a node that is a
        // whole-pixel translation to its corner: a move is the node's value. A translation for the reason scrolled
        // content's node is one. @see #paintChildren
        if (box.willChangeTransform()) {
            Matrix4f origin = nodeOrigin.set(base).mul(box.localToWorld());
            movedWorld.set(origin);
            // AT ITS CORNER AT REST: its own transform is drawn over the node, so a flight leaves the node where it is.
            if (box.isTransformed()) origin.set(base).mul(box.restToWorld());
            float x = Math.round(origin.m30()), y = Math.round(origin.m31());
            int moved = ctx.addNode(origin.translation(x, y, 0f), true);
            if (moved != 0) {
                box.noteMovedNode(moved, ctx.frameId(), movedWorld);
                int outer = ctx.enterNode(moved);
                try {
                    Matrix4f inner = new Matrix4f(base).translateLocal(-x, -y, 0f);
                    if (!paintSurface(box, ctx, inner, asContext, opacity)) paintBoxIn(box, ctx, inner, asContext, opacity, false);
                } finally {
                    ctx.enterNode(outer);
                }
                return;
            }
        }
        paintBoxIn(box, ctx, base, asContext, opacity, false);
    }

    /**
     * A box the compositor moves, drawn into its surface and composited from it under its node, its opacity and any
     * compositor fade applied there (render-graph G7): false, having drawn nothing, where it has none.
     */
    private boolean paintSurface(Box box, CgUiPaintContext ctx, Matrix4f base, boolean asContext, float opacity) {
        // AT REST: its own transform goes into the composite, so a flight or a turn keeps the picture. Where it has
        // one, `base` becomes base x turn^-1, turn being the transform in world space, and the composite base x turn x
        // base^-1 in the draw space.
        Matrix4f turn = null;
        if (box.isTransformed()) {
            // Fresh, not scratch: the walk below may reach another surface while these are still wanted.
            Matrix4f world = new Matrix4f(box.restToWorld()).invert().mulLocal(box.localToWorld());
            turn = new Matrix4f(base).mul(world).mul(surfaceScratch.set(base).invert());
            base = new Matrix4f(base).mul(world.invert());
        }
        // The world ink carries the transform, and the rest base takes it back out.
        inkThrough(box, targetOf(ctx, base));
        LayerRegion region = ctx.surfaceRegion(ink[0], ink[1], ink[2], ink[3]);
        // KEYED BY ITS NODE, so a window shown again finds its picture; a mirror's box is not its node's.
        Object key = box.node().box() == box ? box.node() : box;
        Surface surface = ctx.surface(key, region);
        if (surface == null) return false;
        // KEPT AGAINST ITS NODE: the region less the node's own place in the target, so a move keeps the picture.
        Matrix4f toTarget = ctx.drawToTarget();
        int nodeX = region.x() - Math.round(toTarget.m30()), nodeY = region.y() - Math.round(toTarget.m31());
        long epoch = ctx.replayEpoch(), stacking = box.tree().stackingEpoch();
        boolean holds = box.retainable()
                && surface.holds(box, box.innerRevision(), nodeX, nodeY, region, epoch, stacking);
        if (holds && !CgUiPaintContext.DAMAGE_CHECK) {
            // NOTHING UNDER IT CHANGED: one composite, and none of its boxes paint to note themselves.
            CgTrace.add(UiTrace.FRAME, "surfaces-kept", 1);
            ctx.notePainted(ctx.targetToDraw(), region.x(), region.y(), region.x() + region.width(),
                    region.y() + region.height());
        } else {
            if (!holds) {
                CgTrace.add(UiTrace.FRAME, !box.retainable() ? "surface-miss-dynamic"
                        : surface.missed(box, box.innerRevision(), nodeX, nodeY, region, epoch, stacking), 1);
            }
            ctx.beginSurface(surface, region, !surface.drewAt(box, nodeX, nodeY, region));
            paintBoxIn(box, ctx, layerBase(ctx, base, region), asContext, 1f, true);
            // CHECK MODE: a surface that would have been kept was walked, and must have found nothing to draw.
            if (ctx.endSurface() && holds) {
                CgTrace.add(UiTrace.FRAME, "surface-check-kept-damaged", 1);
                CrystalGuiCore.LOGGER.warn("[damage-check] {} would have been kept, but its walk damaged it",
                        layerLabel(box.node()));
            }
            if (CgUiPaintContext.DAMAGE_CHECK) checkDamage(box, ctx, base, region, surface, asContext);
            surface.drew(box, box.innerRevision(), nodeX, nodeY, region, epoch, stacking);
            // ITS PICTURE: the border box in the texture, what a thumbnail draws.
            boundsThrough(0f, 0f, box.width(), box.height(), surfaceScratch.set(targetOf(ctx, base)).mul(box.localToWorld()));
            surface.pictured(ink[0] - region.x(), ink[1] - region.y(), ink[2] - region.x(), ink[3] - region.y(),
                    box.width(), box.height());
            CgTrace.add(UiTrace.FRAME, "surfaces-painted", 1);
        }
        box.noteFadedNode(ctx.blitLayer(surface.target(), opacity, region, box.animatesOnCompositor(), turn),
                ctx.frameId());
        if (turn == null) {
            ctx.damageSurface(region);
        } else {
            boundsThrough(region.x(), region.y(), region.x() + region.width(), region.y() + region.height(),
                    surfaceScratch.set(ctx.drawToTarget()).mul(turn).mul(ctx.targetToDraw()));
            ctx.damageSurface(ctx.surfaceRegion(ink[0], ink[1], ink[2], ink[3]));
        }
        return true;
    }

    private final Matrix4f surfaceScratch = new Matrix4f();

    /**
     * Check mode: the box painted again, whole, into a texture beside its surface -- replaying, and placing nothing --
     * and the two compared once both are drawn. A difference is a change the surface's damage missed.
     */
    private void checkDamage(Box box, CgUiPaintContext ctx, Matrix4f base, LayerRegion region, Surface surface,
                             boolean asContext) {
        ctx.beginDamageCheck(surface, region);
        Matrix4f at = new Matrix4f(base);
        ctx.withoutRetention(() -> paintBoxIn(box, ctx, layerBase(ctx, at, region), asContext, 1f, true));
        ctx.endLayerFbo();
        ctx.compareSurface(surface, region, layerLabel(box.node()));
    }

    /**
     * {@link #paintBox} once it is known to paint, in the node it draws in. {@code surface}: drawn into its own
     * surface, whose composite takes its opacity and fade, so it opens no group layer for either.
     */
    private void paintBoxIn(Box box, CgUiPaintContext ctx, Matrix4f base, boolean asContext, float opacity,
                            boolean surface) {
        UIElement node = box.node();
        ComputedStyle style = node.computedStyle();
        PoseStack pose = ctx.getPoseStack();
        pose.pushPose();
        pose.last().pose().set(base).mul(box.localToWorld());
        // WHAT THIS BOX PAINTS, for the backdrop: a glass element drawn later recaptures only if this lands on
        // what it samples. Noted before and after, since a capture taken by a child in between clears it -- but
        // a glass box only after: noted before its own draw, it found itself painted over and recaptured always.
        if (style.get(StylePropertyRegistry.BACKDROP_FILTER) == null) notePainted(box, ctx, pose.last().pose());
        try {
            Radii radii = radiiOf(style, box.width(), box.height());
            boolean clips = box.clips();
            boolean mask = clips && (!radii.isZero() || style.get(StylePropertyRegistry.MASK) != CgUiDrawable.EMPTY);
            boolean scissor = clips && !mask;
            // A MASK WITH NOTHING TO MASK. The multiply applies to the CHILDREN's layer and to nothing
            // else, so a childless box was opening a layer, drawing into it and compositing it back at
            // opacity 1 -- the whole apparatus for an identity. Rounded `overflow: hidden` is on almost
            // every surface in this UI, and most of the leaves wearing it have no children at all.
            if (mask && box.children().isEmpty() && !CgUiPaintContext.LEGACY_LAYERS) {
                CgTrace.add(UiTrace.FRAME, "masks-elided", 1);
                mask = false;
            }
            // A compositor fading the box needs its effect node even at full opacity.
            boolean fades = !surface && box.animatesOnCompositor();
            boolean needsLayer = opacity < 1f || mask || fades;

            // AND AN OPACITY THAT CANNOT SELF-OVERLAP folds into the draw instead of flattening a
            // subtree -- Skia's rule, and Flutter's advice to colour a container rather than wrap it in
            // an `Opacity`. Group opacity differs from per-primitive opacity only where two primitives
            // cover the same pixel; where there is only one, they are the same number.
            if (needsLayer && !mask && !fades && !CgUiPaintContext.LEGACY_LAYERS && foldsOpacity(box, style, node)) {
                CgTrace.add(UiTrace.FRAME, "layers-elided", 1);
                float previousOpacity = ctx.pushLayerOpacity(opacity);
                try {
                    ctx.beginSegment();
                    paintSelf(box, style, ctx, radii);
                    paintOverlay(box, style, ctx);
                    paintOutline(box, style, ctx);
                    ctx.endSegment();
                    place(box, ctx, BoxReplay.BEFORE, true);
                } finally {
                    ctx.popLayerOpacity(previousOpacity);
                }
                return;
            }

            if (!needsLayer) {
                paintOwnBefore(box, style, node, ctx, radii);
                paintChildren(box, ctx, base, scissor, asContext);
                paintOwnAfter(box, style, node, ctx);
                return;
            }

            // A LAYER, SIZED TO WHAT GOES IN IT. The region is the subtree's ink bounds through the
            // pose, already intersected with whatever is clipping -- so an element wholly scrolled out
            // of its container costs nothing at all here, and one that is 20px wide costs 20px rather
            // than a screen.
            // WHAT THE LAYER IS FOR. `layers=17` is a number nobody can act on: a mask layer is two
            // targets and a composite, an opacity layer is one, and the two are removed by different
            // things -- a radius that need not clip, or an opacity that could fold.
            // A mask's own layers are counted where they are opened: most masks are a rounded clip and open none.
            if (!mask || opacity < 1f) CgTrace.add(UiTrace.FRAME, "layers-opacity", 1);
            LayerRegion region = regionOf(box, ctx, base);
            if (region.isEmpty()) return;
            // WHO PAYS THE FILL, on the blame channel: `layer-clear-kpx` says how much, and this which element.
            if (CgTrace.isEnabled(UiTrace.BLAME)) {
                CgTrace.add(UiTrace.FRAME, "~layer-kpx " + (mask ? "mask " : "opacity ") + layerLabel(node),
                        (long) region.width() * region.height() / 1000L);
            }

            // AND IF NOTHING UNDER IT MOVED, THE PICTURE IS STILL THERE. The whole of what a frame owes
            // an unchanged subtree is one composited quad; the clear, the walk and every draw beneath
            // are the difference between two frames, and there is none.
            // THE REFUSAL IS COUNTED AS WELL AS THE HIT. A readout showing seventeen layers and no
            // reuse reads as a broken cache; most of the time nothing asked it, because a subtree that
            // repaints itself may not be kept. @see Box#retainable
            RetainedLayer keep = null;
            // A box the compositor animates keeps its picture for the flight, dynamic content and all, as a window
            // manager's does: every frame of it is then one composite.
            if (box.retainable() || fades) keep = ctx.retain(box, region, box.subtreeRevision());
            else CgTrace.add(UiTrace.FRAME, "retain-dynamic", 1);
            if (keep != null && keep.isFresh()) {
                // A WHOLE SUBTREE IN ONE COMPOSITE, and none of its boxes paint to note themselves.
                ctx.notePainted(ctx.targetToDraw(), region.x(), region.y(), region.x() + region.width(),
                        region.y() + region.height());
                box.noteFadedNode(ctx.blitLayer(keep.target(), opacity, region, fades), ctx.frameId());
                ctx.damageSurface(region);
                return;
            }

            // A MASK AT FULL OPACITY needs no layer around the box: grouping at 1 is plain painter's order, so the
            // box paints into the target and only its children go through the mask. A layer as large as a whole
            // editor cleared and blitted once less a frame.
            if (mask && opacity >= 1f && keep == null && !fades && !CgUiPaintContext.LEGACY_LAYERS) {
                paintMaskedChildrenOnly(box, style, node, ctx, base, radii, region, asContext);
                return;
            }

            // The layer's own origin: its pixel (0,0) is the region's corner, so everything drawn
            // inside it -- this box and every descendant -- goes through a base shifted to match.
            Matrix4f inner = layerBase(ctx, base, region);
            pose.last().pose().set(inner).mul(box.localToWorld());
            LayerRegion inside = region.atOrigin();

            // The subtree blends as one unit before opacity applies, and a mask multiplies only the
            // CHILDREN -- the box's own background is composited unmasked underneath.
            CgGraphTexture subtreeFbo = keep != null
                    ? ctx.beginLayerFbo(keep.target(), region)
                    : ctx.beginLayerFbo(region);
            paintOwnBefore(box, style, node, ctx, radii);
            if (mask && clipsAsShape(box, style) && pushShapeClip(box, style, ctx)) {
                try {
                    paintChildren(box, ctx, inner, axisAligned(ctx), asContext);
                } finally {
                    ctx.popRoundedClip();
                }
            } else if (mask) {
                CgTrace.add(UiTrace.FRAME, "layers-mask", 1);
                CgGraphTexture childrenFbo = ctx.beginLayerFbo(inside);
                paintChildren(box, ctx, inner, false, asContext);
                CgGraphTexture maskFbo = ctx.beginLayerFbo(inside);
                paintMask(box, style, ctx);
                ctx.endLayerFbo();
                ctx.compositeMask(childrenFbo, maskFbo, inside);
                ctx.endLayerFbo();
                ctx.blitLayer(childrenFbo, 1f, inside);
            } else {
                paintChildren(box, ctx, inner, scissor, asContext);
            }
            // Inside the layer, so the outline fades with the box: CSS puts it in the opacity group.
            paintOwnAfter(box, style, node, ctx);
            ctx.endLayerFbo();
            if (keep != null) keep.painted();
            box.noteFadedNode(ctx.blitLayer(subtreeFbo, opacity, region, fades), ctx.frameId());
            ctx.damageSurface(region);
        } finally {
            painted.set(base).mul(box.localToWorld());
            notePainted(box, ctx, painted);
            pose.popPose();
        }
    }

    /**
     * A masking box at opacity 1: itself straight into the target, then its children clipped to the mask -- by a
     * rounded clip when the mask is the box's own shape, else through a layer multiplied by the mask -- then its
     * decoration over both. The pose on entry is the box's own, unshifted.
     */
    private void paintMaskedChildrenOnly(Box box, ComputedStyle style, UIElement node, CgUiPaintContext ctx,
                                                Matrix4f base, Radii radii, LayerRegion region, boolean asContext) {
        PoseStack pose = ctx.getPoseStack();
        paintOwnBefore(box, style, node, ctx, radii);

        if (clipsAsShape(box, style) && pushShapeClip(box, style, ctx)) {
            try {
                paintChildren(box, ctx, base, axisAligned(ctx), asContext);
            } finally {
                ctx.popRoundedClip();
            }
        } else {
            CgTrace.add(UiTrace.FRAME, "layers-mask", 1);
            Matrix4f inner = layerBase(ctx, base, region);
            pose.last().pose().set(inner).mul(box.localToWorld());
            LayerRegion inside = region.atOrigin();
            CgGraphTexture childrenFbo = ctx.beginLayerFbo(region);
            paintChildren(box, ctx, inner, false, asContext);
            CgGraphTexture maskFbo = ctx.beginLayerFbo(inside);
            paintMask(box, style, ctx);
            ctx.endLayerFbo();
            ctx.compositeMask(childrenFbo, maskFbo, inside);
            ctx.endLayerFbo();
            ctx.blitLayer(childrenFbo, 1f, region);
            ctx.damageSurface(region);
            pose.last().pose().set(base).mul(box.localToWorld());
        }
        paintOwnAfter(box, style, node, ctx);
    }

    /** A box's own paint under its children, as one segment. @see CgUiPaintContext#beginSegment */
    private void paintOwnBefore(Box box, ComputedStyle style, UIElement node, CgUiPaintContext ctx, Radii radii) {
        int key = keyOf(box, node, ctx, BoxReplay.BEFORE);
        if (key == SAME && !checked(box, BoxReplay.BEFORE) && box.replay.replay(BoxReplay.BEFORE, ctx)) {
            place(box, ctx, BoxReplay.BEFORE, false);
            return;
        }
        ctx.beginSegment();
        paintSelf(box, style, ctx, radii);
        node.paintContent(ctx, box);
        endSegment(box, node, ctx, BoxReplay.BEFORE, key);
        place(box, ctx, BoxReplay.BEFORE, true);
    }

    /** A box's own paint over its children, as one segment. */
    private void paintOwnAfter(Box box, ComputedStyle style, UIElement node, CgUiPaintContext ctx) {
        int key = keyOf(box, node, ctx, BoxReplay.AFTER);
        if (key == SAME && !checked(box, BoxReplay.AFTER) && box.replay.replay(BoxReplay.AFTER, ctx)) {
            place(box, ctx, BoxReplay.AFTER, false);
            return;
        }
        ctx.beginSegment();
        node.paintDecoration(ctx, box);
        paintOverlay(box, style, ctx);
        paintOutline(box, style, ctx);
        endSegment(box, node, ctx, BoxReplay.AFTER, key);
        place(box, ctx, BoxReplay.AFTER, true);
    }

    /**
     * Where {@code segment} just drew in the surface being walked, if it drew straight into one: the bounds of its
     * draws, or its own ink where those are not known, in the surface's pixels. {@code changed}: painted, not replayed.
     * @see Surface
     */
    private void place(Box box, CgUiPaintContext ctx, int segment, boolean changed) {
        Surface surface = ctx.walkingSurface();
        if (surface == null) return;
        // WHAT IT DREW, not what it might: an overlay sized to a whole canvas that drew one outline damages the outline.
        if (!ctx.segmentDrawn(ink)) {
            boundsThrough(box.localInkL, box.localInkT, box.localInkR, box.localInkB, ctx.targetPose());
        }
        if (box.replay == null) box.replay = new BoxReplay();
        box.replay.place(surface, segment, (int) Math.floor(ink[0]), (int) Math.floor(ink[1]),
                (int) Math.ceil(ink[2]), (int) Math.ceil(ink[3]), changed, ctx.frameId());
    }

    /** Check mode: a segment whose key holds is painted and compared rather than replayed. */
    private static boolean checked(Box box, int segment) {
        return CgUiPaintContext.REPLAY_CHECK && box.replay.kept(segment);
    }

    /** Ends a box's segment, keeping what it recorded when a key speaks for it, or comparing it in check mode. */
    private static void endSegment(Box box, UIElement node, CgUiPaintContext ctx, int segment, int key) {
        if (key == UNKEYED) {
            ctx.endSegment();
        } else if (key == SAME && checked(box, segment)) {
            String differs = box.replay.check(segment, ctx);
            if (differs == null) return;
            CgTrace.add(UiTrace.FRAME, "replay-check-differs", 1);
            CrystalGuiCore.LOGGER.warn("[replay-check] {} drew {} its children otherwise under an unchanged key: {}",
                    layerLabel(node), segment == BoxReplay.BEFORE ? "under" : "over", differs);
        } else {
            box.replay.kept(segment, ctx.endSegment(box.replay.stretch(segment)));
        }
    }

    /** A layer's owner as a profile names it: its tag, then its first class. */
    private static String layerLabel(UIElement node) {
        String tag = node.tagName();
        for (String cls : node.getClasses()) return tag + "." + cls;
        return tag;
    }

    /** @see CgUiPaintContext#notePainted -- the box's own ink, as the tree composed it. */
    private static void notePainted(Box box, CgUiPaintContext ctx, Matrix4f pose) {
        ctx.notePainted(pose, box.localInkL, box.localInkT, box.localInkR, box.localInkB);
    }

    /** {@link #notePainted}'s scratch. Read immediately: a tree paints one box at a time. */
    private final Matrix4f painted = new Matrix4f();

    /**
     * Whether {@code opacity} can be multiplied into this box's own draw rather than flattening it
     * through a layer first.
     *
     * <p>It can when nothing the box paints can land on top of anything else it paints: one background,
     * or one overlay, or one outline, and no children. Two primitives over one pixel is exactly where
     * group opacity and per-primitive opacity part company — {@code 0.5} over {@code 0.5} composites to
     * {@code 0.75} drawn separately and to {@code 0.5} drawn as a group.</p>
     *
     * <p><b>A node that paints its own content is never folded</b>, even a leaf, because
     * {@code _LayerOpacity} is a property of the materials in this repository and CrystalGraphics' text
     * material does not carry it — a folded label would simply ignore the fade. That is also why the
     * question is asked of the CLASS rather than of the style: a subclass painting by hand is invisible
     * from here otherwise. {@code backdrop-filter} is excluded for its own reason: it composites what is
     * behind the element, which is not this element's paint to scale.</p>
     */
    private static boolean foldsOpacity(Box box, ComputedStyle style, UIElement node) {
        if (!box.children().isEmpty()) return false;
        if (node.paintsItsOwnContent()) return false;
        if (style.get(StylePropertyRegistry.BACKDROP_FILTER) != null) return false;

        int primitives = 0;
        CgUiDrawable background = style.get(StylePropertyRegistry.BACKGROUND);
        CgUiDrawable overlay = style.get(StylePropertyRegistry.OVERLAY);
        // A drawable that covers a pixel twice is already two primitives on its own -- a stack, a
        // cross-fade, a vector whose paths cross. @see CgUiDrawable#drawsOnePrimitive
        if (!background.drawsOnePrimitive() || !overlay.drawsOnePrimitive()) return false;
        if (background != CgUiDrawable.EMPTY || style.isSet(StylePropertyRegistry.BACKGROUND_COLOR)) primitives++;
        if (overlay != CgUiDrawable.EMPTY) primitives++;
        LengthPercent stroke = style.get(StylePropertyRegistry.OUTLINE_WIDTH);
        if (style.get(StylePropertyRegistry.OUTLINE) != CgUiDrawable.EMPTY
                || stroke != null && stroke.resolve(box.width()) > 0f) primitives++;
        return primitives <= 1;
    }

    /**
     * Where this box's layer goes in the current target, and how big it needs to be: its subtree's
     * world ink bounds carried through {@code base} into the target's own physical pixels, then
     * clipped by {@link CgUiPaintContext#layerRegion}.
     *
     * <p>{@code base} is affine and usually a scale plus a translation, so the four transformed corners
     * bound the rectangle exactly; under a rotation they bound it conservatively, which is the right
     * answer for an allocation.</p>
     */
    private LayerRegion regionOf(Box box, CgUiPaintContext ctx, Matrix4f base) {
        inkThrough(box, targetOf(ctx, base));
        return ctx.layerRegion(ink[0], ink[1], ink[2], ink[3]);
    }

    /**
     * The box's subtree ink bounds carried through {@code base} into the target's pixels, written to {@link #ink} as
     * {@code x0, y0, x1, y1}. Asked per box by the cull, so into scratch rather than a new rectangle.
     */
    private void inkThrough(Box box, Matrix4f base) {
        boundsThrough(box.inkX0(), box.inkY0(), box.inkX1(), box.inkY1(), base);
    }

    /** The rectangle {@code (x0, y0)-(x1, y1)} through {@code base}, its bounding box written to {@link #ink}. */
    private void boundsThrough(float x0, float y0, float x1, float y1, Matrix4f base) {
        float m00 = base.m00(), m10 = base.m10(), m30 = base.m30();
        float m01 = base.m01(), m11 = base.m11(), m31 = base.m31();
        float ax = m00 * x0 + m10 * y0 + m30, ay = m01 * x0 + m11 * y0 + m31;
        float bx = m00 * x1 + m10 * y0 + m30, by = m01 * x1 + m11 * y0 + m31;
        float cx = m00 * x1 + m10 * y1 + m30, cy = m01 * x1 + m11 * y1 + m31;
        float dx = m00 * x0 + m10 * y1 + m30, dy = m01 * x0 + m11 * y1 + m31;
        ink[0] = Math.min(Math.min(ax, bx), Math.min(cx, dx));
        ink[1] = Math.min(Math.min(ay, by), Math.min(cy, dy));
        ink[2] = Math.max(Math.max(ax, bx), Math.max(cx, dx));
        ink[3] = Math.max(Math.max(ay, by), Math.max(cy, dy));
    }

    /** {@link #keyOf}'s answers: no key speaks for the segment, it differs from last time, or it is the same. */
    private static final int UNKEYED = 0, CHANGED = 1, SAME = 2;

    /**
     * Whether {@code segment} would draw what it drew last time (render-graph G6.2), counted: `segments-unchanged`
     * against `-changed`, and the two kinds a key cannot speak for, `-dynamic` (a widget painting what the tree
     * cannot see) and `-unkeyed` (a draw space that is not a translation of the target).
     */
    private int keyOf(Box box, UIElement node, CgUiPaintContext ctx, int segment) {
        if (node.paintsDynamically()) {
            CgTrace.add(UiTrace.FRAME, "segments-dynamic", 1);
            if (box.replay != null) box.replay.forget(segment);
            return UNKEYED;
        }
        Matrix4f pose = ctx.getPoseStack().last().pose();
        if (!ctx.clipInDraw(segmentClip)) {
            CgTrace.add(UiTrace.FRAME, "segments-unkeyed", 1);
            if (box.replay != null) box.replay.forget(segment);
            return UNKEYED;
        }
        boundsThrough(box.localInkL, box.localInkT, box.localInkR, box.localInkB, pose);
        float[] k = segmentKey;
        k[0] = pose.m00();
        k[1] = pose.m01();
        k[2] = pose.m10();
        k[3] = pose.m11();
        k[4] = pose.m30();
        k[5] = pose.m31();
        k[6] = Math.max(ink[0], segmentClip[0]);
        k[7] = Math.max(ink[1], segmentClip[1]);
        k[8] = Math.min(ink[2], segmentClip[2]);
        k[9] = Math.min(ink[3], segmentClip[3]);
        k[10] = ctx.layerOpacity();
        if (box.replay == null) box.replay = new BoxReplay();
        boolean same = box.replay.sameAs(segment, k, box.contentRevision, ctx.replayEpoch());
        CgTrace.add(UiTrace.FRAME, same ? "segments-unchanged" : "segments-changed", 1);
        return same ? SAME : CHANGED;
    }

    private final float[] segmentKey = new float[BoxReplay.KEY_FLOATS], segmentClip = new float[4];

    /** {@link #inkThrough}'s answer. Read immediately: a tree paints one box at a time. */
    private final float[] ink = new float[4];

    /**
     * What {@code box} hosts, inside its clip: its negative {@code z-index} boxes, its normal flow and its other
     * z-ordered boxes when it paints as a context, and its normal flow alone otherwise.
     */
    private void paintChildren(Box box, CgUiPaintContext ctx, Matrix4f base, boolean scissor,
                                      boolean asContext) {
        if (box.children().isEmpty()) return;
        if (scissor) {
            // The PADDING box, as CSS clips: border excluded, padding included. In this box's own
            // space, and the context quantises it once in physical pixels through the pose.
            pushPaddingScissor(box, ctx);
        }
        // SCROLLED CONTENT IS RECORDED IN ITS OWN SPACE, under a node whose value is the scroll: a compositor scrolls
        // it by moving the node, with nothing recorded again. A scroll-exempt child does not move with it.
        // A TRANSLATION BY WHOLE PIXELS, with the scale left in the pose: text snaps to the pixel grid through the pose,
        // and a node scaling to layout units snapped it to every second pixel at a uiScale of 2.
        int content = 0;
        Matrix4f contentBase = base;
        if (box.isScrollContainer() && (box.maxScrollLeft() > 0f || box.maxScrollTop() > 0f)) {
            Matrix4f origin = nodeOrigin.set(base).mul(box.localToWorld())
                    .translate(-box.scrollLeft(), -box.scrollTop(), 0f);
            float x = Math.round(origin.m30()), y = Math.round(origin.m31());
            content = ctx.addNode(origin.translation(x, y, 0f), true);
            if (content != 0) {
                contentBase = new Matrix4f(base).translateLocal(-x, -y, 0f);
                box.noteScrolledNode(content, ctx.frameId());
            }
        }
        int outer = ctx.spatialNode();
        try {
            StackingOrder order = asContext ? box.stackingOrder() : null;
            if (order != null) paintLiftedIn(order.negative, box, ctx, content, contentBase, outer);
            List<Box> children = box.children();
            for (int i = 0; i < children.size(); i++) {
                Box child = children.get(i);
                if (child.isZOrdered()) continue;
                if (content == 0 || child.node().isScrollExempt()) {
                    paintBox(child, ctx, base, false);
                } else {
                    ctx.enterNode(content);
                    paintBox(child, ctx, contentBase, false);
                    ctx.enterNode(outer);
                }
            }
            if (order != null) {
                paintLiftedIn(order.zero, box, ctx, content, contentBase, outer);
                paintLiftedIn(order.positive, box, ctx, content, contentBase, outer);
                paintLiftedIn(order.top, box, ctx, content, contentBase, outer);
            }
        } finally {
            ctx.enterNode(outer);
            if (scissor) ctx.popScissor();
        }
    }

    /** A context's list painted in its scrolled content's node, when it has one. */
    private void paintLiftedIn(List<Box> lifted, Box context, CgUiPaintContext ctx, int content, Matrix4f contentBase,
                               int outer) {
        if (content == 0) {
            paintLifted(lifted, context, ctx, contentBase);
            return;
        }
        ctx.enterNode(content);
        try {
            paintLifted(lifted, context, ctx, contentBase);
        } finally {
            ctx.enterNode(outer);
        }
    }

    /** {@link #paintChildren}'s scratch for a scroll node's origin, read by {@code addNode} at once. */
    private final Matrix4f nodeOrigin = new Matrix4f();
    /** A moved box's world before rounding, handed to the box at once. */
    private final Matrix4f movedWorld = new Matrix4f();

    /**
     * {@code base} carried into the bound target's pixels: itself at node 0, where draws are already in them. Read
     * at once; the matrix may be scratch.
     */
    private Matrix4f targetOf(CgUiPaintContext ctx, Matrix4f base) {
        return ctx.spatialNode() == 0 ? base : targetBase.set(ctx.drawToTarget()).mul(base);
    }

    private final Matrix4f targetBase = new Matrix4f();

    /**
     * The base drawing inside a layer at {@code region}: shifted to the layer's corner at node 0, where draws are in
     * the target's pixels, and unchanged under a node, whose layer is placed by the pass's view.
     */
    private static Matrix4f layerBase(CgUiPaintContext ctx, Matrix4f base, LayerRegion region) {
        return ctx.spatialNode() == 0 ? new Matrix4f(base).translateLocal(-region.x(), -region.y(), 0f) : base;
    }

    private static void pushPaddingScissor(Box box, CgUiPaintContext ctx) {
        FloatRect b = box.border();
        ctx.pushScissor(b.left, b.top,
                Math.max(0f, box.width() - b.left - b.right),
                Math.max(0f, box.height() - b.top - b.bottom));
    }

    /** A context's list, each box painted through the clips of every box between it and {@code context}. */
    private void paintLifted(List<Box> lifted, Box context, CgUiPaintContext ctx, Matrix4f base) {
        for (int i = 0; i < lifted.size(); i++) {
            Box box = lifted.get(i);
            // CULLED BEFORE THE WALK UP: a context lists every realised row of every virtualised list under it.
            if (CgUiPaintContext.CULL) {
                inkThrough(box, targetOf(ctx, base));
                if (ctx.outsideClip(ink[0], ink[1], ink[2], ink[3])) continue;
            }
            List<Box> clips = clipsBetween(box, context);
            try {
                paintClipped(box, clips, clips.size() - 1, ctx, base);
            } finally {
                clipDepth--;
            }
        }
    }

    /**
     * The boxes between {@code lifted} and {@code context} that clip, innermost first, in a list held for this
     * nesting depth: a lifted box's paint can reach another context's lifted boxes before it is done with its
     * own. The caller releases it by decrementing {@link #clipDepth}.
     */
    private List<Box> clipsBetween(Box lifted, Box context) {
        if (clipDepth == clipLists.size()) clipLists.add(new ArrayList<>(4));
        List<Box> clips = clipLists.get(clipDepth++);
        clips.clear();
        for (Box between = lifted.host(); between != null && between != context; between = between.host()) {
            if (between.clips()) clips.add(between);
        }
        return clips;
    }

    /** @see #clipsBetween */
    private final List<List<Box>> clipLists = new ArrayList<>();
    private int clipDepth;

    /**
     * {@code lifted} under {@code clips[0..at]}, outermost applied first: a square clip as a scissor in its own
     * space, a rounded one as a mask layer, as the box would have masked it had the lifted box stayed in its flow.
     */
    private void paintClipped(Box lifted, List<Box> clips, int at, CgUiPaintContext ctx, Matrix4f base) {
        if (at < 0) {
            paintBox(lifted, ctx, base, lifted.isStackingContext());
            return;
        }
        Box clip = clips.get(at);
        ComputedStyle style = clip.node().computedStyle();
        PoseStack pose = ctx.getPoseStack();
        boolean square = radiiOf(style, clip.width(), clip.height()).isZero();
        LayerRegion region = square ? null : regionOf(lifted, ctx, base);
        if (region != null && region.isEmpty()) return;
        // A LAYER PAIR PER LIFTED BOX PER ROUNDED ANCESTOR, for corners it was nowhere near: every graph node and
        // port editor inside a rounded window paid two targets and a composite for a clip a scissor makes exactly.
        boolean elided = !square && !CgUiPaintContext.LEGACY_LAYERS
                && missesRoundedCorners(clip, style, region, targetOf(ctx, base));
        boolean shaped = false;
        if (!square && !elided && clipsAsShape(clip, style)) {
            pose.pushPose();
            pose.last().pose().set(base).mul(clip.localToWorld());
            shaped = pushShapeClip(clip, style, ctx);
            pose.popPose();
        }
        if (square || elided || shaped) {
            pose.pushPose();
            pose.last().pose().set(base).mul(clip.localToWorld());
            // A rounded clip follows any pose; a scissor is a screen rect, which a rotated box is not.
            boolean padded = !shaped || axisAligned(ctx);
            if (padded) pushPaddingScissor(clip, ctx);
            // The layer it replaces was also a clip to the region, and a box painting past its own ink relied on it.
            // Not for a box a compositor moves: the region is in the target's pixels and would stay where it was recorded.
            boolean toRegion = (elided || shaped) && !lifted.willChangeTransform();
            if (toRegion) {
                if (elided) CgTrace.add(UiTrace.FRAME, "masks-elided", 1);
                pose.last().pose().set(ctx.targetToDraw());
                ctx.pushScissor(region.x(), region.y(), region.width(), region.height());
            }
            pose.popPose();
            try {
                paintClipped(lifted, clips, at - 1, ctx, base);
            } finally {
                if (toRegion) ctx.popScissor();
                if (padded) ctx.popScissor();
                if (shaped) ctx.popRoundedClip();
            }
            return;
        }
        CgTrace.add(UiTrace.FRAME, "layers-mask", 1);
        Matrix4f inner = layerBase(ctx, base, region);
        LayerRegion inside = region.atOrigin();
        CgGraphTexture content = ctx.beginLayerFbo(region);
        paintClipped(lifted, clips, at - 1, ctx, inner);
        CgGraphTexture mask = ctx.beginLayerFbo(inside);
        pose.pushPose();
        pose.last().pose().set(inner).mul(clip.localToWorld());
        paintMask(clip, style, ctx);
        pose.popPose();
        ctx.endLayerFbo();
        ctx.compositeMask(content, mask, inside);
        ctx.endLayerFbo();
        ctx.blitLayer(content, 1f, region);
    }

    // ── Background ───────────────────────────────────────────────────────────

    private void paintSelf(Box box, ComputedStyle style, CgUiPaintContext ctx, Radii radii) {
        float width = box.width(), height = box.height();

        // THE BACKDROP FIRST, and it is not the background: `backdrop-filter` acts on what is BEHIND the
        // element, so the element's own background is drawn over the result. That ordering is the whole
        // reason it is a property -- as a `background: glass(...)` value it occupied the one background
        // slot, and an element could have glass or a colour, never both.
        //
        // It clips itself to the radii, like any CornerRadiusAware drawable: wrapped in a rounded quad
        // it would come out a rounded rectangle full of nothing.
        CgUiBackdropFilter backdrop = style.get(StylePropertyRegistry.BACKDROP_FILTER);
        if (backdrop != null) {
            backdrop.setCornerRadii(radii.rxTL, radii.ryTL, radii.rxTR, radii.ryTR,
                    radii.rxBR, radii.ryBR, radii.rxBL, radii.ryBL);
            ctx.setColor(WHITE);
            backdrop.draw(ctx, 0f, 0f, width, height);
        }

        CgUiDrawable background = style.get(StylePropertyRegistry.BACKGROUND);
        int backgroundColor = style.get(StylePropertyRegistry.BACKGROUND_COLOR);
        // background-color defaults to white (a no-op tint), so whether one was AUTHORED cannot be
        // read off the value; the snapshot says whether anything set it.
        boolean explicitBackgroundColor = style.isSet(StylePropertyRegistry.BACKGROUND_COLOR);
        float borderWidth = borderSides(box);
        boolean wrap = !radii.isZero() || borderWidth > 0f;

        // A drawable that clips ITSELF (glass) takes the radii and is not wrapped -- wrapped, it would
        // become a rounded rectangle full of nothing.
        if (background instanceof CornerRadiusAware aware) {
            aware.setCornerRadii(radii.rxTL, radii.ryTL, radii.rxTR, radii.ryTR,
                    radii.rxBR, radii.ryBR, radii.rxBL, radii.ryBL);
            ctx.setColor(backgroundColor);
            background.draw(ctx, 0f, 0f, width, height);
            return;
        }
        if (wrap && paintRounded(style, ctx, width, height, radii, borderWidth, background, backgroundColor,
                explicitBackgroundColor)) {
            return;
        }
        if (background == CgUiDrawable.EMPTY) {
            ctx.setColor(WHITE);
            if (explicitBackgroundColor) ctx.fillRect(0f, 0f, width, height, backgroundColor);
        } else {
            ctx.setColor(backgroundColor);
            background.draw(ctx, 0f, 0f, width, height);
        }
    }

    /** @return whether it painted; false when the background is a kind the rounded wrap cannot clip. */
    private boolean paintRounded(ComputedStyle style, CgUiPaintContext ctx, float width, float height,
                                        Radii radii, float borderWidth, CgUiDrawable background,
                                        int backgroundColor, boolean explicitBackgroundColor) {
        int borderColor = style.get(StylePropertyRegistry.BORDER_COLOR);
        int borderTop = edgeColor(style.get(StylePropertyRegistry.BORDER_TOP_COLOR), borderColor);
        int borderBottom = edgeColor(style.get(StylePropertyRegistry.BORDER_BOTTOM_COLOR), borderColor);
        if (background == CgUiDrawable.EMPTY) {
            int fillArgb;
            if (explicitBackgroundColor) {
                fillArgb = backgroundColor;
            } else if (borderWidth > 0f) {
                // Border only: a transparent interior carrying the border's rgb, so the shader's
                // edge->fill mix has no dark fringe to bleed.
                fillArgb = borderColor & 0x00FFFFFF;
            } else {
                return false;
            }
            ctx.setColor(WHITE);
            shaped(ctx, radii).size(width, height)
                    .border(borderWidth, borderColor, borderTop, borderBottom)
                    .borderSides(sides[0], sides[1], sides[2], sides[3])
                    .fillColor(fillArgb)
                    .submit();
            return true;
        }
        if (!canPaintRounded(background)) return false;
        ctx.setColor(backgroundColor);
        paintRoundedLayer(ctx, background, width, height, radii, borderWidth, borderColor, borderTop, borderBottom);
        return true;
    }

    private void paintRoundedLayer(CgUiPaintContext ctx, CgUiDrawable d, float width, float height,
                                          Radii radii, float borderWidth, int borderColor, int borderTop, int borderBottom) {
        if (d instanceof CgUiCrossFade cf) {
            float previous = ctx.pushLayerOpacity(1f - cf.getT());
            try {
                paintRoundedLayer(ctx, cf.getFrom(), width, height, radii, borderWidth, borderColor, borderTop, borderBottom);
            } finally {
                ctx.popLayerOpacity(previous);
            }
            previous = ctx.pushLayerOpacity(cf.getT());
            try {
                paintRoundedLayer(ctx, cf.getTo(), width, height, radii, borderWidth, borderColor, borderTop, borderBottom);
            } finally {
                ctx.popLayerOpacity(previous);
            }
            return;
        }
        shaped(ctx, radii).size(width, height)
                .border(borderWidth, borderColor, borderTop, borderBottom)
                .borderSides(sides[0], sides[1], sides[2], sides[3])
                .fill(((CgUiRect) d).getFill())
                .submit();
    }

    /**
     * The box's four border widths into {@link #sides}, answering the widest: a border is drawn when any side has one.
     * Each side at its own width -- the stroke took the left width for all four, so a box with only a top border drew
     * none, and one with a thick top drew it thin.
     */
    private float borderSides(Box box) {
        sides[0] = box.border().left;
        sides[1] = box.border().top;
        sides[2] = box.border().right;
        sides[3] = box.border().bottom;
        return Math.max(Math.max(sides[0], sides[1]), Math.max(sides[2], sides[3]));
    }

    /** {@link #borderSides}' answer, L,T,R,B. Read immediately: a tree paints one box at a time. */
    private final float[] sides = new float[4];

    private static int edgeColor(int edge, int fallback) {
        return (edge >>> 24) == 0 ? fallback : edge;
    }

    // ── Mask ─────────────────────────────────────────────────────────────────

    /**
     * The mask of a clip that is not a rounded clip: the box's own shape with its border band at alpha 0, whatever its
     * background, or the {@code mask} drawable laid out on the box and cut to that shape.
     */
    private void paintMask(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        float borderWidth = borderSides(box);
        ctx.setColor(WHITE);
        CgUiDrawable source = style.get(StylePropertyRegistry.MASK);
        if (source == CgUiDrawable.EMPTY) {
            paintMaskShape(ctx, CgUiDrawable.EMPTY, 0f, 0f, box.width(), box.height(),
                    radiiOf(style, box.width(), box.height()), borderWidth);
            return;
        }
        layMask(box, style, source);
        Radii radii = radiiOf(style, laid[2], laid[3]);
        paintMaskShape(ctx, source, laid[0], laid[1], laid[2], laid[3], radii, borderWidth);
    }

    /** Lays the {@code mask} drawable's rectangle out into {@link #laid}. */
    private void layMask(Box box, ComputedStyle style, CgUiDrawable source) {
        originBox(box, style.get(StylePropertyRegistry.MASK_ORIGIN), originBox);
        LengthPercent offset = style.get(StylePropertyRegistry.MASK_OFFSET);
        float offsetX = offset == null ? 0f : offset.resolve(originBox[2]);
        float offsetY = offset == null ? 0f : offset.resolve(originBox[3]);
        CgUiLayerBox.resolveInto(source,
                originBox[0] - offsetX, originBox[1] - offsetY,
                Math.max(0f, originBox[2] + 2f * offsetX),
                Math.max(0f, originBox[3] + 2f * offsetY),
                style.get(StylePropertyRegistry.MASK_SIZE), style.get(StylePropertyRegistry.MASK_POSITION), laid);
    }

    /**
     * Whether {@code clip}'s rounded mask cuts {@code region} exactly as its padding-box scissor would: no
     * {@code mask} drawable, the box axis-aligned in the target, and the region missing all four rounded corners.
     * A node deep inside a rounded window is the common case.
     */
    private boolean missesRoundedCorners(Box clip, ComputedStyle style, LayerRegion region, Matrix4f base) {
        Matrix4f space = clipSpace.set(base).mul(clip.localToWorld());
        if (space.m10() != 0f || space.m01() != 0f || space.m00() <= 0f || space.m11() <= 0f) return false;
        if (style.get(StylePropertyRegistry.MASK) != CgUiDrawable.EMPTY) return false;
        float w = clip.width(), h = clip.height();

        float x0 = (region.x() - space.m30()) / space.m00();
        float y0 = (region.y() - space.m31()) / space.m11();
        float x1 = (region.x() + region.width() - space.m30()) / space.m00();
        float y1 = (region.y() + region.height() - space.m31()) / space.m11();
        Radii r = radiiOf(style, w, h);
        return !overlaps(x0, y0, x1, y1, 0f, 0f, r.rxTL, r.ryTL)
                && !overlaps(x0, y0, x1, y1, w - r.rxTR, 0f, w, r.ryTR)
                && !overlaps(x0, y0, x1, y1, w - r.rxBR, h - r.ryBR, w, h)
                && !overlaps(x0, y0, x1, y1, 0f, h - r.ryBL, r.rxBL, h);
    }

    /**
     * Whether {@code box}'s children are clipped with {@link CgUiPaintContext#pushRoundedClip} rather than a mask layer:
     * any box without a {@code mask} drawable, since {@code overflow} clips to the shape whatever the background.
     */
    private static boolean clipsAsShape(Box box, ComputedStyle style) {
        return CgUiPaintContext.ROUNDED_CLIP && style.get(StylePropertyRegistry.MASK) == CgUiDrawable.EMPTY;
    }

    /** Whether the current pose keeps a rect a rect on screen, so a scissor can cut it. */
    private static boolean axisAligned(CgUiPaintContext ctx) {
        Matrix4f m = ctx.targetPose();
        return m.m10() == 0f && m.m01() == 0f;
    }

    /**
     * {@code box}'s shape less its border, as a rounded clip in the current pose's space; false when the clips are
     * already nested as deep as a draw can carry.
     */
    private boolean pushShapeClip(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        Radii r = radiiOf(style, box.width(), box.height());
        clipRx[0] = r.rxTL; clipRx[1] = r.rxTR; clipRx[2] = r.rxBR; clipRx[3] = r.rxBL;
        clipRy[0] = r.ryTL; clipRy[1] = r.ryTR; clipRy[2] = r.ryBR; clipRy[3] = r.ryBL;
        borderSides(box);
        return ctx.pushRoundedClip(0f, 0f, box.width(), box.height(), clipRx, clipRy, sides);
    }

    /** @see #pushShapeClip -- TL, TR, BR, BL, as the clip table takes them. */
    private final float[] clipRx = new float[4], clipRy = new float[4];

    private static boolean overlaps(float ax0, float ay0, float ax1, float ay1, float bx0, float by0, float bx1, float by1) {
        return ax0 < bx1 && bx0 < ax1 && ay0 < by1 && by0 < ay1;
    }

    /** @see #missesRoundedCorners */
    private final Matrix4f clipSpace = new Matrix4f();

    private void paintMaskShape(CgUiPaintContext ctx, CgUiDrawable d, float x, float y, float width, float height,
                                       Radii radii, float borderWidth) {
        if (d instanceof CgUiCrossFade cf) {
            float previous = ctx.pushLayerOpacity(1f - cf.getT());
            try {
                paintMaskShape(ctx, cf.getFrom(), x, y, width, height, radii, borderWidth);
            } finally {
                ctx.popLayerOpacity(previous);
            }
            previous = ctx.pushLayerOpacity(cf.getT());
            try {
                paintMaskShape(ctx, cf.getTo(), x, y, width, height, radii, borderWidth);
            } finally {
                ctx.popLayerOpacity(previous);
            }
            return;
        }
        CgUiRect.Draw mask = shaped(ctx, radii).at(x, y).size(width, height);
        // A mask that would reveal NOTHING reveals the whole shape instead -- `background: none` and
        // `background: #00000000` must clip the same way.
        if (revealsNothing(d)) mask.fillColor(WHITE);
        else mask.fill(((CgUiRect) d).getFill());
        if (borderWidth > 0f) mask.border(borderWidth, 0x00000000).borderSides(sides[0], sides[1], sides[2], sides[3]);
        mask.submit();
    }

    // ── Overlay and outline ──────────────────────────────────────────────────

    private void paintOverlay(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        ctx.setColor(WHITE);
        CgUiDrawable overlay = style.get(StylePropertyRegistry.OVERLAY);
        if (overlay == CgUiDrawable.EMPTY) return;
        // A mark takes the box's `color`; a picture keeps its own palette.
        if (overlay.followsTextColor()) ctx.setColor(style.get(StylePropertyRegistry.COLOR));
        originBox(box, style.get(StylePropertyRegistry.OVERLAY_ORIGIN), originBox);
        CgUiLayerBox.resolveInto(overlay, originBox[0], originBox[1], originBox[2], originBox[3],
                style.get(StylePropertyRegistry.OVERLAY_SIZE), style.get(StylePropertyRegistry.OVERLAY_POSITION), laid);
        overlay.draw(ctx, laid[0], laid[1], laid[2], laid[3]);
    }

    private void paintOutline(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        float width = box.width(), height = box.height();
        CgUiDrawable outline = style.get(StylePropertyRegistry.OUTLINE);
        LengthPercent strokeLp = style.get(StylePropertyRegistry.OUTLINE_WIDTH);
        float stroke = strokeLp == null ? 0f : strokeLp.resolve(width);
        boolean hasDrawable = outline != CgUiDrawable.EMPTY;
        if (!hasDrawable && stroke <= 0f) return;
        ctx.setColor(WHITE);
        float top = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_TOP), height);
        float bottom = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM), height);
        float left = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_LEFT), width);
        float right = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT), width);
        if (hasDrawable) {
            outline.draw(ctx, -left, -top, Math.max(0f, width + left + right), Math.max(0f, height + top + bottom));
            return;
        }
        // The SDF stroke measures inward from the shape's outer edge; a CSS outline grows outward from
        // the offset edge -- so inflate by offset + width and let the inward stroke land in the band.
        float insetTop = top + stroke, insetBottom = bottom + stroke, insetLeft = left + stroke, insetRight = right + stroke;
        // RESOLVED AGAIN rather than carried from paintBox: the children painted in between share the
        // one scratch. @see #radii
        Radii ring = radiiOf(style, width, height)
                .expand((insetLeft + insetRight) * 0.5f, (insetTop + insetBottom) * 0.5f);
        int color = style.get(StylePropertyRegistry.OUTLINE_COLOR);
        shaped(ctx, ring)
                .at(-insetLeft, -insetTop)
                .size(width + insetLeft + insetRight, height + insetTop + insetBottom)
                .fillColor(color & 0x00FFFFFF)
                .border(stroke, color)
                .submit();
    }

    private static float resolve(@Nullable LengthPercent lp, float against) {
        return lp == null ? 0f : lp.resolve(against);
    }

    /**
     * How far this box paints in its OWN space, as {@code left, top, right, bottom} â€” its border box
     * grown by the outline and by whatever the node says it draws beyond it.
     *
     * <p>Here rather than in {@link BoxTree} because it is the same arithmetic {@link #paintOutline}
     * does, and a layer sized from a different answer than the one the painter draws is a clipped
     * outline nobody looks for. The subtree's share, and the clip {@code overflow} imposes on it, are
     * the tree's â€” this is one box.</p>
     */
    static void localInk(Box box, float[] ltrb) {
        ComputedStyle style = box.node().computedStyle();
        float width = box.width(), height = box.height();
        float left = 0f, top = 0f, right = width, bottom = height;

        CgUiDrawable outline = style.get(StylePropertyRegistry.OUTLINE);
        LengthPercent strokeLp = style.get(StylePropertyRegistry.OUTLINE_WIDTH);
        float stroke = strokeLp == null ? 0f : strokeLp.resolve(width);
        boolean hasDrawable = outline != CgUiDrawable.EMPTY;
        if (hasDrawable || stroke > 0f) {
            // A drawable outline fills the offset rect; a stroked one grows outward from it by its own
            // width. Offsets are commonly NEGATIVE here (`*` sets -1px), so this can subtract.
            float grow = hasDrawable ? 0f : stroke;
            left = Math.min(left, -(resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_LEFT), width) + grow));
            right = Math.max(right, width + resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT), width) + grow);
            top = Math.min(top, -(resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_TOP), height) + grow));
            bottom = Math.max(bottom, height + resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM), height) + grow);
        }

        InkOverflow declared = box.node().inkOverflow();
        if (!declared.isZero()) {
            left = Math.min(left, -declared.left());
            top = Math.min(top, -declared.top());
            right = Math.max(right, width + declared.right());
            bottom = Math.max(bottom, height + declared.bottom());
        }

        ltrb[0] = left;
        ltrb[1] = top;
        ltrb[2] = right;
        ltrb[3] = bottom;
    }

    /** One of the CSS box-model boxes, in the box's own space, as {@code x, y, width, height}. */
    private static void originBox(Box box, @Nullable BoxOrigin origin, float[] out) {
        float width = box.width(), height = box.height();
        if (origin == null || origin == BoxOrigin.BORDER_BOX) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = width;
            out[3] = height;
            return;
        }
        FloatRect b = box.border();
        float l = b.left, t = b.top, r = b.right, bo = b.bottom;
        if (origin == BoxOrigin.CONTENT_BOX) {
            FloatRect p = box.padding();
            l += p.left;
            t += p.top;
            r += p.right;
            bo += p.bottom;
        }
        out[0] = l;
        out[1] = t;
        out[2] = Math.max(0f, width - l - r);
        out[3] = Math.max(0f, height - t - bo);
    }

    // ── Fills and radii ──────────────────────────────────────────────────────

    /**
     * The context's rect scratch, carrying the element's radii — and NOT a {@code CgUiRect}.
     *
     * <p>The radii are applied here rather than pushed into the background the cascade handed us: that
     * value is shared across frames and elements, and {@code CgUiRect} equality is what stops a repeated
     * write retargeting a transition. It used to be built as a value all the same, which cost a
     * {@code Fill}, one or two rects and two capturing lambdas for every element carrying a radius or a
     * border — per frame, on every themed surface and all ten thousand nodes of a graph.</p>
     */
    private static CgUiRect.Draw shaped(CgUiPaintContext ctx, Radii radii) {
        return ctx.rect().radii(radii.rxTL, radii.ryTL, radii.rxTR, radii.ryTR,
                radii.rxBR, radii.ryBR, radii.rxBL, radii.ryBL);
    }

    /** Whether the rounded wrap can express this background: only a rect has a fill to re-shape. */
    private static boolean canPaintRounded(CgUiDrawable d) {
        if (d instanceof CgUiCrossFade cf) return canPaintRounded(cf.getFrom()) && canPaintRounded(cf.getTo());
        return d != CgUiDrawable.EMPTY && d instanceof CgUiRect;
    }

    private static boolean revealsNothing(CgUiDrawable d) {
        if (!(d instanceof CgUiRect rect)) return true;
        return rect.getFill() instanceof CgUiRect.Fill.Color(int argb) && (argb >>> 24) == 0;
    }

    /** The eight resolved corner radii of a box. Filled by {@link #radiiOf}. */
    static final class Radii {
        float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

        boolean isZero() {
            return rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f && rxBR == 0f && ryBR == 0f && rxBL == 0f && ryBL == 0f;
        }

        /** Grows every non-zero radius IN PLACE, for the outline ring. */
        Radii expand(float dx, float dy) {
            rxTL = grow(rxTL, dx); ryTL = grow(ryTL, dy);
            rxTR = grow(rxTR, dx); ryTR = grow(ryTR, dy);
            rxBR = grow(rxBR, dx); ryBR = grow(ryBR, dy);
            rxBL = grow(rxBL, dx); ryBL = grow(ryBL, dy);
            return this;
        }

        private static float grow(float r, float d) {
            return r <= 0f ? 0f : Math.max(0f, r + d);
        }
    }

    /**
     * <b>The radii scratch, and the rule that makes it safe: it is filled immediately before it is read, and never
     * read across anything that can paint another box.</b> So {@link #paintSelf} takes it (it runs before this box's
     * children) while {@link #paintOutline} resolves it again (it runs after them).
     */
    private final Radii radii = new Radii();

    /** @see #radii */
    private final float[] originBox = new float[4];

    /** @see #radii */
    private final float[] laid = new float[4];

    /** Resolves a box's radii into this painter's scratch and answers it. */
    private Radii radiiOf(ComputedStyle style, float width, float height) {
        return radiiOf(style, width, height, radii);
    }

    /** Resolves a box's radii into {@code into} and answers it. */
    static Radii radiiOf(ComputedStyle style, float width, float height, Radii into) {
        into.rxTL = resolve(style.get(BorderRadiusProperties.TOP_LEFT_X), width);
        into.ryTL = resolve(style.get(BorderRadiusProperties.TOP_LEFT_Y), height);
        into.rxTR = resolve(style.get(BorderRadiusProperties.TOP_RIGHT_X), width);
        into.ryTR = resolve(style.get(BorderRadiusProperties.TOP_RIGHT_Y), height);
        into.rxBR = resolve(style.get(BorderRadiusProperties.BOTTOM_RIGHT_X), width);
        into.ryBR = resolve(style.get(BorderRadiusProperties.BOTTOM_RIGHT_Y), height);
        into.rxBL = resolve(style.get(BorderRadiusProperties.BOTTOM_LEFT_X), width);
        into.ryBL = resolve(style.get(BorderRadiusProperties.BOTTOM_LEFT_Y), height);
        // OVERLAPPING CURVES, CSS Backgrounds 3 section 5.5: where two radii on one side add up past it, EVERY radius
        // is scaled by the one factor that makes them fit. The shader capped each axis at half the box on its own,
        // which drew `border-radius: 999px` on a wide box as an ellipse where CSS draws a pill.
        float f = fit(1f, width, into.rxTL + into.rxTR);
        f = fit(f, width, into.rxBL + into.rxBR);
        f = fit(f, height, into.ryTL + into.ryBL);
        f = fit(f, height, into.ryTR + into.ryBR);
        if (f < 1f) {
            into.rxTL *= f; into.ryTL *= f; into.rxTR *= f; into.ryTR *= f;
            into.rxBR *= f; into.ryBR *= f; into.rxBL *= f; into.ryBL *= f;
        }
        return into;
    }

    /** {@code f} lowered to what fits radii summing to {@code sum} on a side of {@code length}. */
    static float fit(float f, float length, float sum) {
        return sum > 0f && length >= 0f && sum > length ? Math.min(f, length / sum) : f;
    }
}
