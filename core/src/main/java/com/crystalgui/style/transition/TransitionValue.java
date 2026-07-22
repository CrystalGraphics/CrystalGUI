package com.crystalgui.style.transition;

import com.crystalgui.style.property.StyleValue;

import java.util.List;

/** Parses the raw {@code transition} shorthand string into {@link TransitionSpec}s (see there for grammar). */
public class TransitionValue extends StyleValue<List<TransitionSpec>> {
    public TransitionValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected List<TransitionSpec> doCompute(String rawValue) {
        return TransitionSpec.parse(rawValue);
    }
}
