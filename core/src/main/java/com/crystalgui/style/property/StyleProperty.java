package com.crystalgui.style.property;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("unchecked")
@Accessors(chain = true)
public class StyleProperty<VALUE> {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);
    
    public final int id;
    public final String name;
    public final Class<VALUE> type;
    public final VALUE initialValue;
    public final ValueParser<VALUE> valueParser;
    /** @see #write(Object) */
    @Nullable
    private ValueWriter<VALUE> valueWriter;
    @Setter
    @Getter
    private IValueInterpolator<VALUE> interpolator = IValueInterpolator.BINARY;
    // config
    @Setter @Getter
    private boolean allowTransition = false;
    /** Whether an element with no candidate at any origin for this property falls back to its
     * parent's computed value instead of {@link #initialValue} — e.g. {@code color} in real CSS.
     * Most properties (anything box-model/layout: width, margin, display, position, ...) do not
     * inherit and should leave this false. */
    @Setter @Getter
    private boolean inheritable = false;

    public StyleProperty(String name, Class<VALUE> type, VALUE initialValue, ValueParser<VALUE> valueParser) {
        this.id = ID_COUNTER.getAndIncrement();
        this.name = name;
        this.type = type;
        this.initialValue = initialValue;
        this.valueParser = valueParser;
    }

    public static <T> StyleProperty<T> of(String name, Class<T> type, T initialValue, ValueParser<T> valueParser) {
        return new StyleProperty<>(name, type, initialValue, valueParser);
    }

    public static <T> StyleProperty<T> of(String name, @Nonnull T initialValue, ValueParser<T> valueParser) {
        return new StyleProperty<>(name, (Class<T>) initialValue.getClass(), initialValue, valueParser);
    }


    /**
     * <b>This value as CSS its own {@link #valueParser} reads back</b> — the half the engine was missing.
     *
     * <pre>{@code
     * String css = StylePropertyRegistry.COLOR.write(0xFF3574F0);   // "#3574F0FF"
     * }</pre>
     *
     * <p>A property has always known how to read its own CSS, because that is how a stylesheet becomes
     * anything. Nothing knew how to write it, so anything that had to MOVE a value — the wire, the
     * clipboard — needed a codec hand-written against the value's Java type, and fifteen properties
     * never got one. They failed at the moment of use, which is a strange way to learn that a border
     * cannot be copied.</p>
     *
     * <p><b>On the property and not on the type</b>, because the type is not enough to know: {@code color}
     * and {@code z-index} are both {@code Integer} and only one of them writes {@code #RRGGBBAA}. The
     * parser lives here for the same reason, and the two have to agree — so they sit together, and
     * {@code StyleValueRoundTripTest} holds every registered property to writing something its own parser
     * reads back as an equal value.</p>
     *
     * <p>The default is {@link String#valueOf}, which is already right for the numbers and booleans and
     * wrong for everything with a syntax. A subclass overrides it, or a property declared inline states
     * one with {@link #setWriter}.</p>
     */
    public String write(VALUE value) {
        return valueWriter != null ? valueWriter.write(value) : String.valueOf(value);
    }

    /** States how this property writes itself, for one declared without a subclass. @see #write */
    public StyleProperty<VALUE> setWriter(ValueWriter<VALUE> writer) {
        this.valueWriter = writer;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        StyleProperty<?> property = (StyleProperty<?>) o;
        return id == property.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return name;
    }


    // ── The property-listener API is GONE, with the engine it existed for ───────────────────────
    //
    // `notifyListeners(UIElement, ...)`, `addListener` and `StyleChangeListener`. Retyping them to
    // `Styleable` -- the interface both engines implement -- was tried and did not fit: every
    // listener registered against them called something only a `UIElement` has
    // (`onResizeModeChanged`, `invalidateFocusableChain`, `TaffyBridge::set*`). The mechanism WAS the
    // old cascade's bridge into Taffy, which is why nothing replaced it: `BoxStyle` reads
    // `ComputedStyle` directly, and the two changes that still need a hook go through
    // `UIElement.computedChanged`.
    //
    // Worth a note rather than a silent deletion because this sits in the SHARED `style/` package --
    // one of the few places where "shared" and "survives" came apart.

    @FunctionalInterface
    public interface ValueParser<T> {
        StyleValue<T> parse(String rawValue);
    }

    /** The other direction. @see StyleProperty#write(Object) */
    @FunctionalInterface
    public interface ValueWriter<T> {
        String write(T value);
    }

}
