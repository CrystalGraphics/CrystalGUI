package com.crystalgui.net.window;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.ui.dom.UIElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The client half of a streamed collection: a list as long as the server says, holding the rows it has
 * sent and placeholders everywhere else.
 *
 * <pre>{@code
 * RemoteRows rows = new RemoteRows(io, listElement, Placeholder::new);
 * ListView<UIElement> view = new ListView<>(rows.model());
 * view.setRenderer(…);                       // draws the element it is handed
 * rows.showing(firstVisibleRow, lastVisibleRow + 1);
 * }</pre>
 *
 * <p>It drives an ordinary {@link ObservableList}, so {@link com.crystalgui.widget.collection.list.ListView}
 * needs no knowledge of any of this: it virtualises a long list the way it always has, and what it is
 * handed for a row it has not been sent yet is a placeholder of the right height rather than nothing.
 * That is what keeps the scrollbar honest while a window is in flight.</p>
 *
 * <h3>Selection is by key</h3>
 *
 * <p>Never by index. An insert above the window moves every index below it, so a selection stored as
 * one lands on a different row — and the row it lands on is somebody else's, in a list two people are
 * looking at. {@link #keyAt} and {@link #indexOfKey} are the conversion.</p>
 */
public final class RemoteRows {

    /** How a viewer asks for a window. Wired to {@code io.call(UiMethods.ROWS, …)} by the panel. */
    @FunctionalInterface
    public interface Requester {
        /** Asks for {@code [from, to)}; the answer is the collection's total count. */
        void request(int from, int to, java.util.function.IntConsumer onCount);
    }

    private final Requester requester;
    private final Supplier<UIElement> placeholder;
    private final ObservableList<UIElement> model = new ObservableList<>();

    /** The described rows the server has sent, by their index in the whole collection. */
    private final List<UIElement> window = new ArrayList<>();
    private int windowFrom;

    /** Each described row's key, so a selection survives the rows moving underneath it. */
    private final List<Object> keys = new ArrayList<>();

    private int count;
    private int askedFrom = -1;
    private int askedTo = -1;

    public RemoteRows(Requester requester, Supplier<UIElement> placeholder) {
        this.requester = Objects.requireNonNull(requester, "requester");
        this.placeholder = Objects.requireNonNull(placeholder, "placeholder");
    }

    /** What a {@code ListView} is built over. As long as the collection, whatever has arrived. */
    public ObservableList<UIElement> model() {
        return model;
    }

    public int count() {
        return count;
    }

    /**
     * Says which rows are on screen, and asks for them if that has moved.
     *
     * <p>Called from the view's scroll handler. Repeating the same range costs nothing — the guard is
     * here rather than at the call site because a scroll handler fires per frame of a drag and every
     * one of them would otherwise be a round trip.</p>
     */
    public void showing(int from, int to) {
        if (from == askedFrom && to == askedTo) return;
        askedFrom = from;
        askedTo = to;
        requester.request(from, to, this::setCount);
    }

    /**
     * The collection's length, from the server.
     *
     * <p>The list is resized to it immediately, before any row has arrived — a scrollbar that grew as
     * rows landed would move under the hand that was dragging it.</p>
     */
    public void setCount(int count) {
        int next = Math.max(0, count);
        if (next == this.count) return;
        this.count = next;
        resize();
    }

    /**
     * The described rows for {@code [from, from + rows.size())} have arrived.
     *
     * <p>Called by the panel from {@code bindWidgets}, since a re-describe rebuilds the row elements
     * and the ones held here are the previous tree's.</p>
     */
    public void received(int from, List<UIElement> rows, List<Object> rowKeys) {
        window.clear();
        window.addAll(rows);
        keys.clear();
        keys.addAll(rowKeys);
        windowFrom = Math.max(0, from);
        resize();
        // WRITTEN AFTER THE RESIZE, so a row never lands at an index the list does not have yet.
        for (int i = 0; i < window.size(); i++) {
            int index = windowFrom + i;
            if (index >= 0 && index < model.size()) model.set(index, window.get(i));
        }
    }

    /** The key of the row at {@code index}, or null when that row has not been sent. */
    public Object keyAt(int index) {
        int within = index - windowFrom;
        return within < 0 || within >= keys.size() ? null : keys.get(within);
    }

    /** Where a key currently sits, or {@code -1} when it is outside the window. */
    public int indexOfKey(Object key) {
        int within = keys.indexOf(key);
        return within < 0 ? -1 : windowFrom + within;
    }

    /**
     * Grows or shrinks the model to {@link #count}, filling the difference with placeholders.
     *
     * <p>Row by row rather than {@code setAll}: an {@code ObservableList} announces each change, and a
     * wholesale replacement tells the view every row moved — which is a full re-realise of the visible
     * band for a list that gained one entry at the bottom.</p>
     */
    private void resize() {
        while (model.size() > count) model.removeAt(model.size() - 1);
        while (model.size() < count) model.add(placeholder.get());
    }
}
