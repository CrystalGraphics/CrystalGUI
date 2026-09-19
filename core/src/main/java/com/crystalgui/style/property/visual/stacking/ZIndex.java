package com.crystalgui.style.property.visual.stacking;

/**
 * A {@code z-index}: {@link #AUTO}, or a number.
 *
 * <pre>{@code
 * ZIndex.AUTO                // stacks with its stacking context's other content, creating none of its own
 * ZIndex.of(2)               // a stacking context, painted over its context's `auto` and lower
 * z.orZero()                 // where a box sits among its context's z-ordered boxes
 * }</pre>
 *
 * <p>A number makes the box a stacking context whether or not it is positioned — CSS's rule for a flex or grid
 * item, which every box here is. {@code auto} is the initial value and is not zero: a {@code z-index: 0} box is a
 * stacking context and an {@code auto} one is not.</p>
 */
public record ZIndex(boolean auto, int value) {

    public static final ZIndex AUTO = new ZIndex(true, 0);

    public static ZIndex of(int value) {
        return new ZIndex(false, value);
    }

    /** The number, or zero for {@code auto}: where the box sits among its stacking context's z-ordered boxes. */
    public int orZero() {
        return auto ? 0 : value;
    }

    @Override
    public String toString() {
        return auto ? "auto" : Integer.toString(value);
    }
}
