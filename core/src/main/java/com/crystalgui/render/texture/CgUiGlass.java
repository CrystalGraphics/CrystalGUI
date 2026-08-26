package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgui.render.CgUiPaintContext;

/**
 * A pane of <b>liquid glass</b>: the backdrop behind this rect, blurred, refracted, tinted and lit.
 *
 * <p>Structurally this is {@link CgUiRoundedRect} again — a drawable owning a shared static material,
 * switching its optional layers on with keyword variants so an unused one is not merely skipped at
 * runtime but <em>absent from the compiled shader</em>. It is deliberately not part of
 * {@code gui_quad.shader}'s shared batch: that material is bound once per frame and every box, glyph and
 * icon in the engine draws through it, so putting two samplers, a uniform block and a mid-frame render
 * target switch there would tax every quad in the application to serve the few that ask for glass.</p>
 *
 * <h3>What it is made of</h3>
 * <pre>
 *   refract → pick blurred/sharp → saturate → tint → specular → noise → SDF mask
 * </pre>
 *
 * <p>Windows' Acrylic recipe with refraction inserted where a real lens puts it. The refraction is the
 * one that separates this from a decade of frosted panels, and the one most easily got wrong: the
 * displacement comes from a <b>surface height profile</b> across the bezel — not from the SDF distance,
 * which yields a bevel rather than a lens. See {@code gui_glass.shader}.</p>
 *
 * <h3>When it cannot render</h3>
 *
 * <p>No GL context, no frame in progress, a failed capture: it paints {@link #setFallbackColor} instead,
 * which is Acrylic's {@code FallbackColor} and a first-class parameter for the same reason — a theme and
 * a settings toggle both need to reach it, and a material that silently draws nothing is worse than one
 * that admits it is a colour today.</p>
 */
public final class CgUiGlass implements CgUiDrawable, CornerRadiusAware {

    /** Shared, like every other drawable's material. {@code gui_glass.shader} declares its own buffer. */
    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_glass.shader");

    /** Drawn instead of glass when there is no backdrop to sample. @see #setFallbackColor */
    private final CgUiRoundedRect fallback = new CgUiRoundedRect();

    private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

    private float blurRadius = 12f;
    private int tintArgb = 0x662B2D30;
    private float saturation = 1.35f;
    private float bezel = 8f;
    private float ior = 1.5f;
    // 1.0 because `specular` is a MASTER MULTIPLIER over the glow and the rim, not a highlight in its
    // own right -- which is how both production references are parameterised (theirs is `specular: 1`
    // over `glow: 0.1` and `edgeHighlight: 0.25`). Defaulting it to a fraction, as the first version
    // did, silently scaled the researched weights down to a third of themselves.
    private float specular = 1.0f;
    private float glow = 0.10f;
    private float edgeHighlight = 0.25f;

    /**
     * How much of the rim is EVEN rather than lit from the light axis. 0 is fully directional, 1 flat.
     *
     * <p>Defaults to a half. The old behaviour is exactly {@code 0}, and it was a defect rather than a
     * default: {@code proj} is zero at the two corners perpendicular to the light, so the hairline
     * vanished at the top-right and bottom-left of every rounded rect while all four straight edges
     * stayed lit. @see gui_glass.shader</p>
     */
    private float rimAmbient = 0.5f;
    private float edgeWidth = 3f;
    private float chromatic = 0.20f;
    private float noise = 0.04f;
    private int fallbackColorArgb = 0xFF2B2D30;

    public CgUiGlass setBlurRadius(float blurRadius) {
        this.blurRadius = Math.max(0f, blurRadius);
        return this;
    }

    /** ARGB laid over the blurred backdrop, straight alpha. */
    public CgUiGlass setTint(int tintArgb) {
        this.tintArgb = tintArgb;
        return this;
    }

    /**
     * How much of the backdrop's colour survives the blur. {@code 1} leaves it alone.
     *
     * <p>Above 1 because a heavy blur averages a scene toward grey, and the backdrop's colour is the
     * only reason to have sampled it — so the blur destroys the first thing the effect is for.</p>
     */
    public CgUiGlass setSaturation(float saturation) {
        this.saturation = Math.max(0f, saturation);
        return this;
    }

    /** Width of the refracting band inward from the edge, in px. Beyond it the glass is flat. */
    public CgUiGlass setBezel(float bezel) {
        this.bezel = Math.max(0f, bezel);
        return this;
    }

    /** Index of refraction: {@code 1} is no lens at all, {@code 1.5} is glass. */
    public CgUiGlass setIor(float ior) {
        this.ior = Math.max(1f, ior);
        return this;
    }

    /** Master highlight strength, scaling both the glow and the rim. {@code 0} compiles them out. */
    public CgUiGlass setSpecular(float specular) {
        this.specular = Math.max(0f, specular);
        return this;
    }

    /**
     * The BROAD falloff along the light axis — bloom rather than an edge.
     *
     * <p>Deliberately the weaker of the two: a highlight made mostly of this reads as glow, not as a
     * surface. The reference implementations weight it about 0.1 against a 0.25 rim.</p>
     */
    public CgUiGlass setGlow(float glow) {
        this.glow = Math.max(0f, glow);
        return this;
    }

    /** The THIN band at the boundary — what actually reads as "this has an edge". @see #setGlow */
    /** @see #rimAmbient */
    public CgUiGlass setRimAmbient(float rimAmbient) {
        this.rimAmbient = Math.min(1f, Math.max(0f, rimAmbient));
        return this;
    }

    public CgUiGlass setEdgeHighlight(float edgeHighlight) {
        this.edgeHighlight = Math.max(0f, edgeHighlight);
        return this;
    }

    /** Width of that band, in PIXELS — a rim is a hairline whatever the bezel behind it is doing. */
    public CgUiGlass setEdgeWidth(float edgeWidth) {
        this.edgeWidth = Math.max(0f, edgeWidth);
        return this;
    }

    /**
     * How far apart the three colour channels refract. {@code 0} compiles the extra taps out.
     *
     * <p>Costs two more lens taps when on, because a prism separates colours by refracting each
     * wavelength through a DIFFERENT ANGLE — one tap tinted three ways cannot express that.</p>
     */
    public CgUiGlass setChromatic(float chromatic) {
        this.chromatic = Math.max(0f, chromatic);
        return this;
    }

    /** Grain. {@code 0} compiles it out. @see #draw */
    public CgUiGlass setNoise(float noise) {
        this.noise = Math.max(0f, noise);
        return this;
    }

    /** Acrylic's {@code FallbackColor}: what this paints when glass cannot be rendered. */
    public CgUiGlass setFallbackColor(int fallbackColorArgb) {
        this.fallbackColorArgb = fallbackColorArgb;
        return this;
    }

    // READ BACK, for a tuner that has to show what it is about to change and print it as CSS. A setter
    // without a getter is fine for a drawable the cascade owns outright; it stops being fine the moment
    // something has to SEED itself from the live value rather than from a guess at the stylesheet's.
    public float getBlurRadius() { return blurRadius; }
    public int getTint() { return tintArgb; }
    public float getSaturation() { return saturation; }
    public float getBezel() { return bezel; }
    public float getIor() { return ior; }
    public float getSpecular() { return specular; }
    public float getGlow() { return glow; }
    public float getEdgeHighlight() { return edgeHighlight; }
    public float getRimAmbient() { return rimAmbient; }
    public float getEdgeWidth() { return edgeWidth; }
    public float getChromatic() { return chromatic; }
    public float getNoise() { return noise; }
    public int getFallbackColor() { return fallbackColorArgb; }

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
            fallback.setCornerRadius(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL);
            fallback.setFillColor(fallbackColorArgb);
            fallback.draw(ctx, mouseX, mouseY, x, y, width, height);
            return;
        }

        // Each of these compiles its layer out entirely rather than branching past it, which is what
        // makes plain frosted glass and the full liquid surface one material instead of three.
        MATERIAL.toggleKeyword("WITH_REFRACTION", ior > 1.001f && bezel > 0f);
        MATERIAL.toggleKeyword("WITH_CHROMATIC", ior > 1.001f && bezel > 0f && chromatic > 0f);
        MATERIAL.toggleKeyword("WITH_SPECULAR", specular > 0f);
        MATERIAL.toggleKeyword("WITH_NOISE", noise > 0f);

        // BEFORE withMaterial: binding validates the samplers the material already holds, and after a
        // surface resize those are textures the rebuild deleted. @see CgUiBackdrop#blurPass
        MATERIAL.applyProperties(b -> {
                b.sampler("_MainTex", 0, backdrop.blurred());
                b.sampler("_SharpTex", 1, backdrop.sharp());
                b.vec4("_CornerRadiusX", rxTL, rxTR, rxBR, rxBL);
                b.vec4("_CornerRadiusY", ryTL, ryTR, ryBR, ryBL);
                b.vec2("_BoxSize", width, height);
                b.vec4("_BackdropRect", backdrop.u0(), backdrop.v0(),
                        backdrop.u1(), backdrop.v1());
                b.colorARGB("_Tint", tintArgb);
                b.set1f("_Saturation", saturation);
                b.set1f("_Bezel", bezel);
                b.set1f("_Ior", ior);
                b.set1f("_Specular", specular);
                b.set1f("_Glow", glow);
                b.set1f("_EdgeHighlight", edgeHighlight);
                b.set1f("_EdgeWidth", edgeWidth);
                b.set1f("_RimAmbient", rimAmbient);
                b.set1f("_Chromatic", chromatic);
                b.set1f("_Noise", noise);
                // Fixed in ELEMENT space, not screen space, so the highlight does not swim across the
                // surface when the window it belongs to is dragged. Up and to the left, which is where
                // every UI toolkit has put its light since bevels were invented.
                // 45 degrees, which is where both references put it -- and with a symmetric
                // highlight the axis is a diagonal rather than a direction, so a diagonal is the
                // honest spelling of it.
                b.vec2("_LightDir", -0.7071f, -0.7071f);
        });
        ctx.withMaterial(MATERIAL, () ->
                ctx.quad().at(x, y).size(width, height).color(ctx.getColor()).submit());
    }
}
