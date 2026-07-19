package com.crystalgui.style.property;

import com.crystalgui.ui.UIElement;
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
    private final List<StyleChangeListener<VALUE>> styleChangeListeners = new ArrayList<>();
    @Setter
    @Getter
    private IValueInterpolator<VALUE> interpolator = IValueInterpolator.BINARY;
    // config
    @Setter @Getter
    private boolean allowTransition = false;

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


    public void notifyListeners(UIElement element, @Nullable VALUE oldVal, @Nullable VALUE newVal) {
        for (var listener : styleChangeListeners) {
            listener.onComputedChange(element, this, oldVal, newVal);
        }
    }

    public StyleProperty<VALUE> addListener(StyleChangeListener<VALUE> listener) {
        styleChangeListeners.add(listener);
        return this;
    }

    @FunctionalInterface
    public interface ValueParser<T> {
        StyleValue<T> parse(String rawValue);
    }

    @FunctionalInterface
    public interface StyleChangeListener<T> {
        void onComputedChange(UIElement element, StyleProperty<T> p, @Nullable T oldVal, @Nullable T newVal);
    }
}
