package com.crystalgui.render.image;

import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.vector.CgStrokeCap;

/**
 * The built-in vector marks CSS's {@code shape("name")} draws: chevrons, triangles, a checkmark, a cross, plus and
 * minus, arrows. Each in the element's {@code color}, fitted into a centred square, never stretched.
 *
 * <pre>{@code
 * background: shape("chevron-down");                         // in a sheet
 * CgUiImage.mark(CgUiMark.CHECKMARK)                          // from Java
 * }</pre>
 */
public enum CgUiMark {
    CHEVRON_UP, CHEVRON_DOWN, CHEVRON_LEFT, CHEVRON_RIGHT,
    TRIANGLE_UP, TRIANGLE_DOWN, TRIANGLE_LEFT, TRIANGLE_RIGHT,
    CHECKMARK,
    CROSS,
    PLUS, MINUS,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT;

    /** A stroke's half-width over the mark's size. */
    private static final float HALF_WIDTH = 0.055f;
    /** The mark's size over the box's shorter side. */
    private static final float SIZE = 0.78f;

    /** Its name in CSS: {@code chevron-down}. */
    public String cssName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The mark CSS names {@code name}, or null. */
    @Nullable
    public static CgUiMark parse(String name) {
        for (CgUiMark mark : values()) if (mark.cssName().equals(name)) return mark;
        return null;
    }

    /** Draws the mark into the box at {@code (x, y)} in {@code argb}. */
    public void paint(CgCanvas canvas, float x, float y, float width, float height, int argb) {
        // A square: a box whose aspect drifts (a fold arrow at the editor's zoom) never stretches the mark
        float size = Math.min(width, height) * SIZE;
        float x0 = x + (width - size) * 0.5f, y0 = y + (height - size) * 0.5f;
        float w = 2f * size * HALF_WIDTH;
        switch (this) {
            case CHEVRON_UP -> polyline(canvas, x0, y0, size, w, argb, 0.24f, 0.68f, 0.50f, 0.36f, 0.76f, 0.68f);
            case CHEVRON_DOWN -> polyline(canvas, x0, y0, size, w, argb, 0.24f, 0.32f, 0.50f, 0.64f, 0.76f, 0.32f);
            case CHEVRON_LEFT -> polyline(canvas, x0, y0, size, w, argb, 0.68f, 0.24f, 0.36f, 0.50f, 0.68f, 0.76f);
            case CHEVRON_RIGHT -> polyline(canvas, x0, y0, size, w, argb, 0.32f, 0.24f, 0.64f, 0.50f, 0.32f, 0.76f);
            case TRIANGLE_RIGHT -> triangle(canvas, x0, y0, size, argb, 0.30f, 0.22f, 0.30f, 0.78f, 0.76f, 0.50f);
            case TRIANGLE_LEFT -> triangle(canvas, x0, y0, size, argb, 0.70f, 0.22f, 0.70f, 0.78f, 0.24f, 0.50f);
            case TRIANGLE_DOWN -> triangle(canvas, x0, y0, size, argb, 0.22f, 0.30f, 0.78f, 0.30f, 0.50f, 0.76f);
            case TRIANGLE_UP -> triangle(canvas, x0, y0, size, argb, 0.22f, 0.70f, 0.78f, 0.70f, 0.50f, 0.24f);
            case CHECKMARK -> polyline(canvas, x0, y0, size, w, argb, 0.22f, 0.52f, 0.42f, 0.72f, 0.80f, 0.26f);
            case CROSS -> {
                line(canvas, x0, y0, size, w, argb, 0.26f, 0.26f, 0.74f, 0.74f);
                line(canvas, x0, y0, size, w, argb, 0.74f, 0.26f, 0.26f, 0.74f);
            }
            case PLUS -> {
                line(canvas, x0, y0, size, w, argb, 0.22f, 0.5f, 0.78f, 0.5f);
                line(canvas, x0, y0, size, w, argb, 0.5f, 0.22f, 0.5f, 0.78f);
            }
            case MINUS -> line(canvas, x0, y0, size, w, argb, 0.22f, 0.5f, 0.78f, 0.5f);
            case ARROW_RIGHT -> arrow(canvas, x0, y0, size, w, argb, 0.16f, 0.5f, 0.62f, 0.5f);
            case ARROW_LEFT -> arrow(canvas, x0, y0, size, w, argb, 0.84f, 0.5f, 0.38f, 0.5f);
            case ARROW_DOWN -> arrow(canvas, x0, y0, size, w, argb, 0.5f, 0.16f, 0.5f, 0.62f);
            case ARROW_UP -> arrow(canvas, x0, y0, size, w, argb, 0.5f, 0.84f, 0.5f, 0.38f);
        }
    }

    /** Two round-capped segments through three points, in fractions of the mark's square. */
    private static void polyline(CgCanvas canvas, float x, float y, float s, float w, int argb,
                                 float ax, float ay, float bx, float by, float cx, float cy) {
        line(canvas, x, y, s, w, argb, ax, ay, bx, by);
        line(canvas, x, y, s, w, argb, bx, by, cx, cy);
    }

    private static void line(CgCanvas canvas, float x, float y, float s, float w, int argb,
                             float ax, float ay, float bx, float by) {
        canvas.spline().line(x + s * ax, y + s * ay, x + s * bx, y + s * by).width(w).color(argb)
                .cap(CgStrokeCap.ROUND).draw();
    }

    private static void triangle(CgCanvas canvas, float x, float y, float s, int argb,
                                 float ax, float ay, float bx, float by, float cx, float cy) {
        canvas.triangle(x + s * ax, y + s * ay, x + s * bx, y + s * by, x + s * cx, y + s * cy).color(argb).draw();
    }

    private static void arrow(CgCanvas canvas, float x, float y, float s, float w, int argb,
                              float ax, float ay, float bx, float by) {
        canvas.spline().line(x + s * ax, y + s * ay, x + s * bx, y + s * by).width(w).color(argb)
                .cap(CgStrokeCap.ROUND, CgStrokeCap.BUTT).arrowAtEnd().draw();
    }
}
