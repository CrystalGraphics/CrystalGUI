package com.crystalgui.style.property.visual.text;

/**
 * CSS-facing {@code text-overflow} value — what to do with text too wide for its box.
 *
 * <p>A port of CSS UI 4. <b>Not inherited</b> (unlike {@code text-align} and {@code white-space}),
 * matching the spec — truncation is a property of the box that clips, not of the text flowing through
 * it.</p>
 *
 * <p><b>This is the web's answer to LDLib's marquee</b>, and the reason no marquee was ported:
 * {@code <marquee>} is obsolete and CSS never replaced it, because in a dense UI an ellipsis says
 * "there is more" without moving. Scrolling text, if ever wanted, is an animation layered on top of
 * this — not an alternative to it.</p>
 *
 * <p>Only meaningful alongside {@code white-space: nowrap}: wrapped text has no horizontal overflow
 * to trim.</p>
 *
 * <p><b>Set this on the {@code UIText} itself, never on a wrapper around it.</b> Because it does not
 * inherit, a declaration on an ancestor never arrives — and the mistake is easy to make and hard to see,
 * since {@code white-space} <em>does</em> inherit, so the nowrap half of the recipe reaches the label
 * from the wrapper while the truncation half silently does not. The result renders as plain
 * {@link #CLIP}. In CSS this property lives on the block container that owns the line; here that
 * container <em>is</em> the {@code UIText}, so a theme targets e.g. {@code dialog .__label__}, not
 * {@code dialog .__title-bar__}. {@code UIText.displayedText()} is how you check.</p>
 */
public enum TextOverflow {
    /** Overflow spills (and is clipped by any ancestor `overflow`). The initial value. */
    CLIP,
    /** Trim to the last glyph that fits alongside an ellipsis, and append one. */
    ELLIPSIS
}
