package com.crystalgui.render.texture;

import com.crystalgui.render.CgUiPaintContext;

/**
 * Pluggable "how do I paint myself into a rect" strategy.
 *
 * <p>Assigned to {@code UIElement.background}/{@code overlay}. Each implementation
 * routes to CgGui's Track A immediate-mode primitives (see {@link CgUiPaintContext})
 * — there is no batching or deferred submission anywhere in this call path. A call
 * to {@link #draw} is expected to issue exactly one GPU draw call (or zero, for a
 * fully-transparent tint) before returning.</p>
 */
public interface CgUiDrawable {

    CgUiQuad EMPTY = new CgUiQuad(0);

    /**
     * Paints this texture into the given rect, immediately.
     *
     * @param ctx      the active paint context for this frame
     * @param x        left edge, in screen pixels
     * @param y        top edge, in screen pixels
     * @param width    rect width, in screen pixels
     * @param height   rect height, in screen pixels
     */
    default void draw(CgUiPaintContext ctx, float x, float y, float width, float height) {
        this.draw(ctx, ctx.mouseX, ctx.mouseY, x, y, width, height);
    }

    void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height);

    /**
     * Natural (unscaled) width in pixels, or {@code -1} when this drawable has no inherent size —
     * solid colours and SDF shapes are defined by whatever rect they're handed, so they report -1.
     *
     * <p>Consumed by {@code overlay-fit: contain|cover|none} via {@link CgUiLayerBox#resolve}, which
     * degrades to {@code fill} when the size is unknown. Texture-backed drawables report their
     * source-rect size, interpreted 1:1 as logical UI pixels.</p>
     */
    default float intrinsicWidth() {
        return -1f;
    }

    /** Natural (unscaled) height in pixels, or {@code -1}. See {@link #intrinsicWidth()}. */
    default float intrinsicHeight() {
        return -1f;
    }

    /**
     * Whether this drawable has <b>no colour of its own</b> and should therefore be painted in the
     * element's {@code color}, the way a glyph is.
     *
     * <p>Default {@code false}: a textured sprite and a multi-colour icon carry a palette the author
     * chose, and multiplying that by the surrounding text colour is how a file-type icon turns into
     * mud. {@link CgUiShape} overrides it to {@code true} — a chevron, a checkmark or a cross is a
     * mark, not a picture, and {@code color} is the only colour it could sensibly take.</p>
     *
     * <p><b>Why this exists at all.</b> {@code UIElement.paintOverlay} used to set the ambient tint to
     * pure white before drawing, so a {@code shape()} overlay painted white <em>whatever the sheet
     * said</em> — it could not be themed, and nobody noticed because white-on-dark is what it wanted
     * to look like anyway. The light theme made every fold arrow in the file tree vanish. The tint is
     * now the element's {@code color} for drawables that answer true here, and white for the rest,
     * which keeps the existing {@code icon("…", #hex, monochrome)} call sites exactly as they were.</p>
     */
    default boolean followsTextColor() {
        return false;
    }
}
