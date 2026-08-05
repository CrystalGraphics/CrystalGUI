package com.crystalgui.core.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Where you have been, and how to step back through it — a browser's Back and Forward.
 *
 * <h3>Sibling to {@code UndoStack}, and deliberately not the same thing</h3>
 *
 * <p>Both are a cursor over a list of past states, which is why they look alike. They differ in what they
 * hold: an undo stack holds <b>changes to a document</b> and its cursor moving <em>edits</em> the document,
 * while this holds <b>places you looked at</b> and its cursor moving changes nothing at all. That is the
 * same document/view boundary the editor's folding and {@code GraphSelection} already draw, applied to
 * navigation — which is why Back does not appear on the undo stack in any editor.</p>
 *
 * <h3>Visiting truncates the forward tail</h3>
 *
 * <p>Going back three pages and then navigating somewhere new discards the three you had gone back past,
 * exactly as a browser does. Keeping them would make Forward mean "somewhere I was going to go", which is
 * a different and much stranger promise.</p>
 *
 * <p>Consecutive visits to the same place collapse: a tree that re-selects its current node on every
 * refresh would otherwise fill this with duplicates and make Back appear broken — one press, no visible
 * movement.</p>
 *
 * @param <T> whatever identifies a place. A path, a URI, a node id
 */
public final class NavigationHistory<T> {

    /** Past the last entry a caller is likely to care about, and small enough to stay honest. */
    public static final int DEFAULT_LIMIT = 64;

    private final List<T> entries = new ArrayList<>();
    private final int limit;

    /** Index of the current entry, or -1 when nothing has been visited. */
    private int cursor = -1;

    public NavigationHistory() {
        this(DEFAULT_LIMIT);
    }

    public NavigationHistory(int limit) {
        if (limit < 1) throw new IllegalArgumentException("A history needs room for at least one entry");
        this.limit = limit;
    }

    /**
     * Records a new place, discarding anything ahead of the cursor.
     *
     * @return false when it was a no-op — nothing new to record
     */
    public boolean visit(T place) {
        if (place == null) return false;
        if (cursor >= 0 && Objects.equals(entries.get(cursor), place)) return false;

        // Everything after the cursor is a future that just stopped existing.
        while (entries.size() > cursor + 1) entries.remove(entries.size() - 1);
        entries.add(place);
        if (entries.size() > limit) entries.remove(0);
        cursor = entries.size() - 1;
        return true;
    }

    public boolean canGoBack() {
        return cursor > 0;
    }

    public boolean canGoForward() {
        return cursor >= 0 && cursor < entries.size() - 1;
    }

    /**
     * Steps back and returns the place now current, or null when there is nowhere to go.
     *
     * <p>The caller is expected to <em>show</em> what comes back <b>without</b> calling {@link #visit}
     * with it. Re-recording it would truncate the forward tail this move just created, so Forward would
     * never be available and Back would appear to do nothing on the second press.</p>
     */
    @Nullable
    public T back() {
        if (!canGoBack()) return null;
        cursor--;
        return entries.get(cursor);
    }

    /** Steps forward. Same contract as {@link #back}. */
    @Nullable
    public T forward() {
        if (!canGoForward()) return null;
        cursor++;
        return entries.get(cursor);
    }

    /** Where you are now, or null before anything has been visited. */
    @Nullable
    public T current() {
        return cursor < 0 ? null : entries.get(cursor);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
        cursor = -1;
    }

    /** Oldest first, for a test or a "recent places" menu. */
    public List<T> entries() {
        return new ArrayList<>(entries);
    }
}
