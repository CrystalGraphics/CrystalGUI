package com.crystalgui.render.texture;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgui.render.texture.geometry.Position;
import com.crystalgui.render.texture.geometry.Size;
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
    /** Fixed multiplicative tint baked into this sprite (e.g. {@code background: image(path, #tint)}),
     * folded together with the paint context's ambient tint at draw time — distinct from
     * {@code background-color}, which layers an independent fill rather than multiplying the image. */
    private int tintArgb = 0xFFFFFFFF;
    /** How the edge/centre regions fill their span — see {@link CgUiRepeat}. Per-axis, like CSS
     * {@code border-image-repeat}. Corners never tile. Baked at parse time alongside {@link #tintArgb}. */
    private CgUiRepeat repeatX = CgUiRepeat.STRETCH;
    private CgUiRepeat repeatY = CgUiRepeat.STRETCH;
    /** Whether the centre region draws at all (Unity's "Fill Center"; CSS's {@code border-image-slice:
     * … fill}). False leaves a frame with a see-through middle. */
    private boolean fillCenter = true;
    /** Multiplier on the 9-slice border widths and tile sizes — Unity's "Pixels Per Unit Multiplier".
     * Lets a 4px source border render chunky at 8px without re-authoring the texture. */
    private float borderScale = 1f;
    /** Whether {@link #setSprite} has ever been called explicitly — until then, {@link #setTexture}/
     * {@link #setTextureSizeReference} keep the sprite rect defaulted to the full texture, so an
     * unsliced "whole image" sprite (no explicit sub-rect) actually has a non-degenerate UV rect
     * instead of the zero-size {@code Size.of(0, 0)} default rendering nothing. */
    private boolean spriteRectExplicit = false;
    /** Path handed to {@link #setTexture(String)}, resolved to a real {@link CgTexture2D} lazily on
     * first use. Deferring matters: creating a texture is a GL operation, and style values are
     * parsed whenever a stylesheet is read — potentially before a GL context exists (and always
     * without one in unit tests). Eager resolution made a {@code sprite(...)} value silently
     * compute to {@code null} in those cases, since {@code StyleValue.compute()} swallows the
     * failure. */
    private String texturePath;
    /** Set when {@link #setTextureSizeReference} was called by an author (e.g. {@code sprite}'s
     * {@code "refW refH"} arg). Guards against lazy texture resolution later overwriting it with the
     * real texture's dimensions — the whole point of that arg is to override them. */
    private boolean textureSizeExplicit = false;

    // Cached derived data, recomputed only when setup data changes (not per-draw).
    private boolean uvDirty = true;
    private boolean hasBorder = false;

    /** Whether {@link #getTexture()} resolved to the fallback checkerboard, recomputed each
     * {@link #draw}. Consumed by {@link #submit}. */
    private boolean missingTexture = false;
    private float u0, u1, u2, u3;
    private float v0, v1, v2, v3;
    private float borderSumX = 0f;
    private float borderSumY = 0f;

    /** Resolves the pending texture path if one is outstanding. Requires a live GL context, so it's
     * deliberately deferred to first use rather than run at parse time. */
    public CgTexture2D getTexture() {
        if (texture == null && texturePath != null) {
            String path = texturePath;
            texturePath = null; // clear first: setTexture(CgTexture2D) would otherwise re-clear it
            setTexture(CgTextureManager.get().getOrCreate(path, CgTextureSpec.RGBA8_NEAREST));
        }
        return texture;
    }

    public CgUiSprite copy() {
        CgUiSprite copied = new CgUiSprite();
        copied.texture = texture;
        copied.texturePath = this.texturePath;
        copied.textureSizeExplicit = this.textureSizeExplicit;
        copied.textureSize = this.textureSize.add(0,0);
        copied.spriteSize = this.spriteSize.add(0,0);
        copied.spritePosition = this.spritePosition.add(0,0);
        copied.borderLeftTop = this.borderLeftTop.add(0,0);
        copied.borderRightBottom = this.borderRightBottom.add(0,0);
        copied.tintArgb = this.tintArgb;
        copied.spriteRectExplicit = this.spriteRectExplicit;
        copied.repeatX = this.repeatX;
        copied.repeatY = this.repeatY;
        copied.fillCenter = this.fillCenter;
        copied.borderScale = this.borderScale;
        return copied;
    }

    public CgUiSprite setTint(int tintArgb) {
        this.tintArgb = tintArgb;
        return this;
    }

    /** Sets the per-axis tiling mode. Pass the same value twice for CSS's one-keyword form. */
    public CgUiSprite setRepeat(CgUiRepeat x, CgUiRepeat y) {
        this.repeatX = x == null ? CgUiRepeat.STRETCH : x;
        this.repeatY = y == null ? CgUiRepeat.STRETCH : y;
        return this;
    }

    public CgUiRepeat getRepeatX() {
        return repeatX;
    }

    public CgUiRepeat getRepeatY() {
        return repeatY;
    }

    public CgUiSprite setFillCenter(boolean fillCenter) {
        this.fillCenter = fillCenter;
        return this;
    }

    public boolean isFillCenter() {
        return fillCenter;
    }

    /** @param borderScale multiplier on border widths and tile sizes; clamped to a sane positive range. */
    public CgUiSprite setBorderScale(float borderScale) {
        this.borderScale = borderScale > 0f ? Math.min(borderScale, 64f) : 1f;
        return this;
    }

    public float getBorderScale() {
        return borderScale;
    }

    /** Source (texture-pixel) width of the centre column — the tile size for horizontal tiling.
     * Scaled by {@link #borderScale} so tiles grow with the borders. */
    public float centerSourceWidth() {
        return Math.max(0f, spriteSize.width - borderLeftTop.x - borderRightBottom.x) * borderScale;
    }

    /** Source (texture-pixel) height of the centre row — the tile size for vertical tiling. */
    public float centerSourceHeight() {
        return Math.max(0f, spriteSize.height - borderLeftTop.y - borderRightBottom.y) * borderScale;
    }

    public CgUiSprite setTexture(CgTexture2D texture) {
        this.texture = texture;
        this.texturePath = null;
        // Don't clobber an author-supplied size reference (sprite()'s "refW refH" arg exists
        // precisely to override the real texture's dimensions, e.g. for resource-pack rescaling).
        if (texture != null && !textureSizeExplicit && texture != CgTextureManager.get().getFallback()) {
            applyTextureSizeReference(texture.getWidth(), texture.getHeight());
        }
        return this;
    }

    /** Records the path only — resolution to a real GL texture is deferred to {@link #getTexture()}.
     * See {@link #texturePath} for why. */
    public CgUiSprite setTexture(String path) {
        this.texturePath = path;
        this.texture = null;
        return this;
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
        this.textureSizeExplicit = true;
        return applyTextureSizeReference(width, height);
    }

    /** The internal form, used when the size is derived from the texture itself — must not mark the
     * reference as author-explicit, or a later real texture could never update it. */
    private CgUiSprite applyTextureSizeReference(int width, int height) {
        this.textureSize = Size.of(width, height);
        if (!spriteRectExplicit) {
            this.spritePosition = Position.of(0, 0);
            this.spriteSize = Size.of(width, height);
        }
        uvDirty = true;
        return this;
    }

    public CgUiSprite setSprite(int x, int y, int width, int height) {
        this.spriteRectExplicit = true;
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

    /** Sum of left+right 9-slice border widths, in the same units as {@link #setBorder}. */
    public float borderSumX() {
        updateUvCacheIfNeeded();
        return borderSumX;
    }

    /** Sum of top+bottom 9-slice border widths, in the same units as {@link #setBorder}. */
    public float borderSumY() {
        updateUvCacheIfNeeded();
        return borderSumY;
    }

    /** Whether this sprite has a real (non-zero) 9-slice border. */
    public boolean hasBorder() {
        updateUvCacheIfNeeded();
        return hasBorder;
    }

    /** Left 9-slice border width, in the same units as {@link #setBorder}. */
    public float getBorderLeft() {
        return borderLeftTop.x;
    }

    /** Top 9-slice border width, in the same units as {@link #setBorder}. */
    public float getBorderTop() {
        return borderLeftTop.y;
    }

    /** Right 9-slice border width, in the same units as {@link #setBorder}. */
    public float getBorderRight() {
        return borderRightBottom.x;
    }

    /** Bottom 9-slice border width, in the same units as {@link #setBorder}. */
    public float getBorderBottom() {
        return borderRightBottom.y;
    }

    /** Cached outer/inner atlas UV breakpoints — see {@link #updateUvCacheIfNeeded()} for how these
     * are derived from {@code spritePosition}/{@code spriteSize}/border insets. Recomputed lazily,
     * same as {@link #hasBorder()}/{@link #borderSumX()}. */
    public float getU0() { updateUvCacheIfNeeded(); return u0; }
    public float getU1() { updateUvCacheIfNeeded(); return u1; }
    public float getU2() { updateUvCacheIfNeeded(); return u2; }
    public float getU3() { updateUvCacheIfNeeded(); return u3; }
    public float getV0() { updateUvCacheIfNeeded(); return v0; }
    public float getV1() { updateUvCacheIfNeeded(); return v1; }
    public float getV2() { updateUvCacheIfNeeded(); return v2; }
    public float getV3() { updateUvCacheIfNeeded(); return v3; }

    /** The source sub-rect's pixel size, interpreted 1:1 as logical UI pixels — so
     * {@code overlay-size: none} on a 10x10 atlas sprite draws it at 10x10, matching how LDLib2
     * sizes its own icon elements. Falls back to -1 while the sprite rect is still degenerate
     * (no texture assigned yet), so fitting degrades to {@code fill} rather than to nothing. */
    @Override
    public float intrinsicWidth() {
        return spriteSize.width > 0 ? spriteSize.width : -1f;
    }

    @Override
    public float intrinsicHeight() {
        return spriteSize.height > 0 ? spriteSize.height : -1f;
    }

    /** The sliced draw, built once and kept -- a fresh one per frame would be garbage in the paint
     * loop. Holds no state of its own beyond the sprite it is pointed at. @see #draw */
    private CgUiRoundedRect slicer;

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        // Via the accessor, not the field: this is the point where a lazily-deferred texture path
        // gets resolved, and it's the first place a GL context is guaranteed to exist.
        CgTexture2D resolved = getTexture();
        if (resolved == null || textureSize.width <= 0 || textureSize.height <= 0) return;

        // A failed load resolves to the shared fallback checkerboard. Recorded once per draw and
        // consumed by submit() below — see there for why the UV crop has to be dropped.
        missingTexture = resolved == CgTextureManager.get().getFallback();

        updateUvCacheIfNeeded();

        final int tintArgb = ArgbMath.multiply(this.tintArgb, ctx.getColor());
        ctx.bindTexture(resolved);

        // A MISSING TEXTURE IS NOT WORTH SLICING, and a whole sprite was never sliced. Without a UV
        // crop (see below) the nine pieces were nine copies of the whole checkerboard rather than a
        // nine-slice, so one stretched copy says "missing" just as loudly -- and it is the one case the
        // sliced path cannot serve, since that samples the sprite's own atlas UVs, which land in an
        // arbitrary corner of the 8x8 fallback and read as a flat colour rather than as broken.
        if (!hasBorder || missingTexture) {
            if (width > 0 && height > 0) {
                CgQuadRenderer.Quad q = ctx.quad().at(x, y).size(width, height).color(tintArgb);
                // THE FALLBACK GETS NO UV CROP: it has no meaningful sub-rect, so cropping into it
                // samples an arbitrary corner and a broken sprite reads as a solid colour instead of as
                // missing. CgUiPaintContext.drawImage is the only other site that can be handed one.
                if (!missingTexture) q.uv(u0, v0, u3, v3);
                q.submit();
                ctx.flush();
            }
            return;
        }

        // THE NINE PIECES ARE ONE DRAW. gui_rounded_rect's WITH_9SLICE_FILL is the same nine-region
        // remap done per PIXEL, and it is better on both counts that used to be traded off.
        //
        // CORRECTNESS: nine quads shared eight interior seams, and a seam is either hard -- the
        // rasteriser's own cut, a visible staircase once the sprite is off its axis -- or softened from
        // both sides, which composites to three quarters and reads as a hairline down the middle of the
        // art. Neither is fixable per quad, because each would have to know what its neighbour drew.
        // The two paths also disagreed under ROUND tiling, by a pixel on every tile divider: this one
        // places them continuously where the quad loop rounded each tile's rect. cgui-nineslice draws
        // the same sprite both ways and says they must match; now there is one answer.
        //
        // COST: this was assumed to be the expensive path, because it is a material switch where nine
        // quads join the frame's existing batch. Measured on cgui-sprite-stress -- 500 sprite-backed
        // cells, nothing else on screen -- it is 2.5-3x CHEAPER, and the batching is real but not where
        // the money is: the nine-quad path held material binds to 6 a frame against 1506, and still
        // lost, because nine instances per sprite against one is what dominates. quadRenderer.flush
        // drops about tenfold. The same shape as SvgRasterCache: the instance upload is the cost.
        // @see CgUiRoundedRect#setFillSprite
        if (slicer == null) slicer = new CgUiRoundedRect();
        slicer.setFillSprite(this);
        int outerTint = ctx.getColor();
        ctx.setColor(tintArgb);   // the material reads the quad colour, and this is the same product
        try {
            slicer.draw(ctx, mouseX, mouseY, x, y, width, height);
        } finally {
            ctx.setColor(outerTint);
        }
    }
}
