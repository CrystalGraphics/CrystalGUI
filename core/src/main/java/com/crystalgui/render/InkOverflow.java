package com.crystalgui.render;

/**
 * How far a node draws outside its own border box, in the node's own space.
 *
 * <p>What {@code UIElement.inkOverflow()} answers, and the one input to a box's ink bounds
 * the cascade cannot supply: an outline or a mask offset is a style value the box tree reads for
 * itself, but a widget painting a handle, a glow or a wire by hand is the only thing that knows.</p>
 *
 * <pre>{@code
 * // A selection frame with 8px handles centred on its corners.
 * @Override public InkOverflow inkOverflow() { return InkOverflow.uniform(4f); }
 *
 * // A drop shadow 12px below and 4px to each side.
 * @Override public InkOverflow inkOverflow() { return new InkOverflow(4f, 0f, 4f, 12f); }
 *
 * // A canvas's wire layer: a box at the plane's origin drawing wherever the nodes are. It culls per wire itself.
 * @Override public InkOverflow inkOverflow() { return InkOverflow.UNBOUNDED; }
 * }</pre>
 *
 * <p>Margins are positive outward and CSS-ordered. A negative one is not an error and not useful —
 * ink bounds always contain the border box, so shrinking is not expressible.</p>
 */
public record InkOverflow(float left, float top, float right, float bottom) {

    /** A node that paints nothing outside its box. */
    public static final InkOverflow NONE = new InkOverflow(0f, 0f, 0f, 0f);

    /**
     * A node whose box says nothing about where it draws, so the painter never culls it by its box. Finite, since the
     * bounds go through matrices; an ancestor's {@code overflow} still clips them.
     */
    public static final InkOverflow UNBOUNDED = uniform(1e6f);

    /** The same margin on all four sides. */
    public static InkOverflow uniform(float margin) {
        return margin == 0f ? NONE : new InkOverflow(margin, margin, margin, margin);
    }

    /** Whether this adds nothing, so a caller can skip the arithmetic. */
    public boolean isZero() {
        return left == 0f && top == 0f && right == 0f && bottom == 0f;
    }
}
