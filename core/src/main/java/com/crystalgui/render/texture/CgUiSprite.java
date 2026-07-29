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
     * {@code overlay-fit: none} on a 10x10 atlas sprite draws it at 10x10, matching how LDLib2
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

        if (!hasBorder) {
            if (width > 0 && height > 0) {
                submit(ctx, x, y, width, height, u0, v0, u3, v3, tintArgb);
                ctx.flush();
            }
            return;
        }

        float bL = borderLeftTop.x * borderScale;
        float bT = borderLeftTop.y * borderScale;
        float bR = borderRightBottom.x * borderScale;
        float bB = borderRightBottom.y * borderScale;

        float scaleX = Math.min(1.0f, width / Math.max(1.0f, bL + bR));
        float scaleY = Math.min(1.0f, height / Math.max(1.0f, bT + bB));

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

        // Tiling only ever applies along the centre column (horizontally) and centre row
        // (vertically) — corners are always a single stretched quad, matching CSS.
        Axis tilesX = Axis.of(repeatX, colW1, centerSourceWidth(), u1, u2);
        Axis tilesY = Axis.of(repeatY, rowH1, centerSourceHeight(), v1, v2);

        // Top / bottom edges: tiled horizontally, stretched vertically.
        if (rowH0 > 0) {
            if (colW0 > 0) submit(ctx, x0, y0, colW0, rowH0, u0, v0, u1, v1, tintArgb);
            emitRow(ctx, tilesX, x1, y0, rowH0, v0, v1, tintArgb);
            if (colW2 > 0) submit(ctx, x2, y0, colW2, rowH0, u2, v0, u3, v1, tintArgb);
        }
        // Left / right edges tiled vertically; centre tiled on both axes.
        if (rowH1 > 0) {
            for (int ty = 0; ty < tilesY.count; ty++) {
                float ty0 = y1 + tilesY.offset(ty);
                float th = tilesY.size(ty);
                if (th <= 0) continue;
                float tv0 = tilesY.uvStart();
                float tv1 = tilesY.uvEnd(ty);
                if (colW0 > 0) submit(ctx, x0, ty0, colW0, th, u0, tv0, u1, tv1, tintArgb);
                if (fillCenter) emitRow(ctx, tilesX, x1, ty0, th, tv0, tv1, tintArgb);
                if (colW2 > 0) submit(ctx, x2, ty0, colW2, th, u2, tv0, u3, tv1, tintArgb);
                maybeFlush(ctx);
            }
        }
        if (rowH2 > 0) {
            if (colW0 > 0) submit(ctx, x0, y2, colW0, rowH2, u0, v2, u1, v3, tintArgb);
            emitRow(ctx, tilesX, x1, y2, rowH2, v2, v3, tintArgb);
            if (colW2 > 0) submit(ctx, x2, y2, colW2, rowH2, u2, v2, u3, v3, tintArgb);
        }

        ctx.flush();
        pendingQuads = 0;
    }

    /**
     * Queues one quad, applying the missing-texture UV rule.
     *
     * <p>The fallback checkerboard has no meaningful sub-rect, so cropping into it samples an
     * arbitrary corner and a broken sprite reads as a solid colour rather than as "missing". Dropping
     * the crop makes every quad show the whole checkerboard, which is recognisable at any size.</p>
     *
     * <p>This rule used to live centrally in {@code CgUiPaintContext.submitQuad}, which could inspect
     * the bound texture before writing UVs. The fluent {@code ctx.quad()} builder has the caller set
     * UVs directly, so the check now lives at the two places that can actually be handed a fallback:
     * here, and {@code CgUiPaintContext.drawImage}. Everything else either binds the 1×1 white pixel
     * or an FBO colour attachment.</p>
     */
    private void submit(CgUiPaintContext ctx, float x, float y, float w, float h,
                        float u0, float v0, float u1, float v1, int argb) {
        CgQuadRenderer.Quad q = ctx.quad().at(x, y).size(w, h).color(argb);
        if (!missingTexture) q.uv(u0, v0, u1, v1);
        q.submit();
    }

    /** Emits one horizontal strip of tiles at a fixed y/height and fixed vertical UV range. */
    private void emitRow(CgUiPaintContext ctx, Axis tilesX, float xStart, float yPos, float h,
                         float vTop, float vBottom, int tintArgb) {
        for (int tx = 0; tx < tilesX.count; tx++) {
            float tx0 = xStart + tilesX.offset(tx);
            float tw = tilesX.size(tx);
            if (tw <= 0) continue;
            submit(ctx, tx0, yPos, tw, h, tilesX.uvStart(), vTop, tilesX.uvEnd(tx), vBottom, tintArgb);
            maybeFlush(ctx);
        }
    }

    /** Quads staged since the last flush. Tiling can emit far more than the 9 a stretch draw does,
     * and the shared quad index buffer tops out at 16384 — past which {@code flush()} reads off the
     * end of the index buffer silently rather than throwing. Flushing in chunks keeps us clear of
     * it. Safe mid-draw: flush touches no shader/texture/blend state, and only whole quads are ever
     * staged here. */
    private int pendingQuads = 0;

    private void maybeFlush(CgUiPaintContext ctx) {
        if (++pendingQuads >= FLUSH_EVERY_QUADS) {
            ctx.flush();
            pendingQuads = 0;
        }
    }

    private static final int FLUSH_EVERY_QUADS = 4096;

    /**
     * One axis's tile layout: how many tiles, where each starts, how long it is, and what UV range it
     * samples. Collapses all four repeat modes into a uniform interface so the emission loops don't
     * branch per mode.
     */
    private record Axis(int count, float tileSize, float gap, float uvLo, float uvHi, float lastFraction) {
        static Axis of(CgUiRepeat mode, float span, float src, float uvLo, float uvHi) {
            if (span <= 0f) return new Axis(0, 0f, 0f, uvLo, uvHi, 1f);
            float rawCount = mode.tileCount(span, src);
            if (mode == CgUiRepeat.STRETCH || rawCount <= 1f && mode != CgUiRepeat.REPEAT) {
                // Single tile filling the span — the pre-existing stretch behaviour.
                return new Axis(1, span, 0f, uvLo, uvHi, 1f);
            }
            return switch (mode) {
                // Whole tiles at natural size plus a clipped remainder; the partial tile samples a
                // proportionally shortened UV range so it crops rather than squashes.
                case REPEAT -> {
                    int whole = (int) Math.floor(rawCount);
                    float frac = rawCount - whole;
                    int total = frac > 0.001f ? whole + 1 : whole;
                    yield new Axis(Math.max(1, total), src, 0f, uvLo, uvHi, frac > 0.001f ? frac : 1f);
                }
                // Tile size stretched slightly so a whole number fits exactly — no clipped tile.
                case ROUND -> {
                    int n = Math.max(1, Math.round(rawCount));
                    yield new Axis(n, span / n, 0f, uvLo, uvHi, 1f);
                }
                // Whole tiles at natural size, leftover space split into equal gaps around them.
                case SPACE -> {
                    int n = Math.max(1, (int) rawCount);
                    yield new Axis(n, src, mode.gap(span, src, n), uvLo, uvHi, 1f);
                }
                default -> new Axis(1, span, 0f, uvLo, uvHi, 1f);
            };
        }

        float offset(int index) {
            return gap + index * (tileSize + gap);
        }

        float size(int index) {
            return index == count - 1 ? tileSize * lastFraction : tileSize;
        }

        float uvStart() {
            return uvLo;
        }

        /** The last tile under REPEAT is cropped, so it samples only part of the UV range. */
        float uvEnd(int index) {
            return index == count - 1 && lastFraction < 1f ? uvLo + (uvHi - uvLo) * lastFraction : uvHi;
        }
    }
}