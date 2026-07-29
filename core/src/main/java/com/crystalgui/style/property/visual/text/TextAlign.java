package com.crystalgui.style.property.visual.text;

/**
 * CSS-facing {@code text-align} value — horizontal alignment of text inside its content box.
 *
 * <p>A port of CSS Text 3. <b>Inherited</b>, initial {@link #LEFT} (CSS says {@code start}, which is
 * {@code left} in every writing mode this engine supports — there are none others).</p>
 *
 * <h3>No {@code justify}</h3>
 * <p>Justification is not "align the line", it is "redistribute the spaces inside it", which means
 * re-shaping with per-word advances the shaper would have to be asked for differently. Omitted rather
 * than aliased to something else, so it fails to parse instead of silently doing the wrong thing.</p>
 *
 * <h3>Vertical alignment is deliberately absent</h3>
 * <p>LDLib's {@code TextElement} carries a {@code textAlignVertical} to match its horizontal one. We
 * do not need it: {@code UIText} pushes its own height to fit its content, so the element <em>is</em>
 * the height of its text and there is no spare vertical room to align within. Positioning text in a
 * taller box is the container's job, via flex {@code align-items} — which is also how the web does it.
 * The gap only appears if someone forces an explicit height onto a {@code UIText}, and CSS's answer
 * there ({@code align-content}) is a separate property, not part of {@code text-align}.</p>
 */
public enum TextAlign {
    LEFT,
    CENTER,
    RIGHT;

    /** Fraction of the leftover space to put before the text: 0, ½ or 1. */
    public float leadingFraction() {
        return switch (this) {
            case LEFT -> 0f;
            case CENTER -> 0.5f;
            case RIGHT -> 1f;
        };
    }
}
