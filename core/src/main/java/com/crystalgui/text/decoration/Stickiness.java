package com.crystalgui.text.decoration;

/**
 * What a {@link TrackedRange} does when text is inserted exactly at one of its edges — Monaco's
 * {@code TrackedRangeStickiness}, ported with its names.
 *
 * <h3>Why four, and why this is the whole difficulty</h3>
 *
 * <p>Everywhere else, mapping a position through an edit is arithmetic: text inserted before it shifts it,
 * text inserted after it does not. <b>Insertion at the boundary is the one case with two defensible
 * answers</b>, and which is right depends entirely on what the range means. Type at the very start of an
 * error squiggle and the new character should be underlined too — the error is still there and now it is one
 * character longer. Type at the very start of a <em>collapsed folding marker</em> and it should not be
 * swallowed. Same edit, same offsets, opposite correct answers, so it cannot be a global policy and it
 * cannot be inferred; it is a property the range is created with.</p>
 *
 * <p>All four exist in Monaco because each has a consumer, and the two asymmetric ones are the reason a
 * boolean is not enough. They are kept here even though only {@link #ALWAYS_GROWS_WHEN_TYPING_AT_EDGES} has
 * a consumer today, because they are two lines each and the alternative is that the second consumer invents
 * a private one somewhere else.</p>
 *
 * <h3>The mapping down to {@code assoc}</h3>
 *
 * <p>{@link com.crystalgui.text.ChangeSet#mapPos} already takes the primitive this needs: {@code assoc < 0}
 * keeps a position <em>before</em> text inserted at it, {@code assoc >= 0} pushes it after. Monaco spells the
 * same thing as {@code stickToPreviousCharacter}, and the two are the same bit — so each mode here is
 * exactly a pair of {@code assoc} values, and nothing downstream needs to know the mode at all.</p>
 *
 * <pre>
 *                                    start   end
 *   ALWAYS_GROWS_WHEN_TYPING_AT_EDGES  -1     +1     grows at both edges
 *   NEVER_GROWS_WHEN_TYPING_AT_EDGES   +1     -1     grows at neither
 *   GROWS_ONLY_WHEN_TYPING_BEFORE      -1     -1     grows at the start only
 *   GROWS_ONLY_WHEN_TYPING_AFTER       +1     +1     grows at the end only
 * </pre>
 *
 * <p>Read the table as Monaco's own: a {@code -1} at an edge means that edge <em>stays put</em> while the
 * insertion happens on the far side of it. At the start, staying put means the new text falls inside the
 * range; at the end, staying put means it falls outside. That is why the same {@code -1} grows the range in
 * one column and not in the other, and it is the single most confusable thing here.</p>
 */
public enum Stickiness {

    /**
     * Text typed at either edge joins the range — a diagnostic squiggle, a selection highlight.
     *
     * <p>The default, and the right one for anything describing a span of text that is still being written.
     * Monaco's {@code AlwaysGrowsWhenTypingAtEdges}.</p>
     */
    ALWAYS_GROWS_WHEN_TYPING_AT_EDGES(-1, 1),

    /**
     * Text typed at either edge stays outside — a decoration marking an exact, already-settled span.
     *
     * <p>Monaco's {@code NeverGrowsWhenTypingAtEdges}. Note that this makes a range at {@code [5,5]}
     * permanently empty: both ends move the same way, so it can never reopen.</p>
     */
    NEVER_GROWS_WHEN_TYPING_AT_EDGES(1, -1),

    /** Grows at the start only. Monaco's {@code GrowsOnlyWhenTypingBefore}. */
    GROWS_ONLY_WHEN_TYPING_BEFORE(-1, -1),

    /** Grows at the end only. Monaco's {@code GrowsOnlyWhenTypingAfter}. */
    GROWS_ONLY_WHEN_TYPING_AFTER(1, 1);

    private final int startAssoc;
    private final int endAssoc;

    Stickiness(int startAssoc, int endAssoc) {
        this.startAssoc = startAssoc;
        this.endAssoc = endAssoc;
    }

    /** The {@code assoc} to map this range's start with. */
    public int startAssoc() {
        return startAssoc;
    }

    /** The {@code assoc} to map this range's end with. */
    public int endAssoc() {
        return endAssoc;
    }
}
