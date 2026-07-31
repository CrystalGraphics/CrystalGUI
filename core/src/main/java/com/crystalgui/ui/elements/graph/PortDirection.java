package com.crystalgui.ui.elements.graph;

/**
 * Which side of a node a port lives on, and therefore which end of a wire it can be.
 *
 * <p>Not a boolean, because every read of it is a question about meaning — "is this an input?" — and
 * {@code isLeft} would be a question about layout that happens to answer it today.</p>
 */
public enum PortDirection {
    /** Left side. Accepts at most one wire. */
    INPUT,
    /** Right side. May feed any number of wires. */
    OUTPUT;

    public boolean isInput() {
        return this == INPUT;
    }

    public boolean isOutput() {
        return this == OUTPUT;
    }

    /** The direction a wire from this one must land on. A wire always joins one of each. */
    public PortDirection opposite() {
        return this == INPUT ? OUTPUT : INPUT;
    }
}
