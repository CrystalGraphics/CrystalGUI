package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.render.CgUiPaintContext;

import java.util.Objects;

/**
 * A rectangle with a {@link Fill} — the one drawable behind every {@code background}, and the
 * rendering mechanism behind the universal {@code border-radius}/{@code border-width}/
 * {@code border-color} layer.
 *
 * <pre>
 * CgUiRect.ofColor(0xFF3574F0)                       // a flat fill
 * new CgUiRect().withFillSprite(sprite)               // a 9-slice
 * new CgUiRect().withFillColor(argb)
 *               .withCornerRadius(6f, 6f)
 *               .withBorder(1f, 0xFF5B8DFF)          // rounded, stroked
 * </pre>
 *
 * <p>Corner radii are independent per corner and per axis (rx/ry, TL/TR/BR/BL, CSS
 * {@code border-radius} order) — elliptical, not just circular.</p>
 *
 * <h3>Two draw paths, and which one runs is not a style choice</h3>
 *
 * <p><b>A plain rectangle draws through the frame's own batch</b> — {@code fillRect} for a colour,
 * one quad for a stretched texture or a whole sprite. Only a rect that the batch cannot express
 * takes the SDF material ({@code gui_rect.shader}): one with a radius, a border, or a 9-slice fill,
 * whose nine regions are remapped per pixel. That split is why merging the three old drawables cost
 * nothing: the material carries sixteen per-draw uniforms, so routing every flat fill through it
 * would make each its own draw, and instance traffic is what dominates a UI frame.</p>
 *
 * <h3>It is a value, and equality is load-bearing</h3>
 *
 * <p>Two rects with the same fill, radii and border are equal, which {@code ElementStyle} relies on:
 * it discards a pushed candidate equal to the one already there, and that is what stops a repeated
 * {@code background} write re-entering the cascade and retargeting a transition in flight. It is
 * also what lets a background written out as CSS and read back compare equal to what it came from.
 * A colour fill compares by value; a texture or sprite fill by identity, which is what a shared
 * atlas sprite wants.</p>
 *
 * <p><b>IMMUTABLE, which is what makes that safe.</b> Every configuration method returns a new rect,
 * so a background the cascade is holding cannot be reshaped by whoever paints it — {@code BoxPainter}
 * builds its own from the element's radii and the background's fill. A shared value that could be
 * mutated is a value in name only, and this one is shared by every element a rule matches.</p>
 */
public final class CgUiRect implements CgUiDrawable {

    /**
     * What the rectangle is filled with. The three cases are the three drawables this class replaced:
     * a flat colour, one stretched texture, and a 9-slice sprite.
     */
    public sealed interface Fill {

        /** A flat ARGB fill, multiplied by the ambient tint. */
        record Color(int argb) implements Fill {
        }

        /** One texture stretched across the whole rect. */
        record Texture(CgTexture2D texture) implements Fill {
        }

        /** A sprite — 9-sliced when it has a border, stretched whole when it does not. */
        record Sprite(CgUiSprite sprite) implements Fill {
        }
    }

    /**
     * The shared SDF material. No {@code attachTo} needed — {@code gui_rect.shader} declares
     * {@code #pragma cg_use quad}, so the instance buffer is wired during parsing.
     *
     * <p>That matters here specifically: {@link #draw} calls {@code toggleKeyword} <em>before</em>
     * the material ever reaches {@code CgQuadRenderer.useMaterial()}, and {@code enableKeyword}
     * compiles on the spot when the shader has not been parsed yet. This class is why the attach has
     * to happen at parse time rather than first use.</p>
     */
    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_rect.shader");

    private final float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;
    private final float borderWidth;
    /** The LEFT and RIGHT edges always take this — there is no border-left/right-color to split them
     * with — and it is what {@link #withBorder(float, int, int, int)}'s top/bottom pair falls back to
     * being equal to when a caller doesn't want a split at all. */
    private final int borderColorArgb;
    /** Equal to {@link #borderColorArgb} unless {@link #withBorder(float, int, int, int)} was used —
     * the pair that lets the shader stroke the TOP and BOTTOM edges differently (Unity's inset
     * text-field bevel). See {@code gui_rect.shader}'s {@code SPLIT_BORDER} feature. */
    private final int borderTopColorArgb;
    private final int borderBottomColorArgb;
    private final Fill fill;

    public CgUiRect() {
        this(new Fill.Color(0xFFFFFFFF), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0xFF000000, 0xFF000000, 0xFF000000);
    }

    private CgUiRect(Fill fill, float rxTL, float ryTL, float rxTR, float ryTR,
                     float rxBR, float ryBR, float rxBL, float ryBL,
                     float borderWidth, int borderColorArgb, int borderTopColorArgb, int borderBottomColorArgb) {
        this.fill = Objects.requireNonNull(fill, "fill");
        this.rxTL = rxTL; this.ryTL = ryTL;
        this.rxTR = rxTR; this.ryTR = ryTR;
        this.rxBR = rxBR; this.ryBR = ryBR;
        this.rxBL = rxBL; this.ryBL = ryBL;
        this.borderWidth = borderWidth;
        this.borderColorArgb = borderColorArgb;
        this.borderTopColorArgb = borderTopColorArgb;
        this.borderBottomColorArgb = borderBottomColorArgb;
    }

    /** A flat fill, which is what a {@code #rrggbb} or {@code rgba()} background parses to. */
    public static CgUiRect ofColor(int colorArgb) {
        return new CgUiRect().withFill(new Fill.Color(colorArgb));
    }

    public CgUiRect withCornerRadius(float rx, float ry) {
        return withCornerRadius(rx, ry, rx, ry, rx, ry, rx, ry);
    }

    /** Independent elliptical radius per corner, CSS {@code border-radius} order (TL,TR,BR,BL),
     * each an (rx,ry) pair. */
    public CgUiRect withCornerRadius(float rxTL, float ryTL, float rxTR, float ryTR,
                                     float rxBR, float ryBR, float rxBL, float ryBL) {
        return new CgUiRect(fill, rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL,
                borderWidth, borderColorArgb, borderTopColorArgb, borderBottomColorArgb);
    }

    public CgUiRect withBorder(float width, int colorArgb) {
        return withBorder(width, colorArgb, colorArgb, colorArgb);
    }

    /**
     * As {@link #withBorder(float, int)}, but the TOP and BOTTOM edges may stroke a different colour
     * from {@code uniformColorArgb} — Unity's inset text-field bevel: dark top, light bottom, same
     * colour as the fill on the left and right (there is no {@code border-left/right-color} to split
     * those with, and the shader has no notion of "left" or "right" edge to begin with).
     *
     * <p>{@code uniformColorArgb} is also the anti-fringe fallback fill {@code BoxPainter} uses for a
     * border with no background, and the shader's {@code _BorderColor} — a caller that passes it for
     * all three arguments is byte-for-byte the old uniform path, since the shader only engages
     * {@code SPLIT_BORDER} when top or bottom actually differs from it.</p>
     */
    public CgUiRect withBorder(float width, int uniformColorArgb, int topColorArgb, int bottomColorArgb) {
        return new CgUiRect(fill, rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL,
                width, uniformColorArgb, topColorArgb, bottomColorArgb);
    }

    public CgUiRect withFill(Fill fill) {
        return new CgUiRect(fill, rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL,
                borderWidth, borderColorArgb, borderTopColorArgb, borderBottomColorArgb);
    }

    public Fill getFill() {
        return fill;
    }

    public CgUiRect withFillColor(int colorArgb) {
        return withFill(new Fill.Color(colorArgb));
    }

    public CgUiRect withFillTexture(CgTexture2D texture) {
        return withFill(new Fill.Texture(texture));
    }

    /** Fills with a sprite: 9-sliced when it has a border, clipped and stroked by the same
     * corner-radius/border SDF as any other fill — the sprite's own alpha (including transparency
     * baked into its art, not just what {@code border-radius} carves out) is what renders. */
    public CgUiRect withFillSprite(CgUiSprite sprite) {
        return withFill(new Fill.Sprite(sprite));
    }

    /** The fill's natural size, which only a sprite has — {@code CgUiLayerBox} reads it for
     * {@code overlay-size}. -1 when there is none, so fitting degrades to {@code fill}. */
    @Override
    public float intrinsicWidth() {
        return fill instanceof Fill.Sprite(CgUiSprite sprite) ? sprite.sourceWidth() : -1f;
    }

    @Override
    public float intrinsicHeight() {
        return fill instanceof Fill.Sprite(CgUiSprite sprite) ? sprite.sourceHeight() : -1f;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        switch (fill) {
            case Fill.Color(int argb) -> {
                if (isPlain()) ctx.fillRect(x, y, width, height, ArgbMath.multiply(argb, ctx.getColor()));
                else drawShaped(ctx, x, y, width, height, ctx.getColor(), argb, null, null);
            }
            case Fill.Texture(CgTexture2D texture) -> {
                if (texture == null) return;
                if (isPlain()) {
                    ctx.bindTexture(texture);
                    ctx.quad().at(x, y).size(width, height).color(ctx.getColor()).submit();
                    ctx.flush();
                } else {
                    drawShaped(ctx, x, y, width, height, ctx.getColor(), 0, texture, null);
                }
            }
            case Fill.Sprite(CgUiSprite sprite) -> drawSprite(ctx, sprite, x, y, width, height);
        }
    }

    /** Whether the frame's own batch can express this rect: no radius, no border. */
    public boolean isPlain() {
        return borderWidth <= 0f
                && rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f
                && rxBR == 0f && ryBR == 0f && rxBL == 0f && ryBL == 0f;
    }

    private void drawSprite(CgUiPaintContext ctx, CgUiSprite sprite,
                            float x, float y, float width, float height) {
        CgTexture2D resolved = sprite.resolveForDraw();
        if (resolved == null) return;
        int tint = ArgbMath.multiply(sprite.getTint(), ctx.getColor());
        boolean missing = sprite.isFallback(resolved);

        // A MISSING TEXTURE IS NOT WORTH SLICING, and a whole sprite was never sliced. The fallback
        // checkerboard has no meaningful sub-rect, so cropping into it samples an arbitrary corner and a
        // broken sprite reads as a solid colour instead of as missing -- which is also the one case the
        // sliced path cannot serve, since that samples the sprite's own atlas UVs. One stretched copy of
        // the whole checkerboard says "missing" at any size. CgUiPaintContext.drawImage is the only
        // other site that can be handed a fallback.
        if (missing || (!sprite.hasBorder() && isPlain())) {
            ctx.bindTexture(resolved);
            CgQuadRenderer.Quad q = ctx.quad().at(x, y).size(width, height).color(tint);
            if (!missing) q.uv(sprite.getU0(), sprite.getV0(), sprite.getU3(), sprite.getV3());
            q.submit();
            ctx.flush();
            return;
        }
        // EVERY SHAPED SPRITE GOES DOWN THE SLICED PATH, bordered or not. With zero borders the nine
        // regions degenerate to one — inner UVs equal outer, so the centre stretches the sprite's own
        // sub-rect — which is the point: the old wrap handed the raw texture to a plain texture fill and
        // dropped the sub-rect, so an ATLAS sprite with a border-radius sampled the whole sheet. It also
        // means a borderless sprite finally honours its repeat modes under a radius.
        drawShaped(ctx, x, y, width, height, tint, 0, null, sprite);
    }

    /**
     * The SDF path — a radius, a border, or a 9-slice, none of which the batch can express.
     *
     * <p>Exactly one of {@code texture}/{@code sprite} is non-null, or neither for a flat fill.</p>
     */
    private void drawShaped(CgUiPaintContext ctx, float x, float y, float width, float height,
                            int quadTint, int fillColorArgb, CgTexture2D texture, CgUiSprite sprite) {
        MATERIAL.toggleKeyword("WITH_BORDER", borderWidth > 0f);
        // Only ever true when the 4-arg setBorder was called with a top or bottom that actually
        // differs from the uniform colour — the 2-arg overload delegates here with all three equal,
        // which keeps every existing caller (the outline ring, the mask border, every uniform-border
        // widget) on the exact same shader path as before this feature existed.
        MATERIAL.toggleKeyword("SPLIT_BORDER",
                borderTopColorArgb != borderColorArgb || borderBottomColorArgb != borderColorArgb);
        boolean with9SliceFill = sprite != null;
        boolean withTextureFill = !with9SliceFill && texture != null;
        MATERIAL.toggleKeyword("WITH_TEXTURE_FILL", withTextureFill);
        MATERIAL.toggleKeyword("WITH_9SLICE_FILL", with9SliceFill);

        ctx.withMaterial(MATERIAL, () -> {
            MATERIAL.applyProperties(b -> {
                b.vec4("_CornerRadiusX", rxTL, rxTR, rxBR, rxBL);
                b.vec4("_CornerRadiusY", ryTL, ryTR, ryBR, ryBL);
                b.set1f("_BorderWidth", borderWidth);
                b.colorARGB("_BorderColor", borderColorArgb);
                b.colorARGB("_BorderColorTop", borderTopColorArgb);
                b.colorARGB("_BorderColorBottom", borderBottomColorArgb);
                b.colorARGB("_FillColor", fillColorArgb);
                b.vec2("_BoxSize", width, height);
                if (with9SliceFill) {
                    b.sampler("_MainTex", 0, sprite.getTexture());
                    float scale = sprite.getBorderScale();
                    float bL = sprite.getBorderLeft() * scale, bT = sprite.getBorderTop() * scale;
                    float bR = sprite.getBorderRight() * scale, bB = sprite.getBorderBottom() * scale;
                    b.vec4("_NineSliceBorder", bL, bT, bR, bB);
                    b.vec4("_NineSliceOuterUV", sprite.getU0(), sprite.getV0(),
                            sprite.getU3(), sprite.getV3());
                    b.vec4("_NineSliceInnerUV", sprite.getU1(), sprite.getV1(),
                            sprite.getU2(), sprite.getV2());

                    // Tile counts are computed HERE, in Java, and handed to the shader — rather than
                    // letting the shader derive them from source sizes, so the rounding happens once.
                    float centerSpanX = Math.max(0f, width - bL - bR);
                    float centerSpanY = Math.max(0f, height - bT - bB);
                    float srcW = sprite.centerSourceWidth();
                    float srcH = sprite.centerSourceHeight();
                    float nx = sprite.getRepeatX().tileCount(centerSpanX, srcW);
                    float ny = sprite.getRepeatY().tileCount(centerSpanY, srcH);
                    b.vec4("_NineSliceTiles", nx, ny, srcW, srcH);
                    b.vec2("_NineSliceRepeat", sprite.getRepeatX().ordinal(), sprite.getRepeatY().ordinal());
                    b.vec2("_NineSliceFlags", sprite.isFillCenter() ? 1f : 0f, 0f);
                } else if (withTextureFill) {
                    b.sampler("_MainTex", 0, texture);
                }
            });
            ctx.quad().at(x, y).size(width, height).color(quadTint).submit();
        });
    }

    /**
     * <b>By value for a flat fill, by identity for anything else</b> — which is exactly what the two
     * drawables this replaced did, and both halves are load-bearing.
     *
     * <p>A flat colour needs value equality because {@code ElementStyle} discards a pushed candidate
     * equal to the one already there, and that is what stops a repeated {@code background} write
     * re-entering the cascade and retargeting a transition in flight; a background written out as CSS
     * and read back also has to compare equal to what it came from.</p>
     *
     * <p>A textured or sprite fill must NOT, because {@code TextureValue.sourceOf} is a weak map keyed
     * by equality: two equal drawables share one entry keyed on whichever was seen first, and when that
     * one is collected the live one is left with no CSS. Only a drawable that can describe itself may
     * afford value equality, and a flat colour is the one that can.</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgUiRect r) || !(fill instanceof Fill.Color) || !(r.fill instanceof Fill.Color)) {
            return false;
        }
        return fill.equals(r.fill)
                && borderWidth == r.borderWidth
                && borderColorArgb == r.borderColorArgb
                && borderTopColorArgb == r.borderTopColorArgb
                && borderBottomColorArgb == r.borderBottomColorArgb
                && rxTL == r.rxTL && ryTL == r.ryTL && rxTR == r.rxTR && ryTR == r.ryTR
                && rxBR == r.rxBR && ryBR == r.ryBR && rxBL == r.rxBL && ryBL == r.ryBL;
    }

    @Override
    public int hashCode() {
        if (!(fill instanceof Fill.Color)) return System.identityHashCode(this);
        return Objects.hash(fill, borderWidth, borderColorArgb, borderTopColorArgb, borderBottomColorArgb,
                rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL);
    }

    @Override
    public String toString() {
        return "CgUiRect(" + fill + (isPlain() ? "" : ", shaped") + ")";
    }
}
