package com.crystalgui.text;

/**
 * A zero-based {@code (row, column)} position in a document, both counted in UTF-16 code units.
 *
 * <p><b>Zero-based, unlike CodeMirror's one-based lines.</b> Every other index in this engine is
 * zero-based — {@code getSiblingIndex}, the virtualised list's row indices, {@code TextRange} — and a
 * document model that disagreed with the list rendering it would put a {@code +1} at the seam between
 * them, which is the kind of thing that is right in the test and off by one on screen.</p>
 *
 * <p>A column is an offset within the line, <em>not</em> a visual column: tabs count as one, and a
 * surrogate pair counts as two. Anything visual belongs to the shaper, which is the only thing that knows
 * what a glyph is wide.</p>
 */
public record TextPoint(int row, int column) implements Comparable<TextPoint> {

    public static final TextPoint ZERO = new TextPoint(0, 0);

    @Override
    public int compareTo(TextPoint other) {
        return row != other.row ? Integer.compare(row, other.row) : Integer.compare(column, other.column);
    }

    @Override
    public String toString() {
        return row + ":" + column;
    }
}
