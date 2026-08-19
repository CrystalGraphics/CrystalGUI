package com.crystalgui.text.markup;

import javax.annotation.Nullable;

/**
 * A run of text with one style — the leaf of {@link MarkupDocument}.
 *
 * <p>Spans do not nest. {@code <b><i>x</i></b>} arrives as one span carrying both, because a consumer
 * laying this out draws a run of glyphs with a set of attributes and has nowhere to put a tree. Nesting
 * is a fact about the <em>source</em>; what survives into the model is what the renderer can act on.</p>
 */
public record MarkupSpan(String text, int styles, @Nullable String target) {

    /** Ordinary prose. */
    public static final int PLAIN = 0;

    /** {@code <code>}, {@code <tt>}, and javadoc's {@code {@code}} — drawn in the code face. */
    public static final int CODE = 1;

    /** {@code <i>}, {@code <em>}. */
    public static final int EMPHASIS = 1 << 1;

    /** {@code <b>}, {@code <strong>}. */
    public static final int STRONG = 1 << 2;

    /**
     * {@code <a href>}, and javadoc's {@code {@link}} — {@link #target} carries where it points.
     *
     * <p>A style rather than a block of its own, because a link is a run of text inside a sentence and
     * anything else would make "the middle third of this paragraph" a structural node.</p>
     */
    public static final int LINK = 1 << 3;

    public static MarkupSpan of(String text) {
        return new MarkupSpan(text, PLAIN, null);
    }

    public static MarkupSpan of(String text, int styles) {
        return new MarkupSpan(text, styles, null);
    }

    /** Whether every bit in {@code style} is set — {@code has(CODE)}, {@code has(CODE | STRONG)}. */
    public boolean has(int style) {
        return (styles & style) == style;
    }

    /**
     * A BITSET, not an enum, and the reason is the paragraph above about nesting.
     *
     * <p>{@code <b><code>x</code></b>} is bold and monospaced at once. An enum would force a choice
     * between them at parse time — where the information to choose does not exist — or an enum set,
     * which is a set of objects per run of text in a document that is mostly runs of text.</p>
     */
    public MarkupSpan with(int style) {
        return new MarkupSpan(text, styles | style, target);
    }
}
