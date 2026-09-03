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
    // `UINode.computedChanged`.
    //
    // Worth a note rather than a silent deletion because this sits in the SHARED `style/` package --
    // one of the few places where "shared" and "survives" came apart.

    @FunctionalInterface
    public interface ValueParser<T> {
        StyleValue<T> parse(String rawValue);
    }

}
