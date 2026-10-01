package com.crystalgui.style.property;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.CssComments;

import javax.annotation.Nullable;
import java.util.Objects;

public abstract class StyleValue<T> {
    public final String rawValue;
    private volatile T computedValue;
    private volatile boolean computed = false;

    public StyleValue(String rawValue) {
        this.rawValue = rawValue;
    }

    /**
     * Computes the value based on the raw input value if it has not been computed already.
     * The computation process is defined by the implementation of the {@code doCompute(String)}
     * method in subclasses. If an exception occurs during the computation, the computed value
     * is set to {@code null}.
     *
     * @return the computed value of type {@code T}, or {@code null} if an exception occurs during computation
     */
    /**
     * The parsed value, computed on first use and shared after. Safe from any thread: a sheet warms its values on a
     * worker while a style pass may ask for the same one, and both get the one instance -- a drawable compares by
     * identity, so a second copy would read as a changed style.
     */
    public T compute() {
        if (!computed) computeOnce();
        return computedValue;
    }

    private synchronized void computeOnce() {
        if (computed) return;
        try {
            // A COMMENT IS WHITESPACE TO A TOKENIZER, and CSS drops them before a value is ever parsed, so a
            // value may carry one. A sheet's own parser already stripped them; this is for a value set
            // directly, where the text reaches the parser as written -- the UI builder switches one layer of
            // a stack off by commenting it INSIDE the value, and inline there is no sheet to strip it.
            computedValue = doCompute(CssComments.has(rawValue) ? CssComments.strip(rawValue) : rawValue);
        } catch (Exception e) {
            CrystalGuiCore.LOGGER.warn("Failed to parse style value '{}': {}", rawValue, e.getMessage());
            computedValue = null;
        }
        computed = true;
    }

    protected abstract @Nullable T doCompute(String rawValue);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StyleValue<?> that)) return false;
        return Objects.equals(rawValue, that.rawValue);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rawValue);
    }
}