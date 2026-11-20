package com.crystalgui.ui.box;

import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.canvas.CgCanvasBox;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgCanvasLayer;
import com.crystalgraphics.api.canvas.CgKeptLayer;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.InkOverflow;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgui.render.texture.*;
import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.render.image.CgUiImagePainter;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.ClipPath;
import com.crystalgui.style.property.visual.BoxOrigin;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.geometry.FloatRect;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.joml.Matrix4f;

/**
 * The paint pass over a {@link BoxTree}: every box in paint order, each drawn in its OWN space with the canvas's
 * transform set from the matrix layout composed, so the picture and the hit-test read one definition of where a box
 * is, and nothing here writes a matrix anything else will read.
 *
 * <p>A box's transform is {@code base x localToWorld}, where {@code base} is the canvas's transform when the paint
 * began (the surface's) carried into whatever node the box is drawn in; a thumbnail is a second box with a second
 * matrix (5.4).</p>
 *
 * <p>The box model is the painter's: background (fill and border together, under the children as CSS stacks them),
 * then the node's {@link UIElement#paintContent content}, the children, the node's
 * {@link UIElement#paintDecoration decoration}, {@code overlay}, and {@code outline} last. A fractional
 * {@code opacity} is a canvas layer, or folded into the draw where nothing overlaps; {@code overflow} is a canvas clip,
 * rounded to the padding box; a {@code mask} drawable masks a layer. The drawables and the compositing are the
 * canvas's: this class only decides what is drawn where.</p>
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

    /** Paints the whole tree under the canvas's transform as the surface transform. */
    void paint(BoxTree tree, CgUiPaintContext ctx) {
        Box root = tree.root();
        if (root == null) return;
        paintBox(root, ctx, baseOf(ctx), true);
    }

    /**
     * Paints ONE box and what it hosts, under the canvas's transform as the surface transform.
     *
     * <p>For a caller drawing a subtree somewhere other than where it lives: a window photographing itself, a preview.
     * Nothing about the boxes is changed by it: the transform is {@code base x localToWorld}, computed per box and
     * never written back, so drawing a subtree a second time cannot disturb where hit-testing thinks it is.</p>
     *
     * <p>A LIVE second copy wants {@link BoxTree#mirror} instead, which lays the subtree out again and gives the copy
     * boxes of its own; this is for a one-shot the caller owns.</p>
     */
    public static void paintSubtree(Box box, CgUiPaintContext ctx) {
        BoxPainter painter = box.tree().painter;
        Matrix4f base = baseOf(ctx);
        // @see CgUiPaintContext#withoutRetention -- a copy drawn at other coordinates must not become what the live
        // tree thinks it last painted. AS A CONTEXT whether or not it is one: a subtree drawn alone paints all of it.
        ctx.withoutRetention(() -> painter.paintBox(box, ctx, base, true));
    }

    /** The canvas's transform now as a base. Once a paint. */
    private static Matrix4f baseOf(CgUiPaintContext ctx) {
        float[] t = ctx.canvas().transform(new float[6]);
        return new Matrix4f().m00(t[0]).m01(t[1]).m10(t[2]).m11(t[3]).m30(t[4]).m31(t[5]);
    }

    /** Sets the canvas's transform to {@code m}'s affine part. */
    private static void pose(CgCanvas canvas, Matrix4f m) {
        canvas.setTransform(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31());
    }

    /**
     * One box and what it paints. {@code asContext}: its stacking context's lists as well as its normal flow, true for
     * a stacking context and for the root of a paint.
     */
    private void paintBox(Box box, CgUiPaintContext ctx, Matrix4f base, boolean asContext) {
        UiSequence.checkpoint();
        float opacity = box.opacity();
        if (opacity <= 0f) return;
        // NOTHING OF IT CAN SHOW: a row scrolled past its list's edge draws nothing the clip would keep.
        if (CgUiPaintContext.CULL) {
            inkThrough(box, base);
            if (ctx.outsideClip(ink[0], ink[1], ink[2], ink[3])) {
                CgTrace.add(UiTrace.FRAME, "culled", 1);
                return;
            }
        }
        // A BOX A COMPOSITOR MOVES (`will-change: transform`) is drawn in its own space, under a node that is a
        // whole-pixel translation to its corner: a move is the node's value.
        if (CgUiPaintContext.NODES && box.willChangeTransform()) {
            Matrix4f origin = nodeOrigin.set(base).mul(box.localToWorld());
            movedWorld.set(origin);
            // AT ITS CORNER AT REST: its own transform is drawn over the node, so a flight leaves the node where it is.
            if (box.isTransformed()) origin.set(base).mul(box.restToWorld());
            float x = Math.round(origin.m30()), y = Math.round(origin.m31());
            CgCanvas canvas = ctx.canvas();
            canvas.save().setTransform(1f, 0f, 0f, 1f, 0f, 0f);
            try {
                box.noteMovedNode(canvas.movable(1f, 0f, 0f, 1f, x, y), ctx.frameId(), movedWorld);
                Matrix4f inner = new Matrix4f(base).translateLocal(-x, -y, 0f);
                // GLASS IN IT: drawn into the frame as any box, where its backdrop is the frame behind it -- a surface
                // holds only the box, so a filter inside one blurs nothing
                boolean glass = box.hasBackdrop();
                if (glass || !paintSurface(box, ctx, inner, asContext, opacity, true)) {
                    paintBoxIn(box, ctx, inner, asContext, opacity, false);
                }
                // ITS PICTURE TOO, while something wants it: drawn beside the frame with its glass flat.
                if (glass && box.node() instanceof Surface.Owner owner && owner.wantsPicture()) {
                    paintSurface(box, ctx, inner, asContext, opacity, false);
                }
            } finally {
                canvas.restore();
            }
            return;
        }
        paintBoxIn(box, ctx, base, asContext, opacity, false);
    }

    /** The kept-layer walk drawing lands in now, while a surface is being drawn; null otherwise. */
    @Nullable
    private CgKeptLayer.Walk walking;

    /**
     * A box the compositor moves, drawn into its surface and composited from it under its node, its opacity and any
     * compositor fade applied there: false, having drawn nothing, where it has none. {@code composite} false draws its
     * picture alone, composited nowhere: the frame has the box already.
     */
    private boolean paintSurface(Box box, CgUiPaintContext ctx, Matrix4f base, boolean asContext, float opacity,
                                 boolean composite) {
        // AT REST: its own transform goes into the composite, so a flight or a turn keeps the picture. Where it has one,
        // `base` becomes base x turn^-1, turn being the transform in world space, and the composite base x turn x base^-1.
        Matrix4f turn = null;
        if (box.isTransformed()) {
            // Fresh, not scratch: the walk below may reach another surface while these are still wanted.
            Matrix4f world = new Matrix4f(box.restToWorld()).invert().mulLocal(box.localToWorld());
            turn = new Matrix4f(base).mul(world).mul(surfaceScratch.set(base).invert());
            base = new Matrix4f(base).mul(world.invert());
        }
        inkThrough(box, base);
        float x0 = ink[0], y0 = ink[1], x1 = ink[2], y1 = ink[3];
        // KEYED BY ITS NODE, so a window shown again finds its picture; a mirror's box is not its node's.
        Object key = box.node().box() == box ? box.node() : box;
        Surface surface = ctx.surface(key, x0, y0, x1, y1);
        if (surface == null) return false;
        if (!box.retainable()) surface.walkAgain();
        CgCanvas canvas = ctx.canvas();
        boolean fades = composite && turn == null && box.animatesOnCompositor();
        canvas.save();
        try {
            int fade = fades ? canvas.fader(opacity) : 0;
            canvas.setTransform(1f, 0f, 0f, 1f, 0f, 0f);
            CgKeptLayer.Walk outer = walking;
            try (CgKeptLayer.Walk walk = surface.walk(ctx, box, box.innerRevision(), ctx.replayEpoch(),
                    box.tree().stackingEpoch(), x0, y0, x1, y1)) {
                // A turned box composites below, through its turn; a picture alone composites nowhere
                walk.opacity(!composite || turn != null ? 0f : fades ? 1f : opacity);
                if (walk.draws()) {
                    long degraded = ctx.textDegradedDrawCount();
                    walking = walk;
                    try {
                        paintBoxIn(box, ctx, base, asContext, 1f, true);
                    } finally {
                        walking = outer;
                    }
                    // TEXT STILL GENERATING was not kept: the surface comes back for it while nothing else changes.
                    if (ctx.textDegradedDrawCount() != degraded) surface.walkAgain();
                    // ITS PICTURE: the border box in the kept picture, whose corner is the walk's.
                    boundsThrough(0f, 0f, box.width(), box.height(), surfaceScratch.set(base).mul(box.localToWorld()));
                    surface.pictured(ink[0] - walk.x(), ink[1] - walk.y(), ink[2] - walk.x(), ink[3] - walk.y(),
                            box.width(), box.height());
                }
            }
            if (composite && turn != null) {
                if (outer != null) {
                    boundsThrough(x0, y0, x1, y1, turn);
                    box.layerPlacement().place(outer, ink[0], ink[1], ink[2] - ink[0], ink[3] - ink[1]);
                }
                pose(canvas, turn);
                canvas.opacity(opacity);
                surface.composite(ctx);
            }
            if (composite) box.noteFadedNode(fade, ctx.frameId());
        } finally {
            canvas.restore();
        }
        return true;
    }

    private final Matrix4f surfaceScratch = new Matrix4f();

    /**
     * {@link #paintBox} once it is known to paint, in the node it draws in. {@code surface}: drawn into its own
     * surface, whose composite takes its opacity and fade, so it opens no group layer for either.
     */
    private void paintBoxIn(Box box, CgUiPaintContext ctx, Matrix4f base, boolean asContext, float opacity,
                            boolean surface) {
        UIElement node = box.node();
        ComputedStyle style = node.computedStyle();
        CgCanvas canvas = ctx.canvas();
        canvas.save();
        pose(canvas, poseScratch.set(base).mul(box.localToWorld()));
        try {
            if (!clipPath(box, style, canvas)) return;
            Radii radii = radiiOf(style, box.width(), box.height());
            boolean clips = box.clips();
            boolean shaped = clips && (!radii.isZero() || style.get(StylePropertyRegistry.MASK) != CgUiImage.NONE);
            boolean square = clips && !shaped;
            // A SHAPE WITH NOTHING TO CLIP: it applies to the children and to nothing else.
            if (shaped && box.children().isEmpty()) {
                CgTrace.add(UiTrace.FRAME, "masks-elided", 1);
                shaped = false;
            }
            // A compositor fading the box needs its effect node even at full opacity.
            boolean fades = !surface && box.animatesOnCompositor();
            boolean group = opacity < 1f || fades;

            // AN OPACITY THAT CANNOT SELF-OVERLAP folds into the draw instead of grouping a subtree: Skia's rule, and
            // Flutter's advice to colour a container rather than wrap it in an `Opacity`. Group opacity differs from
            // per-primitive opacity only where two primitives cover the same pixel.
            if (group && !shaped && !fades && foldsOpacity(box, style, node)) {
                CgTrace.add(UiTrace.FRAME, "layers-elided", 1);
                canvas.opacity(opacity);
                ctx.beginSegment();
                paintSelf(box, style, ctx, radii);
                paintOverlay(box, style, ctx);
                paintOutline(box, style, ctx);
                ctx.endSegment();
                place(box, ctx, BoxReplay.BEFORE, true);
                return;
            }

            if (!group) {
                paintOwnBefore(box, style, node, ctx, radii);
                if (shaped) clipChildren(box, style, ctx, base, asContext);
                else paintChildren(box, ctx, base, square, asContext);
                paintOwnAfter(box, style, node, ctx);
                return;
            }

            // A GROUP: the subtree composited as one picture at its opacity, over its ink.
            CgTrace.add(UiTrace.FRAME, "layers-opacity", 1);
            inkThrough(box, base);
            // Who pays the layer, on the blame channel: its kilopixels, by element.
            if (CgTrace.isEnabled(UiTrace.BLAME)) {
                CgTrace.add(UiTrace.FRAME, "~layer-kpx opacity " + layerLabel(node),
                        (long) ((ink[2] - ink[0]) * (ink[3] - ink[1]) / 1000f));
            }
            if (walking != null) {
                // ITS COMPOSITE lands in the surface at its ink, every walk: its opacity is not in its segments' keys
                canvas.save().setTransform(1f, 0f, 0f, 1f, 0f, 0f);
                box.layerPlacement().place(walking, ink[0], ink[1], ink[2] - ink[0], ink[3] - ink[1]);
                canvas.restore();
            }
            int fade = fades ? canvas.fader(opacity) : 0;
            pose(canvas, base);
            try (CgCanvasLayer layer = canvas.layer(box.inkX0(), box.inkY0(), box.inkX1() - box.inkX0(),
                    box.inkY1() - box.inkY0())) {
                if (!fades) layer.opacity(opacity);
                pose(canvas, poseScratch.set(base).mul(box.localToWorld()));
                paintOwnBefore(box, style, node, ctx, radii);
                if (shaped) clipChildren(box, style, ctx, base, asContext);
                else paintChildren(box, ctx, base, square, asContext);
                // Inside the layer, so the outline fades with the box: CSS puts it in the opacity group.
                paintOwnAfter(box, style, node, ctx);
            }
            box.noteFadedNode(fade, ctx.frameId());
        } finally {
            canvas.restore();
        }
    }

    /** {@link #paintBoxIn}'s transform scratch: set on the canvas at once, never read after a child paints. */
    private final Matrix4f poseScratch = new Matrix4f();

    /**
     * {@code clip-path: inset()} over the box and everything it hosts, as a clip in the box's own space; false when
     * nothing is left to draw.
     *
     * <p>Grown by the box's own outline, which {@link #paintOutline} rings around the clip rather than the box: a panel
     * revealed by its clip keeps its rim on every edge of what shows.</p>
     */
    private boolean clipPath(Box box, ComputedStyle style, CgCanvas canvas) {
        if (!clipRect(box, style, clipPathRect)) return true;
        float left = clipPathRect[0], top = clipPathRect[1], width = clipPathRect[2], height = clipPathRect[3];
        if (width <= 0f || height <= 0f) return false;
        float ring = outlineReach(box, style);
        float rx = Math.min(clipPathRect[4], width / 2f), ry = Math.min(clipPathRect[5], height / 2f);
        // Grown as the ring's own corners are, a square clip staying square
        float gx = rx <= 0f ? 0f : rx + ring, gy = ry <= 0f ? 0f : ry + ring;
        CgCornerRadii r = box.clipPathRadii;
        if (r == null || r.topLeftX() != gx || r.topLeftY() != gy) box.clipPathRadii = r = CgCornerRadii.elliptical(gx, gy);
        canvas.clip(left - ring, top - ring, width + 2f * ring, height + 2f * ring, r);
        return true;
    }

    /**
     * The box's {@code clip-path} as {@code left, top, width, height, rx, ry} in its own space, or false for none.
     * The radii are {@code round} resolved against the clip's width and height, unclamped.
     */
    static boolean clipRect(Box box, ComputedStyle style, float[] into) {
        ClipPath clip = style.get(StylePropertyRegistry.CLIP_PATH);
        if (clip == null || clip.isNone()) return false;
        float w = box.width(), h = box.height();
        into[0] = clip.left().resolve(w);
        into[1] = clip.top().resolve(h);
        into[2] = w - into[0] - clip.right().resolve(w);
        into[3] = h - into[1] - clip.bottom().resolve(h);
        into[4] = clip.round().resolve(Math.max(0f, into[2]));
        into[5] = clip.round().resolve(Math.max(0f, into[3]));
        return true;
    }

    /** How far past its edge a box's stroked outline reaches, offsets included; zero with none. */
    private static float outlineReach(Box box, ComputedStyle style) {
        LengthPercent strokeLp = style.get(StylePropertyRegistry.OUTLINE_WIDTH);
        float stroke = strokeLp == null ? 0f : strokeLp.resolve(box.width());
        if (stroke <= 0f) return 0f;
        float w = box.width(), h = box.height();
        return stroke + Math.max(Math.max(resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_TOP), h),
                        resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM), h)),
                Math.max(resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_LEFT), w),
                        resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT), w)));
    }

    /** @see #clipPath -- {@code left, top, width, height, rx, ry}. */
    private final float[] clipPathRect = new float[6];

    /**
     * A shaped box's children, clipped to its padding box with its inner corners, and through its {@code mask}
     * drawable where it has one. The canvas's transform on entry is the box's own, and is again on return.
     */
    private void clipChildren(Box box, ComputedStyle style, CgUiPaintContext ctx, Matrix4f base, boolean asContext) {
        CgCanvas canvas = ctx.canvas();
        canvas.save();
        try {
            shapeClip(box, style, canvas);
            CgUiImage source = style.get(StylePropertyRegistry.MASK);
            if (source == CgUiImage.NONE) {
                CgTrace.add(UiTrace.FRAME, "clips-rounded", 1);
                paintChildren(box, ctx, base, false, asContext);
                return;
            }
            CgTrace.add(UiTrace.FRAME, "layers-mask", 1);
            layMask(box, style, source);
            try (CgCanvasLayer layer = canvas.layer(laid[0], laid[1], laid[2], laid[3])) {
                maskWith(layer, source);
                paintChildren(box, ctx, base, false, asContext);
            }
        } finally {
            canvas.restore();
        }
    }

    /**
     * {@code layer} masked by a {@code mask} image: a colour by its alpha, a picture by its texels' alpha. One that
     * would reveal nothing (a clear colour) or that is not a picture reveals the whole shape instead.
     */
    private void maskWith(CgCanvasLayer layer, CgUiImage source) {
        // A cross-fade masks with the side it is nearer: a mask's own transition is not drawn
        while (source instanceof CgUiImage.CrossFade f) source = f.t() < 0.5f ? f.from() : f.to();
        switch (source) {
            case CgUiImage.Color c -> {
                if ((c.argb() >>> 24) != 0) layer.opacity((c.argb() >>> 24) / 255f);
            }
            case CgUiImage.Picture picture -> layer.mask(CgBrush.texture(images.texture(picture)));
            case CgUiImage.Sheet sheet -> {
                CgTexture texture = images.texture(sheet);
                if (texture != null) layer.mask(CgBrush.texture(texture));
            }
            default -> {
            }
        }
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
     * Where {@code segment} just drew in the surface being walked, if one is: the bounds of its draws, or its own ink
     * where those are not known. {@code changed}: painted, not replayed. @see Surface
     */
    private void place(Box box, CgUiPaintContext ctx, int segment, boolean changed) {
        CgKeptLayer.Walk walk = walking;
        if (walk == null) return;
        // WHAT IT DREW, not what it might: an overlay sized to a whole canvas that drew one outline damages the outline.
        if (!ctx.segmentDrawn(ink)) {
            float[] t = ctx.canvas().transform(affine);
            boundsThrough(box.localInkL, box.localInkT, box.localInkR, box.localInkB, t[0], t[1], t[2], t[3], t[4], t[5]);
        }
        if (box.replay == null) box.replay = new BoxReplay();
        box.replay.place(ctx.canvas(), walk, segment, ink[0], ink[1], ink[2], ink[3], changed);
    }

    /** Check mode: a segment whose key holds is painted and compared rather than replayed. */
    private static boolean checked(Box box, int segment) {
        return CgUiPaintContext.REPLAY_CHECK && box.replay.kept(segment);
    }

    /** Ends a box's segment, keeping what it drew when a key speaks for it, or comparing it in check mode. */
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
            box.replay.kept(segment, ctx.endSegment(box.replay.chunk(segment)));
        }
    }

    /** A layer's owner as a profile names it: its tag, then its first class. */
    private static String layerLabel(UIElement node) {
        String tag = node.tagName();
        for (String cls : node.getClasses()) return tag + "." + cls;
        return tag;
    }

    /**
     * Whether {@code opacity} can be multiplied into this box's own draw rather than grouping it through a layer.
     *
     * <p>It can when nothing the box paints can land on top of anything else it paints: one background, or one
     * overlay, or one outline, and no children. Two primitives over one pixel is exactly where group opacity and
     * per-primitive opacity part company: {@code 0.5} over {@code 0.5} composites to {@code 0.75} drawn separately and
     * to {@code 0.5} drawn as a group.</p>
     *
     * <p><b>A node that paints its own content is never folded</b>, even a leaf: the canvas's opacity does not fade
     * text, whose colour is its own, so a folded label would ignore the fade. That is also why the question is asked of
     * the CLASS rather than of the style. {@code backdrop-filter} is excluded for its own reason: it composites what
     * is behind the element, which is not this element's paint to scale.</p>
     */
    private static boolean foldsOpacity(Box box, ComputedStyle style, UIElement node) {
        if (!box.children().isEmpty()) return false;
        if (node.paintsItsOwnContent()) return false;
        if (style.get(StylePropertyRegistry.BACKDROP_FILTER) != CgBackdropFilter.NONE) return false;

        int primitives = 0;
        CgUiImage background = style.get(StylePropertyRegistry.BACKGROUND);
        CgUiImage overlay = style.get(StylePropertyRegistry.OVERLAY);
        // An image that covers a pixel twice is already two primitives on its own: a stack, a cross-fade, a vector
        // whose paths cross. @see CgUiImage#onePrimitive
        if (!background.onePrimitive() || !overlay.onePrimitive()) return false;
        if (background != CgUiImage.NONE || style.isSet(StylePropertyRegistry.BACKGROUND_COLOR)) primitives++;
        if (overlay != CgUiImage.NONE) primitives++;
        LengthPercent stroke = style.get(StylePropertyRegistry.OUTLINE_WIDTH);
        if (style.get(StylePropertyRegistry.OUTLINE) != CgUiImage.NONE
                || stroke != null && stroke.resolve(box.width()) > 0f) primitives++;
        return primitives <= 1;
    }

    /**
     * The box's subtree ink bounds carried through {@code base} into the device pixels of the node drawn in, written to
     * {@link #ink} as {@code x0, y0, x1, y1}. Asked per box by the cull, so into scratch rather than a new rectangle.
     */
    private void inkThrough(Box box, Matrix4f base) {
        boundsThrough(box.inkX0(), box.inkY0(), box.inkX1(), box.inkY1(), base);
    }

    /** The rectangle {@code (x0, y0)-(x1, y1)} through {@code base}, its bounding box written to {@link #ink}. */
    private void boundsThrough(float x0, float y0, float x1, float y1, Matrix4f base) {
        boundsThrough(x0, y0, x1, y1, base.m00(), base.m01(), base.m10(), base.m11(), base.m30(), base.m31());
    }

    /** The rectangle through {@code x' = a x + c y + tx, y' = b x + d y + ty}, its bounding box written to {@link #ink}. */
    private void boundsThrough(float x0, float y0, float x1, float y1, float a, float b, float c, float d, float tx,
                               float ty) {
        float ax = a * x0 + c * y0 + tx, ay = b * x0 + d * y0 + ty;
        float bx = a * x1 + c * y0 + tx, by = b * x1 + d * y0 + ty;
        float cx = a * x1 + c * y1 + tx, cy = b * x1 + d * y1 + ty;
        float dx = a * x0 + c * y1 + tx, dy = b * x0 + d * y1 + ty;
        ink[0] = Math.min(Math.min(ax, bx), Math.min(cx, dx));
        ink[1] = Math.min(Math.min(ay, by), Math.min(cy, dy));
        ink[2] = Math.max(Math.max(ax, bx), Math.max(cx, dx));
        ink[3] = Math.max(Math.max(ay, by), Math.max(cy, dy));
    }

    /** {@link #keyOf}'s answers: no key speaks for the segment, it differs from last time, or it is the same. */
    private static final int UNKEYED = 0, CHANGED = 1, SAME = 2;

    /**
     * Whether {@code segment} would draw what it drew last time, counted: `segments-unchanged` against `-changed`, and
     * `-dynamic`, a widget painting what the tree cannot see, which no key speaks for.
     */
    private int keyOf(Box box, UIElement node, CgUiPaintContext ctx, int segment) {
        if (node.paintsDynamically()) {
            CgTrace.add(UiTrace.FRAME, "segments-dynamic", 1);
            if (box.replay != null) box.replay.forget(segment);
            return UNKEYED;
        }
        CgCanvas canvas = ctx.canvas();
        float[] t = canvas.transform(affine);
        float[] clip = canvas.clipBounds(segmentClip);
        boundsThrough(box.localInkL, box.localInkT, box.localInkR, box.localInkB, t[0], t[1], t[2], t[3], t[4], t[5]);
        float[] k = segmentKey;
        System.arraycopy(t, 0, k, 0, 6);
        k[6] = Math.max(ink[0], clip[0]);
        k[7] = Math.max(ink[1], clip[1]);
        k[8] = Math.min(ink[2], clip[2]);
        k[9] = Math.min(ink[3], clip[3]);
        k[10] = canvas.currentOpacity();
        if (box.replay == null) box.replay = new BoxReplay();
        boolean same = box.replay.sameAs(segment, k, box.contentRevision, ctx.replayEpoch());
        CgTrace.add(UiTrace.FRAME, same ? "segments-unchanged" : "segments-changed", 1);
        return same ? SAME : CHANGED;
    }

    private final float[] segmentKey = new float[BoxReplay.KEY_FLOATS], segmentClip = new float[4];
    private final float[] affine = new float[6];

    /** {@link #inkThrough}'s answer. Read immediately: a tree paints one box at a time. */
    private final float[] ink = new float[4];

    /**
     * What {@code box} hosts, inside its clip: its negative {@code z-index} boxes, its normal flow and its other
     * z-ordered boxes when it paints as a context, and its normal flow alone otherwise.
     */
    private void paintChildren(Box box, CgUiPaintContext ctx, Matrix4f base, boolean square, boolean asContext) {
        if (box.children().isEmpty()) return;
        CgCanvas canvas = ctx.canvas();
        canvas.save();
        try {
            // The PADDING box, as CSS clips: border excluded, padding included. In this box's own space.
            if (square) paddingClip(box, canvas);
            // SCROLLED CONTENT IS RECORDED IN ITS OWN SPACE, under a node whose value is the scroll: a compositor scrolls
            // it by moving the node, with nothing recorded again. A scroll-exempt child does not move with it.
            // A TRANSLATION BY WHOLE PIXELS, the scale left in the transform: text snaps to the pixel grid through it.
            int content = 0;
            Matrix4f contentBase = base;
            if (CgUiPaintContext.NODES && box.isScrollContainer()
                    && (box.maxScrollLeft() > 0f || box.maxScrollTop() > 0f)) {
                Matrix4f origin = nodeOrigin.set(base).mul(box.localToWorld())
                        .translate(-box.scrollLeft(), -box.scrollTop(), 0f);
                float x = Math.round(origin.m30()), y = Math.round(origin.m31());
                canvas.save().setTransform(1f, 0f, 0f, 1f, 0f, 0f);
                content = canvas.movable(1f, 0f, 0f, 1f, x, y);
                canvas.restore();
                contentBase = new Matrix4f(base).translateLocal(-x, -y, 0f);
                box.noteScrolledNode(content, ctx.frameId());
            }
            StackingOrder order = asContext ? box.stackingOrder() : null;
            if (order != null) paintLiftedIn(order.negative, box, ctx, content, contentBase);
            List<Box> children = box.children();
            boolean inside = false;
            try {
                for (int i = 0; i < children.size(); i++) {
                    Box child = children.get(i);
                    if (child.isZOrdered()) continue;
                    boolean scrolls = content != 0 && !child.node().isScrollExempt();
                    if (scrolls != inside) {
                        if (inside) canvas.restore();
                        else canvas.save().enter(content);
                        inside = scrolls;
                    }
                    paintBox(child, ctx, scrolls ? contentBase : base, false);
                }
            } finally {
                if (inside) canvas.restore();
            }
            if (order != null) {
                paintLiftedIn(order.zero, box, ctx, content, contentBase);
                paintLiftedIn(order.positive, box, ctx, content, contentBase);
                paintLiftedIn(order.top, box, ctx, content, contentBase);
            }
        } finally {
            canvas.restore();
        }
    }

    /** A context's list painted in its scrolled content's node, when it has one. */
    private void paintLiftedIn(List<Box> lifted, Box context, CgUiPaintContext ctx, int content, Matrix4f contentBase) {
        if (lifted.isEmpty()) return;
        if (content == 0) {
            paintLifted(lifted, context, ctx, contentBase);
            return;
        }
        CgCanvas canvas = ctx.canvas();
        canvas.save().enter(content);
        try {
            paintLifted(lifted, context, ctx, contentBase);
        } finally {
            canvas.restore();
        }
    }

    /** {@link #paintChildren}'s scratch for a scroll node's origin, read at once. */
    private final Matrix4f nodeOrigin = new Matrix4f();
    /** A moved box's world before rounding, handed to the box at once. */
    private final Matrix4f movedWorld = new Matrix4f();

    /** Clips to the box's padding box, in its own space: the transform now. */
    private static void paddingClip(Box box, CgCanvas canvas) {
        FloatRect b = box.border();
        canvas.clip(b.left, b.top, Math.max(0f, box.width() - b.left - b.right),
                Math.max(0f, box.height() - b.top - b.bottom));
    }

    /**
     * Clips to the box's padding box with its inner corners, CSS's overflow clip of a rounded box: each outer radius
     * less the border on its side. The transform now is the box's own.
     */
    private void shapeClip(Box box, ComputedStyle style, CgCanvas canvas) {
        Radii r = radiiOf(style, box.width(), box.height());
        FloatRect b = box.border();
        float tlx = Math.max(0f, r.rxTL - b.left), tly = Math.max(0f, r.ryTL - b.top);
        float trx = Math.max(0f, r.rxTR - b.right), try_ = Math.max(0f, r.ryTR - b.top);
        float brx = Math.max(0f, r.rxBR - b.right), bry = Math.max(0f, r.ryBR - b.bottom);
        float blx = Math.max(0f, r.rxBL - b.left), bly = Math.max(0f, r.ryBL - b.bottom);
        CgCornerRadii kept = box.clipRadii;
        if (kept == null || kept.topLeftX() != tlx || kept.topLeftY() != tly || kept.topRightX() != trx
                || kept.topRightY() != try_ || kept.bottomRightX() != brx || kept.bottomRightY() != bry
                || kept.bottomLeftX() != blx || kept.bottomLeftY() != bly) {
            box.clipRadii = kept = new CgCornerRadii(tlx, tly, trx, try_, brx, bry, blx, bly);
        }
        canvas.clip(b.left, b.top, Math.max(0f, box.width() - b.left - b.right),
                Math.max(0f, box.height() - b.top - b.bottom), kept);
    }

    /** A context's list, each box painted through the clips of every box between it and {@code context}. */
    private void paintLifted(List<Box> lifted, Box context, CgUiPaintContext ctx, Matrix4f base) {
        for (int i = 0; i < lifted.size(); i++) {
            Box box = lifted.get(i);
            // CULLED BEFORE THE WALK UP: a context lists every realised row of every virtualised list under it.
            if (CgUiPaintContext.CULL) {
                inkThrough(box, base);
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
     * The boxes between {@code lifted} and {@code context} that clip, innermost first, in a list held for this nesting
     * depth: a lifted box's paint can reach another context's lifted boxes before it is done with its own. The caller
     * releases it by decrementing {@link #clipDepth}.
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
     * {@code lifted} under {@code clips[0..at]}, outermost applied first: each clip in its own box's space, as the box
     * would have clipped it had the lifted box stayed in its flow.
     */
    private void paintClipped(Box lifted, List<Box> clips, int at, CgUiPaintContext ctx, Matrix4f base) {
        if (at < 0) {
            paintBox(lifted, ctx, base, lifted.isStackingContext());
            return;
        }
        Box clip = clips.get(at);
        ComputedStyle style = clip.node().computedStyle();
        CgCanvas canvas = ctx.canvas();
        canvas.save();
        try {
            pose(canvas, poseScratch.set(base).mul(clip.localToWorld()));
            boolean square = radiiOf(style, clip.width(), clip.height()).isZero()
                    && style.get(StylePropertyRegistry.MASK) == CgUiImage.NONE;
            if (square) {
                paddingClip(clip, canvas);
            } else {
                shapeClip(clip, style, canvas);
                CgUiImage source = style.get(StylePropertyRegistry.MASK);
                if (source != CgUiImage.NONE) {
                    CgTrace.add(UiTrace.FRAME, "layers-mask", 1);
                    layMask(clip, style, source);
                    try (CgCanvasLayer layer = canvas.layer(laid[0], laid[1], laid[2], laid[3])) {
                        maskWith(layer, source);
                        paintClipped(lifted, clips, at - 1, ctx, base);
                    }
                    return;
                }
            }
            paintClipped(lifted, clips, at - 1, ctx, base);
        } finally {
            canvas.restore();
        }
    }

    // ── Background ───────────────────────────────────────────────────────────

    private void paintSelf(Box box, ComputedStyle style, CgUiPaintContext ctx, Radii radii) {
        float width = box.width(), height = box.height();

        // THE BACKDROP FIRST, and it is not the background: `backdrop-filter` acts on what is BEHIND the element, so the
        // element's own background is drawn over the result. It clips itself to the radii.
        CgBackdropFilter backdrop = style.get(StylePropertyRegistry.BACKDROP_FILTER);
        CgCornerRadii corners = corners(box, radii);
        if (backdrop != CgBackdropFilter.NONE) {
            CgCanvasBox pane = ctx.canvas().box(0f, 0f, width, height).backdropFilter(backdrop);
            if (corners != null) pane.radii(corners);
            pane.draw();
        }

        CgUiImage background = style.get(StylePropertyRegistry.BACKGROUND);
        // background-color tints the image; with none, and set, it is the fill. Its default is white (a no-op tint),
        // so whether one was authored is read off the snapshot.
        int backgroundColor = style.get(StylePropertyRegistry.BACKGROUND_COLOR);
        CgUiImagePainter.Border edge = border(box, style);
        if (background == CgUiImage.NONE) {
            if (style.isSet(StylePropertyRegistry.BACKGROUND_COLOR)) {
                images.fill(ctx, backgroundColor, 0f, 0f, width, height, corners, edge);
            } else {
                images.paint(ctx, CgUiImage.NONE, 0f, 0f, width, height, WHITE, WHITE, corners, edge);
            }
            return;
        }
        images.paint(ctx, background, 0f, 0f, width, height, backgroundColor, WHITE, corners, edge);
    }

    /** The box's border as the image painter takes it, or null with none: each side at its own width and colour. */
    @Nullable
    private CgUiImagePainter.Border border(Box box, ComputedStyle style) {
        if (borderSides(box) <= 0f) return null;
        int color = style.get(StylePropertyRegistry.BORDER_COLOR);
        CgUiImagePainter.Border e = edge;
        e.left = sides[0];
        e.top = sides[1];
        e.right = sides[2];
        e.bottom = sides[3];
        e.color = color;
        e.topColor = edgeColor(style.get(StylePropertyRegistry.BORDER_TOP_COLOR), color);
        e.bottomColor = edgeColor(style.get(StylePropertyRegistry.BORDER_BOTTOM_COLOR), color);
        e.leftColor = edgeColor(style.get(StylePropertyRegistry.BORDER_LEFT_COLOR), color);
        e.rightColor = edgeColor(style.get(StylePropertyRegistry.BORDER_RIGHT_COLOR), color);
        return e;
    }

    /** {@link #border}'s scratch, read before another box paints. */
    private final CgUiImagePainter.Border edge = new CgUiImagePainter.Border();

    /** The box's corners as the canvas takes them, kept on the box while they hold; null when square. */
    @Nullable
    private static CgCornerRadii corners(Box box, Radii r) {
        if (r.isZero()) return null;
        return box.paintRadii = keep(box.paintRadii, r);
    }

    /** What every image of the tree is drawn by: its resolved resources are shared, its scratch is this painter's. */
    private final CgUiImagePainter images = new CgUiImagePainter();

    /**
     * The box's four border widths into {@link #sides}, answering the widest: a border is drawn when any side has one.
     * Each side at its own width.
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

    /** Lays the {@code mask} drawable's rectangle out into {@link #laid}. */
    private void layMask(Box box, ComputedStyle style, CgUiImage source) {
        originBox(box, style.get(StylePropertyRegistry.MASK_ORIGIN), originBox);
        LengthPercent offset = style.get(StylePropertyRegistry.MASK_OFFSET);
        float offsetX = offset == null ? 0f : offset.resolve(originBox[2]);
        float offsetY = offset == null ? 0f : offset.resolve(originBox[3]);
        images.fit(source, originBox[0] - offsetX, originBox[1] - offsetY,
                Math.max(0f, originBox[2] + 2f * offsetX), Math.max(0f, originBox[3] + 2f * offsetY),
                style.get(StylePropertyRegistry.MASK_SIZE), style.get(StylePropertyRegistry.MASK_POSITION), laid);
    }

    // ── Overlay and outline ──────────────────────────────────────────────────

    private void paintOverlay(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        CgUiImage overlay = style.get(StylePropertyRegistry.OVERLAY);
        if (overlay == CgUiImage.NONE) return;
        originBox(box, style.get(StylePropertyRegistry.OVERLAY_ORIGIN), originBox);
        images.fit(overlay, originBox[0], originBox[1], originBox[2], originBox[3],
                style.get(StylePropertyRegistry.OVERLAY_SIZE), style.get(StylePropertyRegistry.OVERLAY_POSITION), laid);
        // A mark takes the box's `color`; a picture keeps its own palette, multiplied by `overlay-tint`
        int color = overlay.followsColor() ? style.get(StylePropertyRegistry.COLOR) : WHITE;
        images.paint(ctx, overlay, laid[0], laid[1], laid[2], laid[3], color,
                style.get(StylePropertyRegistry.OVERLAY_TINT), null, null);
    }

    private void paintOutline(Box box, ComputedStyle style, CgUiPaintContext ctx) {
        float width = box.width(), height = box.height();
        CgUiImage outline = style.get(StylePropertyRegistry.OUTLINE);
        LengthPercent strokeLp = style.get(StylePropertyRegistry.OUTLINE_WIDTH);
        float stroke = strokeLp == null ? 0f : strokeLp.resolve(width);
        boolean hasDrawable = outline != CgUiImage.NONE;
        if (!hasDrawable && stroke <= 0f) return;
        float top = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_TOP), height);
        float bottom = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM), height);
        float left = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_LEFT), width);
        float right = resolve(style.get(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT), width);
        if (hasDrawable) {
            images.paint(ctx, outline, -left, -top, Math.max(0f, width + left + right),
                    Math.max(0f, height + top + bottom), WHITE, WHITE, null, null);
            return;
        }
        // The stroke measures inward from the shape's outer edge; a CSS outline grows outward from the offset edge --
        // so inflate by offset + width and let the inward stroke land in the band.
        float insetTop = top + stroke, insetBottom = bottom + stroke, insetLeft = left + stroke, insetRight = right + stroke;
        // AROUND WHAT SHOWS while a clip-path cuts the box, its corners the clip's: the rim follows the reveal.
        float x = 0f, y = 0f;
        Radii ring;
        if (clipRect(box, style, outlineClip)) {
            x = outlineClip[0];
            y = outlineClip[1];
            width = Math.max(0f, outlineClip[2]);
            height = Math.max(0f, outlineClip[3]);
            float rx = Math.min(outlineClip[4], width / 2f), ry = Math.min(outlineClip[5], height / 2f);
            ring = radii;
            ring.rxTL = ring.rxTR = ring.rxBR = ring.rxBL = rx;
            ring.ryTL = ring.ryTR = ring.ryBR = ring.ryBL = ry;
            ring.expand((insetLeft + insetRight) * 0.5f, (insetTop + insetBottom) * 0.5f);
        } else {
            // RESOLVED AGAIN rather than carried from paintBox: the children painted in between share the one scratch.
            ring = radiiOf(style, width, height)
                    .expand((insetLeft + insetRight) * 0.5f, (insetTop + insetBottom) * 0.5f);
        }
        int color = style.get(StylePropertyRegistry.OUTLINE_COLOR);
        CgCanvasBox rim = ctx.canvas().box(x - insetLeft, y - insetTop, width + insetLeft + insetRight,
                height + insetTop + insetBottom).fill(color & 0x00FFFFFF).border(stroke, color);
        if (!ring.isZero()) rim.radii(ringRadii = keep(ringRadii, ring));
        rim.draw();
    }

    /** @see #paintOutline -- the clip it rings, apart from {@link #clipPathRect}, which a child overwrites. */
    private final float[] outlineClip = new float[6];

    private static float resolve(@Nullable LengthPercent lp, float against) {
        return lp == null ? 0f : lp.resolve(against);
    }

    /**
     * How far this box paints in its OWN space, as {@code left, top, right, bottom}: its border box grown by the
     * outline and by whatever the node says it draws beyond it.
     *
     * <p>Here rather than in {@link BoxTree} because it is the same arithmetic {@link #paintOutline} does, and a layer
     * sized from a different answer than the one the painter draws is a clipped outline nobody looks for. The
     * subtree's share, and the clip {@code overflow} imposes on it, are the tree's: this is one box.</p>
     */
    static void localInk(Box box, float[] ltrb) {
        ComputedStyle style = box.node().computedStyle();
        float width = box.width(), height = box.height();
        float left = 0f, top = 0f, right = width, bottom = height;

        CgUiImage outline = style.get(StylePropertyRegistry.OUTLINE);
        LengthPercent strokeLp = style.get(StylePropertyRegistry.OUTLINE_WIDTH);
        float stroke = strokeLp == null ? 0f : strokeLp.resolve(width);
        boolean hasDrawable = outline != CgUiImage.NONE;
        if (hasDrawable || stroke > 0f) {
            // A drawable outline fills the offset rect; a stroked one grows outward from it by its own width. Offsets
            // are commonly NEGATIVE here (`*` sets -1px), so this can subtract.
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

    /** The outline ring's corners as last drawn: a run of equal rings shares one. */
    @Nullable
    private CgCornerRadii ringRadii;

    /** {@code kept} while it equals {@code r}, else {@code r} as the canvas takes it. */
    private static CgCornerRadii keep(@Nullable CgCornerRadii kept, Radii r) {
        if (kept != null && kept.topLeftX() == r.rxTL && kept.topLeftY() == r.ryTL && kept.topRightX() == r.rxTR
                && kept.topRightY() == r.ryTR && kept.bottomRightX() == r.rxBR && kept.bottomRightY() == r.ryBR
                && kept.bottomLeftX() == r.rxBL && kept.bottomLeftY() == r.ryBL) {
            return kept;
        }
        return new CgCornerRadii(r.rxTL, r.ryTL, r.rxTR, r.ryTR, r.rxBR, r.ryBR, r.rxBL, r.ryBL);
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
        // OVERLAPPING CURVES, CSS Backgrounds 3 section 5.5: where two radii on one side add up past it, EVERY radius is
        // scaled by the one factor that makes them fit.
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
