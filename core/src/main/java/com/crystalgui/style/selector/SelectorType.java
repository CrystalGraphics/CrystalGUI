package com.crystalgui.style.selector;

/** Kind of one simple-selector part within a {@link CompoundSelector}, with real CSS specificity weights. */
public enum SelectorType {
    UNIVERSAL(0),
    TYPE(1),
    CLASS(10),
    PSEUDO_CLASS(10),
    ID(100),
    ;

    public final int weight;

    SelectorType(int weight) {
        this.weight = weight;
    }
}
