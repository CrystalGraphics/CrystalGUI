package com.crystalgui.text;


import javax.annotation.Nullable;

/**
 * A half-open {@code [start, end)} character range within a run of text — the unit registered with
 * {@code HighlightRegistry}.
 *
 * <p>The web's equivalent is a DOM {@code Range}, and the difference is deliberate: a DOM Range carries
 * its own container node, which is what lets {@code CSS.highlights} be a single global registry. Ours is
 * a pair of indices with no node, so the registry lives on the element instead
 * ({@code ui.text.HighlightRegistry}). Calling it {@code Range} outright would have been closer to the web and
 * worse to read, since in a UI toolkit that name suggests a slider's bounds at least as strongly.</p>
 */
public record TextRange(int start, int end) {

    public TextRange {
        if (start < 0) throw new IllegalArgumentException("TextRange start must not be negative: " + start);
        if (end <= start) {
            throw new IllegalArgumentException("TextRange must be non-empty: [" + start + ", " + end + ")");
        }
    }

    public static TextRange of(int start, int end) {
        return new TextRange(start, end);
    }

    public int length() {
        return end - start;
    }

    /**
     * This range narrowed to {@code [0, limit)}, or {@code null} if nothing of it survives.
     *
     * <p>Two things need this, and both would otherwise throw from inside a paint on a later frame with
     * no caller code on the stack: shortening the text below a registered range, and
     * {@code text-overflow: ellipsis}, which paints a <em>prefix</em> of the string.</p>
     */
    @Nullable
    public TextRange clippedTo(int limit) {
        if (start >= limit) return null;
        return end <= limit ? this : new TextRange(start, limit);
    }
}
