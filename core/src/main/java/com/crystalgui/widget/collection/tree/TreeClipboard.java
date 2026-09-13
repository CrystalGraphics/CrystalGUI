package com.crystalgui.widget.collection.tree;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.platform.CgPlatform;

/**
 * What a tree's Cut and Copy put down and Paste picks up: the items, and whether they are to move or be copied.
 *
 * <pre>{@code
 * // one per KIND of tree, shared, so a cut in one Project panel pastes in another
 * private static final TreeClipboard<CgPath> CLIPBOARD = new TreeClipboard<>();
 * }</pre>
 *
 * <ul>
 *   <li>The text form goes to the platform clipboard too, so what was copied also pastes into a message.</li>
 *   <li>A cut is consumed by its paste, since the items have moved; a copy stays for the next paste.</li>
 * </ul>
 */
public final class TreeClipboard<T> {

    public enum Mode {
        COPY, CUT
    }

    private final List<T> items = new ArrayList<>();

    private Mode mode = Mode.COPY;

    /** Holds {@code held} for a paste to act on, and puts {@code text} on the platform clipboard. */
    public void put(List<T> held, Mode intent, String text) {
        items.clear();
        items.addAll(held);
        mode = intent;
        if (!items.isEmpty()) CgPlatform.input().setClipboard(text);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Mode mode() {
        return mode;
    }

    /** A copy of what is held. */
    public List<T> items() {
        return List.copyOf(items);
    }

    /** Whether {@code item} is held by a cut — a row a theme dims. */
    public boolean isCut(T item) {
        return mode == Mode.CUT && items.contains(item);
    }

    /** What is held, clearing it when it was a cut. */
    public List<T> consumeIfCut() {
        List<T> held = List.copyOf(items);
        if (mode == Mode.CUT) items.clear();
        return held;
    }

    public void clear() {
        items.clear();
    }
}
