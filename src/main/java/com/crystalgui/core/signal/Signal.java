package com.crystalgui.core.signal;

import java.util.List;

/**
 * Unified signal namespace for CrystalGUI.
 *
 * <p>Provides zero-, one-, and two-argument signal variants that share a common
 * connection machinery via {@link SignalBase}. No per-emit array snapshots;
 * disconnect-during-emit is safe (deferred removal). Single-thread only.</p>
 *
 * <h3>Variants</h3>
 * <ul>
 *   <li>{@link Signal.Action} — zero-argument signal ({@code emit()})</li>
 *   <li>{@link Signal.Value}&lt;T&gt; — one-argument signal ({@code emit(T)})</li>
 *   <li>{@link Signal.Pair}&lt;A,B&gt; — two-argument signal ({@code emit(A, B)})</li>
 * </ul>
 *
 * <h3>Usage examples</h3>
 * <pre>{@code
 * // Zero-arg (button click):
 * Signal.Action clicked = new Signal.Action();
 * clicked.connect(() -> handleClick());
 * clicked.emit();
 *
 * // One-arg (hover change):
 * Signal.Value<Boolean> hoverChanged = new Signal.Value<>();
 * hoverChanged.connect(hovered -> updateStyle(hovered));
 * hoverChanged.emit(true);
 *
 * // Two-arg (property change):
 * Signal.Pair<String, String> changed = new Signal.Pair<>();
 * changed.connect((oldVal, newVal) -> log(oldVal + " -> " + newVal));
 * changed.emit("old", "new");
 * }</pre>
 */
public final class Signal {

    private Signal() {} // namespace only

    // ════════════════════════════════════════════════════════════════════
    //  Zero-argument signal
    // ════════════════════════════════════════════════════════════════════

    /**
     * Zero-argument signal. Use for parameterless notifications
     * (e.g. button clicks, action triggers).
     */
    public static final class Action extends SignalBase<Runnable> {

        /** Connects a zero-arg listener. */
        public Connection connect(Runnable listener) {
            return addSlot(listener);
        }

        /** Emits to all connected listeners. */
        public void emit() {
            beginEmit();
            try {
                List<SlotEntry<Runnable>> slots = slots();
                for (int i = 0, n = slots.size(); i < n; i++) {
                    SlotEntry<Runnable> entry = slots.get(i);
                    if (entry.connected) {
                        entry.listener.run();
                    }
                }
            } finally {
                endEmit();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  One-argument signal
    // ════════════════════════════════════════════════════════════════════

    /**
     * One-argument typed signal.
     *
     * @param <T> the argument type passed to listeners
     */
    public static final class Value<T> extends SignalBase<Value.Listener<T>> {

        @FunctionalInterface
        public interface Listener<T> {
            void accept(T value);
        }

        /** Connects a one-arg listener. */
        public Connection connect(Listener<T> listener) {
            return addSlot(listener);
        }

        /** Emits the given value to all connected listeners. */
        public void emit(T value) {
            beginEmit();
            try {
                List<SlotEntry<Listener<T>>> slots = slots();
                for (int i = 0, n = slots.size(); i < n; i++) {
                    SlotEntry<Listener<T>> entry = slots.get(i);
                    if (entry.connected) {
                        entry.listener.accept(value);
                    }
                }
            } finally {
                endEmit();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Two-argument signal
    // ════════════════════════════════════════════════════════════════════

    /**
     * Two-argument typed signal. Used for property {@code (oldValue, newValue)}
     * change notifications and similar pair-based events.
     *
     * @param <A> the first argument type
     * @param <B> the second argument type
     */
    public static final class Pair<A, B> extends SignalBase<Pair.Listener<A, B>> {

        @FunctionalInterface
        public interface Listener<A, B> {
            void accept(A a, B b);
        }

        /** Connects a two-arg listener. */
        public Connection connect(Listener<A, B> listener) {
            return addSlot(listener);
        }

        /** Emits the given pair to all connected listeners. */
        public void emit(A a, B b) {
            beginEmit();
            try {
                List<SlotEntry<Listener<A, B>>> slots = slots();
                for (int i = 0, n = slots.size(); i < n; i++) {
                    SlotEntry<Listener<A, B>> entry = slots.get(i);
                    if (entry.connected) {
                        entry.listener.accept(a, b);
                    }
                }
            } finally {
                endEmit();
            }
        }
    }
}
