package com.crystalgui.render.texture;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.svg.SvgDocument;

import javax.annotation.Nullable;

/**
 * Draws an {@link SvgDocument} into a rect — the drawable face of the SVG stack.
 *
 * <h3>Why this is not {@code SvgDocument} implementing the interface directly</h3>
 *
 * <p>The obvious move is to put {@code draw} on the document and be done. It does not work, and the reason
 * is the same one that makes {@code currentColor} late-bound in the first place: <b>a document is a shared,
 * cached parse result and a drawable carries presentation state.</b> {@link CgUiDrawable#draw} has no tint
 * parameter, so a document implementing it would have to hold the tint as a field — and a selected file-tree
 * row and an unselected one, drawing the same icon in the same frame, would be writing to the same field.
 * Whichever painted last would win for both.</p>
 *
 * <p>So: one document, as many drawables over it as there are appearances. The same relationship
 * {@link CgUiSprite} has with the sprite it points at.</p>
 *
 * <h3>Fitted, never stretched</h3>
 *
 * <p>The viewBox is scaled to fit inside the rect and centred, preserving aspect — SVG's own
 * {@code preserveAspectRatio="xMidYMid meet"} default, and the same rule {@link CgUiShape} spells out for
 * its own square coordinate space. No icon system lets its artwork stretch to its container, and a rect
 * whose aspect drifts with zoom is how you find out.</p>
 */
public final class CgUiSvg implements CgUiDrawable {

    private final SvgDocument document;

    /**
     * What {@code currentColor} resolves to, multiplied by the context colour at draw time.
     *
     * <p>White by default, so an untinted icon takes the context colour alone — which is exactly what
     * {@link CgUiShape} does, and it is what makes a monochrome set (Feather, Lucide, Tabler — all authored
     * as {@code stroke="currentColor"}) theme from the cascade for free.</p>
     */
    private int tintArgb = 0xFFFFFFFF;

    private boolean monochrome;

    private float strokeHalfWidth;

    public CgUiSvg(SvgDocument document) {
        this.document = document;
    }

    /** Loads and wraps in one step, sharing the parsed document via {@link SvgDocument#of}. */
    @Nullable
    public static CgUiSvg of(String path) {
        SvgDocument document = SvgDocument.of(path);
        return document == null ? null : new CgUiSvg(document);
    }

    public SvgDocument getDocument() {
        return document;
    }

    /** What {@code currentColor} resolves to. The file's own literal colours are left alone — see {@link #setMonochrome}. */
    public CgUiSvg setTint(int argb) {
        this.tintArgb = argb;
        return this;
    }

    /**
     * Forces <b>every</b> colour in the file to the tint, not only {@code currentColor}.
     *
     * <p>Off by default, which is the distinction that matters: a themed monochrome set is authored as
     * {@code currentColor} and wants the tint; a logo has its own palette and must keep it. Turning this on
     * is asking for a silhouette, and is the right answer for a disabled state or a drag ghost.</p>
     */
    public CgUiSvg setMonochrome(boolean monochrome) {
        this.monochrome = monochrome;
        return this;
    }

    /**
     * Overrides the stroke half-width, in screen pixels. {@code 0} keeps the file's own widths.
     *
     * <p>Only consulted in {@linkplain #setMonochrome monochrome} mode, because the two go together: a set
     * whose colour the consumer decides is a set whose weight the consumer decides. Honouring the file's
     * {@code stroke-width} is right the rest of the time — it is stated in viewBox units, so it scales with
     * the icon instead of thinning as the icon grows.</p>
     */
    public CgUiSvg setStrokeHalfWidth(float halfWidth) {
        this.strokeHalfWidth = halfWidth;
        return this;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        if (document == null || document.isEmpty()) return;
        float boxWidth = document.width(), boxHeight = document.height();
        if (boxWidth <= 0f || boxHeight <= 0f || width <= 0f || height <= 0f) return;

        float scale = Math.min(width / boxWidth, height / boxHeight);
        float left = x + (width - boxWidth * scale) * 0.5f;
        float top = y + (height - boxHeight * scale) * 0.5f;
        int argb = ArgbMath.multiply(tintArgb, ctx.getColor());

        if (monochrome) {
            document.renderMonochrome(ctx, left, top, scale, argb, strokeHalfWidth);
        } else {
            document.render(ctx, left, top, scale, argb);
        }
    }

    /**
     * The viewBox size, read 1:1 as logical UI pixels — the convention {@link CgUiDrawable#intrinsicWidth}
     * states for texture-backed drawables, and what {@code overlay-fit: contain|cover|none} resolves
     * against.
     */
    @Override
    public float intrinsicWidth() {
        return document == null ? -1f : document.width();
    }

    @Override
    public float intrinsicHeight() {
        return document == null ? -1f : document.height();
    }
}
