package com.crystalgui.style.property.visual.stacking;

/**
 * The CSS {@code will-change} keywords this engine acts on: what a compositor may change on a box between frames its
 * document draws. {@link #TRANSFORM} and {@link #OPACITY} make the box a stacking context, as CSS says.
 *
 * <pre>{@code
 * window { will-change: transform; }            // recorded under a node of its own: a compositor moves it
 * .fader { will-change: opacity, transform; }
 * }</pre>
 */
public enum WillChange {
    /** Recorded in its own space under a spatial node, which a compositor moves. */
    TRANSFORM,
    /** A stacking context; its layer composite, when it has one, is an effect node a compositor fades. */
    OPACITY,
    /** Accepted for the web's sake: every scrolling box's content is already under a node of its own. */
    SCROLL_POSITION
}
