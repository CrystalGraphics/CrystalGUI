package com.crystalgui.rewrite.texture;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.rewrite.render.CgUiPaintContext;

/**
 * Full-rect image draw from a {@link CgTexture2D}, with an optional UV sub-rect for
 * atlas/sprite-sheet use.
 *
 * <p>The texture is resolved once via {@link CgTexture2D#create(String)} (cached by
 * CrystalGraphics' own texture manager — repeated construction of this class with the
 * same path is cheap) and reused on every {@link #draw} call.</p>
 */
public final class CgUiSprite implements ICgUiDrawable {

    private final CgTexture2D texture;
    private final float u0, v0, u1, v1;

    /** Full-rect UVs (0,0)-(1,1). */
    public CgUiSprite(String texturePath) {
        this(texturePath, 0f, 0f, 1f, 1f);
    }

    /** Sub-rect UVs, e.g. for one icon inside a shared atlas. */
    public CgUiSprite(String texturePath, float u0, float v0, float u1, float v1) {
        this.texture = CgTexture2D.create(texturePath, CgTextureSpec.RGBA8_NEAREST);
        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float x, float y, float width, float height, int tintArgb) {
        ctx.drawImage(texture, x, y, width, height, u0, v0, u1, v1, tintArgb);
    }
}
