package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiRect;
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
     * {@code target}'s LAYOUT box as {x, y, width, height} in {@code space}'s coordinates.
     *
     * <p><b>This is what design-time chrome wants</b> — the selection outline, the hover highlight, the
     * resize handles, the snap guides. Each of them states or edits the element's geometry, and a
     * transformed element draws somewhere other than it measures. {@link #of} is for the few things that
     * have to sit on what the eye sees, such as the in-place text editor over the glyphs it is
     * editing.</p>
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
        CgUiRect stroke = new CgUiRect();
        // A transparent interior carrying the stroke's rgb, so the shader's edge-to-fill mix has no dark
        // fringe to bleed. @see BoxPainter#paintRounded, which does the same for the same reason.
        stroke.setFillColor(argb & 0x00FFFFFF);
        stroke.setBorder(thickness, argb);
        stroke.draw(ctx, ring[0], ring[1], ring[2], ring[3]);
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

