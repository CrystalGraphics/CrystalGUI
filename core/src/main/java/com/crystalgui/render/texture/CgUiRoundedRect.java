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
    /** Non-null when the fill is a 9-slice sprite — mutually exclusive with {@link #fillTexture}
     * (single stretched texture) and a plain {@link #fillColorArgb}. Reuses {@link CgUiSprite}'s
     * own cached UV-breakpoint/border math directly rather than duplicating it. */
    private CgUiSprite fillSprite;

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
        this.fillSprite = null;
        return this;
    }

    public CgUiRoundedRect setFillTexture(CgTexture2D texture) {
        this.fillTexture = texture;
        this.fillSprite = null;
        return this;
    }

    /** Fills with a 9-slice sprite, clipped/stroked by the same corner-radius/border SDF as any
     * other fill — the sprite's own alpha (including any transparency baked into its art, not just
     * what {@code border-radius} carves out) is what actually renders. */
    public CgUiRoundedRect setFillSprite(CgUiSprite sprite) {
        this.fillSprite = sprite;
        this.fillTexture = null;
        return this;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        MATERIAL.toggleKeyword("WITH_BORDER", borderWidth > 0f);
        boolean with9SliceFill = fillSprite != null;
        boolean withTextureFill = !with9SliceFill && fillTexture != null;
        MATERIAL.toggleKeyword("WITH_TEXTURE_FILL", withTextureFill);
        MATERIAL.toggleKeyword("WITH_9SLICE_FILL", with9SliceFill);

        ctx.withMaterial(MATERIAL, () -> {
            MATERIAL.applyProperties(b -> {
                b.vec4("_CornerRadiusX", rxTL, rxTR, rxBR, rxBL);
                b.vec4("_CornerRadiusY", ryTL, ryTR, ryBR, ryBL);
                b.set1f("_BorderWidth", borderWidth);
                b.colorARGB("_BorderColor", borderColorArgb);
                b.colorARGB("_FillColor", fillColorArgb);
                b.vec2("_BoxSize", width, height);
                if (with9SliceFill) {
                    b.sampler("_MainTex", 0, fillSprite.getTexture());
                    float scale = fillSprite.getBorderScale();
                    float bL = fillSprite.getBorderLeft() * scale, bT = fillSprite.getBorderTop() * scale;
                    float bR = fillSprite.getBorderRight() * scale, bB = fillSprite.getBorderBottom() * scale;
                    b.vec4("_NineSliceBorder", bL, bT, bR, bB);
                    b.vec4("_NineSliceOuterUV", fillSprite.getU0(), fillSprite.getV0(),
                            fillSprite.getU3(), fillSprite.getV3());
                    b.vec4("_NineSliceInnerUV", fillSprite.getU1(), fillSprite.getV1(),
                            fillSprite.getU2(), fillSprite.getV2());

                    // Tile counts are computed HERE, in Java, and handed to the shader — rather than
                    // letting the shader derive them from source sizes. That's what makes this path
                    // and CgUiSprite's CPU quad loop agree by construction: they can't round
                    // differently because only one of them does the rounding.
                    float centerSpanX = Math.max(0f, width - bL - bR);
                    float centerSpanY = Math.max(0f, height - bT - bB);
                    float srcW = fillSprite.centerSourceWidth();
                    float srcH = fillSprite.centerSourceHeight();
                    float nx = fillSprite.getRepeatX().tileCount(centerSpanX, srcW);
                    float ny = fillSprite.getRepeatY().tileCount(centerSpanY, srcH);
                    b.vec4("_NineSliceTiles", nx, ny, srcW, srcH);
                    b.vec2("_NineSliceRepeat", fillSprite.getRepeatX().ordinal(), fillSprite.getRepeatY().ordinal());
                    b.vec2("_NineSliceFlags", fillSprite.isFillCenter() ? 1f : 0f, 0f);
                } else if (withTextureFill) {
                    b.sampler("_MainTex", 0, fillTexture);
                }
            });
            ctx.submitQuad(x, y, width, height, 0f, 0f, 1f, 1f, ctx.getColor());
        });
    }
}
