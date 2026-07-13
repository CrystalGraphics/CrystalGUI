package com.crystalgui.core.property;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;

import java.util.Objects;

/**
 * Observable value with equality-suppressing change notification.
 *
 * <p>{@code Property<T>} wraps a value and fires a {@link Signal.Value} only when
 * the new value differs from the old one (via {@link Objects#equals}). Supports
 * one-way and bidirectional binding to other properties.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Property<String> name = Property.of("default");
 * name.onChange().connect(newValue -> System.out.println(newValue));
 * name.set("hello"); // prints "hello"
 * name.set("hello"); // no-op — value unchanged
 * }</pre>
 *
 * <h3>Binding</h3>
 * <pre>{@code
 * Property<Integer> a = Property.of(5);
 * Property<Integer> b = Property.of(0);
 * b.bindTo(a);        // b follows a
 * a.set(10);          // b.get() == 10
 * b.unbind();         // break the link
 * }</pre>
 *
 * @param <T> the type of value this property holds
 */
public final class Property<T> {

    private T value;
    private final Signal.Value<T> onChange = new Signal.Value<>();
    private Connection bindingConnection;
    private boolean updating; // guard against infinite loops in bidirectional binding

    private Property(T initialValue) {
        this.value = initialValue;
    }

    /** Create a new property with the given initial value. */
    public static <T> Property<T> of(T initialValue) {
        return new Property<>(initialValue);
    }

    /** Get the current value. */
    public T get() {
        return value;
    }

    /**
     * Set a new value. If the new value differs from the current value
     * (via {@link Objects#equals}), fires the change signal.
     */
    public void set(T newValue) {
        if (Objects.equals(value, newValue)) return;
        value = newValue;
        onChange.emit(newValue);
    }

    /** The change signal. Connect to be notified when the value changes. */
    public Signal.Value<T> onChange() {
        return onChange;
    }

    /**
     * One-way binding: this property follows {@code source}.
     * When source changes, this property updates to match.
     * Only one binding at a time — calling again replaces the previous.
     */
    public void bindTo(Property<T> source) {
        unbind();
        bindingConnection = source.onChange().connect(newValue -> {
            if (!updating) {
                updating = true;
                try {
                    set(newValue);
                } finally {
                    updating = false;
                }
            }
        });
        // Sync initial value
        if (!Objects.equals(value, source.get())) {
            value = source.get();
            onChange.emit(value);
        }
    }

    /**
     * Bidirectional binding: both properties follow each other.
     * Changing either updates the other. Uses an {@code updating} guard
     * to prevent infinite loops.
     */
    public void bindBidirectional(Property<T> other) {
        bindTo(other);
        other.bindTo(this);
    }

    /** Disconnect all bindings. */
    public void unbind() {
        if (bindingConnection != null) {
            bindingConnection.disconnect();
            bindingConnection = null;
        }
    }
}
