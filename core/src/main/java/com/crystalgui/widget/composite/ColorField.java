package com.crystalgui.widget.composite;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgCanvasBox;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.paint.pattern.CgGrid;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.render.image.CgUiPainter;
import com.crystalgraphics.api.paint.CgColor;

/**
 * The colour picker's fields, drawn by hand: a hue ring, a saturation/value square, a ramp between two colours over a
 * checkerboard, a hue strip. A value: equal fields are equal, so restyling one restarts no transition.
 *
 * <pre>{@code
 * ring.generalStyle(g -> g.background(ColorField.hueRing(0.72f).image()));
 * square.generalStyle(g -> g.background(ColorField.svSquare(hue).image()));
 * swatch.generalStyle(g -> g.background(ColorField.ramp(argb, argb).corners(2f).image()));
 * }</pre>
 *
 * <ul>
 *   <li>Each draws into the whole box; {@link #corners} rounds it. The element's tint multiplies it.</li>
 *   <li>A ramp's colours are straight {@code 0xAARRGGBB}; one with alpha shows the checkerboard through.</li>
 * </ul>
 */
public final class ColorField implements CgUiPainter {

    public enum Mode { HUE_RING, SV_SQUARE, RAMP, HUE_STRIP }

    private static final int[] HUES = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
    private static final float CHECKER = 4f;
    private static final int LIGHT = 0xFF9E9E9E, DARK = 0xFF707070;
    private static final CgGradient STRIP = hues(CgGradient.linear(90f), false);
    private static final CgGradient RING = hues(CgGradient.conic(0.5f, 0.5f, 0f), true);
    private static final CgGradient DARKEN = CgGradient.linear(180f).stop(0f, 0x00000000).stop(1f, 0xFF000000);
    private static final CgGrid CHECKERS = CgGrid.checker(LIGHT, DARK).cell(CHECKER);

    private final Mode mode;
    private final float hue, innerRadius;
    private final int from, to;
    @Nullable
    private final CgCornerRadii radii;
    /** The paint this value needs, made once; not part of the value. */
    @Nullable
    private final CgGradient paint;
    /** The ring as last drawn and the box it fits; not part of the value. */
    @Nullable
    private volatile Ring ring;

    private record Ring(float width, float height, CgVectorPath path, CgStroke stroke) {
    }

    private ColorField(Mode mode, float hue, float innerRadius, int from, int to, @Nullable CgCornerRadii radii) {
        this.mode = mode;
        this.hue = hue;
        this.innerRadius = innerRadius;
        this.from = from;
        this.to = to;
        this.radii = radii;
        this.paint = switch (mode) {
            case SV_SQUARE -> CgGradient.linear(90f).stop(0f, 0xFFFFFFFF).stop(1f, CgColor.hsvArgb(hue * 360f, 1f, 1f, 1f));
            case RAMP -> CgGradient.linear(90f).stop(0f, from).stop(1f, to);
            default -> null;
        };
    }

    /** Every hue around a ring {@code innerRadius} of the box's half-size thick from its edge. */
    public static ColorField hueRing(float innerRadius) {
        return new ColorField(Mode.HUE_RING, 0f, innerRadius, 0, 0, null);
    }

    /** Saturation across, value down, at {@code hue} (0 to 1). */
    public static ColorField svSquare(float hue) {
        return new ColorField(Mode.SV_SQUARE, hue, 0f, 0, 0, null);
    }

    /** {@code from} on the left to {@code to} on the right, over a checkerboard. */
    public static ColorField ramp(int from, int to) {
        return new ColorField(Mode.RAMP, 0f, 0f, from, to, null);
    }

    /** Every hue, left to right. */
    public static ColorField hueStrip() {
        return new ColorField(Mode.HUE_STRIP, 0f, 0f, 0, 0, null);
    }

    /** This field with every corner rounded by {@code radius}. */
    public ColorField corners(float radius) {
        return new ColorField(mode, hue, innerRadius, from, to, radius > 0f ? CgCornerRadii.of(radius) : null);
    }

    /** This field with its corners rounded by {@code radii}. */
    public ColorField corners(CgCornerRadii radii) {
        return new ColorField(mode, hue, innerRadius, from, to, radii);
    }

    /** As a style value. */
    public CgUiImage image() {
        return CgUiImage.painted(this);
    }

    @Override
    public void paint(CgCanvas canvas, float width, float height, int color) {
        if (width <= 0f || height <= 0f) return;
        switch (mode) {
            case HUE_RING -> {
                Ring r = ring;
                if (r == null || r.width != width || r.height != height) {
                    float band = (1f - innerRadius) * Math.min(width, height) * 0.5f;
                    // The stroke centred on an ellipse half the band in: the ring fills the box's own ellipse
                    ring = r = new Ring(width, height,
                            CgVectorPath.builder().addOval(band * 0.5f, band * 0.5f, width - band, height - band).build(),
                            CgStroke.of(band));
                }
                // A path's paint takes no tint: the tint's alpha fades it
                canvas.save().opacity((color >>> 24) / 255f).stroke(r.path, r.stroke, RING).restore();
            }
            case SV_SQUARE -> {
                fill(canvas, width, height, Objects.requireNonNull(paint), color);
                fill(canvas, width, height, DARKEN, color);
            }
            case RAMP -> {
                fill(canvas, width, height, CHECKERS, color);
                fill(canvas, width, height, Objects.requireNonNull(paint), color);
            }
            case HUE_STRIP -> fill(canvas, width, height, STRIP, color);
        }
    }

    private void fill(CgCanvas canvas, float width, float height, CgPaint paint, int tint) {
        CgCanvasBox box = canvas.box(0f, 0f, width, height).fill(paint).tint(tint);
        if (radii != null) box.radii(radii);
        box.draw();
    }

    private static CgGradient hues(CgGradient shape, boolean reversed) {
        float[] positions = new float[HUES.length];
        int[] colours = new int[HUES.length];
        for (int i = 0; i < HUES.length; i++) {
            positions[i] = i / 6f;
            colours[i] = HUES[reversed ? HUES.length - 1 - i : i];
        }
        return shape.stops(positions, colours);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ColorField f && mode == f.mode && Float.compare(hue, f.hue) == 0
                && Float.compare(innerRadius, f.innerRadius) == 0 && from == f.from && to == f.to
                && Objects.equals(radii, f.radii);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, hue, innerRadius, from, to, radii);
    }
}
