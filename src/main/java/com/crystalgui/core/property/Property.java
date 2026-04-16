package com.crystalgui.core.property;

import com.crystalgui.core.event.CgUiDebug;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;

import java.util.Objects;

/**
 * Observable property with synchronous equality-suppressing change notification.
 *
 * <p>When the value changes (per {@code Objects.equals}), the {@link #changed}
 * signal emits {@code (oldValue, newValue)}.</p>
 *
 * <p>Supports one-way binding (this mirrors another property) and bidirectional
 * binding (both properties stay in sync) with a reentrancy guard to prevent
 * infinite recursion.</p>
 *
 * <p><b>Single-thread only.</b></p>
 *
 * @param <T> the value type
 */
public final class Property<T> {

    private T value;

    /** Emits (oldValue, newValue) when the property value changes. */
    public final Signal.Pair<T, T> changed = new Signal.Pair<>();

    private boolean updating;

    public Property(T initialValue) {
        this.value = initialValue;
    }

    /** Returns the current value. */
    public T get() {
        return value;
    }

    /**
     * Sets the value. If the new value differs from the old (per {@code Objects.equals}),
     * the {@link #changed} signal is emitted with {@code (old, new)}.
     *
     * @param newValue the new value
     */
    public void set(T newValue) {
        if (updating) return; // reentrancy guard for bidirectional binding
        T oldValue = this.value;
        if (Objects.equals(oldValue, newValue)) return;

        CgUiDebug.logPropertySet("Property", oldValue, newValue);
        this.value = newValue;
        updating = true;
        try {
            changed.emit(oldValue, newValue);
        } finally {
            updating = false;
        }
    }

    /**
     * One-way binding: this property mirrors the source.
     * The current value is set immediately to the source's current value.
     *
     * @param source the source property to follow
     * @return a connection that can be disconnected to stop following
     */
    public Connection bindTo(final Property<T> source) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        CgUiDebug.logPropertyBind("target", "source");
        set(source.get());
        return source.changed.connect((oldVal, newVal) -> set(newVal));
    }

    /**
     * Bidirectional binding: both properties stay in sync.
     * This property is immediately set to the other's current value.
     *
     * <p>The reentrancy guard in {@link #set} prevents infinite recursion.</p>
     *
     * @param other the other property
     * @return a connection that disconnects both directions when called
     */
    public Connection bindBidirectional(final Property<T> other) {
        if (other == null) throw new IllegalArgumentException("other must not be null");
        CgUiDebug.logPropertyBind("bidirectional-a", "bidirectional-b");
        // Initialize this to other's value
        set(other.get());

        final Connection forward = other.changed.connect((oldVal, newVal) -> set(newVal));
        final Connection reverse = changed.connect((oldVal, newVal) -> other.set(newVal));

        return new Connection() {
            private boolean connected = true;

            @Override
            public void disconnect() {
                if (!connected) return;
                connected = false;
                forward.disconnect();
                reverse.disconnect();
            }

            @Override
            public boolean isConnected() {
                return connected;
            }
        };
    }
}
