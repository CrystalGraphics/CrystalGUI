package com.crystalgui.text.lang;

/**
 * What kind of thing an action is — and, through {@link #tier()}, where it sorts.
 *
 * <p>LSP's {@code CodeActionKind} is a dotted string hierarchy ({@code quickfix},
 * {@code refactor.extract}, {@code source.organizeImports}) because a client has to filter on it over a
 * wire. Nothing here filters on it, so it is an enum: the set is small, the compiler checks it, and the
 * ordering can hang off it instead of being written out somewhere else.</p>
 *
 * <h3>Why the ordering lives here rather than on the action</h3>
 *
 * <p>Actions arrive from independent contributors that cannot see each other's answers, so the merge has
 * to rank them without asking any of them to compare. A <b>declared tier</b> does that; a computed
 * relevance score does not, and this codebase has already paid for the difference — completion ranks by
 * match tier precisely because a score folding several signals together let brevity outrank proximity,
 * and it took reading a harness log to see why. A contributor says which tier it is in; ties break on
 * insertion order, which is the one thing the merge genuinely knows.</p>
 */
public enum CodeActionKind {

    /** Fixes a reported problem. The only kind that is <em>about</em> a diagnostic. */
    QUICK_FIX(0),

    /** Changes working code without changing what it does — extract, inline, make package-private. */
    REFACTOR(2),

    /**
     * Changes only how the code is <b>punctuated</b> — braces around a single-statement body.
     *
     * <p>Below {@link #REFACTOR} because it is the one kind that is nearly always available and nearly
     * never what was wanted. Braces can be added to or removed from every {@code if} and every loop in a
     * file, so wherever a real conversion also applies — "Convert to enhanced for", "Replace if chain with
     * switch" — the two competed on <em>insertion order</em>, and the popup offered to move a brace
     * instead. Reported twice from the harness on exactly those two.</p>
     *
     * <p>The line is drawn at "would a reader call this different code": a block around one statement is a
     * delimiter, and splitting a declaration or naming an expression is not. Those stay {@code REFACTOR}.</p>
     */
    LAYOUT(3),

    /** Acts on the file rather than on a selection — organise imports, add missing overrides. */
    SOURCE(4),

    /**
     * <b>Changes what the code does</b> — the one kind that is not meaning-preserving.
     *
     * <p>Nearly last, and for a reason that is not about how often it applies: <em>a default must never be
     * a behaviour change</em>. Everything above this can be applied by somebody who has not read it
     * carefully and leave the program doing the same thing; this cannot. "Negate comparison" is the only
     * one today, and it ranked above "Convert to enhanced for" on a {@code for} loop — whose condition is a
     * comparison — purely on which family was registered first.</p>
     *
     * <p>It is <b>above</b> {@link #SUPPRESS} because it is at least an edit somebody asked for.</p>
     */
    ALTERING(5),

    /**
     * Stops the problem being reported rather than fixing it.
     *
     * <p>Last on purpose. It is available for nearly every diagnostic (see the shape-derived
     * contributors), so ranking it any higher would bury the fixes that actually change the code under a
     * row that is always there.</p>
     */
    SUPPRESS(6);

    private final int tier;

    CodeActionKind(int tier) {
        this.tier = tier;
    }

    /**
     * Lower sorts first. {@link #QUICK_FIX} leaves a gap above it, which
     * {@link CodeAction#preferred()} occupies — the preferred fix is not a kind of its own, it is the
     * one of its kind that sorts ahead of the others.
     *
     * <p>The gap is also why <b>only {@code QUICK_FIX} may be shown inline</b> by the popup: everything
     * in a higher tier is something to choose rather than to default to — a whole-file tidy, a
     * refactoring, a suppression — and each of those already argues that for itself.</p>
     */
    public int tier() {
        return tier;
    }
}
