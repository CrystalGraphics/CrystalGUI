package com.crystalgui.ui.box;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiCrossFade;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiLayerBox;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiRoundedRect;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.render.texture.CornerRadiusAware;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.BoxOrigin;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.dom.Node;
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
 * CSS stacks them), then the node's {@link Node#paintContent content}, the children, the node's
 * {@link Node#paintDecoration decoration}, {@code overlay}, and {@code outline} last. A fractional
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

    private static void paintBox(Box box, CgUiPaintContext ctx, Matrix4f base) {
        float opacity = box.opacity();
        if (opacity <= 0f) return;
        Node node = box.node();
        ComputedStyle style = node.computedStyle();
        PoseStack pose = ctx.getPoseStack();
        pose.pushPose();
        pose.last().pose().set(base).mul(box.localToWorld());
        try {
            Radii radii = radiiOf(style, box.width(), box.height());
            boolean clips = box.clips();
            boolean mask = clips && (!radii.isZero() || style.get(StylePropertyRegistry.MASK) != CgUiDrawable.EMPTY);
            boolean scissor = clips && !mask;
            boolean needsLayer = opacity < 1f || mask;

            if (!needsLayer) {
                paintSelf(box, style, ctx, radii);
                node.paintContent(ctx, box);
                paintChildren(box, ctx, base, scissor);
                node.paintDecoration(ctx, box);
                paintOverlay(box, style, ctx);
                paintOutline(box, style, ctx, radii);
                return;
            }

            // A layer: the subtree blends as one unit before opacity applies, and a mask multiplies
            // only the CHILDREN -- the box's own background is composited unmasked underneath.
            CgFrameBuffer subtreeFbo = ctx.beginLayerFbo();
            paintSelf(box, style, ctx, radii);
            node.paintContent(ctx, box);
            if (mask && !box.children().isEmpty()) {
                CgFrameBuffer childrenFbo = ctx.beginLayerFbo();
                paintChildren(box, ctx, base, false);
                CgFrameBuffer maskFbo = ctx.beginLayerFbo();
                paintMask(box, style, ctx);
                ctx.endLayerFbo();
                ctx.compositeMask(childrenFbo, maskFbo);
                ctx.endLayerFbo();
                ctx.blitLayer(childrenFbo, 1f);
            } else {
                paintChildren(box, ctx, base, scissor);
            }
            node.paintDecoration(ctx, box);
            paintOverlay(box, style, ctx);
            // Inside the layer, so the outline fades with the box: CSS puts it in the opacity group.
            paintOutline(box, style, ctx, radii);
            ctx.endLayerFbo();
            ctx.blitLayer(subtreeFbo, opacity);
        } finally {
            pose.popPose();
        }
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
            RectFill fill;
            if (explicitBackgroundColor) {
                fill = new ColorFill(backgroundColor);
            } else if (borderWidth > 0f) {
                // Border only: a transparent interior carrying the border's rgb, so the shader's
                // edge->fill mix has no dark fringe to bleed.
                fill = new ColorFill(borderColor & 0x00FFFFFF);
            } else {
                return false;
            }
            ctx.setColor(WHITE);
            roundedRect(radii, borderWidth, borderColor, borderTop, borderBottom, fill).draw(ctx, 0f, 0f, width, height);
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
            ctx.withLayerOpacity(1f - cf.getT(), () -> paintRoundedLayer(ctx, cf.getFrom(), width, height,
                    radii, borderWidth, borderColor, borderTop, borderBottom));
            ctx.withLayerOpacity(cf.getT(), () -> paintRoundedLayer(ctx, cf.getTo(), width, height,
                    radii, borderWidth, borderColor, borderTop, borderBottom));
            return;
        }
        roundedRect(radii, borderWidth, borderColor, borderTop, borderBottom, fillOf(d)).draw(ctx, 0f, 0f, width, height);
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
        CgUiLayerBox originBox = originBox(box, style.get(StylePropertyRegistry.MASK_ORIGIN));
        LengthPercent offset = style.get(StylePropertyRegistry.MASK_OFFSET);
        float offsetX = offset == null ? 0f : offset.resolve(originBox.width());
        float offsetY = offset == null ? 0f : offset.resolve(originBox.height());
        CgUiLayerBox laid = CgUiLayerBox.resolve(source,
                originBox.x() - offsetX, originBox.y() - offsetY,
                Math.max(0f, originBox.width() + 2f * offsetX),
                Math.max(0f, originBox.height() + 2f * offsetY),
                style.get(StylePropertyRegistry.MASK_FIT), style.get(StylePropertyRegistry.MASK_POSITION));
        Radii radii = radiiOf(style, laid.width(), laid.height());
        ctx.setColor(WHITE);
        paintMaskShape(ctx, source, laid.x(), laid.y(), laid.width(), laid.height(), radii, borderWidth);
    }

    private static void paintMaskShape(CgUiPaintContext ctx, CgUiDrawable d, float x, float y, float width, float height,
                                       Radii radii, float borderWidth) {
        if (d instanceof CgUiCrossFade cf) {
            ctx.withLayerOpacity(1f - cf.getT(), () -> paintMaskShape(ctx, cf.getFrom(), x, y, width, height, radii, borderWidth));
            ctx.withLayerOpacity(cf.getT(), () -> paintMaskShape(ctx, cf.getTo(), x, y, width, height, radii, borderWidth));
            return;
        }
        // A mask that would reveal NOTHING reveals the whole shape instead -- `background: none` and
        // `background: #00000000` must clip the same way.
        RectFill fill = revealsNothing(d) ? new ColorFill(WHITE) : fillOf(d);
        CgUiRoundedRect mask = fillOnlyRect(radii, fill);
        if (borderWidth > 0f) mask.setBorder(borderWidth, 0x00000000);
        mask.draw(ctx, x, y, width, height);
    }

    // ── Overlay and outline ──────────────────────────────────────────────────

    private static void paintOverlay(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        ctx.setColor(WHITE);
        CgUiDrawable overlay = style.get(StylePropertyRegistry.OVERLAY);
        if (overlay == CgUiDrawable.EMPTY) return;
        // A mark takes the box's `color`; a picture keeps its own palette.
        if (overlay.followsTextColor()) ctx.setColor(style.get(StylePropertyRegistry.COLOR));
        CgUiLayerBox originBox = originBox(box, style.get(StylePropertyRegistry.OVERLAY_ORIGIN));
        CgUiLayerBox laid = CgUiLayerBox.resolve(overlay,
                originBox.x(), originBox.y(), originBox.width(), originBox.height(),
                style.get(StylePropertyRegistry.OVERLAY_FIT), style.get(StylePropertyRegistry.OVERLAY_POSITION));
        overlay.draw(ctx, laid.x(), laid.y(), laid.width(), laid.height());
    }

    private static void paintOutline(Box box, ComputedStyle style, CgUiPaintContext ctx, Radii radii) {
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
        Radii ring = radii.expand((insetLeft + insetRight) * 0.5f, (insetTop + insetBottom) * 0.5f);
        int color = style.get(StylePropertyRegistry.OUTLINE_COLOR);
        CgUiRoundedRect rect = fillOnlyRect(ring, new ColorFill(color & 0x00FFFFFF));
        rect.setBorder(stroke, color);
        rect.draw(ctx, -insetLeft, -insetTop, width + insetLeft + insetRight, height + insetTop + insetBottom);
    }

    private static float resolve(@Nullable LengthPercent lp, float against) {
        return lp == null ? 0f : lp.resolve(against);
    }

    /** One of the CSS box-model boxes, in the box's own space. */
    private static CgUiLayerBox originBox(Box box, @Nullable BoxOrigin origin) {
        float width = box.width(), height = box.height();
        if (origin == null || origin == BoxOrigin.BORDER_BOX) return new CgUiLayerBox(0f, 0f, width, height);
        FloatRect b = box.border();
        float l = b.left, t = b.top, r = b.right, bo = b.bottom;
        if (origin == BoxOrigin.CONTENT_BOX) {
            FloatRect p = box.padding();
            l += p.left;
            t += p.top;
            r += p.right;
            bo += p.bottom;
        }
        return new CgUiLayerBox(l, t, Math.max(0f, width - l - r), Math.max(0f, height - t - bo));
    }

    // ── Fills and radii ──────────────────────────────────────────────────────

    private sealed interface RectFill permits ColorFill, TextureFill, NineSliceFill {
    }

    private record ColorFill(int argb) implements RectFill {
    }

    private record TextureFill(CgTexture2D texture) implements RectFill {
    }

    private record NineSliceFill(CgUiSprite sprite) implements RectFill {
    }

    private static boolean canPaintRounded(CgUiDrawable d) {
        if (d instanceof CgUiCrossFade cf) return canPaintRounded(cf.getFrom()) && canPaintRounded(cf.getTo());
        return fillOf(d) != null;
    }

    private static @Nullable RectFill fillOf(CgUiDrawable d) {
        if (d == CgUiDrawable.EMPTY) return null;
        if (d instanceof CgUiQuad quad) return new ColorFill(quad.getColorArgb());
        if (d instanceof CgUiSprite sprite) {
            CgTexture2D texture = sprite.getTexture();
            if (texture == null) return null;
            return sprite.hasBorder() ? new NineSliceFill(sprite) : new TextureFill(texture);
        }
        return null;
    }

    private static boolean revealsNothing(CgUiDrawable d) {
        RectFill fill = fillOf(d);
        return fill == null || fill instanceof ColorFill(int argb) && (argb >>> 24) == 0;
    }

    private static CgUiRoundedRect fillOnlyRect(Radii radii, RectFill fill) {
        CgUiRoundedRect rect = new CgUiRoundedRect();
        rect.setCornerRadius(radii.rxTL, radii.ryTL, radii.rxTR, radii.ryTR, radii.rxBR, radii.ryBR, radii.rxBL, radii.ryBL);
        switch (fill) {
            case ColorFill(int argb) -> rect.setFillColor(argb);
            case TextureFill(CgTexture2D texture) -> rect.setFillTexture(texture);
            case NineSliceFill(CgUiSprite sprite) -> rect.setFillSprite(sprite);
        }
        return rect;
    }

    private static CgUiRoundedRect roundedRect(Radii radii, float borderWidth, int borderColor, int borderTop,
                                               int borderBottom, RectFill fill) {
        CgUiRoundedRect rect = fillOnlyRect(radii, fill);
        if (borderWidth > 0f) rect.setBorder(borderWidth, borderColor, borderTop, borderBottom);
        return rect;
    }

    /** The eight resolved corner radii of a box. */
    record Radii(float rxTL, float ryTL, float rxTR, float ryTR, float rxBR, float ryBR, float rxBL, float ryBL) {
        boolean isZero() {
            return rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f && rxBR == 0f && ryBR == 0f && rxBL == 0f && ryBL == 0f;
        }

        Radii expand(float dx, float dy) {
            return new Radii(grow(rxTL, dx), grow(ryTL, dy), grow(rxTR, dx), grow(ryTR, dy),
                    grow(rxBR, dx), grow(ryBR, dy), grow(rxBL, dx), grow(ryBL, dy));
        }

        private static float grow(float r, float d) {
            return r <= 0f ? 0f : Math.max(0f, r + d);
        }
    }

    static Radii radiiOf(ComputedStyle style, float width, float height) {
        return new Radii(
                resolve(style.get(BorderRadiusProperties.TOP_LEFT_X), width),
                resolve(style.get(BorderRadiusProperties.TOP_LEFT_Y), height),
                resolve(style.get(BorderRadiusProperties.TOP_RIGHT_X), width),
                resolve(style.get(BorderRadiusProperties.TOP_RIGHT_Y), height),
                resolve(style.get(BorderRadiusProperties.BOTTOM_RIGHT_X), width),
                resolve(style.get(BorderRadiusProperties.BOTTOM_RIGHT_Y), height),
                resolve(style.get(BorderRadiusProperties.BOTTOM_LEFT_X), width),
                resolve(style.get(BorderRadiusProperties.BOTTOM_LEFT_Y), height));
    }
}
