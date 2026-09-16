package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * A parent's children kept in step with a count: reused by position, created and removed only at the end.
 *
 * <pre>{@code
 * ChildList<UIElement> rows = new ChildList<>(stack, index -> row(index));   // built once per position
 *
 * void show(List<String> layers) {                                          // as often as you like
 *     rows.resize(layers.size());
 *     for (int i = 0; i < layers.size(); i++) paint(rows.get(i), layers.get(i));
 * }
 * }</pre>
 *
 * <p><b>Use this instead of {@code removeAll()} and appending again</b> for anything redrawn while it can
 * be pressed. Rebuilding destroys the element the pointer is on while its own press is still being
 * delivered, so the release lands nowhere and a button in the row never activates, and a handle being
 * dragged is detached under the drag. The same defect was found separately in a table header, the taskbar,
 * a layer stack and a gradient bar.</p>
 *
 * <ul>
 *   <li>The factory is called with the position a child is created for, and that position never changes
 *       for that child. A listener it attaches can capture the index safely.</li>
 *   <li>The parent holds nothing else, since new children are appended at its end.</li>
 *   <li>Updating a child's content is the caller's: this only decides which children exist.</li>
 * </ul>
 */
public final class ChildList<E extends UIElement> {

    private final UIElement parent;
    private final IntFunction<E> create;
    private final List<E> children = new ArrayList<>();

    public ChildList(UIElement parent, IntFunction<E> create) {
        this.parent = parent;
        this.create = create;
    }

    /** Makes exactly {@code count} children exist, keeping the first ones as they are. */
    public void resize(int count) {
        while (children.size() > count) parent.remove(children.remove(children.size() - 1));
        while (children.size() < count) {
            E child = create.apply(children.size());
            children.add(child);
            parent.append(child);
        }
    }

    public E get(int index) {
        return children.get(index);
    }

    public int size() {
        return children.size();
    }
}
