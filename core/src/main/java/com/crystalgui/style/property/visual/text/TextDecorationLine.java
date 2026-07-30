package com.crystalgui.style.property.visual.text;

/**
 * CSS {@code text-decoration-line} — a line drawn along a run of text.
 *
 * <p>Constant names are the CSS keywords, which is why it is {@link #LINE_THROUGH} and not
 * "strikethrough": {@code text-decoration-line: line-through} is what an author writes, and a
 * stylesheet keyword that has to be mentally translated is a keyword that gets typed wrong.</p>
 *
 * <p>{@code blink} is not here and will not be. It was removed from the spec, no engine honours it, and
 * it is the one CSS value universally regretted.</p>
 *
 * <p><b>The longhand is registered, not the {@code text-decoration} shorthand.</b> CSS's shorthand also
 * sets {@code -color}, {@code -style} and {@code -thickness}, none of which the text stack can express —
 * a decoration is drawn in the run's own colour at the font's own thickness. Registering the shorthand
 * would advertise three knobs that silently do nothing, which is worse than not offering it.</p>
 */
public enum TextDecorationLine {
    UNDERLINE,
    LINE_THROUGH,
    OVERLINE
}
