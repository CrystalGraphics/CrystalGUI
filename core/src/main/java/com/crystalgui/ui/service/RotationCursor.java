package com.crystalgui.ui.service;

import com.crystalgui.render.CgUiPaintContext;

/**
 * <b>The curved double arrow that says "this rotates"</b>, drawn at any angle.
 *
 * <p>Paint.NET's answer to the rotate cursor, and the reason to prefer it: the arrow is drawn rather
 * than presented, so it turns with the gesture instead of snapping to whichever of four or eight
 * pictures is nearest. Use it from any consumer that rotates something — a transform box, a knob, a
 * colour wheel — through {@link CursorDecoration}:</p>
 *
 * <pre>{@code
 * input.setCursorDecoration((ctx, x, y) ->
 *         RotationCursor.paint(ctx, x, y, (float) Math.atan2(y - pivotY, x - pivotX)));
 * }</pre>
 *
 * <p><b>{@code radians} is the RADIAL direction — pivot to pointer — not the tangent.</b> The arrow is
 * drawn across it, which is the direction the thing actually travels, and passing the tangent instead
 * turns it ninety degrees into pointing at the pivot. Taking the radius rather than the tangent is what
 * lets a caller hand over {@code atan2} of the two points it already has.</p>
 *
 * <p>Set a real {@code grab}/{@code grabbing} cursor alongside it. @see CursorDecoration</p>
 */
public final class RotationCursor {

    /**
     * How far back TOWARD THE PIVOT the arrow sits from the pointer.
     *
     * <p>Back rather than out, for two reasons that happen to want the same thing. The cursor under it
     * is {@code CursorBitmaps}' 32x32 art centred on the hotspot, so it covers everything within about
     * sixteen pixels of the pointer — drawn at the pointer the arrow is simply not visible. And the
     * pointer is out in the rotate band while the thing being turned is inward of it, so back is where
     * the arrow reads as attached to the corner rather than floating off the box.</p>
     *
     * <p>The arc still bows OUTWARD from here, away from the pivot, so it stays concentric with the
     * turn.</p>
     */
    private static final float INSET = 20f;

    /** Signed for the maths: the arrow's chord sits at this radius from the pointer. @see #INSET */
    private static final float OFFSET = -INSET;

    /** Half the arrow's span along the tangent. The arc is this tall each way from its middle. */
    private static final float REACH = 15f;

    /** How far the arc bows away from its own chord — what makes it read as a turn, not a slider. */
    private static final float BOW = 7f;

    /** How far back from the tip an arrowhead's barbs reach. */
    private static final float HEAD = 6.5f;

    /** How far either side of the arc those barbs are turned, in radians. */
    private static final float BARB = 0.52f;

    /** Drawn twice: a dark halo first, a light body over it. @see #paint */
    private static final float BODY_WIDTH = 1.1f;
    private static final float HALO_WIDTH = 2.6f;

    private static final int BODY = 0xFFFFFFFF;
    private static final int HALO = 0xC0000000;

    private RotationCursor() {
    }

    /**
     * @param radians from the pivot TOWARD the pointer; the arrow is drawn across it
     */
    public static void paint(CgUiPaintContext ctx, float x, float y, float radians) {
        // A WHITE BODY OVER A DARK HALO, which is what every OS cursor does and why CursorBitmaps draws
        // its own art the same way: design-time chrome lands on whatever the document happens to be, and
        // a single-colour mark disappears against half of it.
        stroke(ctx, x, y, radians, HALO_WIDTH, HALO);
        stroke(ctx, x, y, radians, BODY_WIDTH, BODY);
    }

    private static void stroke(CgUiPaintContext ctx, float x, float y, float radians,
                               float width, int colour) {
        // Local space: +x is the radial direction, +y the tangent, so a point is the pointer plus
        // `radial` along the radius and `tangential` across it. Expanded rather than passed through a
        // helper returning a point, because this runs every frame a rotate gesture is live.
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        // The arc bows OUTWARD along +x, so it curves around the pivot the way the thing it describes
        // will travel.
        float startX = x + cos * OFFSET + sin * REACH;
        float startY = y + sin * OFFSET - cos * REACH;
        float viaX = x + cos * (OFFSET + BOW);
        float viaY = y + sin * (OFFSET + BOW);
        float endX = x + cos * OFFSET - sin * REACH;
        float endY = y + sin * OFFSET + cos * REACH;

        ctx.curve().from(startX, startY).via(viaX, viaY).to(endX, endY)
                .width(width).color(colour).submit();

        // THE ARC'S OWN TANGENT AT EACH END, which for a quadratic is the run to the control point. Both
        // curl inward, because the arc bows out between them.
        head(ctx, x, y, cos, sin, -REACH, -BOW, -REACH, width, colour);
        head(ctx, x, y, cos, sin, REACH, -BOW, REACH, width, colour);
    }

    /**
     * One arrowhead, its barbs turned either side of the direction the arrow POINTS.
     *
     * <p>Not either side of the radius, which is what this did first: on an arc that bows, the tangent at
     * the end is nowhere near the radius, so one barb lines up with the stroke and the other stands off
     * it — the head reads as detached rather than as the end of the line.</p>
     *
     * @param outRadial the tip's outward direction in local (radial, tangential); need not be unit
     */
    private static void head(CgUiPaintContext ctx, float x, float y, float cos, float sin,
                             float tangential, float outRadial, float outTangential,
                             float width, int colour) {
        float length = (float) Math.hypot(outRadial, outTangential);
        if (length < 1e-4f) return;
        float ux = outRadial / length;
        float uy = outTangential / length;
        float cosBarb = (float) Math.cos(BARB);
        float sinBarb = (float) Math.sin(BARB);

        float tipX = x + cos * OFFSET - sin * tangential;
        float tipY = y + sin * OFFSET + cos * tangential;

        for (int side = -1; side <= 1; side += 2) {
            float barbRadial = -HEAD * (ux * cosBarb - side * uy * sinBarb);
            float barbTangential = -HEAD * (side * ux * sinBarb + uy * cosBarb);
            float endX = x + cos * (OFFSET + barbRadial) - sin * (tangential + barbTangential);
            float endY = y + sin * (OFFSET + barbRadial) + cos * (tangential + barbTangential);
            ctx.curve().line(tipX, tipY, endX, endY).width(width).color(colour).submit();
        }
    }
}
