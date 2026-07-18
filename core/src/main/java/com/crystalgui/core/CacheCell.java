package com.crystalgui.core;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.function.Function;

@Accessors(chain = true)
public class CacheCell<T> {
    private T value;
    @Getter
    private boolean isDirty;
    @Setter
    private Function<T, T> calculator;

    public CacheCell(T initialValue) {
        this(initialValue, true);
    }

    public CacheCell(T initialValue, boolean isDirty) {
        this.value = initialValue;
        this.isDirty = isDirty;
    }

    // Call this whenever the underlying source changes
    public void invalidate() {
        this.isDirty = true;
    }

    public void set(T value) {
        this.set(value, false);
    }

    private void set(T value, boolean setDirty) {
        this.value = value;
        this.isDirty = setDirty;
    }

    public T get() {
        return get(calculator);
    }

    // Pass a supplier (a lambda) that defines how to calculate the value
    public T get(Function<T, T> calculator) {
        if (isDirty) {
            value = calculator.apply(value);
            isDirty = false;
        }
        return value;
    }

}