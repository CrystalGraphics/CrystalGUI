package com.crystalgui.style.selector;

/** Kind of one simple-selector part within a {@link CompoundSelector}, with real CSS specificity weights. */
public enum SelectorType {
    UNIVERSAL(0),
    TYPE(1),
    /**
     * A pseudo-<b>element</b> — {@code ::highlight(name)}, and so far only that.
     *
     * <p>Weight 1, the same as {@link #TYPE}, because CSS Selectors 4 counts pseudo-elements in the
     * <em>type</em> component of specificity rather than the class component. Easy to get wrong by
     * analogy with {@link #PSEUDO_CLASS}, which really is 10.</p>
     */
    PSEUDO_ELEMENT(1),
    CLASS(10),
    PSEUDO_CLASS(10),
    ID(100),
    ;

    public final int weight;

    SelectorType(int weight) {
        this.weight = weight;
    }
}
