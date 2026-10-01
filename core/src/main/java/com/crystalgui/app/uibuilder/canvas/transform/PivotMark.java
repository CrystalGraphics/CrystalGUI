package com.crystalgui.app.uibuilder.canvas.transform;

import com.crystalgui.render.CgUiPaintContext;

/**
 * The mark a transform's pivot is drawn as: a ring with four arms, each pass drawn over a wider halo.
 *
 * <pre>{@code
 * PivotMark.paint(paint, at.x, at.y, style.get(OUTLINE_COLOR), style.get(TEXT_DECORATION_COLOR));
 * }</pre>
 *
 * <p><b>One mark, wherever a pivot is shown</b> — the free transform draws it on the canvas and the transform lab
 * draws it on its specimen, so the same thing is recognisably the same thing in both.</p>
 *
 * <p><b>Two colours, and neither is the chrome's.</b> In the chrome colour it was invisible on anything
 * selected-blue, which is most of what a builder points at, and a mark that disappears into the element it belongs
 * to cannot say where the transform turns. The halo is what carries it over a pale element as well as a dark one,
 * which no single colour does: After Effects' anchor point and Blender's 3D cursor are both two-tone for the same
 * reason. The ring is Photoshop's shape, and it is what tells the mark apart from a snap guide's cross.</p>
 */
public final class PivotMark {

    /** The mark's whole span, in logical pixels — arm tip to arm tip. */
    public static final float SIZE = 9f;

    /** The mark itself: one logical pixel, as every line the box draws is. */
    private static final float HAIRLINE = 0.5f;

    /** The under-pass, wider, so the mark reads on a pale element as well as a dark one. */
    private static final float HALO = 1.1f;

    private PivotMark() {
    }

    /**
     * Draws the mark centred on {@code (x, y)}.
     *
     * @param mark the ring and its arms
     * @param halo the wider pass under them
     */
    public static void paint(CgUiPaintContext paint, float x, float y, int mark, int halo) {
        pass(paint, x, y, halo, HALO);
        pass(paint, x, y, mark, HAIRLINE);
    }

    /** One pass of the mark: the ring, then the four arms outside it. */
    private static void pass(CgUiPaintContext paint, float x, float y, int colour, float width) {
        float arm = SIZE * 0.5f;
        float radius = SIZE * 0.28f;
        paint.rect()
                .at(x - radius, y - radius)
                .size(radius * 2f, radius * 2f)
                .radius(radius, radius)
                .border(width * 2f, colour)
                .fillColor(0)
                .submit();
        line(paint, x - arm, y, x - radius, y, colour, width);
        line(paint, x + radius, y, x + arm, y, colour, width);
        line(paint, x, y - arm, x, y - radius, colour, width);
        line(paint, x, y + radius, x, y + arm, colour, width);
    }

    private static void line(CgUiPaintContext paint, float x0, float y0, float x1, float y1, int colour, float width) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        if (dx * dx + dy * dy < 0.0001f) return;
        paint.curve().line(x0, y0, x1, y1).width(width).color(colour).submit();
    }
}
