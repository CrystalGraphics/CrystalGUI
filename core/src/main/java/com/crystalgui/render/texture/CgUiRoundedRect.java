package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.render.CgUiPaintContext;

/**
 * SDF-based rounded-rectangle drawable — the "canvas" primitive for {@code border-radius}
 * styling: a flat-color or texture-filled interior, with an optional stroked border, both
 * clipped by the same rounded-box distance field (see {@code gui_rounded_rect.shader}).
 *
 * <p>Uses one shared, cached {@link CgMaterial} (matching {@code CgTextRenderer.TEXT_MATERIAL}'s
 * established pattern — {@code applyProperties} is CPU-only and cheap to re-issue immediately
 * before each draw, so a fresh {@link CgMaterial#newInstance} per element would only buy an
 * independent property UBO at the cost of a GPU resource every caller has to remember to
 * delete, for no real benefit here).</p>
 */
public final class CgUiRoundedRect implements CgUiDrawable {

    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_rounded_rect.shader");

    private float cornerRadius = 0f;
    private float borderWidth = 0f;
    private int borderColorArgb = 0xFF000000;
    private int fillColorArgb = 0xFFFFFFFF;
    private CgTexture2D fillTexture;

    public CgUiRoundedRect setCornerRadius(float cornerRadius) {
        this.cornerRadius = cornerRadius;
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
        return this;
    }

    public CgUiRoundedRect setFillTexture(CgTexture2D texture) {
        this.fillTexture = texture;
        return this;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        boolean withBorder = borderWidth > 0f;
        boolean withTextureFill = fillTexture != null;

        setKeyword("WITH_BORDER", withBorder);
        setKeyword("WITH_TEXTURE_FILL", withTextureFill);

        ctx.withMaterial(MATERIAL, () -> {
            MATERIAL.applyProperties(b -> {
                b.set1f("_CornerRadius", cornerRadius);
                b.set1f("_BorderWidth", borderWidth);
                b.colorARGB("_BorderColor", borderColorArgb);
                b.colorARGB("_FillColor", fillColorArgb);
                b.vec2("_BoxSize", width, height);
                if (withTextureFill) {
                    b.sampler("_MainTex", 0, fillTexture);
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
