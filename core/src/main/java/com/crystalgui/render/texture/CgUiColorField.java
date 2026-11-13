package com.crystalgui.render.texture;

import com.crystalgraphics.render.canvas.CgCanvasGradient;
import com.crystalgraphics.render.canvas.CgCanvasGrid;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgui.render.CgUiPaintContext;

/**
 * The continuously-varying surfaces a colour picker is made of — a hue ring, a saturation/value square, a two-stop
 * gradient over a transparency checkerboard and a hue strip — drawn as the canvas's own gradients and checker.
 *
 * <pre>{@code
 * new CgUiColorField().setMode(Mode.HUE_RING).setInnerRadius(0.72f);         // the ring
 * new CgUiColorField().setMode(Mode.SV_SQUARE).setHue(0.6f);                  // the square at a hue
 * new CgUiColorField().setMode(Mode.GRADIENT).setGradient(0x00FF0000, 0xFFFF0000).setCornerRadius(3f, 3f);
 * }</pre>
 *
 * <ul>
 *   <li>Each mode is one or two canvas draws: CSS's {@code conic-gradient} for the ring, the web picker's two
 *       stacked {@code linear-gradient}s for the square, {@link CgCanvasGrid#checker} under a gradient for a
 *       channel's track.</li>
 *   <li>The hue runs as {@code ColorSelector}'s hit test reads it: red at the top of the ring, counter-clockwise,
 *       and left to right along the strip. HSV's hue at full saturation and value is linear between its six
 *       primaries, so six stops draw it exactly.</li>
 *   <li>The paint context's colour tints every draw.</li>
 * </ul>
 */
public final class CgUiColorField implements CgUiDrawable {

    /** Which surface to draw. */
    public enum Mode {
        /** An annulus whose hue follows the angle. */
        HUE_RING,
        /** Saturation across x, value up y, at {@link #setHue a fixed hue}. */
        SV_SQUARE,
        /**
         * A two-stop gradient composited over a transparency checkerboard.
         *
         * <p>Used by <b>all four</b> channel sliders, alpha included. An opaque track simply never
         * reveals the checker, so the alpha track needs no separate mode and cannot drift from its
         * neighbours.</p>
         */
        GRADIENT,
        /**
         * A full hue sweep across x — the H channel's track.
         *
         * <p>Separate from {@link #GRADIENT} because hue is the one channel two endpoints cannot
         * describe: 0 and 1 are both red, so a ramp between them is a flat red bar.</p>
         */
        HUE_STRIP
    }

    /** The hue's primaries at sixths of a turn, red to red: HSV's hue sweep, linear between them. */
    private static final int[] HUES = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
    /** The checkerboard's cells, in px, and its two greys. */
    private static final float CHECKER = 4f;
    private static final int LIGHT = 0xFF9E9E9E, DARK = 0xFF707070;

    /**
     * Shared by every field, made at the first draw: a server assembles colour pickers and draws none, and a canvas
     * type loaded on construction would fail there. A quad takes its material's values as it is submitted.
     */
    private static CgCanvasGradient gradient;
    private static CgCanvasGrid checker;

    private Mode mode = Mode.GRADIENT;
    private float hue;
    private float innerRadius = 0.72f;
    private int fromArgb = 0xFF000000;
    private int toArgb = 0xFFFFFFFF;
    private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

    public CgUiColorField setMode(Mode value) {
        this.mode = value == null ? Mode.GRADIENT : value;
        return this;
    }

    /** The hue an {@link Mode#SV_SQUARE} is drawn at, 0..1. */
    public CgUiColorField setHue(float value) {
        this.hue = value;
        return this;
    }

    /** The ring's inner edge as a fraction of its outer radius, so the band scales with the widget. */
    public CgUiColorField setInnerRadius(float value) {
        this.innerRadius = value;
        return this;
    }

    /** The two stops of a {@link Mode#GRADIENT}, as ARGB. */
    public CgUiColorField setGradient(int from, int to) {
        this.fromArgb = from;
        this.toArgb = to;
        return this;
    }

    /** Uniform corner rounding, matching the rest of the UI's radii. */
    public CgUiColorField setCornerRadius(float rx, float ry) {
        return setCornerRadius(rx, ry, rx, ry, rx, ry, rx, ry);
    }

    /**
     * Independent elliptical radius per corner, CSS {@code border-radius} order (TL,TR,BR,BL), each an (rx,ry) pair —
     * as {@link CgUiRect#setCornerRadius(float, float, float, float, float, float, float, float)}.
     *
     * <p>The only way a field is rounded: it paints itself, so CSS {@code border-radius} has no flat fill of it to
     * clip. {@code ColorSelector}'s before/after swatch pair rounds only each one's outer edge; the ring is round of
     * itself and ignores these.</p>
     */
    public CgUiColorField setCornerRadius(float rxTL, float ryTL, float rxTR, float ryTR,
                                           float rxBR, float ryBR, float rxBL, float ryBL) {
        this.rxTL = rxTL; this.ryTL = ryTL;
        this.rxTR = rxTR; this.ryTR = ryTR;
        this.rxBR = rxBR; this.ryBR = ryBR;
        this.rxBL = rxBL; this.ryBL = ryBL;
        return this;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        if (gradient == null) {
            gradient = new CgCanvasGradient(CgCanvasMaterials.SCREEN);
            checker = new CgCanvasGrid(CgCanvasMaterials.SCREEN);
        }
        int tint = ctx.getColor();
        switch (mode) {
            case HUE_RING -> {
                float rx = width * 0.5f, ry = height * 0.5f;
                gradient.size(width, height).conic(rx, ry, 0f).radii(rx, ry, rx, ry, rx, ry, rx, ry)
                        .border((1f - innerRadius) * Math.min(rx, ry)).clearStops();
                // Counter-clockwise from red: the primaries reversed over a clockwise turn.
                for (int i = 0; i < HUES.length; i++) gradient.stop(i / 6f, HUES[HUES.length - 1 - i]);
                submit(ctx, x, y, width, height, tint);
            }
            case SV_SQUARE -> {
                // White to the hue across, then black rising from transparent down: value times the saturated colour.
                shaped(width, height, 90f).stop(0f, 0xFFFFFFFF).stop(1f, ArgbMath.fromHsv(hue, 1f, 1f, 0xFF));
                submit(ctx, x, y, width, height, tint);
                shaped(width, height, 180f).stop(0f, 0x00000000).stop(1f, 0xFF000000);
                submit(ctx, x, y, width, height, tint);
            }
            case GRADIENT -> {
                checker.size(width, height).cell(CHECKER, CHECKER).checker(LIGHT, DARK)
                        .radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL).apply();
                ctx.quad(checker.material()).at(x, y).size(width, height).color(tint).submit();
                shaped(width, height, 90f).stop(0f, fromArgb).stop(1f, toArgb);
                submit(ctx, x, y, width, height, tint);
            }
            case HUE_STRIP -> {
                shaped(width, height, 90f);
                for (int i = 0; i < HUES.length; i++) gradient.stop(i / 6f, HUES[i]);
                submit(ctx, x, y, width, height, tint);
            }
        }
    }

    /** The shared gradient, linear at {@code degrees} over the box with the field's corners, its stops cleared. */
    private CgCanvasGradient shaped(float width, float height, float degrees) {
        return gradient.size(width, height).angle(degrees).border(0f)
                .radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL).clearStops();
    }

    private static void submit(CgUiPaintContext ctx, float x, float y, float width, float height, int tint) {
        for (int i = 0, n = gradient.windows(); i < n; i++) {
            gradient.window(i);
            ctx.quad(gradient.material()).at(x, y).size(width, height).color(tint).submit();
        }
    }
}
