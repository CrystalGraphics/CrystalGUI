package com.crystalgui.net.window;

import com.crystalgui.core.signal.Signal;

import java.util.List;

/**
 * A collection a server holds and a viewer looks at <b>a window of</b>.
 *
 * <pre>{@code
 * RowSource<ItemStack> inventory = new RowSource<>() {
 *     public int count()                      { return machine.slots(); }
 *     public List<ItemStack> rows(int a, int b) { return machine.slice(a, b); }
 *     public Object keyOf(ItemStack item)     { return item.slotId(); }
 * };
 * }</pre>
 *
 * <p>Handed to {@link ServerScope#stream}, which describes only the rows a viewer can see. A ten
 * thousand row inventory therefore costs a window, not ten thousand described elements — and the
 * window slides as the viewer scrolls rather than the whole list being re-sent.</p>
 *
 * <h3>Keys, not indices</h3>
 *
 * <p>{@link #keyOf} is what makes an insert an insert: a row whose key has not changed keeps its
 * element, and everything a viewer had done to it survives. Keyed by index instead, inserting at the
 * top renumbers every row below it and the viewer is handed a rebuild of a list that mostly did not
 * change. It is also what {@code SELECTION} travels as, so a reorder does not move somebody's
 * selection onto a different row.</p>
 *
 * <h3>Asking for a range is not asking to be told</h3>
 *
 * <p>{@link #rows} may be called on any tick and must be cheap for a range the size of a screen —
 * slicing a list, reading a page. It must not open a file or run a query per call; if the answer is
 * expensive, cache it behind this and announce through {@link #onDidChange} when the cache moves.</p>
 */
public interface RowSource<T> {

    /** How many rows there are in total. What the viewer's scrollbar is sized from. */
    int count();

    /**
     * The rows in {@code [from, to)}, clamped to what exists.
     *
     * <p>May answer fewer than asked for — a window that runs past the end of a list that has just
     * shrunk is ordinary, and is not worth an exception.</p>
     */
    List<T> rows(int from, int to);

    /** This row's stable identity. Not its index. @see RowSource */
    Object keyOf(T item);

    /**
     * The collection changed — a row was added, removed, reordered or edited.
     *
     * <p>Announce rather than push: the scope re-reads the windows viewers are actually looking at,
     * so a change to a row nobody can see costs one comparison. A source with no signal at all is
     * legal and is polled per tick like any other projection.</p>
     */
    default Signal.Action onDidChange() {
        return null;
    }
}
