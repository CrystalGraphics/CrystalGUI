package com.crystalgui.style.property.visual;

/**
 * Which box a drawable layer is laid into — CSS {@code background-origin}.
 *
 * <p>Parsed case-insensitively with {@code -}/{@code _} interchangeable (see
 * {@code EnumValue}), so {@code overlay-origin: padding-box} resolves to {@link #PADDING_BOX}.</p>
 */
public enum BoxOrigin {
    /** The element's full outer box — padding and border included. The default, and what every
     * drawable layer used unconditionally before this property existed. */
    BORDER_BOX,
    /** Inside the border, but still including padding. */
    PADDING_BOX,
    /** Inside both border and padding — the same box text is laid out in. */
    CONTENT_BOX
}
