package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

/**
 * One node's border box, in an overlay's own space.
 *
 * <pre>{@code
 * float[] r = CanvasRects.of(node, overlay);
 * if (r != null) ctx.fillRect(r[0], r[1], r[2], r[3], colour);
 * }</pre>
 *
 * <p><b>Through the matrices, never by subtracting positions.</b> {@code Box.x()} is parent-relative, so
 * {@code target.x() - overlay.x()} is meaningful only when the two share a parent and is silently wrong
 * otherwise — wrong by an amount that depends on how deep in the tree they are. Between a canvas overlay
 * and a node inside an artboard there are a pan, a zoom and the artboard's own scale, and this carries
 * all three because it transforms two corners rather than translating one.</p>
 *
 * <p>Null whenever there is nothing laid out to measure. That is the ordinary state, not a failure: a
 * node that is hidden, frozen, {@code display: none} or momentarily out of the tree has no box, and a
 * canvas overlay is pointed at exactly the kind of tree that does all four while being looked at.</p>
 */
public final class CanvasRects {

    private CanvasRects() {
    }

    /**
     * {@code target}'s DRAWN border box as {x, y, width, height} in {@code space}'s own coordinates.
     *
     * <p>Carries the element's own {@code transform}, so on a scaled or rotated element this is the
     * axis-aligned bounds of what is painted rather than the box the layout computed. For chrome that
     * states or edits geometry, use {@link #ofLayout} instead.</p>
     */
    @Nullable
    public static float[] of(@Nullable UIElement target, @Nullable UIElement space) {
        return of(target == null ? null : target.box(), space == null ? null : space.box());
    }

    /** @see #of(UIElement, UIElement) */
    @Nullable
    public static float[] of(@Nullable Box target, @Nullable Box space) {
        if (target == null || space == null) return null;
        Vector2f topLeft = corner(target, space, 0f, 0f);
        Vector2f bottomRight = corner(target, space, target.width(), target.height());
        return new float[]{
                topLeft.x, topLeft.y,
                Math.max(0f, bottomRight.x - topLeft.x),
                Math.max(0f, bottomRight.y - topLeft.y)};
    }

    /**
     * {@code target}'s pre-transform pixels mapped into {@code space}'s.
     *
     * <p>Everything between the two is still carried — the pan, the zoom, the artboard's scale, every
     * ancestor's transform — and only the node's OWN transform is divided out. What that is for: a
     * gesture that edits the LAYOUT box has to be drawn on the layout box and measured in it, or it
     * shows one rectangle and writes another.</p>
     *
     * <p>Right-multiplied out rather than rebuilt from the parent, because {@code localToWorld} is
     * composed as {@code host * translate * transform} and undoing the last factor is exact — a parent
     * walk would have to re-derive the scroll and the host chain and could drift from it.</p>
     */
    @Nullable
    public static Matrix4f layoutFrame(@Nullable UIElement target, @Nullable UIElement space) {
        Box from = target == null ? null : target.box();
        Box to = space == null ? null : space.box();
        if (from == null || to == null) return null;
        Matrix4f applied = new Matrix4f();
        from.transform().applyTo(applied, 0f, 0f, from.width(), from.height(),
                originOf(target, from, true), originOf(target, from, false));
        return new Matrix4f(to.worldToLocal())
                .mul(new Matrix4f(from.localToWorld()).mul(applied.invert()));
    }

    /**
     * {@code target}'s LAYOUT box as {x, y, width, height} in {@code space}'s coordinates: where it would be drawn
     * without its own {@code transform}. What a drop target and the snap guides measure against, since the parent's
     * layout places children by it; selection chrome sits on {@link #quadOf what is painted}.
     */
    @Nullable
    public static float[] ofLayout(@Nullable UIElement target, @Nullable UIElement space) {
        Matrix4f frame = layoutFrame(target, space);
        Box box = target == null ? null : target.box();
        if (frame == null || box == null) return null;
        Vector2f topLeft = apply(frame, 0f, 0f);
        Vector2f bottomRight = apply(frame, box.width(), box.height());
        return new float[]{topLeft.x, topLeft.y,
                Math.abs(bottomRight.x - topLeft.x), Math.abs(bottomRight.y - topLeft.y)};
    }

    /**
     * {@code target}'s border box as painted: its four corners in {@code space}, top-left, top-right, bottom-right,
     * bottom-left, as {@code x, y} pairs — its own {@code transform} and everything above it applied.
     *
     * <pre>{@code
     * float[] quad = CanvasRects.quadOf(node, overlay);
     * CanvasRects.outlineQuad(ctx, quad, 1f, accent);     // on the element as the eye sees it
     * float[] tag = CanvasRects.bounds(quad);             // somewhere to put a label beside it
     * }</pre>
     *
     * <p>What selection chrome sits on, as Figma's and Unity's does: a rotated or skewed element is selected where it
     * is drawn. Null when either has no box.</p>
     */
    @Nullable
    public static float[] quadOf(@Nullable UIElement target, @Nullable UIElement space) {
        Matrix4f frame = localToSpace(target, space);
        Box box = target == null ? null : target.box();
        if (frame == null || box == null) return null;
        float w = box.width(), h = box.height();
        Vector2f a = apply(frame, 0f, 0f), b = apply(frame, w, 0f), c = apply(frame, w, h), d = apply(frame, 0f, h);
        return new float[]{a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y};
    }

    /** The point at fractions {@code (fx, fy)} of a quad from {@link #quadOf}: {@code (0.5, 1)} is the bottom's middle. */
    public static Vector2f pointOn(float[] quad, float fx, float fy) {
        float topX = quad[0] + (quad[2] - quad[0]) * fx, topY = quad[1] + (quad[3] - quad[1]) * fx;
        float bottomX = quad[6] + (quad[4] - quad[6]) * fx, bottomY = quad[7] + (quad[5] - quad[7]) * fx;
        return new Vector2f(topX + (bottomX - topX) * fy, topY + (bottomY - topY) * fy);
    }

    /** A quad's axis-aligned bounds, as {x, y, width, height}. */
    public static float[] bounds(float[] quad) {
        float x0 = Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
        float y0 = Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
        float x1 = Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
        float y1 = Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
        return new float[]{x0, y0, x1 - x0, y1 - y0};
    }

    /** Whether a quad is an upright rectangle: moved and scaled, never rotated or skewed. */
    public static boolean isUpright(float[] quad) {
        float e = 0.01f;
        return Math.abs(quad[1] - quad[3]) < e && Math.abs(quad[5] - quad[7]) < e
                && Math.abs(quad[0] - quad[6]) < e && Math.abs(quad[2] - quad[4]) < e;
    }

    /**
     * Outlines a quad from {@link #quadOf}: upright, the ring {@link #outline} draws; rotated or skewed, a hairline
     * along each edge, which stays one pixel wide whatever the element's own transform does to its box.
     */
    public static void outlineQuad(CgUiPaintContext ctx, @Nullable float[] quad, float thickness, int argb) {
        if (quad == null) return;
        if (isUpright(quad)) {
            outline(ctx, bounds(quad), thickness, argb);
            return;
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            line(ctx, quad[i * 2], quad[i * 2 + 1], quad[j * 2], quad[j * 2 + 1], thickness * 0.5f, argb);
        }
    }

    /**
     * One straight stroke at any angle, {@code halfWidth} either side of it.
     *
     * <p><b>A stroke, not a rotated fill.</b> A hairline at 30 degrees has no whole pixel anywhere along it and the
     * quad path carries no coverage term to soften one with; the stroke path computes coverage from the segment's
     * distance field, so the edge is smooth at every angle and every zoom.</p>
     */
    public static void line(CgUiPaintContext ctx, float x0, float y0, float x1, float y1, float halfWidth, int argb) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        if (dx * dx + dy * dy < 0.0001f) return;
        ctx.curve().line(x0, y0, x1, y1).width(halfWidth).color(argb).submit();
    }

    /**
     * How many of {@code overlay}'s pixels one of {@code space}'s own is — the zoom, the artboard's page
     * scale and every ancestor's transform together.
     *
     * <pre>{@code
     * float scale = CanvasRects.scaleOf(node.parentElement(), overlay);
     * float layoutDx = pointerDx / scale;   // and a snap tolerance in screen pixels is measured through it
     * }</pre>
     *
     * <p>Not the surface's zoom, which leaves the page scale out: at a page scale of 2 a drag divided by
     * the zoom alone ran at twice the pointer's speed. 1 when either has no box.</p>
     */
    public static float scaleOf(@Nullable UIElement space, @Nullable UIElement overlay) {
        return scaleOf(of(space, overlay), space == null ? null : space.box());
    }

    /** As above, from {@code space}'s drawn box already measured with {@link #of}. */
    public static float scaleOf(@Nullable float[] drawn, @Nullable Box space) {
        if (drawn == null || space == null) return 1f;
        if (space.width() > 1e-3f) return drawn[2] / space.width();
        if (space.height() > 1e-3f) return drawn[3] / space.height();
        return 1f;
    }

    /**
     * Whether a WORLD point falls inside {@code node}'s layout box.
     *
     * <p>What design-time picking asks, so that the mouse agrees with every other piece of chrome. The
     * engine's own hit test inverts {@code localToWorld} and therefore answers about what is PAINTED,
     * which is the right question for a running UI and the wrong one for a designer: a transform is not
     * something Taffy knows about, so selecting by it means manipulating a rectangle the layout engine
     * cannot reason about.</p>
     *
     * <p>Raw pointer pixels are world pixels — the box tree's own {@code pick} takes them unconverted.</p>
     */
    public static boolean layoutContains(@Nullable UIElement node, float worldX, float worldY) {
        Box box = node == null ? null : node.box();
        if (box == null || box.width() <= 0f || box.height() <= 0f) return false;
        Matrix4f applied = new Matrix4f();
        box.transform().applyTo(applied, 0f, 0f, box.width(), box.height(),
                originOf(node, box, true), originOf(node, box, false));
        Vector2f local = apply(
                new Matrix4f(box.localToWorld()).mul(applied.invert()).invert(), worldX, worldY);
        return local.x >= 0f && local.y >= 0f && local.x <= box.width() && local.y <= box.height();
    }

    /** The compositor's pinned origin when there is one, else the cascade's, else the centre. */
    private static float originOf(UIElement node, Box box, boolean horizontal) {
        Float pinned = horizontal ? box.transformOriginX() : box.transformOriginY();
        if (pinned != null) return pinned;
        float extent = horizontal ? box.width() : box.height();
        LengthPercent origin = node.getStyle().computed().get(horizontal
                ? StylePropertyRegistry.TRANSFORM_ORIGIN_X
                : StylePropertyRegistry.TRANSFORM_ORIGIN_Y);
        return origin == null ? extent * 0.5f : origin.resolve(extent);
    }

    private static Vector2f apply(Matrix4f m, float x, float y) {
        Vector3f out = m.transformPosition(new Vector3f(x, y, 0f));
        return new Vector2f(out.x, out.y);
    }

    /**
     * {@code target}'s own pixels mapped into {@code space}'s, transform and all.
     *
     * <pre>{@code
     * Matrix4f m = CanvasRects.localToSpace(node, overlay);
     * Vector2f delta = CanvasRects.toLocalDelta(m, viewportDx, viewportDy);   // px in the node
     * }</pre>
     *
     * <p>What a gesture needs that {@link #of} cannot give it: a rect is an axis-aligned box, so a
     * pointer delta divided by the zoom is only right when nothing between here and the node scales or
     * rotates. A transformed element breaks that silently — the box moves by the transform's factor more
     * than the hand did.</p>
     */
    @Nullable
    public static Matrix4f localToSpace(@Nullable UIElement target, @Nullable UIElement space) {
        Box from = target == null ? null : target.box();
        Box to = space == null ? null : space.box();
        if (from == null || to == null) return null;
        return new Matrix4f(to.worldToLocal()).mul(from.localToWorld());
    }

    /**
     * A delta in {@code space}'s pixels, expressed in the target's own.
     *
     * <p>A DIRECTION, so the translation is dropped and only the linear part is inverted — a delta has no
     * origin to be measured from.</p>
     */
    public static Vector2f toLocalDelta(Matrix4f localToSpace, float dx, float dy) {
        Matrix4f inverse = new Matrix4f(localToSpace).invert();
        return new Vector2f(inverse.m00() * dx + inverse.m10() * dy,
                inverse.m01() * dx + inverse.m11() * dy);
    }

    private static Vector2f corner(Box target, Box space, float localX, float localY) {
        Vector2f world = Transform2D.apply(target.localToWorld(), localX, localY);
        return Transform2D.apply(space.worldToLocal(), world.x, world.y);
    }

    /**
     * Draws {@code thickness} px of outline hugging a rectangle from OUTSIDE it.
     *
     * <p><b>One SDF ring, not four fills.</b> Four axis-aligned quads are exact on whole pixels and
     * ragged everywhere else, and a design canvas is everywhere else: pan and zoom put every edge on a
     * fraction, and the quad path has no coverage term to soften one with. The rounded-rect material
     * computes coverage from the distance field, so the ring is smooth at any position and any zoom --
     * the same reason the CSS {@code outline} the engine draws goes through it. It is also one draw
     * call rather than four, and no two strokes overlap at the corners to blend twice.</p>
     */
    public static void outline(CgUiPaintContext ctx, @Nullable float[] rect, float thickness, int argb) {
        float[] ring = outlineRing(rect, thickness);
        if (ring == null) return;
        // A transparent interior carrying the stroke's rgb, so the shader's edge-to-fill mix has no dark
        // fringe to bleed. @see BoxPainter#paintRounded, which does the same for the same reason.
        ctx.rect().at(ring[0], ring[1]).size(ring[2], ring[3])
                .fillColor(argb & 0x00FFFFFF)
                .border(thickness, argb)
                .submit();
    }

    /**
     * The rectangle an outline occupies, <b>outside</b> the one it points at.
     *
     * <p>It used to be drawn inside, which means an outline covers the outermost pixels of the very
     * thing it is pointing at. Invisible on a box with padding and obvious on one whose content reaches
     * its edge: a slider's thumb sits flush against the control's left edge at minimum, so the selection
     * stroke ran through it and it read as the thumb spilling out of its own box. Nothing was spilling
     * -- measured, the thumb is 10px wide at x=0 inside a 150px control -- the stroke was simply on top
     * of it.</p>
     *
     * <p>Outside also removes the clamp the inside version needed: two strokes on a box thinner than
     * twice the thickness used to overlap and paint the whole thing solid, so the thickness had to be
     * halved on a small box and a 2px-tall element got a 1px outline. A ring drawn outside never
     * overlaps itself.</p>
     *
     * <p>Separate from the painting so it can be asserted without a GL context.</p>
     *
     * @return {x, y, width, height} of the ring itself, whose stroke runs inward from its own edge, or
     *         null when there is nothing to ring
     */
    @Nullable
    public static float[] outlineRing(@Nullable float[] rect, float t) {
        if (rect == null || rect[2] <= 0f || rect[3] <= 0f) return null;
        return new float[]{rect[0] - t, rect[1] - t, rect[2] + t + t, rect[3] + t + t};
    }
}

