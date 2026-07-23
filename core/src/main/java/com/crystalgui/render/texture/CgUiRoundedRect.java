package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.render.CgUiPaintContext;

/**
 * SDF-based rounded-rectangle "canvas" — the actual rendering mechanism behind the universal
 * {@code border-radius}/{@code border-width}/{@code border-color} layer: a flat-color or
 * texture-filled interior, with an optional stroked border, both clipped by the same rounded-box
 * distance field (see {@code gui_rounded_rect.shader}). Corner radii are independent per corner
 * and per axis (rx/ry, TL/TR/BR/BL, CSS {@code border-radius} order) — elliptical, not just
 * circular, corners.
 *
 * <p>Built fresh each frame by {@code UIElement.paintSelf} from the currently-resolved
 * border-radius/border-width/border-color style values and the resolved {@code background}
 * drawable — it is <strong>not</strong> itself parsed out of a stylesheet value anymore (there is
 * no {@code roundedrect(...)} background function). Because of that, it's never held inside the
 * {@code background} cascade, so transitions animate the underlying scalar/color longhands
 * directly rather than morphing two {@code CgUiRoundedRect} instances.</p>
 */
public final class CgUiRoundedRect implements CgUiDrawable {

    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_rounded_rect.shader");

    private float rxTL = 0f, ryTL = 0f, rxTR = 0f, ryTR = 0f, rxBR = 0f, ryBR = 0f, rxBL = 0f, ryBL = 0f;
    private float borderWidth = 0f;
    private int borderColorArgb = 0xFF000000;
    private int fillColorArgb = 0xFFFFFFFF;
    private CgTexture2D fillTexture;

    public CgUiRoundedRect setCornerRadius(float rx, float ry) {
        return setCornerRadius(rx, ry, rx, ry, rx, ry, rx, ry);
    }

    /** Independent elliptical radius per corner, CSS {@code border-radius} order (TL,TR,BR,BL),
     * each an (rx,ry) pair. */
    public CgUiRoundedRect setCornerRadius(float rxTL, float ryTL, float rxTR, float ryTR,
                                            float rxBR, float ryBR, float rxBL, float ryBL) {
        this.rxTL = rxTL; this.ryTL = ryTL;
        this.rxTR = rxTR; this.ryTR = ryTR;
        this.rxBR = rxBR; this.ryBR = ryBR;
        this.rxBL = rxBL; this.ryBL = ryBL;
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
        setKeyword("WITH_BORDER", borderWidth > 0f);
        boolean withTextureFill = fillTexture != null;
        setKeyword("WITH_TEXTURE_FILL", withTextureFill);

        ctx.withMaterial(MATERIAL, () -> {
            MATERIAL.applyProperties(b -> {
                b.vec4("_CornerRadiusX", rxTL, rxTR, rxBR, rxBL);
                b.vec4("_CornerRadiusY", ryTL, ryTR, ryBR, ryBL);
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
