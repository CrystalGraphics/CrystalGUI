package com.crystalgui.render.texture;

import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.canvas.CgCanvasBox;
import javax.annotation.Nullable;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import com.crystalgraphics.render.canvas.CgCanvasGlass;
import com.crystalgui.render.CgUiPaintContext;

/**
 * What {@code backdrop-filter} paints: <b>the backdrop behind this rect, blurred, refracted, tinted
 * and lit</b>.
 *
 * <pre>{@code
 * backdrop-filter: blur(24px) saturate(1.2);
 * backdrop-filter: blur(24px) tint(#1F202313) bezel(8px) ior(1.5) noise(0.04);
 * }</pre>
 *
 * <p>A property, not a {@code background}: it filters what shows through the element, so it draws under the
 * element's own background. {@code BackdropFilterValue} is the grammar; the canvas's glass captures, blurs
 * and draws it ({@link CgCanvasGlass} (Acrylic's recipe with a lens). With nothing to capture it paints
 * {@link #getFallbackColorArgb}, Acrylic's {@code FallbackColor}.</p>
 */
@Accessors(chain = true)
public final class CgUiBackdropFilter implements CgUiDrawable, CornerRadiusAware {

    /** What {@code backdrop-filter: none} computes to: present so the declaration parses and overrides a lower one, filtering nothing. */
    public static final CgUiBackdropFilter NONE = new CgUiBackdropFilter();

    /** Whether {@code filter} filters anything: not null and not {@link #NONE}. */
    public static boolean isActive(@Nullable CgUiBackdropFilter filter) {
        return filter != null && filter != NONE;
    }

    /** Radius of the Gaussian, in logical px. */
    @Getter @Setter
    private float blurRadius = CgCanvasGlass.DEFAULT.blur();

    /** ARGB laid over the blurred backdrop, straight alpha. */
    @Getter @Setter
    private int tintArgb = CgCanvasGlass.DEFAULT.tint();

    /** How much of the backdrop's colour survives the blur; {@code 1} leaves it alone, above 1 undoes the blur's greying. */
    @Getter @Setter
    private float saturation = CgCanvasGlass.DEFAULT.saturation();

    /**
     * The luminosity blend, 0..1: how much of the tint's brightness the backdrop takes, keeping its hue, before the
     * tint's alpha mixes its colour in. WinUI's acrylic is 0.96, Mica 1.0.
     */
    @Getter @Setter
    private float luminosity = CgCanvasGlass.DEFAULT.luminosity();

    /** Width of the refracting band inward from the edge, in px; flat beyond it. */
    @Getter @Setter
    private float bezel = CgCanvasGlass.DEFAULT.bezel();

    /** Index of refraction: {@code 1} is no lens at all, {@code 1.5} is glass. */
    @Getter @Setter
    private float ior = CgCanvasGlass.DEFAULT.ior();

    /** A multiplier over {@link #glow} and the rim, not a highlight of its own; {@code 0} compiles both out. */
    @Getter @Setter
    private float specular = CgCanvasGlass.DEFAULT.specular();

    /** The broad falloff along the light axis, kept weaker than the rim (0.1 to 0.25) or the panel reads as glow. */
    @Getter @Setter
    private float glow = CgCanvasGlass.DEFAULT.glow();

    /** The thin band at the boundary: what reads as an edge. */
    @Getter @Setter
    private float edgeHighlight = CgCanvasGlass.DEFAULT.rim();

    /**
     * How much of the rim is even rather than lit from the light axis: 0 directional, 1 flat. Fully directional, the
     * rim vanishes at the two corners perpendicular to the light.
     */
    @Getter @Setter
    private float rimAmbient = CgCanvasGlass.DEFAULT.rimAmbient();

    /** Width of the rim, in pixels whatever the bezel. */
    @Getter @Setter
    private float edgeWidth = CgCanvasGlass.DEFAULT.rimWidth();

    /** How far apart the three colour channels refract: two more lens taps; {@code 0} compiles them out. */
    @Getter @Setter
    private float chromatic = CgCanvasGlass.DEFAULT.chromatic();

    /** Grain; {@code 0} compiles it out. */
    @Getter @Setter
    private float noise = CgCanvasGlass.DEFAULT.noise();

    /** Acrylic's {@code FallbackColor}: what this paints when the backdrop cannot be captured. */
    @Getter @Setter
    private int fallbackColorArgb = 0xFF2B2D30;

    /**
     * Every value of {@code look}, a preset of CrystalGraphics' glass or one of its own; the fallback colour stays.
     *
     * <pre>{@code
     * new CgUiBackdropFilter().look(CgCanvasGlass.LIQUID);
     * }</pre>
     */
    public CgUiBackdropFilter look(CgCanvasGlass.Look look) {
        blurRadius = look.blur();
        tintArgb = look.tint();
        saturation = look.saturation();
        luminosity = look.luminosity();
        bezel = look.bezel();
        ior = look.ior();
        chromatic = look.chromatic();
        specular = look.specular();
        glow = look.glow();
        edgeHighlight = look.rim();
        edgeWidth = look.rimWidth();
        rimAmbient = look.rimAmbient();
        noise = look.noise();
        return this;
    }

    @Override
    public void setCornerRadii(float rxTL, float ryTL, float rxTR, float ryTR,
                               float rxBR, float ryBR, float rxBL, float ryBL) {
        boolean square = rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f && rxBR == 0f && ryBR == 0f
                && rxBL == 0f && ryBL == 0f;
        CgCornerRadii r = cornerRadii;
        if (square) {
            cornerRadii = null;
        } else if (r == null || r.topLeftX() != rxTL || r.topLeftY() != ryTL || r.topRightX() != rxTR
                || r.topRightY() != ryTR || r.bottomRightX() != rxBR || r.bottomRightY() != ryBR
                || r.bottomLeftX() != rxBL || r.bottomLeftY() != ryBL) {
            cornerRadii = new CgCornerRadii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL);
        }
    }

    /** The glass as the canvas draws it, made again at a draw after any knob changed. */
    @Nullable
    private CgBackdropFilter filter;
    /** The corners as the canvas takes them, remade only when one changes; null when square. */
    @Nullable
    private CgCornerRadii cornerRadii;

    private CgBackdropFilter filter() {
        CgBackdropFilter f = filter;
        if (f == null || f.blur() != blurRadius || f.tint() != tintArgb || f.saturation() != saturation
                || f.luminosity() != luminosity || f.bezel() != bezel || f.ior() != ior || f.chromatic() != chromatic
                || f.specular() != specular || f.glow() != glow || f.rim() != edgeHighlight || f.rimWidth() != edgeWidth
                || f.rimAmbient() != rimAmbient || f.noise() != noise || f.fallback() != fallbackColorArgb) {
            filter = f = CgBackdropFilter.DEFAULT.blur(blurRadius).tint(tintArgb).color(saturation, luminosity)
                    .lens(bezel, ior).chromatic(chromatic).highlight(specular, glow, edgeHighlight, edgeWidth, rimAmbient)
                    .noise(noise).fallback(fallbackColorArgb);
        }
        return f;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        // The canvas's glass: it captures what is behind the pane when it draws
        CgCanvasBox pane = ctx.canvas().box(x, y, width, height).backdropFilter(filter());
        if (cornerRadii != null) pane.radii(cornerRadii);
        pane.draw();
    }
}
