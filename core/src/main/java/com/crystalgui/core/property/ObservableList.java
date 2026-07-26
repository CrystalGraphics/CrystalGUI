package com.crystalgui.core.property;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Observable list with synchronous structural change notification — the collection counterpart to
 * {@link Property}, which is scalar-only. Where {@link Property#changed} emits a single
 * {@code (oldValue, newValue)} replacement, {@link #changed} here emits one {@link Change} per
 * structural mutation (insert/remove/update/clear), since replacing the whole list on every element
 * add/remove would defeat the point of watching a list incrementally (e.g. a server-driven list of
 * rows rendered as UI elements, where re-rendering every row on every single insertion is wasteful).
 *
 * <p><b>Single-thread only</b> — same model as {@link Property}/{@link Signal}, no new concurrency
 * concerns introduced.</p>
 *
 * @param <T> the element type
 */
public final class ObservableList<T> implements Iterable<T> {

    public enum ChangeType {
        INSERT, REMOVE, UPDATE, CLEAR
    }

    /** One structural change. For {@code INSERT}, {@code oldValue} is {@code null} and
     * {@code newValue} is the inserted element. For {@code REMOVE}, {@code oldValue} is the removed
     * element and {@code newValue} is {@code null}. For {@code UPDATE}, both are set. For
     * {@code CLEAR}, {@code index} is {@code -1} and both values are {@code null} — listeners that
     * care about clears should re-read the (now empty) list rather than rely on per-element values. */
    public static final class Change<T> {
        public final ChangeType type;
        public final int index;
        public final T oldValue;
        public final T newValue;

        private Change(ChangeType type, int index, T oldValue, T newValue) {
            this.type = type;
            this.index = index;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        static <T> Change<T> insert(int index, T value) {
            return new Change<>(ChangeType.INSERT, index, null, value);
        }

        static <T> Change<T> remove(int index, T value) {
            return new Change<>(ChangeType.REMOVE, index, value, null);
        }

        static <T> Change<T> update(int index, T oldValue, T newValue) {
            return new Change<>(ChangeType.UPDATE, index, oldValue, newValue);
        }

        static <T> Change<T> clear() {
            return new Change<>(ChangeType.CLEAR, -1, null, null);
        }
    }

    private final List<T> items = new ArrayList<>();

    /** Emits one {@link Change} per structural mutation. */
    public final Signal.Value<Change<T>> changed = new Signal.Value<>();

    public T get(int index) {
        return items.get(index);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean contains(T value) {
        return items.contains(value);
    }

    /** Read-only view — mutate through {@link #add}/{@link #removeAt}/{@link #set}/{@link #clear}
     * so every change is observed, not through this list directly. */
    public List<T> asUnmodifiableList() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public Iterator<T> iterator() {
        return asUnmodifiableList().iterator();
    }

    public void add(T value) {
        add(items.size(), value);
    }

    public void add(int index, T value) {
        items.add(index, value);
        changed.emit(Change.insert(index, value));
    }

    /** Named {@code removeAt}, not {@code remove(int)}, to avoid the classic {@code List<Integer>}
     * overload-ambiguity pitfall between an index-based and a value-based remove. */
    public T removeAt(int index) {
        T removed = items.remove(index);
        changed.emit(Change.remove(index, removed));
        return removed;
    }

    /** Removes the first occurrence of {@code value}, if present. Returns whether anything was
     * removed. */
    public boolean remove(T value) {
        int index = items.indexOf(value);
        if (index < 0) return false;
        removeAt(index);
        return true;
    }

    /** Replaces the element at {@code index}. No-ops (and emits nothing) if the new value equals
     * the old one, per {@code Objects.equals} — matches {@link Property#set}'s equality-suppressing
     * convention. */
    public T set(int index, T value) {
        T old = items.get(index);
        if (Objects.equals(old, value)) return old;
        items.set(index, value);
        changed.emit(Change.update(index, old, value));
        return old;
    }

    public void clear() {
        if (items.isEmpty()) return;
        items.clear();
        changed.emit(Change.clear());
    }

    /** Convenience — connects a listener directly without reaching through {@link #changed}. */
    public Connection onChange(Signal.Value.Listener<Change<T>> listener) {
        return changed.connect(listener);
    }
}
