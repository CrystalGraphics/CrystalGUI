package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.render.CgUiPaintContext;

/**
 * SDF-based rounded-rectangle drawable — the "canvas" primitive for {@code border-radius}
 * styling: a flat-color or texture-filled interior, with an optional stroked border, both
 * clipped by the same rounded-box distance field (see {@code gui_rounded_rect.shader}). Corner
 * radii are independent per corner (TL/TR/BR/BL, CSS {@code border-radius} order).
 *
 * <p>Uses one shared, cached {@link CgMaterial} (matching {@code CgTextRenderer.TEXT_MATERIAL}'s
 * established pattern — {@code applyProperties} is CPU-only and cheap to re-issue immediately
 * before each draw, so a fresh {@link CgMaterial#newInstance} per element would only buy an
 * independent property UBO at the cost of a GPU resource every caller has to remember to
 * delete, for no real benefit here).</p>
 */
public final class CgUiRoundedRect implements CgUiDrawable {

    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_rounded_rect.shader");

    private float radiusTL = 0f, radiusTR = 0f, radiusBR = 0f, radiusBL = 0f;
    private float borderWidth = 0f;
    private int borderColorArgb = 0xFF000000;
    private int fillColorArgb = 0xFFFFFFFF;
    private CgTexture2D fillTexture;

    // Mixed-fill state, set only by morph() when interpolating between two fills that can't be
    // smoothly lerped as a single color (a texture on either side — see morph()'s doc). When set,
    // draw() renders the (already-morphed) shape twice, once per fill, blended by layer opacity —
    // the shape/border are identical both times, only the fill visibly cross-fades.
    private boolean mixedFill = false;
    private float mixedFillT = 0f;
    private int fillColorArgbB = 0xFFFFFFFF;
    private CgTexture2D fillTextureB;

    /** {@code false} once this instance is itself a mixed-fill snapshot (from {@link #morph}) —
     * {@code fillColorArgb}/{@code fillTexture} alone no longer represent its true visual state (the
     * "B" pass and blend progress live in separate fields), so {@link #morph} must not read them
     * directly as if they were a single resolved fill; callers should fall back to compositing
     * (e.g. {@link CgUiCrossFade}) instead. */
    public boolean isPureFill() {
        return !mixedFill;
    }

    public CgUiRoundedRect setCornerRadius(float radius) {
        return setCornerRadius(radius, radius, radius, radius);
    }

    /** @param topLeft/topRight/bottomRight/bottomLeft independent per-corner radii, CSS {@code border-radius} order. */
    public CgUiRoundedRect setCornerRadius(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        this.radiusTL = topLeft;
        this.radiusTR = topRight;
        this.radiusBR = bottomRight;
        this.radiusBL = bottomLeft;
        return this;
    }

    public CgUiRoundedRect setBorder(float width, int colorArgb) {
        this.borderWidth = width;
        this.borderColorArgb = colorArgb;
        return this;
    }

    public CgUiRoundedRect setFillColor(int colorArgb) {
        this.fillColorArgb = colorArgb;
        this.fillTexture = null;
        this.mixedFill = false;
        return this;
    }

    public CgUiRoundedRect setFillTexture(CgTexture2D texture) {
        this.fillTexture = texture;
        this.mixedFill = false;
        return this;
    }

    /**
     * True shape morph between two {@code CgUiRoundedRect}s: corner radii, border width, and
     * border color are all true-lerped (a single draw of one intermediate shape, no compositing),
     * unlike {@link CgUiCrossFade}'s draw-both-and-blend-opacity approach — appropriate here
     * because, unlike two unrelated 9-slice sprites, two SDF rects are the same procedural shape
     * family and their parameters interpolate meaningfully.
     *
     * <p>Fill is the one part that can't always be a pure parameter lerp: if both sides are flat
     * colors, it lerps the color too (fully continuous). If either side has a texture fill, there's
     * no more a well-defined per-pixel blend here than there is for two arbitrary textures
     * elsewhere in this engine — but since the morphed shape is identical on both sides now (not
     * true for two independent 9-slice sprites), the fallback is cheap and clean: draw the same
     * shape twice, once per fill, blended by layer opacity.
     */
    public static CgUiRoundedRect morph(CgUiRoundedRect from, CgUiRoundedRect to, float t) {
        CgUiRoundedRect result = new CgUiRoundedRect();
        result.radiusTL = lerp(from.radiusTL, to.radiusTL, t);
        result.radiusTR = lerp(from.radiusTR, to.radiusTR, t);
        result.radiusBR = lerp(from.radiusBR, to.radiusBR, t);
        result.radiusBL = lerp(from.radiusBL, to.radiusBL, t);
        result.borderWidth = lerp(from.borderWidth, to.borderWidth, t);
        result.borderColorArgb = ArgbMath.lerp(from.borderColorArgb, to.borderColorArgb, t);

        boolean fromHasTexture = from.fillTexture != null;
        boolean toHasTexture = to.fillTexture != null;
        if (!fromHasTexture && !toHasTexture) {
            result.fillColorArgb = ArgbMath.lerp(from.fillColorArgb, to.fillColorArgb, t);
        } else {
            result.mixedFill = true;
            result.mixedFillT = t;
            result.fillColorArgb = from.fillColorArgb;
            result.fillTexture = from.fillTexture;
            result.fillColorArgbB = to.fillColorArgb;
            result.fillTextureB = to.fillTexture;
        }
        return result;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        setKeyword("WITH_BORDER", borderWidth > 0f);

        if (!mixedFill) {
            drawOnePass(ctx, x, y, width, height, fillColorArgb, fillTexture);
        } else {
            ctx.withLayerOpacity(1f - mixedFillT, () -> drawOnePass(ctx, x, y, width, height, fillColorArgb, fillTexture));
            ctx.withLayerOpacity(mixedFillT, () -> drawOnePass(ctx, x, y, width, height, fillColorArgbB, fillTextureB));
        }
    }

    private void drawOnePass(CgUiPaintContext ctx, float x, float y, float width, float height, int fillColor, CgTexture2D fillTex) {
        boolean withTextureFill = fillTex != null;
        setKeyword("WITH_TEXTURE_FILL", withTextureFill);

        ctx.withMaterial(MATERIAL, () -> {
            MATERIAL.applyProperties(b -> {
                b.vec4("_CornerRadius", radiusTL, radiusTR, radiusBR, radiusBL);
                b.set1f("_BorderWidth", borderWidth);
                b.colorARGB("_BorderColor", borderColorArgb);
                b.colorARGB("_FillColor", fillColor);
                b.vec2("_BoxSize", width, height);
                if (withTextureFill) {
                    b.sampler("_MainTex", 0, fillTex);
                }
            });
            ctx.submitQuad(x, y, width, height, 0f, 0f, 1f, 1f, ctx.getColor());
        });
    }

    private static void setKeyword(String name, boolean enabled) {
        if (enabled) {
            if (!MATERIAL.isKeywordEnabled(name)) MATERIAL.enableKeyword(name);
        } else {
            if (MATERIAL.isKeywordEnabled(name)) MATERIAL.disableKeyword(name);
        }
    }
}
