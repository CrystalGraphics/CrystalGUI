package com.crystalgui.render.texture;

import javax.annotation.Nullable;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import com.crystalgraphics.render.canvas.CgCanvasGlass;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
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
 * element's own background. {@code BackdropFilterValue} is the grammar; {@link CgUiBackdrop} captures and blurs; the
 * draw is CrystalGraphics' {@link CgCanvasGlass} (Acrylic's recipe with a lens). With nothing to capture it paints
 * {@link #getFallbackColorArgb}, Acrylic's {@code FallbackColor}.</p>
 */
@Accessors(chain = true)
public final class CgUiBackdropFilter implements CgUiDrawable, CornerRadiusAware {

    /** Every glass's draw, on the frame thread; made at the first draw, since a server builds filters and draws none. */
    private static CgCanvasGlass gpu;

    /** What {@code backdrop-filter: none} computes to: present so the declaration parses and overrides a lower one, filtering nothing. */
    public static final CgUiBackdropFilter NONE = new CgUiBackdropFilter();

    /** Whether {@code filter} filters anything: not null and not {@link #NONE}. */
    public static boolean isActive(@Nullable CgUiBackdropFilter filter) {
        return filter != null && filter != NONE;
    }

    private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

    /** Per-draw scratch for {@link #quadBody}, a field so a draw allocates no capture. Not part of the value. */
    private CgUiPaintContext drawCtx;
    private float drawX, drawY, drawWidth, drawHeight;
    private final Runnable quadBody = this::quadBody;

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
        this.rxTL = rxTL; this.ryTL = ryTL;
        this.rxTR = rxTR; this.ryTR = ryTR;
        this.rxBR = rxBR; this.ryBR = ryBR;
        this.rxBL = rxBL; this.ryBL = ryBL;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        if (gpu == null) gpu = new CgCanvasGlass(CgCanvasMaterials.SCREEN);
        gpu.size(width, height).radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL)
                .tint(tintArgb).saturation(saturation).luminosity(luminosity)
                .lens(bezel, ior).chromatic(chromatic)
                .specular(specular).highlight(glow, edgeHighlight, edgeWidth, rimAmbient)
                .noise(noise);
        CgUiPaintContext.Backdrop backdrop = ctx.backdropFor(x, y, width, height, blurRadius, gpu.reach());
        if (backdrop == null) {
            ctx.rect().at(x, y).size(width, height)
                    .radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL)
                    .fillColor(fallbackColorArgb)
                    .submit();
            return;
        }
        gpu.backdrop(backdrop.source(), backdrop.offsetX(), backdrop.offsetY(), backdrop.x0(), backdrop.y0(),
                        backdrop.x1(), backdrop.y1())
                // Before withMaterial: binding validates the samplers it holds, deleted by a surface resize.
                .apply();
        drawCtx = ctx;
        drawX = x; drawY = y; drawWidth = width; drawHeight = height;
        ctx.withMaterial(gpu.material(), quadBody);
    }

    private void quadBody() {
        drawCtx.quad().at(drawX, drawY).size(drawWidth, drawHeight).color(drawCtx.getColor()).submit();
    }
}
