package com.crystalgui.style.property.visual;

/**
 * CSS-facing {@code overflow:} value — mirrors real CSS's {@code overflow: visible|hidden}: the
 * author only says whether clipping happens, never which clip mechanism implements it. The actual
 * clip mechanism ({@link OverflowClip#SCISSOR} vs {@link OverflowClip#MASK}) is auto-detected from
 * the element's own resolved shape (corner radius, explicit {@code mask:}, sprite background) —
 * see {@code UIElement#resolveOverflowClip()}. Replaces the old {@code clip: none|scissor|mask}
 * property, which let authors pick the mechanism directly.
 */
public enum Overflow {
    VISIBLE,
    HIDDEN
}
