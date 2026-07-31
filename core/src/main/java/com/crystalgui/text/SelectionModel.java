package com.crystalgui.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every caret in a document, kept sorted, non-overlapping, and with one of them primary.
 *
 * <p>Multi-cursor is designed in from the start rather than retrofitted, because the alternative is
 * rewriting every movement method later: each of them reads and writes the caret directly, so a caret
 * that becomes a list touches all of them at once.</p>
 *
 * <h3>The invariant is the whole class</h3>
 * <p><b>Sorted, and no two selections touching.</b> It is not tidiness — it is what makes a multi-caret
 * edit expressible at all. {@link ChangeSet} requires its changes sorted and non-overlapping and refuses
 * anything else, and that is exactly right: two carets inside the same range have no defined combined
 * edit, and whichever was applied second would silently win. So overlaps are merged <em>here</em>, when
 * carets move, rather than discovered later when an edit is built from them.</p>
 *
 * <h3>Which one is primary</h3>
 * <p>The primary caret is the one that scrolls into view, the one single-caret API reports, and the one a
 * plain click resets to. It is tracked through merges: if the primary is absorbed into a neighbour, the
 * absorbing selection becomes primary, so "the caret I am driving" survives a merge rather than jumping
 * to whichever happened to sort first.</p>
 */
public final class SelectionModel {

    private final List<Selection> selections = new ArrayList<>();
    private int primaryIndex;

    public SelectionModel() {
        selections.add(Selection.caret(0));
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    /** Sorted, non-overlapping, never empty. */
    public List<Selection> all() {
        return Collections.unmodifiableList(selections);
    }

    public int count() {
        return selections.size();
    }

    public boolean isMultiple() {
        return selections.size() > 1;
    }

    public Selection primary() {
        return selections.get(Math.max(0, Math.min(primaryIndex, selections.size() - 1)));
    }

    public int primaryIndex() {
        return primaryIndex;
    }

    /** True when any caret has a non-empty range — not merely the primary one. */
    public boolean hasSelection() {
        for (Selection selection : selections) {
            if (!selection.isEmpty()) return true;
        }
        return false;
    }

    // ── Replacing ───────────────────────────────────────────────────────────────────────────────

    /** Collapses to a single selection — what a plain click and most API calls do. */
    public SelectionModel set(Selection only) {
        selections.clear();
        selections.add(only);
        primaryIndex = 0;
        return this;
    }

    public SelectionModel setAll(List<Selection> replacements, int primary) {
        if (replacements.isEmpty()) return this;
        selections.clear();
        selections.addAll(replacements);
        primaryIndex = Math.max(0, Math.min(primary, selections.size() - 1));
        normalise();
        return this;
    }

    /** Adds another caret. The new one becomes primary, as it is the one just placed. */
    public SelectionModel add(Selection extra) {
        selections.add(extra);
        primaryIndex = selections.size() - 1;
        normalise();
        return this;
    }

    /** Drops every caret but the primary — what Escape does. */
    public SelectionModel collapseToPrimary() {
        Selection kept = primary();
        return set(kept);
    }

    /** Collapses every selection to its own head, keeping all the carets. */
    public SelectionModel collapseEachToHead() {
        List<Selection> collapsed = new ArrayList<>(selections.size());
        for (Selection selection : selections) collapsed.add(selection.collapsed());
        return setAll(collapsed, primaryIndex);
    }

    /**
     * Applies {@code mover} to every selection, then restores the invariant.
     *
     * <p>Every movement goes through here, which is what keeps "does this work with several carets?" from
     * being a question that has to be asked once per key.</p>
     */
    public SelectionModel transform(java.util.function.UnaryOperator<Selection> mover) {
        List<Selection> moved = new ArrayList<>(selections.size());
        for (Selection selection : selections) moved.add(mover.apply(selection));
        return setAll(moved, primaryIndex);
    }

    // ── Surviving an edit ───────────────────────────────────────────────────────────────────────

    /**
     * Carries every caret through a change.
     *
     * <p>The anchor and the head are mapped independently and with opposite bias, which is what keeps a
     * selection covering the text it covered: its start associates with the character before it and its
     * end with the character after. An empty caret maps with a forward bias so that typing leaves it
     * <em>after</em> what was just typed — the one behaviour everyone notices immediately when it is
     * wrong.</p>
     */
    public SelectionModel mapThrough(ChangeSet change) {
        if (change == null || change.isEmpty()) return this;
        List<Selection> mapped = new ArrayList<>(selections.size());
        for (Selection selection : selections) {
            if (selection.isEmpty()) {
                int at = change.mapPos(selection.head(), 1);
                mapped.add(Selection.caret(at));
            } else {
                boolean reversed = selection.isReversed();
                int lo = change.mapPos(selection.start(), -1);
                int hi = Math.max(lo, change.mapPos(selection.end(), 1));
                mapped.add(reversed ? new Selection(hi, lo) : new Selection(lo, hi));
            }
        }
        return setAll(mapped, primaryIndex);
    }

    public SelectionModel clampTo(int documentLength) {
        List<Selection> clamped = new ArrayList<>(selections.size());
        for (Selection selection : selections) clamped.add(selection.clampedTo(documentLength));
        return setAll(clamped, primaryIndex);
    }

    // ── The invariant ───────────────────────────────────────────────────────────────────────────

    /**
     * Sorts, then merges anything touching, keeping track of where the primary went.
     *
     * <p>Merging <b>touching</b> rather than only overlapping selections is deliberate: two carets at the
     * same offset are one caret, and leaving them as two means every subsequent keystroke is inserted
     * twice at the same place.</p>
     */
    private void normalise() {
        if (selections.size() <= 1) {
            primaryIndex = 0;
            return;
        }
        Selection primary = primary();

        List<Selection> sorted = new ArrayList<>(selections);
        Collections.sort(sorted);

        List<Selection> merged = new ArrayList<>(sorted.size());
        int newPrimary = 0;
        for (Selection selection : sorted) {
            boolean wasPrimary = selection == primary;
            if (!merged.isEmpty() && merged.get(merged.size() - 1).touches(selection)) {
                int last = merged.size() - 1;
                merged.set(last, merged.get(last).mergedWith(selection));
                // The primary survives being absorbed: the caret being driven should stay the one being
                // driven rather than jumping to whichever selection happened to sort first.
                if (wasPrimary) newPrimary = last;
            } else {
                merged.add(selection);
                if (wasPrimary) newPrimary = merged.size() - 1;
            }
        }

        selections.clear();
        selections.addAll(merged);
        primaryIndex = Math.max(0, Math.min(newPrimary, selections.size() - 1));
    }

    @Override
    public String toString() {
        return selections + " primary=" + primaryIndex;
    }
}
