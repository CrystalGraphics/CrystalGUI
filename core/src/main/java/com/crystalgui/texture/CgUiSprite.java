package com.crystalgui.texture;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgui.core.geometry.Position;
import com.crystalgui.core.geometry.Size;
import com.crystalgui.render.CgUiPaintContext;

/**
 * Full-rect image draw from a {@link CgTexture2D}, with an optional UV sub-rect for
 * atlas/sprite-sheet use.
 *
 * <p>The texture is resolved once via {@link CgTexture2D#create(String)} (cached by
 * CrystalGraphics' own texture manager — repeated construction of this class with the
 * same path is cheap) and reused on every {@link #draw} call.</p>
 */
public final class CgUiSprite implements CgUiDrawable {

    // Set-up data.
    private CgTexture2D texture;
    private Size textureSize = Size.of(0, 0);
    private Size spriteSize = Size.of(0, 0);
    private Position spritePosition = Position.of(0, 0);
    private Position borderLeftTop = Position.of(0, 0);
    private Position borderRightBottom = Position.of(0, 0);

    // Cached derived data, recomputed only when setup data changes (not per-draw).
    private boolean uvDirty = true;
    private boolean hasBorder = false;
    private float u0, u1, u2, u3;
    private float v0, v1, v2, v3;
    private float borderSumX = 0f;
    private float borderSumY = 0f;

    public CgUiSprite copy() {
        CgUiSprite copied = new CgUiSprite();
        copied.texture = texture;
        copied.textureSize = this.textureSize.add(0,0);
        copied.spriteSize = this.spriteSize.add(0,0);
        copied.spritePosition = this.spritePosition.add(0,0);
        copied.borderLeftTop = this.borderLeftTop.add(0,0);
        copied.borderRightBottom = this.borderRightBottom.add(0,0);
        return copied;
    }

    public CgUiSprite setTexture(CgTexture2D texture) {
        this.texture = texture;
        if (texture != null && texture != CgTextureManager.get().getFallback()) {
            this.setTextureSizeReference(texture.getWidth(), texture.getHeight());
        }
        return this;
    }
    public CgUiSprite setTexture(String path) {
        return this.setTexture(CgTextureManager.get().getOrCreate(path, CgTextureSpec.RGBA8_NEAREST));
    }

    /**
     * Sets the specified texture size, used for reference when calculating UVs.
     * <br>
     * This is important for when texture-packs change textures, we would be reading the modified texture size (256x256 -> 1024x1024)
     * @param width width of the texture-reference
     * @param height height of the texture-reference
     * @return this object used for chaining
     */
    public CgUiSprite setTextureSizeReference(int width, int height) {
        this.textureSize = Size.of(width, height);
        uvDirty = true;
        return this;
    }

    public CgUiSprite setSprite(int x, int y, int width, int height) {
        this.spritePosition = Position.of(x, y);
        this.spriteSize = Size.of(width, height);
        uvDirty = true;
        return this;
    }

    public CgUiSprite setBorder(int left, int top, int right, int bottom) {
        this.borderLeftTop = Position.of(left, top);
        this.borderRightBottom = Position.of(right, bottom);
        uvDirty = true;
        return this;
    }

    public CgUiSprite setBorder(int border) {
        return setBorder(border, border, border, border);
    }

    /**
     * Recomputes cached UV coordinates and border metadata. Only runs when setup data
     * (texture / sprite rect / border) has actually changed, not on every draw call.
     */
    private void updateUvCacheIfNeeded() {
        if (!uvDirty) return;

        float texW = textureSize.width;
        float texH = textureSize.height;
        if (texW <= 0 || texH <= 0) {
            uvDirty = false;
            return;
        }

        float sx = spritePosition.x;
        float sy = spritePosition.y;
        float sw = spriteSize.width;
        float sh = spriteSize.height;

        float bL = borderLeftTop.x;
        float bT = borderLeftTop.y;
        float bR = borderRightBottom.x;
        float bB = borderRightBottom.y;

        float invTexW = 1.0f / texW;
        float invTexH = 1.0f / texH;

        u0 = sx * invTexW;
        u1 = (sx + bL) * invTexW;
        u2 = (sx + sw - bR) * invTexW;
        u3 = (sx + sw) * invTexW;

        v0 = sy * invTexH;
        v1 = (sy + bT) * invTexH;
        v2 = (sy + sh - bB) * invTexH;
        v3 = (sy + sh) * invTexH;

        borderSumX = bL + bR;
        borderSumY = bT + bB;
        hasBorder = borderSumX > 0f || borderSumY > 0f;

        uvDirty = false;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        if (texture == null || textureSize.width <= 0 || textureSize.height <= 0) return;

        updateUvCacheIfNeeded();

        final int tintArgb = ctx.getColor();
        ctx.bindTexture(texture);

        if (!hasBorder) {
            if (width > 0 && height > 0) {
                ctx.submitQuad(x, y, width, height, u0, v0, u3, v3, tintArgb);
                ctx.flush();
            }
            return;
        }

        float bL = borderLeftTop.x;
        float bT = borderLeftTop.y;
        float bR = borderRightBottom.x;
        float bB = borderRightBottom.y;

        float scaleX = Math.min(1.0f, width / Math.max(1.0f, borderSumX));
        float scaleY = Math.min(1.0f, height / Math.max(1.0f, borderSumY));

        float drawL = bL * scaleX;
        float drawR = bR * scaleX;
        float drawT = bT * scaleY;
        float drawB = bB * scaleY;

        float x0 = x;
        float x1 = x + drawL;
        float x2 = x + width - drawR;
        float x3 = x + width;

        float y0 = y;
        float y1 = y + drawT;
        float y2 = y + height - drawB;
        float y3 = y + height;

        float colW0 = x1 - x0, colW1 = x2 - x1, colW2 = x3 - x2;
        float rowH0 = y1 - y0, rowH1 = y2 - y1, rowH2 = y3 - y2;

        if (rowH0 > 0) {
            if (colW0 > 0) ctx.submitQuad(x0, y0, colW0, rowH0, u0, v0, u1, v1, tintArgb);
            if (colW1 > 0) ctx.submitQuad(x1, y0, colW1, rowH0, u1, v0, u2, v1, tintArgb);
            if (colW2 > 0) ctx.submitQuad(x2, y0, colW2, rowH0, u2, v0, u3, v1, tintArgb);
        }
        if (rowH1 > 0) {
            if (colW0 > 0) ctx.submitQuad(x0, y1, colW0, rowH1, u0, v1, u1, v2, tintArgb);
            if (colW1 > 0) ctx.submitQuad(x1, y1, colW1, rowH1, u1, v1, u2, v2, tintArgb);
            if (colW2 > 0) ctx.submitQuad(x2, y1, colW2, rowH1, u2, v1, u3, v2, tintArgb);
        }
        if (rowH2 > 0) {
            if (colW0 > 0) ctx.submitQuad(x0, y2, colW0, rowH2, u0, v2, u1, v3, tintArgb);
            if (colW1 > 0) ctx.submitQuad(x1, y2, colW1, rowH2, u1, v2, u2, v3, tintArgb);
            if (colW2 > 0) ctx.submitQuad(x2, y2, colW2, rowH2, u2, v2, u3, v3, tintArgb);
        }

        ctx.flush();
    }
}