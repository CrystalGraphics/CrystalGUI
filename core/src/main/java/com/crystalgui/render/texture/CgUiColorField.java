package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgui.render.CgUiPaintContext;

/**
 * The continuously-varying surfaces a colour picker is made of — a hue ring, a saturation/value
 * square, and a two-stop gradient over a transparency checkerboard.
 *
 * <h3>Why these cannot be ordinary drawables</h3>
 * <p>Every other {@link CgUiDrawable} paints something whose colour is constant across the quad, or
 * comes from a texture. These are <b>functions of position</b>: a hue ring's colour depends on the
 * angle, an SV square's on both axes at once. A texture would have to be regenerated whenever the hue
 * changed and would band visibly at any size, so they go through their own material — the same reason
 * {@link CgUiRoundedRect} does.</p>
 *
 * <h3>One drawable, three modes</h3>
 * <p>They share a class because they share everything except six lines of GLSL: the same rounded-box
 * clip, the same quad submission, the same material. Splitting them into three drawables would be
 * three copies of the binding code around three different {@code if} branches.</p>
 */
public final class CgUiColorField implements CgUiDrawable {

    /** Which surface to draw. Ordinals are the shader's {@code _Mode}, so the two must stay in step. */
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

    /**
     * The shared material, resolved on first <b>draw</b> rather than at class init.
     *
     * <h4>Lazy because a colour picker is constructed headlessly</h4>
     * <p>{@code ColorSelector} builds one of these while assembling itself, and a dedicated server
     * assembles widget trees with CrystalGraphics core absent by design — {@code headlessTest} asserts
     * exactly that. A {@code static final} initialiser would load the material class the moment this
     * class did, failing with {@code NoClassDefFoundError} on construction rather than on paint.</p>
     *
     * <p>Deferring it keeps the rule the codebase already states: a CrystalGraphics core type may be
     * reached from inside a paint-method body, and nowhere else.</p>
     *
     * @see CgUiRoundedRect for why this needs no {@code attachTo} — the shader declares its buffer
     */
    private static CgMaterial material;

    private static CgMaterial material() {
        if (material == null) material = CgMaterial.load("crystalgui:shaders/gui_color_field.shader");
        return material;
    }

    private Mode mode = Mode.GRADIENT;
    private float hue;
    private float innerRadius = 0.72f;
    private int fromArgb = 0xFF000000;
    private int toArgb = 0xFFFFFFFF;
    private float radiusX;
    private float radiusY;

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
        this.radiusX = rx;
        this.radiusY = ry;
        return this;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        CgMaterial mat = material();
        ctx.withMaterial(mat, () -> {
            mat.applyProperties(b -> {
                b.set1f("_Mode", mode.ordinal());
                b.set1f("_Hue", hue);
                b.set1f("_InnerRadius", innerRadius);
                b.colorARGB("_ColorA", fromArgb);
                b.colorARGB("_ColorB", toArgb);
                b.vec4("_CornerRadiusX", radiusX, radiusX, radiusX, radiusX);
                b.vec4("_CornerRadiusY", radiusY, radiusY, radiusY, radiusY);
                b.vec2("_BoxSize", width, height);
            });
            ctx.quad().at(x, y).size(width, height).color(ctx.getColor()).submit();
        });
    }
}
