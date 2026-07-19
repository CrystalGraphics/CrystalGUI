package com.crystalgui.core.data;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.function.LongUnaryOperator;

@Accessors(chain = true)
public class LongCacheCell {

    private long value;

    @Getter
    private boolean isDirty;

    private LongUnaryOperator calculator;

    public LongCacheCell setCalculator(LongUnaryOperator calc) {
        this.calculator = calc;
        this.isDirty = true;
        return this;
    }

    public LongCacheCell() {
        this(0L);
    }

    public LongCacheCell(long initialValue) {
        this(initialValue, true);
    }

    public LongCacheCell(long initialValue, boolean isDirty) {
        set(initialValue, isDirty);
    }

    // Call this whenever the underlying source changes
    public LongCacheCell invalidate() {
        this.isDirty = true;
        return this;
    }

    public LongCacheCell set(long value) {
        this.set(value, false);
        return this;
    }

    private void set(long value, boolean setDirty) {
        this.value = value;
        this.isDirty = setDirty;
    }

    public long get() {
        return get(calculator);
    }

    // Pass a lambda that defines how to calculate the value
    public long get(LongUnaryOperator calculator) {
        if (isDirty) {
            value = calculator.applyAsLong(value);
            isDirty = false;
        }
        return value;
    }
}