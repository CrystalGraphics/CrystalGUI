package com.crystalgui.core.signal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Signal primitives for CrystalGUI's local reactivity system.
 *
 * <p>Three signal types mirror common UI patterns:
 * <ul>
 *   <li>{@link Action} — zero-arg signal (click, submit)</li>
 *   <li>{@link Value} — single-value change signal (text changed, value updated)</li>
 *   <li>{@link Pair} — two-value signal (range selection, drag begin/end)</li>
 * </ul>
 *
 * <p>All signals are single-threaded (UI thread only). No weak references,
 * no thread safety — keep it simple for Java 8.</p>
 */
public final class Signal {

    private Signal() {}

    /**
     * Zero-argument action signal. Connect {@link Runnable} listeners and
     * call {@link #emit()} to fire them all.
     */
    public static final class Action extends SignalBase {
        private final List<Runnable> listeners = new ArrayList<>();

        /** Fire all connected listeners. */
        public void emit() {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).run();
            }
        }

        /**
         * Connect a listener. Returns a {@link Connection} that can be used
         * to disconnect later.
         */
        public Connection connect(Runnable listener) {
            listeners.add(listener);
            Connection conn = new Connection(() -> listeners.remove(listener));
            addConnection(conn);
            return conn;
        }
    }

    /**
     * Single-value change signal. Connect {@link Consumer} listeners and
     * call {@link #emit(Object)} to fire them with the new value.
     */
    public static final class Value<T> extends SignalBase {
        private final List<Consumer<T>> listeners = new ArrayList<>();

        /** Fire all connected listeners with the given value. */
        public void emit(T value) {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).accept(value);
            }
        }

        /**
         * Connect a listener. Returns a {@link Connection} that can be used
         * to disconnect later.
         */
        public Connection connect(Consumer<T> listener) {
            listeners.add(listener);
            Connection conn = new Connection(() -> listeners.remove(listener));
            addConnection(conn);
            return conn;
        }
    }

    /**
     * Two-value signal. Connect {@link BiConsumer} listeners and
     * call {@link #emit(Object, Object)} to fire them with both values.
     */
    public static final class Pair<A, B> extends SignalBase {
        private final List<BiConsumer<A, B>> listeners = new ArrayList<>();

        /** Fire all connected listeners with the given pair of values. */
        public void emit(A first, B second) {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).accept(first, second);
            }
        }

        /**
         * Connect a listener. Returns a {@link Connection} that can be used
         * to disconnect later.
         */
        public Connection connect(BiConsumer<A, B> listener) {
            listeners.add(listener);
            Connection conn = new Connection(() -> listeners.remove(listener));
            addConnection(conn);
            return conn;
        }
    }
}
