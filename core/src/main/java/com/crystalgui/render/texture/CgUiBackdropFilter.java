package com.crystalgui.render.texture;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
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
 * <p>A property rather than a {@code background} value, and that is not only a name: a filter acts on
 * what shows THROUGH an element, so this is drawn under the element's own background and the two
 * compose. {@code BackdropFilterValue} is the grammar and where a value from CSS is bounded;
 * {@link CgUiBackdrop} is the capture-and-blur primitive underneath.</p>
 *
 * <h3>What it is made of</h3>
 * <pre>
 *   refract -&gt; pick blurred/sharp -&gt; saturate -&gt; luminosity -&gt; tint -&gt; specular -&gt; noise -&gt; SDF mask
 * </pre>
 *
 * <p>Windows' Acrylic recipe with refraction inserted where a real lens puts it. The refraction is what
 * separates this from a decade of frosted panels, and the thing most easily got wrong: the displacement
 * comes from a <b>surface height profile</b> across the bezel, not from the SDF distance, which yields a
 * bevel rather than a lens. Every optional layer is a shader keyword, so an unused one is absent from
 * the compiled program rather than skipped at runtime — see {@code gui_backdrop_filter.shader}.</p>
 *
 * <h3>When it cannot render</h3>
 *
 * <p>No GL context, no frame in progress, a failed capture: it paints {@link #getFallbackColorArgb}
 * instead, which is Acrylic's {@code FallbackColor} and a first-class parameter for the same reason — a
 * theme and a settings toggle both need to reach it, and a material that silently draws nothing is worse
 * than one that admits it is a colour today.</p>
 */
@Accessors(chain = true)
public final class CgUiBackdropFilter implements CgUiDrawable, CornerRadiusAware {

    /** Shared, like every other drawable's material. {@code gui_backdrop_filter.shader} declares its own buffer. */
    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_backdrop_filter.shader");

    private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

    /**
     * <b>Per-draw scratch for {@link #quadBody} and {@link #writeProperties}</b>, which are held as
     * FIELDS so a draw allocates neither.
     *
     * <p>{@code withMaterial} and {@code applyProperties} each take a callback, and a lambda over the
     * draw's arguments is a fresh capture every time one runs — on a path that is per element per
     * frame. A method reference stored once costs nothing after construction. These fields are not
     * part of the value: nothing here is read by {@code equals} and nothing survives the draw.</p>
     */
    private CgUiPaintContext drawCtx;
    private CgUiPaintContext.Backdrop drawBackdrop;
    private float drawX, drawY, drawWidth, drawHeight;
    private final Runnable quadBody = this::quadBody;
    private final java.util.function.Consumer<CgShaderBindings> propertyWriter = this::writeProperties;

    /** Radius of the Gaussian, in logical px. */
    @Getter @Setter
    private float blurRadius = 12f;

    /** ARGB laid over the blurred backdrop, straight alpha. */
    @Getter @Setter
    private int tintArgb = 0x662B2D30;

    /**
     * How much of the backdrop's colour survives the blur. {@code 1} leaves it alone.
     *
     * <p>Above 1 because a heavy blur averages a scene toward grey, and the backdrop's colour is the
     * only reason to have sampled it — so the blur destroys the first thing the effect is for.</p>
     */
    @Getter @Setter
    private float saturation = 1.35f;

    /**
     * The luminosity blend, 0..1: the backdrop keeps its hue and saturation and takes the tint's
     * brightness by this much, before the tint's own alpha mixes its colour in. WinUI's acrylic is
     * 0.96 and Mica 1.0 — it is most of what makes those materials read as a temperature.
     */
    @Getter @Setter
    private float luminosity = 0f;

    /** Width of the refracting band inward from the edge, in px. Beyond it the surface is flat. */
    @Getter @Setter
    private float bezel = 8f;

    /** Index of refraction: {@code 1} is no lens at all, {@code 1.5} is glass. */
    @Getter @Setter
    private float ior = 1.5f;

    /**
     * Master highlight strength, scaling both the glow and the rim. {@code 0} compiles them out.
     *
     * <p>1.0 because it is a MULTIPLIER over those two rather than a highlight in its own right, which
     * is how both production references are parameterised — theirs is {@code specular: 1} over
     * {@code glow: 0.1} and {@code edgeHighlight: 0.25}. Defaulting it to a fraction, as the first
     * version did, silently scaled the researched weights down to a third of themselves.</p>
     */
    @Getter @Setter
    private float specular = 1.0f;

    /**
     * The BROAD falloff along the light axis — bloom rather than an edge.
     *
     * <p>Deliberately the weaker of the two: a highlight made mostly of this reads as glow, not as a
     * surface. The reference implementations weight it about 0.1 against a 0.25 rim.</p>
     */
    @Getter @Setter
    private float glow = 0.10f;

    /** The THIN band at the boundary — what actually reads as "this has an edge". @see #glow */
    @Getter @Setter
    private float edgeHighlight = 0.25f;

    /**
     * How much of the rim is EVEN rather than lit from the light axis. 0 is fully directional, 1 flat.
     *
     * <p>Defaults to a half. The old behaviour is exactly {@code 0}, and it was a defect rather than a
     * default: {@code proj} is zero at the two corners perpendicular to the light, so the hairline
     * vanished at the top-right and bottom-left of every rounded rect while all four straight edges
     * stayed lit. @see gui_backdrop_filter.shader</p>
     */
    @Getter @Setter
    private float rimAmbient = 0.5f;

    /** Width of that band, in PIXELS — a rim is a hairline whatever the bezel behind it is doing. */
    @Getter @Setter
    private float edgeWidth = 3f;

    /**
     * How far apart the three colour channels refract. {@code 0} compiles the extra taps out.
     *
     * <p>Costs two more lens taps when on, because a prism separates colours by refracting each
     * wavelength through a DIFFERENT ANGLE — one tap tinted three ways cannot express that.</p>
     */
    @Getter @Setter
    private float chromatic = 0.20f;

    /** Grain. {@code 0} compiles it out. @see #draw */
    @Getter @Setter
    private float noise = 0.04f;

    /** Acrylic's {@code FallbackColor}: what this paints when the backdrop cannot be captured. */
    @Getter @Setter
    private int fallbackColorArgb = 0xFF2B2D30;

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
        // THE RADIUS IS PASSED THROUGH, IN PIXELS. It used to be translated here into a dual-Kawase
        // iteration count plus a within-level tap offset, and that translation is worth recording as a
        // thing NOT to bring back without a test that can see one level in isolation: the mapping was
        // rederived three times, each version was defensible on paper, and every one of them produced a
        // panel that darkened as the radius grew. A separable blur has one number and no mapping.
        CgUiPaintContext.Backdrop backdrop = ctx.backdropFor(x, y, width, height, blurRadius);
        if (backdrop == null || backdrop.sharp() == null || backdrop.blurred() == null) {
            // The context's scratch, so a glass panel with nothing behind it does not allocate a rect
            // per frame to say so. @see #setFallbackColor
            ctx.rect().at(x, y).size(width, height)
                    .radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL)
                    .fillColor(fallbackColorArgb)
                    .submit();
            return;
        }

        // Each of these compiles its layer out entirely rather than branching past it, which is what
        // makes plain frosted glass and the full liquid surface one material instead of three.
        MATERIAL.toggleKeyword("WITH_REFRACTION", ior > 1.001f && bezel > 0f);
        MATERIAL.toggleKeyword("WITH_CHROMATIC", ior > 1.001f && bezel > 0f && chromatic > 0f);
        MATERIAL.toggleKeyword("WITH_SPECULAR", specular > 0f);
        MATERIAL.toggleKeyword("WITH_NOISE", noise > 0f);

        drawCtx = ctx;
        drawBackdrop = backdrop;
        drawX = x; drawY = y; drawWidth = width; drawHeight = height;
        // BEFORE withMaterial: binding validates the samplers the material already holds, and after a
        // surface resize those are textures the rebuild deleted. @see CgUiBackdropFilter#blurPass
        MATERIAL.applyProperties(propertyWriter);
        ctx.withMaterial(MATERIAL, quadBody);
    }

    private void quadBody() {
        drawCtx.quad().at(drawX, drawY).size(drawWidth, drawHeight).color(drawCtx.getColor()).submit();
    }

    private void writeProperties(CgShaderBindings b) {
        b.sampler("_MainTex", 0, drawBackdrop.blurred());
        b.sampler("_SharpTex", 1, drawBackdrop.sharp());
        b.vec4("_CornerRadiusX", rxTL, rxTR, rxBR, rxBL);
        b.vec4("_CornerRadiusY", ryTL, ryTR, ryBR, ryBL);
        b.vec2("_BoxSize", drawWidth, drawHeight);
        b.vec4("_BackdropRect", drawBackdrop.u0(), drawBackdrop.v0(),
                drawBackdrop.u1(), drawBackdrop.v1());
        b.colorARGB("_Tint", tintArgb);
        b.set1f("_Saturation", saturation);
        b.set1f("_Luminosity", luminosity);
        b.set1f("_Bezel", bezel);
        b.set1f("_Ior", ior);
        b.set1f("_Specular", specular);
        b.set1f("_Glow", glow);
        b.set1f("_EdgeHighlight", edgeHighlight);
        b.set1f("_EdgeWidth", edgeWidth);
        b.set1f("_RimAmbient", rimAmbient);
        b.set1f("_Chromatic", chromatic);
        b.set1f("_Noise", noise);
        // Fixed in ELEMENT space, not screen space, so the highlight does not swim across the surface
        // when the window it belongs to is dragged. Up and to the left, which is where every UI toolkit
        // has put its light since bevels were invented. 45 degrees, which is where both references put
        // it -- and with a symmetric highlight the axis is a diagonal rather than a direction, so a
        // diagonal is the honest spelling of it.
        b.vec2("_LightDir", -0.7071f, -0.7071f);
    }
}
