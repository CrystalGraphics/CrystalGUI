package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.render.CgUiPaintContext;

import java.util.Objects;
import java.util.function.Consumer;

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
 * so a background the cascade is holding cannot be reshaped by whoever paints it. A shared value that
 * could be mutated is a value in name only, and this one is shared by every element a rule matches.</p>
 *
 * <h3>Building one to draw it is the wrong way round</h3>
 *
 * <p>The copies are affordable where a value is parsed once and cascaded. They are not affordable per
 * element per frame, which is what a painter does — so a painter does not build one at all:
 * {@link Draw}, reached as {@code ctx.rect()}, is a single reusable scratch that draws the same
 * picture and allocates nothing. {@code BoxPainter} composes the element's radii, its border and the
 * background's own {@code Fill} straight into it, and {@link #draw} does the same with this value's
 * fields.</p>
 *
 * <pre>{@code
 * ctx.rect().at(x, y).size(w, h).radius(6f, 6f).border(1f, edge).fillColor(bg).submit();
 * }</pre>
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

    /**
     * <b>The whole rect at once</b> — every field, one object.
     *
     * <p>For a caller that already holds all of it, which is what a PAINTER does: the fluent
     * {@code with} methods each answer a copy, so building the same rect through them allocates one
     * object per call and throws away all but the last. That is affordable where a value is built once
     * and cascaded; it is not affordable per element per frame. {@code BoxPainter} builds one of these
     * for every element carrying a radius or a border, which on a canvas holding ten thousand nodes is
     * forty thousand objects a frame through the chain and ten thousand through this.</p>
     *
     * <pre>{@code
     * CgUiRect.shaped(fill, rx, ry, rx, ry, rx, ry, rx, ry, 1f, edge, edge, edge)
     *         .draw(ctx, 0f, 0f, width, height);
     * }</pre>
     *
     * <p>Radii are per corner and per axis in CSS {@code border-radius} order (TL, TR, BR, BL), each an
     * (rx, ry) pair. Pass {@code borderColorArgb} for all three colours for an unsliced border — the
     * shader only engages {@code SPLIT_BORDER} when top or bottom actually differs.</p>
     */
    public static CgUiRect shaped(Fill fill, float rxTL, float ryTL, float rxTR, float ryTR,
                                  float rxBR, float ryBR, float rxBL, float ryBL, float borderWidth,
                                  int borderColorArgb, int borderTopColorArgb, int borderBottomColorArgb) {
        return new CgUiRect(fill, rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL,
                borderWidth, borderColorArgb, borderTopColorArgb, borderBottomColorArgb);
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

    /**
     * Draws this rect through the paint context's shared {@link Draw} scratch, which is where every
     * rect in the engine is actually drawn.
     */
    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        ctx.rect()
                .at(x, y).size(width, height)
                .radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL)
                .border(borderWidth, borderColorArgb, borderTopColorArgb, borderBottomColorArgb)
                .fill(fill)
                .submit();
    }

    /** Whether the frame's own batch can express this rect: no radius, no border. */
    public boolean isPlain() {
        return borderWidth <= 0f
                && rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f
                && rxBR == 0f && ryBR == 0f && rxBL == 0f && ryBL == 0f;
    }

    /**
     * <b>A rect being drawn</b> — the paint context's single scratch, reached as {@code ctx.rect()}
     * and the counterpart to {@code ctx.quad()} in every convention that matters.
     *
     * <pre>{@code
     * ctx.rect().at(x, y).size(w, h).fillColor(argb).submit();
     * ctx.rect().at(x, y).size(w, h).radius(6f, 6f).border(1f, edge).fillColor(bg).submit();
     * ctx.rect().at(x, y).size(w, h).radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL)
     *           .fill(background.getFill()).submit();
     * }</pre>
     *
     * <p><b>Build and {@code submit()} in one expression.</b> The next {@code ctx.rect()} resets and
     * reuses this instance, so holding one past its {@code submit()} is not safe. Anything left unset
     * is what a bare {@link CgUiRect} carries: no radius, no border, an opaque white fill. The tint
     * comes from {@code ctx.getColor()} at submit time, exactly as it does for a drawable.</p>
     *
     * <p><b>This is the allocation-free path, and it is why it exists.</b> {@link CgUiRect} is a value
     * the cascade holds and shares, so every {@code with} method answers a copy — right for a value,
     * ruinous for a painter. {@code BoxPainter} needs a rect for every element carrying a
     * {@code border-radius} or a border, which is most of a themed screen and all ten thousand nodes of
     * a graph: through the value that was a {@code Fill}, one or two {@code CgUiRect}s, and the two
     * capturing lambdas the draw itself needed, per element per frame. A painter that HAS radii and a
     * fill but no rect comes straight here and builds none of them.</p>
     *
     * <p>It is its own {@code Runnable} and {@code Consumer} for the same reason: {@code withMaterial}
     * and {@code applyProperties} each take a callback, and a lambda over the draw's arguments is a
     * fresh capture every time one runs.</p>
     */
    public static final class Draw implements Runnable, Consumer<CgShaderBindings> {

        /** What fill was last set. Distinguishes a colour fill from a texture fill whose texture is
         * null, which draws nothing rather than drawing white. */
        private enum Kind { COLOR, TEXTURE, SPRITE }

        private CgUiPaintContext ctx;
        private float x, y, width, height;
        private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;
        private float borderWidth;
        private int borderColorArgb, borderTopColorArgb, borderBottomColorArgb;
        private Kind kind;
        private int fillColorArgb;
        private CgTexture2D texture;
        private CgUiSprite sprite;

        /** Set by {@link #drawShaped} for {@link #run} and {@link #accept} to read: the SDF path's two
         * callbacks take no arguments, so what they draw with lives here. */
        private int quadTint, shaderFillArgb;

        /** Resets to a bare rect and binds the context. {@code CgUiPaintContext.rect()} is the way in;
         * nothing else should call this. */
        public Draw begin(CgUiPaintContext ctx) {
            this.ctx = ctx;
            x = 0f; y = 0f; width = 0f; height = 0f;
            rxTL = 0f; ryTL = 0f; rxTR = 0f; ryTR = 0f;
            rxBR = 0f; ryBR = 0f; rxBL = 0f; ryBL = 0f;
            borderWidth = 0f;
            borderColorArgb = 0xFF000000;
            borderTopColorArgb = 0xFF000000;
            borderBottomColorArgb = 0xFF000000;
            kind = Kind.COLOR;
            fillColorArgb = 0xFFFFFFFF;
            texture = null;
            sprite = null;
            return this;
        }

        public Draw at(float x, float y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Draw size(float width, float height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /** The same elliptical radius on all four corners. */
        public Draw radius(float rx, float ry) {
            return radii(rx, ry, rx, ry, rx, ry, rx, ry);
        }

        /** Per corner and per axis, CSS {@code border-radius} order (TL, TR, BR, BL). */
        public Draw radii(float rxTL, float ryTL, float rxTR, float ryTR,
                          float rxBR, float ryBR, float rxBL, float ryBL) {
            this.rxTL = rxTL; this.ryTL = ryTL;
            this.rxTR = rxTR; this.ryTR = ryTR;
            this.rxBR = rxBR; this.ryBR = ryBR;
            this.rxBL = rxBL; this.ryBL = ryBL;
            return this;
        }

        public Draw border(float width, int colorArgb) {
            return border(width, colorArgb, colorArgb, colorArgb);
        }

        /** @see CgUiRect#withBorder(float, int, int, int) for what the top/bottom pair is for. */
        public Draw border(float width, int uniformColorArgb, int topColorArgb, int bottomColorArgb) {
            this.borderWidth = width;
            this.borderColorArgb = uniformColorArgb;
            this.borderTopColorArgb = topColorArgb;
            this.borderBottomColorArgb = bottomColorArgb;
            return this;
        }

        // Each fill setter CLEARS THE OTHERS. On the immutable value these three were alternatives in a
        // sealed type; here they are fields, and the SDF path picks its keywords by asking which are
        // non-null -- so a second fill call on the same scratch would otherwise draw a colour through
        // the texture variant.
        public Draw fillColor(int colorArgb) {
            this.kind = Kind.COLOR;
            this.fillColorArgb = colorArgb;
            this.texture = null;
            this.sprite = null;
            return this;
        }

        public Draw fillTexture(CgTexture2D texture) {
            this.kind = Kind.TEXTURE;
            this.texture = texture;
            this.sprite = null;
            return this;
        }

        public Draw fillSprite(CgUiSprite sprite) {
            this.kind = Kind.SPRITE;
            this.sprite = sprite;
            this.texture = null;
            return this;
        }

        /** Takes a {@link Fill} the caller already holds — a cascaded rect's, typically, which is
         * shared and must not be rebuilt merely to be drawn. */
        public Draw fill(Fill fill) {
            return switch (fill) {
                case Fill.Color(int argb) -> fillColor(argb);
                case Fill.Texture(CgTexture2D t) -> fillTexture(t);
                case Fill.Sprite(CgUiSprite s) -> fillSprite(s);
            };
        }

        /** Draws it. Everything set here is spent; the next {@code ctx.rect()} clears the scratch. */
        public void submit() {
            if (width <= 0f || height <= 0f) return;
            switch (kind) {
                case COLOR -> {
                    if (isPlain()) ctx.fillRect(x, y, width, height, ArgbMath.multiply(fillColorArgb, ctx.getColor()));
                    else drawShaped(ctx.getColor(), fillColorArgb);
                }
                case TEXTURE -> {
                    if (texture == null) return;
                    if (isPlain()) {
                        ctx.bindTexture(texture);
                        ctx.quad().at(x, y).size(width, height).color(ctx.getColor()).submit();
                        ctx.flush();
                    } else {
                        drawShaped(ctx.getColor(), 0);
                    }
                }
                case SPRITE -> drawSprite();
            }
        }

        /** Whether the frame's own batch can express this rect: no radius, no border. */
        private boolean isPlain() {
            return borderWidth <= 0f
                    && rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f
                    && rxBR == 0f && ryBR == 0f && rxBL == 0f && ryBL == 0f;
        }

        private void drawSprite() {
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
            // regions degenerate to one -- inner UVs equal outer, so the centre stretches the sprite's own
            // sub-rect -- which is the point: the old wrap handed the raw texture to a plain texture fill and
            // dropped the sub-rect, so an ATLAS sprite with a border-radius sampled the whole sheet. It also
            // means a borderless sprite finally honours its repeat modes under a radius.
            drawShaped(tint, 0);
        }

        /**
         * The SDF path — a radius, a border, or a 9-slice, none of which the batch can express.
         *
         * <p>{@code this} goes to both callbacks rather than a lambda; see the class doc.</p>
         */
        private void drawShaped(int quadTint, int shaderFillArgb) {
            this.quadTint = quadTint;
            this.shaderFillArgb = shaderFillArgb;
            MATERIAL.toggleKeyword("WITH_BORDER", borderWidth > 0f);
            // Only ever true when the 4-arg border was given a top or bottom that actually differs from
            // the uniform colour -- the 2-arg overload passes all three equal, which keeps every existing
            // caller (the outline ring, the mask border, every uniform-border widget) on the exact same
            // shader path as before this feature existed.
            MATERIAL.toggleKeyword("SPLIT_BORDER",
                    borderTopColorArgb != borderColorArgb || borderBottomColorArgb != borderColorArgb);
            MATERIAL.toggleKeyword("WITH_TEXTURE_FILL", sprite == null && texture != null);
            MATERIAL.toggleKeyword("WITH_9SLICE_FILL", sprite != null);
            ctx.withMaterial(MATERIAL, this);
        }

        @Override
        public void run() {
            MATERIAL.applyProperties(this);
            ctx.quad().at(x, y).size(width, height).color(quadTint).submit();
        }

        @Override
        public void accept(CgShaderBindings b) {
            b.vec4("_CornerRadiusX", rxTL, rxTR, rxBR, rxBL);
            b.vec4("_CornerRadiusY", ryTL, ryTR, ryBR, ryBL);
            b.set1f("_BorderWidth", borderWidth);
            b.colorARGB("_BorderColor", borderColorArgb);
            b.colorARGB("_BorderColorTop", borderTopColorArgb);
            b.colorARGB("_BorderColorBottom", borderBottomColorArgb);
            b.colorARGB("_FillColor", shaderFillArgb);
            b.vec2("_BoxSize", width, height);
            if (sprite != null) {
                b.sampler("_MainTex", 0, sprite.getTexture());
                float scale = sprite.getBorderScale();
                float bL = sprite.getBorderLeft() * scale, bT = sprite.getBorderTop() * scale;
                float bR = sprite.getBorderRight() * scale, bB = sprite.getBorderBottom() * scale;
                b.vec4("_NineSliceBorder", bL, bT, bR, bB);
                b.vec4("_NineSliceOuterUV", sprite.getU0(), sprite.getV0(), sprite.getU3(), sprite.getV3());
                b.vec4("_NineSliceInnerUV", sprite.getU1(), sprite.getV1(), sprite.getU2(), sprite.getV2());

                // Tile counts are computed HERE, in Java, and handed to the shader -- rather than
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
            } else if (texture != null) {
                b.sampler("_MainTex", 0, texture);
            }
        }
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
