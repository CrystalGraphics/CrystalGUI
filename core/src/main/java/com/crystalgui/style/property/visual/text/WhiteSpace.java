package com.crystalgui.style.property.visual.text;

/**
 * CSS-facing {@code white-space} value — currently only the wrapping half.
 *
 * <p><b>Inherited</b>, initial {@link #NORMAL}, matching CSS.</p>
 *
 * <p>Real CSS {@code white-space} is a shorthand covering three things at once: whether text wraps,
 * whether sequences of spaces collapse, and whether newlines are honoured. Only wrapping is modelled
 * here, because the other two are shaper-level concerns this engine does not currently express — so
 * {@code pre}, {@code pre-wrap} and {@code pre-line} are absent rather than pretending to work.</p>
 *
 * <p>{@link #NOWRAP} is what makes {@code text-overflow: ellipsis} meaningful: text that wraps never
 * overflows horizontally, so it never has anything to truncate.</p>
 */
public enum WhiteSpace {
    /** Wraps at the content box, as everything did before this property existed. */
    NORMAL,
    /** One line, however long. Overflows the box unless {@code text-overflow} truncates it. */
    NOWRAP;

    public boolean wraps() {
        return this == NORMAL;
    }
}
