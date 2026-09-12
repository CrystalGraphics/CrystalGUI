package com.crystalgui.style.property.visual.text;

/**
 * CSS Fill and Stroke Level 3's {@code stroke-align}: which side of the glyph outline a
 * {@code text-stroke}'s width is spent on.
 *
 * <p>The draft this comes from has been stalled since 2017 and no browser implements it, so the
 * names are borrowed rather than depended on. What the web has instead is
 * {@code -webkit-text-stroke}, which is permanently {@link #CENTER} — and the reason
 * {@code paint-order: stroke} is in every stroked-text recipe ever written is that it hides the half
 * of a centred stroke that thins the letterform. This engine offers the alignment directly, so the
 * workaround is optional.</p>
 *
 * <p>The initial value here is {@link #OUTSET} rather than {@code CENTER}: there is no compatibility
 * obligation to inherit a default everyone works around. @see com.crystalgraphics.api.text.CgStrokeAlign</p>
 */
public enum StrokeAlign {
    /** Half outside, half inside. {@code -webkit-text-stroke}'s behaviour; thins the glyph. */
    CENTER,
    /** Entirely outside the outline. The letterform keeps its weight. The initial value. */
    OUTSET,
    /** Entirely inside the outline. The letterform keeps its footprint; counters close first. */
    INSET
}
