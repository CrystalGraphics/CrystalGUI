package com.crystalgui.style.property.visual;

/**
 * CSS {@code scroll-behavior} — whether a scroll jumps straight to its destination or eases toward it.
 *
 * <p>Applies to wheel and programmatic scrolling. Dragging a scrollbar thumb is always instant
 * regardless: the thumb has to stay under the cursor, the same reason {@code Slider} and
 * {@code SplitView} refuse to animate their drags.</p>
 */
public enum ScrollBehavior {
    /** Jump straight there. CSS's default. */
    AUTO,
    /** Ease toward the destination over a few frames. */
    SMOOTH
}
