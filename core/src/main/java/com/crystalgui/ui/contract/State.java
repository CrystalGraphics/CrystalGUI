package com.crystalgui.ui.contract;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;

/**
 * One piece of a widget's <b>authored</b> state, declared once as a {@code static final} on the widget
 * class. {@code plan_ui_rewrite.md} M1.
 *
 * <h3>What this replaces</h3>
 *
 * <p>A hand-written {@code writeState}/{@code readState} pair per widget — twelve of them, each a place
 * to forget a key, transpose an order, or write something that cannot be read back. The pair was also
 * <em>the only</em> statement of what a widget carries, so nothing else could ask: the description
 * codec, the coverage test and the server's validation each had to know a widget by name. A slot is
 * one declaration all of them read.</p>
 *
 * <h3>Authored state only — the same line {@code writeState} always drew</h3>
 *
 * <p>Never declare a slot for pressed, hovered, focused, caret position or scroll offset. Those belong
 * to whichever side the user's pointer is on, and a server pushing them fights the person using the UI.
 * The test is the document/view boundary the undo stack already draws: if reloading ought to give it
 * back, it is state; if it is only how you are <em>looking</em> at the thing, it is not.</p>
 *
 * <h3>Declaration order is apply order, and that is load-bearing</h3>
 *
 * <p>Several widgets have ordered state. {@code Slider} must take its range before its value or the
 * value is clamped against the old bounds; {@code ColorSelector} must take its mode first;
 * {@code Dropdown} must have its options before an index into them means anything. The hand-written
 * {@code readState} methods encoded that in statement order, invisibly. A contract encodes it in
 * declaration order, which is the same thing said where it can be seen — and
 * {@link WidgetContract#read} applies slots in the order they were declared.</p>
 *
 * @param <W> the widget type
 * @param <V> the value type
 */
public final class State<W extends UIElement, V> {

    private final String key;
    private final StateType<V> type;
    private final Function<W, V> getter;
    private final BiConsumer<W, V> setter;
    private final V fallback;
    @Nullable
    private final V omitWhen;
    @Nullable
    private final UnaryOperator<V> sanitize;

    private State(String key, StateType<V> type, Function<W, V> getter, BiConsumer<W, V> setter,
                  V fallback, @Nullable V omitWhen, @Nullable UnaryOperator<V> sanitize) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = Objects.requireNonNull(setter, "setter");
        this.fallback = fallback;
        this.omitWhen = omitWhen;
        this.sanitize = sanitize;
    }

    /**
     * A slot.
     *
     * @param key      the wire name. <b>Part of the format</b> — changing one changes the content hash
     *                 of every description carrying this widget
     * @param fallback what {@link #read} uses when the key is absent, which is also what a decoder sees
     *                 for a widget written by an older peer that did not have this slot
     */
    public static <W extends UIElement, V> State<W, V> of(
            String key, StateType<V> type, Function<W, V> getter, BiConsumer<W, V> setter, V fallback) {
        return new State<>(key, type, getter, setter, fallback, null, null);
    }

    /**
     * A copy of this slot that writes nothing when the value equals {@code value}.
     *
     * <p>Not an optimisation — it is what keeps a default-valued widget's state <em>absent</em> rather
     * than present-and-default, which is the difference between two descriptions hashing the same and
     * not. The hand-written methods spelled this {@code putStringIfNot} / {@code putBoolIfNot}.</p>
     */
    public State<W, V> omittedWhen(V value) {
        return new State<>(key, type, getter, setter, fallback, value, sanitize);
    }

    /**
     * A copy of this slot that passes an incoming value through {@code sanitize} before applying it.
     *
     * <p>For a value arriving from <b>the far side</b>, which is why it exists at all: a peer is not
     * trustworthy, and "the widget's setter will cope" is a hope rather than a guarantee. M3 makes this
     * the server-side validation path for reported events; here it already guards {@link #read}.</p>
     */
    public State<W, V> sanitizedBy(UnaryOperator<V> sanitize) {
        return new State<>(key, type, getter, setter, fallback, omitWhen, sanitize);
    }

    public String key() {
        return key;
    }

    public V fallback() {
        return fallback;
    }

    /** This slot's value on {@code widget}. */
    public V read(W widget) {
        return getter.apply(widget);
    }

    /** Writes this slot's value, unless it is the omitted-when value. */
    public <T> void write(W widget, StateMap<T> out) {
        V value = getter.apply(widget);
        if (omitWhen != null && Objects.equals(omitWhen, value)) return;
        type.put(out, key, value);
    }

    /** Applies this slot from {@code in}, sanitizing first if this slot asked for it. */
    public <T> void apply(W widget, StateMap<T> in) {
        V value = type.get(in, key, fallback);
        if (sanitize != null) value = sanitize.apply(value);
        setter.accept(widget, value);
    }

    @Override
    public String toString() {
        return "State[" + key + "]";
    }
}
