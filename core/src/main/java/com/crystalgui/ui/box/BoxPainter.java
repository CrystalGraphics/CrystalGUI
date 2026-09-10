package com.crystalgui.ui.box;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.LayerRegion;
import com.crystalgui.render.RetainedLayer;
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
 * context's layer-FBO path exactly as before; a square clip is a scissor. The drawables, the SDF
 * rounded rect and the compositing are the backend's and unchanged — this class only decides what
 * is drawn where.</p>
 */
public final class BoxPainter {

    private static final int WHITE = 0xFFFFFFFF;

    private BoxPainter() {
    }

    /** Paints the whole tree with the pose on the stack as the surface transform. */
    public static void paint(BoxTree tree, CgUiPaintContext ctx) {
        Box root = tree.root();
        if (root == null) return;
        Matrix4f base = new Matrix4f(ctx.getPoseStack().last().pose());
        paintBox(root, ctx, base);
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
        Matrix4f base = new Matrix4f(ctx.getPoseStack().last().pose());
        // @see CgUiPaintContext#withoutRetention -- a copy drawn at other coordinates must not become
        // what the live tree thinks it last painted.
        ctx.withoutRetention(() -> paintBox(box, ctx, base));
    }

    private static void paintBox(Box box, CgUiPaintContext ctx, Matrix4f base) {
        float opacity = box.opacity();
        if (opacity <= 0f) return;
        UIElement node = box.node();
        ComputedStyle style = node.computedStyle();
        PoseStack pose = ctx.getPoseStack();
        pose.pushPose();
        pose.last().pose().set(base).mul(box.localToWorld());
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
                FrameProfile.count("masks-elided", 1);
                mask = false;
            }
            boolean needsLayer = opacity < 1f || mask;

            // AND AN OPACITY THAT CANNOT SELF-OVERLAP folds into the draw instead of flattening a
            // subtree -- Skia's rule, and Flutter's advice to colour a container rather than wrap it in
            // an `Opacity`. Group opacity differs from per-primitive opacity only where two primitives
            // cover the same pixel; where there is only one, they are the same number.
            if (needsLayer && !mask && !CgUiPaintContext.LEGACY_LAYERS && foldsOpacity(box, style, node)) {
                FrameProfile.count("layers-elided", 1);
                float previousOpacity = ctx.pushLayerOpacity(opacity);
                try {
                    paintSelf(box, style, ctx, radii);
                    paintOverlay(box, style, ctx);
                    paintOutline(box, style, ctx);
                } finally {
                    ctx.popLayerOpacity(previousOpacity);
                }
                return;
            }

            if (!needsLayer) {
                paintSelf(box, style, ctx, radii);
                node.paintContent(ctx, box);
                paintChildren(box, ctx, base, scissor);
                node.paintDecoration(ctx, box);
                paintOverlay(box, style, ctx);
                paintOutline(box, style, ctx);
                return;
            }

            // A LAYER, SIZED TO WHAT GOES IN IT. The region is the subtree's ink bounds through the
            // pose, already intersected with whatever is clipping -- so an element wholly scrolled out
            // of its container costs nothing at all here, and one that is 20px wide costs 20px rather
            // than a screen.
            LayerRegion region = regionOf(box, ctx, base);
            if (region.isEmpty()) return;

            // AND IF NOTHING UNDER IT MOVED, THE PICTURE IS STILL THERE. The whole of what a frame owes
            // an unchanged subtree is one composited quad; the clear, the walk and every draw beneath
            // are the difference between two frames, and there is none.
            RetainedLayer keep = box.retainable() ? ctx.retain(box, region, box.subtreeRevision()) : null;
            if (keep != null && keep.isFresh()) {
                ctx.blitLayer(keep.fbo(), opacity, region);
                return;
            }

            // The layer's own origin: its pixel (0,0) is the region's corner, so everything drawn
            // inside it -- this box and every descendant -- goes through a base shifted to match.
            Matrix4f inner = new Matrix4f(base).translateLocal(-region.x(), -region.y(), 0f);
            pose.last().pose().set(inner).mul(box.localToWorld());
            LayerRegion inside = region.atOrigin();

            // The subtree blends as one unit before opacity applies, and a mask multiplies only the
            // CHILDREN -- the box's own background is composited unmasked underneath.
            CgFrameBuffer subtreeFbo = keep != null
                    ? ctx.beginLayerFbo(keep.fbo(), region)
                    : ctx.beginLayerFbo(region);
            paintSelf(box, style, ctx, radii);
            node.paintContent(ctx, box);
            if (mask) {
                CgFrameBuffer childrenFbo = ctx.beginLayerFbo(inside);
                paintChildren(box, ctx, inner, false);
                CgFrameBuffer maskFbo = ctx.beginLayerFbo(inside);
                paintMask(box, style, ctx);
                ctx.endLayerFbo();
                ctx.compositeMask(childrenFbo, maskFbo, inside);
                ctx.endLayerFbo();
                ctx.blitLayer(childrenFbo, 1f, inside);
            } else {
                paintChildren(box, ctx, inner, scissor);
            }
            node.paintDecoration(ctx, box);
            paintOverlay(box, style, ctx);
            // Inside the layer, so the outline fades with the box: CSS puts it in the opacity group.
            paintOutline(box, style, ctx);
            ctx.endLayerFbo();
            if (keep != null) keep.painted();
            ctx.blitLayer(subtreeFbo, opacity, region);
        } finally {
            pose.popPose();
        }
    }

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
    private static LayerRegion regionOf(Box box, CgUiPaintContext ctx, Matrix4f base) {
        float x0 = box.inkX0(), y0 = box.inkY0(), x1 = box.inkX1(), y1 = box.inkY1();
        float m00 = base.m00(), m10 = base.m10(), m30 = base.m30();
        float m01 = base.m01(), m11 = base.m11(), m31 = base.m31();
        float ax = m00 * x0 + m10 * y0 + m30, ay = m01 * x0 + m11 * y0 + m31;
        float bx = m00 * x1 + m10 * y0 + m30, by = m01 * x1 + m11 * y0 + m31;
        float cx = m00 * x1 + m10 * y1 + m30, cy = m01 * x1 + m11 * y1 + m31;
        float dx = m00 * x0 + m10 * y1 + m30, dy = m01 * x0 + m11 * y1 + m31;
        return ctx.layerRegion(
                Math.min(Math.min(ax, bx), Math.min(cx, dx)),
                Math.min(Math.min(ay, by), Math.min(cy, dy)),
                Math.max(Math.max(ax, bx), Math.max(cx, dx)),
                Math.max(Math.max(ay, by), Math.max(cy, dy)));
    }

    private static void paintChildren(Box box, CgUiPaintContext ctx, Matrix4f base, boolean scissor) {
        if (box.children().isEmpty()) return;
        if (scissor) {
            // The PADDING box, as CSS clips: border excluded, padding included. In this box's own
            // space, and the context quantises it once in physical pixels through the pose.
            FloatRect b = box.border();
            ctx.pushScissor(b.left, b.top,
                    Math.max(0f, box.width() - b.left - b.right),
                    Math.max(0f, box.height() - b.top - b.bottom));
        }
        try {
            for (Box child : box.children()) paintBox(child, ctx, base);
        } finally {
            if (scissor) ctx.popScissor();
        }
    }

    // ── Background ───────────────────────────────────────────────────────────

    private static void paintSelf(Box box, ComputedStyle style, CgUiPaintContext ctx, Radii radii) {
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
        float borderWidth = box.border().left;
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
    private static boolean paintRounded(ComputedStyle style, CgUiPaintContext ctx, float width, float height,
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
                    .fillColor(fillArgb)
                    .submit();
            return true;
        }
        if (!canPaintRounded(background)) return false;
        ctx.setColor(backgroundColor);
        paintRoundedLayer(ctx, background, width, height, radii, borderWidth, borderColor, borderTop, borderBottom);
        return true;
    }

    private static void paintRoundedLayer(CgUiPaintContext ctx, CgUiDrawable d, float width, float height,
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
                .fill(((CgUiRect) d).getFill())
                .submit();
    }

    private static int edgeColor(int edge, int fallback) {
        return (edge >>> 24) == 0 ? fallback : edge;
    }

    // ── Mask ─────────────────────────────────────────────────────────────────

    /** The default {@code overflow: hidden} mask: the box's own rounded shape with the border band at alpha 0. */
    private static void paintMask(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        float borderWidth = box.border().left;
        CgUiDrawable maskDrawable = style.get(StylePropertyRegistry.MASK);
        CgUiDrawable source = maskDrawable != CgUiDrawable.EMPTY ? maskDrawable : style.get(StylePropertyRegistry.BACKGROUND);
        originBox(box, style.get(StylePropertyRegistry.MASK_ORIGIN), ORIGIN_BOX);
        LengthPercent offset = style.get(StylePropertyRegistry.MASK_OFFSET);
        float offsetX = offset == null ? 0f : offset.resolve(ORIGIN_BOX[2]);
        float offsetY = offset == null ? 0f : offset.resolve(ORIGIN_BOX[3]);
        CgUiLayerBox.resolveInto(source,
                ORIGIN_BOX[0] - offsetX, ORIGIN_BOX[1] - offsetY,
                Math.max(0f, ORIGIN_BOX[2] + 2f * offsetX),
                Math.max(0f, ORIGIN_BOX[3] + 2f * offsetY),
                style.get(StylePropertyRegistry.MASK_SIZE), style.get(StylePropertyRegistry.MASK_POSITION), LAID);
        Radii radii = radiiOf(style, LAID[2], LAID[3]);
        ctx.setColor(WHITE);
        paintMaskShape(ctx, source, LAID[0], LAID[1], LAID[2], LAID[3], radii, borderWidth);
    }

    private static void paintMaskShape(CgUiPaintContext ctx, CgUiDrawable d, float x, float y, float width, float height,
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
        if (borderWidth > 0f) mask.border(borderWidth, 0x00000000);
        mask.submit();
    }

    // ── Overlay and outline ──────────────────────────────────────────────────

    private static void paintOverlay(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        ctx.setColor(WHITE);
        CgUiDrawable overlay = style.get(StylePropertyRegistry.OVERLAY);
        if (overlay == CgUiDrawable.EMPTY) return;
        // A mark takes the box's `color`; a picture keeps its own palette.
        if (overlay.followsTextColor()) ctx.setColor(style.get(StylePropertyRegistry.COLOR));
        originBox(box, style.get(StylePropertyRegistry.OVERLAY_ORIGIN), ORIGIN_BOX);
        CgUiLayerBox.resolveInto(overlay, ORIGIN_BOX[0], ORIGIN_BOX[1], ORIGIN_BOX[2], ORIGIN_BOX[3],
                style.get(StylePropertyRegistry.OVERLAY_SIZE), style.get(StylePropertyRegistry.OVERLAY_POSITION), LAID);
        overlay.draw(ctx, LAID[0], LAID[1], LAID[2], LAID[3]);
    }

    private static void paintOutline(Box box, ComputedStyle style, CgUiPaintContext ctx) {
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
        // one scratch. @see #RADII
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

    /** The eight resolved corner radii of a box. Filled by {@link #radiiOf}; see {@link #RADII}. */
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
     * <b>The one radii scratch, and the rule that makes it safe: it is filled immediately before it is
     * read, and never read across anything that can paint another box.</b>
     *
     * <p>So {@link #paintSelf} takes it (it runs before this box's children) while
     * {@link #paintOutline} resolves it again (it runs after them), and {@link #paintMask} fills it
     * with its own. A record here was eight floats per box per frame — every box, not only the ones
     * carrying a radius, since the value is what answers {@code isZero()}.</p>
     *
     * <p>Static, like {@link #ORIGIN_BOX} and {@link #LAID}, because painting is already serialised:
     * it all goes through the {@code CgUiPaintContext} singleton, which holds one bound material, one
     * pose stack and one rect scratch of its own.</p>
     */
    private static final Radii RADII = new Radii();

    /** @see #RADII */
    private static final float[] ORIGIN_BOX = new float[4];

    /** @see #RADII */
    private static final float[] LAID = new float[4];

    /** Resolves this box's radii into {@link #RADII} and answers it. */
    static Radii radiiOf(ComputedStyle style, float width, float height) {
        RADII.rxTL = resolve(style.get(BorderRadiusProperties.TOP_LEFT_X), width);
        RADII.ryTL = resolve(style.get(BorderRadiusProperties.TOP_LEFT_Y), height);
        RADII.rxTR = resolve(style.get(BorderRadiusProperties.TOP_RIGHT_X), width);
        RADII.ryTR = resolve(style.get(BorderRadiusProperties.TOP_RIGHT_Y), height);
        RADII.rxBR = resolve(style.get(BorderRadiusProperties.BOTTOM_RIGHT_X), width);
        RADII.ryBR = resolve(style.get(BorderRadiusProperties.BOTTOM_RIGHT_Y), height);
        RADII.rxBL = resolve(style.get(BorderRadiusProperties.BOTTOM_LEFT_X), width);
        RADII.ryBL = resolve(style.get(BorderRadiusProperties.BOTTOM_LEFT_Y), height);
        return RADII;
    }
}
