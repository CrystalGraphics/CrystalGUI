package com.crystalgui.ui.contract;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import com.crystalgui.serialization.StateMap;

/**
 * One piece of a widget's <b>authored</b> state, declared once as a {@code static final} on the widget
 * class. {@code plan/engine-rewrite.md} M1.
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
public final class State<W, V> {

    private final String key;
    private final StateType<V> type;
    private final Function<W, V> getter;
    private final BiConsumer<W, V> setter;
    private final V fallback;
    @Nullable
    private final V omitWhen;
    @Nullable
    private final UnaryOperator<V> sanitize;

    @Nullable
    private final Hint hint;

    private State(String key, StateType<V> type, Function<W, V> getter, BiConsumer<W, V> setter,
                  V fallback, @Nullable V omitWhen, @Nullable UnaryOperator<V> sanitize, @Nullable Hint hint) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = Objects.requireNonNull(setter, "setter");
        this.fallback = fallback;
        this.omitWhen = omitWhen;
        this.sanitize = sanitize;
        this.hint = hint;
    }

    /**
     * A slot.
     *
     * @param key      the wire name. <b>Part of the format</b> — changing one changes the content hash
     *                 of every description carrying this widget
     * @param fallback what {@link #read} uses when the key is absent, which is also what a decoder sees
     *                 for a widget written by an older peer that did not have this slot
     */
    public static <W, V> State<W, V> of(
            String key, StateType<V> type, Function<W, V> getter, BiConsumer<W, V> setter, V fallback) {
        return new State<>(key, type, getter, setter, fallback, null, null, null);
    }

    /**
     * A copy of this slot that writes nothing when the value equals {@code value}.
     *
     * <p>Not an optimisation — it is what keeps a default-valued widget's state <em>absent</em> rather
     * than present-and-default, which is the difference between two descriptions hashing the same and
     * not. The hand-written methods spelled this {@code putStringIfNot} / {@code putBoolIfNot}.</p>
     */
    public State<W, V> omittedWhen(V value) {
        return new State<>(key, type, getter, setter, fallback, value, sanitize, hint);
    }

    /**
     * A copy of this slot that passes an incoming value through {@code sanitize} before applying it.
     *
     * <p>For a value arriving from <b>the far side</b>, which is why it exists at all: a peer is not
     * trustworthy, and "the widget's setter will cope" is a hope rather than a guarantee. M3 makes this
     * the server-side validation path for reported events; here it already guards {@link #read}.</p>
     */
    public State<W, V> sanitizedBy(UnaryOperator<V> sanitize) {
        return new State<>(key, type, getter, setter, fallback, omitWhen, sanitize, hint);
    }

    /**
     * A copy of this slot carrying what an editor needs beyond the value's type.
     *
     * <pre>{@code
     * State.of("color", StateTypes.INT, ColorSelector::getColor, ColorSelector::setColor, 0xFFFFFFFF)
     *         .described(State.Hint.COLOR);
     * State.of("fraction", StateTypes.FLOAT, ProgressBar::fraction, ProgressBar::setFraction, -1f)
     *         .described(State.Hint.range(-1f, 1f));
     * }</pre>
     *
     * <p>Optional, and read only by editors: a slot with no hint still gets a control chosen from
     * {@link StateType#valueClass()}. Nothing on the wire changes.</p>
     */
    public State<W, V> described(Hint hint) {
        Objects.requireNonNull(hint, "hint");
        // A description said before the hint survives it, so the two can be declared in either order.
        if (hint.description() == null && this.hint != null && this.hint.description() != null) {
            hint = hint.describedAs(this.hint.description());
        }
        return new State<>(key, type, getter, setter, fallback, omitWhen, sanitize, hint);
    }

    /**
     * A copy of this slot saying what it does, for an editor's hint.
     *
     * <pre>{@code
     * State.of("step", StateTypes.FLOAT, Slider::getStep, Slider::setStep, 0f)
     *         .describedAs("The increment the value snaps to; 0 is continuous.");
     * }</pre>
     */
    public State<W, V> describedAs(String text) {
        return new State<>(key, type, getter, setter, fallback, omitWhen, sanitize,
                (hint == null ? Hint.NONE : hint).describedAs(text));
    }

    /** What this slot's editor was told, or null. */
    @Nullable
    public Hint hint() {
        return hint;
    }

    /** The value's type. */
    public StateType<V> type() {
        return type;
    }

    /**
     * Editor metadata for a slot: a display label, a numeric range, and what an {@code int} or a string
     * really is.
     *
     * <pre>{@code
     * State.Hint.COLOR                          // an ARGB int, or an int[] of them
     * State.Hint.range(0f, 1f)                  // a slider rather than a number field
     * State.Hint.MULTILINE.label("Body text")   // hints compose
     * }</pre>
     *
     * @param label     shown instead of the key, or null
     * @param min       the low end of a range, or NaN for none
     * @param max       the high end of a range, or NaN for none
     * @param color     an integer value is ARGB
     * @param multiline a string value takes several lines
     * @param asset     the kind of asset a string names ({@code "icon"}, {@code "sprite"}), or null
     * @param spanMin   the key of a sibling slot holding this value's low bound, or null
     * @param spanMax   the key of a sibling slot holding its high bound, or null
     * @param description what the slot does, for a hint, or null
     */
    public record Hint(@Nullable String label, float min, float max, boolean color, boolean multiline,
                       @Nullable String asset, @Nullable String spanMin, @Nullable String spanMax,
                       @Nullable String description) {

        public static final Hint NONE = new Hint(null, Float.NaN, Float.NaN, false, false, null, null, null, null);

        public static final Hint COLOR = NONE.withColor();

        public static final Hint MULTILINE = new Hint(null, Float.NaN, Float.NaN, false, true, null, null, null, null);

        public static Hint range(float min, float max) {
            return new Hint(null, min, max, false, false, null, null, null, null);
        }

        public static Hint asset(String kind) {
            return new Hint(null, Float.NaN, Float.NaN, false, false, kind, null, null, null);
        }

        /**
         * A value whose scale is the span between two SIBLING slots — a slider's value between its own min
         * and max. An editor scrubs it at a hundredth of that span, read when the drag starts.
         */
        public static Hint spanOf(String minKey, String maxKey) {
            return new Hint(null, Float.NaN, Float.NaN, false, false, null, minKey, maxKey, null);
        }

        public Hint label(String value) {
            return new Hint(value, min, max, color, multiline, asset, spanMin, spanMax, description);
        }

        public Hint withColor() {
            return new Hint(label, min, max, true, multiline, asset, spanMin, spanMax, description);
        }

        public Hint describedAs(String text) {
            return new Hint(label, min, max, color, multiline, asset, spanMin, spanMax, text);
        }

        public boolean hasRange() {
            return !Float.isNaN(min) && !Float.isNaN(max);
        }
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

    /**
     * Writes {@code value} straight onto {@code widget}, sanitizer applied.
     *
     * <p>The symmetric partner of {@link #read}, and what a projection uses: the wire path goes through
     * {@link #apply} because it has a {@code StateMap} to decode, and a model has a value already.</p>
     */
    public void set(W widget, V value) {
        setter.accept(widget, sanitize == null ? value : sanitize.apply(value));
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
