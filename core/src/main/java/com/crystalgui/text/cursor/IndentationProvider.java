package com.crystalgui.text.cursor;

import com.crystalgui.text.Rope;

/**
 * How deep a new line should be — asked of something that has parsed the file.
 *
 * <h3>The rule this replaces, and why it was always a placeholder</h3>
 *
 * <p>{@link TypeOperations#enterAt} copies the previous line's leading whitespace and adds one level when
 * the line ends in an opening bracket. That is right often enough to be usable and wrong in every case a
 * language's structure is not spelled with a brace at the end of a line: a Java {@code case} arm, a
 * continuation of a wrapped expression, a CSS declaration inside a nested rule, an HTML tag. It has named
 * its successor in a comment since it was written.</p>
 *
 * <p>This is that successor, and it is deliberately the <b>smallest</b> question a tree can answer:
 * <em>how many levels deep is a line inserted here</em>. Not what whitespace to write — that is
 * {@link IndentStyle}'s, and a provider that answered in characters would have to know about tabs, about
 * the document's own convention, and about the settings that own both.</p>
 *
 * <h3>{@code -1} is the ordinary answer and is not a failure</h3>
 *
 * <p>A provider says {@code -1} when it has no opinion — the language ships no indent query, the tree is
 * unparsed, the caret is somewhere the query says nothing about. The caller then uses the rule it already
 * had, which is why this is additive: every language that gains a provider improves, and every language
 * without one behaves exactly as it does today.</p>
 */
@FunctionalInterface
public interface IndentationProvider {

    /**
     * The indent level a line inserted immediately after {@code row} should have, or {@code -1}.
     *
     * <p>A <b>level</b>, not a column: one level is whatever {@code IndentStyle} says it is, so the same
     * answer serves a file indented with tabs and one indented with four spaces.</p>
     *
     * @param row the row the caret is on when Enter is pressed — the line being split, not the new one
     */
    int levelsAfterRow(Rope document, int row);

    /**
     * The level the line at {@code row} should itself have, or {@code -1}.
     *
     * <p>Separate from {@link #levelsAfterRow} because the two genuinely differ, and the case that shows
     * it is the one every editor gets asked about: the {@code }} that closes a block sits one level
     * <em>out</em> from the body above it. A caller writing the closing half of a brace pair needs this
     * one; a caller writing the body needs the other.</p>
     */
    default int levelsAtRow(Rope document, int row) {
        return -1;
    }

    /** A provider with no opinion about anything — the default, and what every language had before. */
    static IndentationProvider none() {
        return (document, row) -> -1;
    }
}
