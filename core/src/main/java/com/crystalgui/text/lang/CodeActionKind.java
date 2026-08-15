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

    /** Acts on the file rather than on a selection — organise imports, add missing overrides. */
    SOURCE(3),

    /**
     * Stops the problem being reported rather than fixing it.
     *
     * <p>Last on purpose. It is available for nearly every diagnostic (see the shape-derived
     * contributors), so ranking it any higher would bury the fixes that actually change the code under a
     * row that is always there.</p>
     */
    SUPPRESS(4);

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
