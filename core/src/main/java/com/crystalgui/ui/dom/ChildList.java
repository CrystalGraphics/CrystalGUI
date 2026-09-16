package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
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
 * <p>For children with an identity of their own — a row per declaration name — use {@link Keyed}.</p>
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

    /**
     * A parent's children kept in step with a list of keys: one child per key, reused while the key stays,
     * moved when it moves, created and removed only for keys that arrive or leave.
     *
     * <pre>{@code
     * ChildList.Keyed<String, Configurator> rows = new ChildList.Keyed<>(list, name -> rowFor(name));
     *
     * PropertyWatch.follow(list, declarations, now -> rows.show(namesOf(now)));   // a value edit changes no keys
     * }</pre>
     *
     * <p>The keyed form of {@link ChildList}, for a list whose entries have an identity: a declaration by its
     * name, a file by its path. Editing one entry's content keeps every child; adding one inserts one child and
     * leaves the element under the pointer alone. React's {@code key} and Flutter's {@code Key}, for the same
     * reason.</p>
     *
     * <ul>
     *   <li>Keys must be unique within one {@link #show} call; a duplicate throws.</li>
     *   <li>The parent holds nothing else, since positions are counted from its first child.</li>
     *   <li>{@link #onRemoved} runs after a child leaves, for anything registered alongside it.</li>
     * </ul>
     */
    public static final class Keyed<K, E extends UIElement> {

        private final UIElement parent;
        private final Function<K, E> create;
        private final Map<K, E> children = new LinkedHashMap<>();

        private BiConsumer<K, E> removed = (key, child) -> { };

        public Keyed(UIElement parent, Function<K, E> create) {
            this.parent = parent;
            this.create = create;
        }

        /** What to do with a child whose key has gone, after it is taken out of the parent. */
        public Keyed<K, E> onRemoved(BiConsumer<K, E> action) {
            this.removed = action;
            return this;
        }

        /** Makes the children exactly one per key, in this order. */
        public void show(List<K> keys) {
            Set<K> wanted = new HashSet<>(keys);
            if (wanted.size() != keys.size()) throw new IllegalArgumentException("duplicate keys: " + keys);
            for (Map.Entry<K, E> entry : new ArrayList<>(children.entrySet())) {
                if (wanted.contains(entry.getKey())) continue;
                children.remove(entry.getKey());
                parent.remove(entry.getValue());
                removed.accept(entry.getKey(), entry.getValue());
            }
            for (int i = 0; i < keys.size(); i++) {
                E child = children.computeIfAbsent(keys.get(i), create);
                if (parent.indexOf(child) != i) parent.insertAt(i, child);
            }
        }

        /** The child for {@code key}, or null when it is not shown. */
        public E get(K key) {
            return children.get(key);
        }

        public int size() {
            return children.size();
        }
    }
}
