package com.crystalgui.core.data;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import javax.annotation.Nullable;
import java.util.function.Function;

@Accessors(chain = true)
public class CacheCell<T> {
    @Nullable
    private T value;
    @Getter
    private boolean isDirty;

    private Function<T, T> calculator;

    public CacheCell() {
        this(null);
    }

    public CacheCell(T initialValue) {
        this(initialValue, true);
    }

    public CacheCell(T initialValue, boolean isDirty) {
        set(initialValue, isDirty);
    }

    public CacheCell<T> setCalculator(Function<T, T> calc) {
        this.calculator = calc;
        this.isDirty = true;
        return this;
    }

    // Call this whenever the underlying source changes
    public CacheCell<T> invalidate() {
        this.isDirty = true;
        return this;
    }

    public CacheCell<T> set(T value) {
        this.set(value, false);
        return this;
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