package com.crystalgui.render.texture;

import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgui.render.CgUiPaintContext;

/**
 * A named vector mark — chevron, triangle, checkmark, cross, plus/minus, arrow — drawn directly by
 * {@code ctx.curve()}/{@code ctx.triangle()}, with no texture asset and no glyph.
 *
 * <p>This is what replaces {@code UIText("v")}/{@code UIText(">")} as a foldout/dropdown indicator:
 * those were font glyphs standing in for shapes the font never actually draws consistently across
 * themes, sizes or fonts at all. {@code CgUiShape} draws the real geometry instead, at whatever size
 * CSS gives it.</p>
 *
 * <h3>CSS surface</h3>
 * <pre>{@code
 * tree .__twisty__ { overlay: shape("chevron-right"); }
 * tree[expanded] .__twisty__ { overlay: shape("chevron-down"); }
 * checkbox:checked .__mark__ { overlay: shape("checkmark"); }
 * }</pre>
 * Parsed by {@code TextureValue}, alongside {@code asset(...)}/{@code sprite(...)}.
 *
 * <h3>Clamped to a centered square — no intrinsic size</h3>
 * <p>Unlike a texture-backed drawable, a shape has no natural pixel dimensions to report: it is
 * parametric, computed fresh from whatever rect {@link #draw} is handed. So there is no
 * {@code overlay-fit: none} story here (that needs an intrinsic size to size <em>from</em>) — a
 * shape works out {@code min(width, height)} and draws itself, centered, inside that square. Give
 * the element an explicit {@code width}/{@code height} and the shape follows.</p>
 * <p><b>The square clamp is load-bearing, not cosmetic.</b> Every kind's geometry is defined in a
 * square coordinate space, and a box handed to {@link #draw} is not guaranteed to be one — {@code
 * TextEditor}'s fold-arrow box, for instance, is {@code width} = a fixed CSS pixel value but {@code
 * height} = the zoomed line height, so the box grows steadily less square as the editor zooms in.
 * Without the clamp a shape stretches to fill whatever non-square rect it is given, which reads as
 * "does not respond well to zoom" rather than as the aspect-ratio bug it actually is.</p>
 *
 * <h3>Stroke kinds vs. fill kinds</h3>
 * <p>Chevrons, checkmark, cross, plus/minus and arrows are 1–2 {@link CgUiPaintContext#curve()}
 * calls — {@link CgVectorRenderer} already draws arbitrary straight strokes with caps, so nothing new
 * was needed in the engine for these. Triangles are the one kind that needs {@link
 * CgUiPaintContext#triangle()} — a filled region, not a stroked path. Callers of this class never
 * see the difference; {@link #draw} dispatches internally.</p>
 *
 * <h3>Every joint is round, including where two segments meet</h3>
 * <p>Chevron's apex and checkmark's low point are each drawn as two independent {@code curve()}
 * calls sharing an endpoint, both capped {@code CAP_ROUND} at every end — deliberately including
 * the shared one. An earlier version butt-capped the joint to dodge the double-round-cap disc-blend
 * {@code CgCurveSplitter#packCaps} documents, which traded that faint artefact for a worse one: two
 * segments meeting at an angle with butt ends leave the OUTER wedge of the bend uncovered by either
 * segment's own rectangle, the ordinary "no line join" gap every vector graphics system avoids by
 * defaulting to round or miter joins. That showed up as a visible notch bitten out of the chevron's
 * point. A round join's disc covers the wedge regardless of angle; the double-blend it reintroduces
 * is invisible at the 8–16px sizes these draw at.</p>
 */
public record CgUiShape(Kind kind) implements CgUiDrawable {

    public enum Kind {
        CHEVRON_UP, CHEVRON_DOWN, CHEVRON_LEFT, CHEVRON_RIGHT,
        TRIANGLE_UP, TRIANGLE_DOWN, TRIANGLE_LEFT, TRIANGLE_RIGHT,
        CHECKMARK,
        CROSS,
        PLUS, MINUS,
        ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT
    }

    /** Fraction of the SQUARE working size (see {@link #draw}) used as stroke half-width. */
    private static final float STROKE_HALF_WIDTH_FRACTION = 0.055f;

    /**
     * How much of the clamped square a shape actually occupies — the rest is outer margin.
     *
     * <p>0.78 rather than 1.0 because every kind's own fractional points already reserve some
     * margin internally (e.g. a chevron's arms span 0.24–0.76), and the two compound: without this,
     * a shape read as noticeably larger/heavier than the reference icons (Feather, IntelliJ) it was
     * modelled on, which draw at a visibly lighter weight than "fill the whole box" produces.</p>
     */
    private static final float SIZE_FRACTION = 0.78f;

    /** Parses a catalog name (e.g. {@code "chevron-down"}) into a {@link Kind}, or {@code null}. */
    public static Kind parseKind(String name) {
        return switch (name) {
            case "chevron-up" -> Kind.CHEVRON_UP;
            case "chevron-down" -> Kind.CHEVRON_DOWN;
            case "chevron-left" -> Kind.CHEVRON_LEFT;
            case "chevron-right" -> Kind.CHEVRON_RIGHT;
            case "triangle-up" -> Kind.TRIANGLE_UP;
            case "triangle-down" -> Kind.TRIANGLE_DOWN;
            case "triangle-left" -> Kind.TRIANGLE_LEFT;
            case "triangle-right" -> Kind.TRIANGLE_RIGHT;
            case "checkmark" -> Kind.CHECKMARK;
            case "cross" -> Kind.CROSS;
            case "plus" -> Kind.PLUS;
            case "minus" -> Kind.MINUS;
            case "arrow-up" -> Kind.ARROW_UP;
            case "arrow-down" -> Kind.ARROW_DOWN;
            case "arrow-left" -> Kind.ARROW_LEFT;
            case "arrow-right" -> Kind.ARROW_RIGHT;
            default -> null;
        };
    }

    /** A mark, not a picture: it has no palette, so the element's {@code color} is its colour.
     * @see CgUiDrawable#followsTextColor() */
    @Override
    public boolean followsTextColor() {
        return true;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        int argb = ArgbMath.multiply(0xFFFFFFFF, ctx.getColor());

        // CLAMP TO A CENTERED SQUARE FIRST — every kind below is defined in a square coordinate
        // space, and stretching it to fill a non-square box is never actually wanted, the same way
        // no icon system (Feather, IntelliJ, Material) lets its glyphs stretch to their container.
        //
        // This is not cosmetic. TextEditor's fold arrow box is width = a fixed CSS padding-right
        // (14px, independent of zoom) but height = lineHeight() (fontSize * multiplier, which DOES
        // scale with the editor's zoom). At the default 11px font the box is nearly square and the
        // mismatch is invisible; at 33px zoom lineHeight triples while the width does not, and an
        // unclamped shape stretched into a thin vertical sliver — "doesn't respond well to zoom" was
        // this box becoming less and less square as zoom increased, not a scaling bug in the shape
        // itself. Squaring off the working area here fixes every such caller at once, including ones
        // that have not hit a non-square box yet.
        float size = Math.min(width, height) * SIZE_FRACTION;
        float x0 = x + (width - size) * 0.5f;
        float y0 = y + (height - size) * 0.5f;

        float hw = size * STROKE_HALF_WIDTH_FRACTION;

        switch (kind) {
            case CHEVRON_UP:    chevron(ctx, x0, y0, size, size, hw, argb, 0.68f, 0.36f, 0.68f); break;
            case CHEVRON_DOWN:  chevron(ctx, x0, y0, size, size, hw, argb, 0.32f, 0.64f, 0.32f); break;
            case CHEVRON_LEFT:  chevronVertical(ctx, x0, y0, size, size, hw, argb, 0.68f, 0.36f, 0.68f); break;
            case CHEVRON_RIGHT: chevronVertical(ctx, x0, y0, size, size, hw, argb, 0.32f, 0.64f, 0.32f); break;

            case TRIANGLE_RIGHT: triangle(ctx, x0, y0, size, size, argb, 0.30f, 0.22f, 0.30f, 0.78f, 0.76f, 0.50f); break;
            case TRIANGLE_LEFT:  triangle(ctx, x0, y0, size, size, argb, 0.70f, 0.22f, 0.70f, 0.78f, 0.24f, 0.50f); break;
            case TRIANGLE_DOWN:  triangle(ctx, x0, y0, size, size, argb, 0.22f, 0.30f, 0.78f, 0.30f, 0.50f, 0.76f); break;
            case TRIANGLE_UP:    triangle(ctx, x0, y0, size, size, argb, 0.22f, 0.70f, 0.78f, 0.70f, 0.50f, 0.24f); break;

            case CHECKMARK: checkmark(ctx, x0, y0, size, size, hw, argb); break;
            case CROSS: cross(ctx, x0, y0, size, size, hw, argb); break;
            case PLUS: plus(ctx, x0, y0, size, size, hw, argb, true); break;
            case MINUS: plus(ctx, x0, y0, size, size, hw, argb, false); break;

            case ARROW_RIGHT: arrow(ctx, x0, y0, size, size, hw, argb, 0.16f, 0.5f, 0.62f, 0.5f); break;
            case ARROW_LEFT:  arrow(ctx, x0, y0, size, size, hw, argb, 0.84f, 0.5f, 0.38f, 0.5f); break;
            case ARROW_DOWN:  arrow(ctx, x0, y0, size, size, hw, argb, 0.5f, 0.16f, 0.5f, 0.62f); break;
            case ARROW_UP:    arrow(ctx, x0, y0, size, size, hw, argb, 0.5f, 0.84f, 0.5f, 0.38f); break;
        }
        ctx.flush();
    }

    // ── Kind implementations ────────────────────────────────────────────────

    /**
     * Horizontal-opening chevron: two strokes meeting at (0.5, midY), arms at leftY/rightY.
     *
     * <p><b>Round at every end, including the shared apex — deliberately.</b> This used to butt-cap
     * the joint to dodge the double-round-cap disc-blend artefact documented on {@code
     * CgVectorRenderer.packCaps}. That traded one problem for a worse one: two independently-stroked
     * segments meeting at an angle with butt ends leave the OUTER wedge of the bend uncovered by
     * either segment's own rectangle — a plain "no join" gap, the same reason every vector graphics
     * system defaults line joins to round or miter rather than none. The chevron's apex rendered
     * with a visible notch bitten out of its point. A round join's disc covers that wedge regardless
     * of the angle, which butt or square never can; the double-blend it reintroduces is a faint
     * antialiasing hardening exactly at the joint, not missing geometry, and is not visible at the
     * sizes this draws at (8–16px icons).</p>
     */
    private static void chevron(CgUiPaintContext ctx, float x, float y, float w, float h, float hw, int argb,
                                float leftY, float midY, float rightY) {
        float lx = x + w * 0.24f, ly = y + h * leftY;
        float mx = x + w * 0.50f, my = y + h * midY;
        float rx = x + w * 0.76f, ry = y + h * rightY;
        ctx.curve().line(lx, ly, mx, my).width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
        ctx.curve().line(mx, my, rx, ry).width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
    }

    /** Vertical-opening chevron (left/right pointing) — same shape, transposed. Round at every end,
     * including the shared apex — see {@link #chevron}'s doc for why. */
    private static void chevronVertical(CgUiPaintContext ctx, float x, float y, float w, float h, float hw, int argb,
                                        float topX, float midX, float bottomX) {
        float tx = x + w * topX, ty = y + h * 0.24f;
        float mx = x + w * midX, my = y + h * 0.50f;
        float bx = x + w * bottomX, by = y + h * 0.76f;
        ctx.curve().line(tx, ty, mx, my).width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
        ctx.curve().line(mx, my, bx, by).width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
    }

    private static void triangle(CgUiPaintContext ctx, float x, float y, float w, float h, int argb,
                                 float fx0, float fy0, float fx1, float fy1, float fx2, float fy2) {
        ctx.triangle()
           .points(x + w * fx0, y + h * fy0, x + w * fx1, y + h * fy1, x + w * fx2, y + h * fy2)
           .color(argb)
           .submit();
    }

    /** Short leg then long leg, sharing the low point — round at every end, including the joint,
     * for the same line-join reason as {@link #chevron}. */
    private static void checkmark(CgUiPaintContext ctx, float x, float y, float w, float h, float hw, int argb) {
        float x1 = x + w * 0.22f, y1 = y + h * 0.52f;
        float x2 = x + w * 0.42f, y2 = y + h * 0.72f;
        float x3 = x + w * 0.80f, y3 = y + h * 0.26f;
        ctx.curve().line(x1, y1, x2, y2).width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
        ctx.curve().line(x2, y2, x3, y3).width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
    }

    /** Two independent diagonals — no shared endpoint, so both can round-cap freely. */
    private static void cross(CgUiPaintContext ctx, float x, float y, float w, float h, float hw, int argb) {
        ctx.curve().line(x + w * 0.26f, y + h * 0.26f, x + w * 0.74f, y + h * 0.74f)
           .width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
        ctx.curve().line(x + w * 0.74f, y + h * 0.26f, x + w * 0.26f, y + h * 0.74f)
           .width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
    }

    /** Horizontal bar, plus an optional vertical one — neither shares an endpoint with the other. */
    private static void plus(CgUiPaintContext ctx, float x, float y, float w, float h, float hw, int argb,
                             boolean withVertical) {
        ctx.curve().line(x + w * 0.22f, y + h * 0.5f, x + w * 0.78f, y + h * 0.5f)
           .width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
        if (withVertical) {
            ctx.curve().line(x + w * 0.5f, y + h * 0.22f, x + w * 0.5f, y + h * 0.78f)
               .width(hw).color(argb).cap(CgVectorRenderer.CAP_ROUND).submit();
        }
    }

    /** Shaft with a round start and an arrowhead end — one curve, CAP_ARROW does the rest. */
    private static void arrow(CgUiPaintContext ctx, float x, float y, float w, float h, float hw, int argb,
                              float fx0, float fy0, float fx1, float fy1) {
        ctx.curve().line(x + w * fx0, y + h * fy0, x + w * fx1, y + h * fy1)
           .width(hw).color(argb)
           .cap(CgVectorRenderer.CAP_ROUND, CgVectorRenderer.CAP_ARROW)
           .submit();
    }
}
