package com.crystalgui.core.data;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.function.IntUnaryOperator;

@Accessors(chain = true)
public class IntCacheCell {

    private int value;

    @Getter
    private boolean isDirty;

    private IntUnaryOperator calculator;

    public IntCacheCell setCalculator(IntUnaryOperator calc) {
        this.calculator = calc;
        this.isDirty = true;
        return this;
    }

    public IntCacheCell() {
        this(0);
    }

    public IntCacheCell(int initialValue) {
        this(initialValue, true);
    }

    public IntCacheCell(int initialValue, boolean isDirty) {
        set(initialValue, isDirty);
    }

    // Call this whenever the underlying source changes
    public IntCacheCell invalidate() {
        this.isDirty = true;
        return this;
    }

    public IntCacheCell set(int value) {
        this.set(value, false);
        return this;
    }

    private void set(int value, boolean setDirty) {
        this.value = value;
        this.isDirty = setDirty;
    }

    public int get() {
        return get(calculator);
    }

    // Pass a lambda that defines how to calculate the value
    public int get(IntUnaryOperator calculator) {
        if (isDirty) {
            value = calculator.applyAsInt(value);
            isDirty = false;
        }
        return value;
    }
}