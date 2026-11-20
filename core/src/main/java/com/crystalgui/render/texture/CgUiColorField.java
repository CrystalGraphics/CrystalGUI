package com.crystalgui.render.texture;

import org.jspecify.annotations.Nullable;
import com.crystalgraphics.api.paint.pattern.CgGrid;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.api.canvas.CgCanvasBox;
import com.crystalgraphics.render.canvas.CgCanvasGrid;
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

    private Mode mode = Mode.GRADIENT;
    private float hue;
    private float innerRadius = 0.72f;
    private int fromArgb = 0xFF000000;
    private int toArgb = 0xFFFFFFFF;
    private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

    // The canvas's paints: the fixed ones shared, the rest remade only when what they show changes
    private static final CgGradient STRIP = hues(CgGradient.linear(90f), false);
    private static final CgGradient RING = hues(CgGradient.conic(0.5f, 0.5f, 0f), true);
    private static final CgGradient DARKEN = CgGradient.linear(180f).stop(0f, 0x00000000).stop(1f, 0xFF000000);
    private static final CgGrid CHECKERS = CgGrid.checker(LIGHT, DARK).cell(CHECKER);
    @Nullable
    private CgGradient saturate, ramp;
    private float saturateHue = Float.NaN;
    private int rampFrom, rampTo;
    @Nullable
    private CgVectorPath ring;
    @Nullable
    private CgStroke ringStroke;
    private float ringWidth = Float.NaN, ringHeight = Float.NaN, ringInner = Float.NaN;
    @Nullable
    private CgCornerRadii cornerRadii;

    /** {@code shape} with the six hues and red again, a stop each, or reversed: counter-clockwise from red on a ring. */
    private static CgGradient hues(CgGradient shape, boolean reversed) {
        float[] positions = new float[HUES.length];
        int[] colours = new int[HUES.length];
        for (int i = 0; i < HUES.length; i++) {
            positions[i] = i / 6f;
            colours[i] = HUES[reversed ? HUES.length - 1 - i : i];
        }
        return shape.stops(positions, colours);
    }

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
        boolean square = rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f && rxBR == 0f && ryBR == 0f
                && rxBL == 0f && ryBL == 0f;
        cornerRadii = square ? null : new CgCornerRadii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL);
        return this;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        drawOnCanvas(ctx, x, y, width, height, ctx.getColor());
    }

    private void drawOnCanvas(CgUiPaintContext ctx, float x, float y, float width, float height, int tint) {
        switch (mode) {
            case HUE_RING -> {
                float band = (1f - innerRadius) * Math.min(width, height) * 0.5f;
                if (ring == null || width != ringWidth || height != ringHeight || innerRadius != ringInner) {
                    // The stroke centred on an ellipse half the band in: the ring fills the box's own ellipse
                    ring = CgVectorPath.builder().addOval(band * 0.5f, band * 0.5f, width - band, height - band).build();
                    ringStroke = CgStroke.of(band);
                    ringWidth = width;
                    ringHeight = height;
                    ringInner = innerRadius;
                }
                // A path's paint takes no tint: the tint's alpha fades it, as it would the ring's colours
                ctx.canvas().save().translate(x, y).opacity((tint >>> 24) / 255f).stroke(ring, ringStroke, RING)
                        .restore();
            }
            case SV_SQUARE -> {
                if (saturate == null || hue != saturateHue) {
                    saturate = CgGradient.linear(90f).stop(0f, 0xFFFFFFFF).stop(1f, ArgbMath.fromHsv(hue, 1f, 1f, 0xFF));
                    saturateHue = hue;
                }
                fill(ctx, x, y, width, height, saturate, tint);
                fill(ctx, x, y, width, height, DARKEN, tint);
            }
            case GRADIENT -> {
                if (ramp == null || fromArgb != rampFrom || toArgb != rampTo) {
                    ramp = CgGradient.linear(90f).stop(0f, fromArgb).stop(1f, toArgb);
                    rampFrom = fromArgb;
                    rampTo = toArgb;
                }
                fill(ctx, x, y, width, height, CHECKERS, tint);
                fill(ctx, x, y, width, height, ramp, tint);
            }
            case HUE_STRIP -> fill(ctx, x, y, width, height, STRIP, tint);
        }
    }

    private void fill(CgUiPaintContext ctx, float x, float y, float width, float height, CgPaint paint, int tint) {
        CgCanvasBox box = ctx.canvas().box(x, y, width, height).fill(paint).tint(tint);
        if (cornerRadii != null) box.radii(cornerRadii);
        box.draw();
    }

}
