package com.crystalgui.text;

/**
 * What a {@link Rope} node knows about the text beneath it, without looking at it.
 *
 * <p>This is the whole idea behind the summary tree: every node caches a summary of its subtree, and
 * because summaries <b>compose</b>, a seek by any summarised quantity can decide which child to descend
 * into without touching the others. That is what makes {@code offsetToPoint}, {@code pointToOffset} and
 * "give me line 4000" all O(log n) against one structure, rather than needing a second index bolted
 * alongside — which is exactly the piece a piece table has to add (VS Code carries a red-black tree of
 * line-break counts for precisely this reason, because a piece table's own line lookup is O(n)).</p>
 *
 * <h3>Composition must be associative, and one field is easy to get wrong</h3>
 * <p>{@link #add} is a monoid with {@link #EMPTY} as its identity, and the tree relies on that: it
 * combines children in whatever grouping the tree shape happens to give, so {@code (a+b)+c} and
 * {@code a+(b+c)} must agree or a summary depends on how the tree was built rather than on what it
 * contains.</p>
 *
 * <p>{@link #lastLineChars()} is the field that makes it non-trivial. It is <em>not</em> additive: if the
 * right-hand side contains a newline then the combined last line is entirely the right side's, and the
 * left side contributes nothing at all. Only when the right side is newline-free do the two run together.
 * Adding it like the other fields yields column numbers that are correct in a flat document and quietly
 * wrong the moment a chunk boundary lands mid-line.</p>
 *
 * <h3>Counted in UTF-16 code units</h3>
 * <p>Not bytes. Java strings are UTF-16, so this is the unit {@code String.length()}, {@code charAt} and
 * every {@code CharSequence} in the engine already speak — an offset here needs no conversion to be used
 * anywhere else. Zed counts UTF-8 bytes because Rust strings are UTF-8; the choice follows the host
 * language, and picking the other one would mean a conversion at every boundary. A UTF-8 dimension can be
 * added here later for anything that serialises, and it is free to add precisely because summaries
 * compose.</p>
 */
public record TextSummary(int chars, int newlines, int lastLineChars) {

    /** The identity element. The summary of no text at all. */
    public static final TextSummary EMPTY = new TextSummary(0, 0, 0);

    /** Summarises a literal piece of text. */
    public static TextSummary of(CharSequence text) {
        int newlines = 0;
        int lastBreak = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
                lastBreak = i;
            }
        }
        return new TextSummary(text.length(), newlines, text.length() - lastBreak - 1);
    }

    /**
     * This summary followed by {@code next} — associative, with {@link #EMPTY} as identity.
     *
     * <p>See the class note on {@code lastLineChars}: it is the one field that does not simply add.</p>
     */
    public TextSummary add(TextSummary next) {
        if (next.chars == 0) return this;
        if (chars == 0) return next;
        return new TextSummary(
                chars + next.chars,
                newlines + next.newlines,
                // A newline on the right ENDS this line, so the combined trailing line is the right
                // side's alone. Only a newline-free right side continues ours.
                next.newlines > 0 ? next.lastLineChars : lastLineChars + next.lastLineChars);
    }

    /** Lines, in the editor sense: a document with no newline is still one line. */
    public int lineCount() {
        return newlines + 1;
    }
}
