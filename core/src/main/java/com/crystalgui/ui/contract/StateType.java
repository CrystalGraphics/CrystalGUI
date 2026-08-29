package com.crystalgui.ui.contract;

import com.crystalgui.serialization.StateMap;

/**
 * How one kind of value crosses a {@link StateMap} — the read/write pair for a {@link State} slot.
 *
 * <h3>Why this is an interface and not a lambda</h3>
 *
 * <p>{@code StateMap<T>} is generic in the <em>ops</em> type (the serialization backend), and a state
 * slot is declared once as a {@code static final} on a widget class, long before anybody knows which
 * backend will carry it. So both methods have to be generic in {@code T} independently of {@code V} —
 * and a generic method cannot be a lambda in Java. {@link StateTypes} holds the constants; nothing
 * else should need to implement this.</p>
 *
 * @param <V> the Java type of the value
 */
public interface StateType<V> {

    /** Writes {@code value} under {@code key}. */
    <T> void put(StateMap<T> out, String key, V value);

    /** Reads back what {@link #put} wrote, or {@code fallback} when the key is absent. */
    <T> V get(StateMap<T> in, String key, V fallback);
}
