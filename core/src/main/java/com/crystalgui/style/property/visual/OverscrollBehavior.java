package com.crystalgui.style.property.visual;

/**
 * CSS {@code overscroll-behavior} — whether a scroll view at its end passes the wheel to the one around it.
 *
 * <pre>{@code
 * .__layer-list__ { overscroll-behavior: contain; }   // spinning past the last row never moves the panel
 * }</pre>
 *
 * <p>Only a view that can scroll on that axis holds the wheel: one whose content fits is not a scroller yet, and
 * the wheel passes through it as CSS says.</p>
 */
public enum OverscrollBehavior {
    /** Chains to the enclosing scroll view at the end. CSS's default. */
    AUTO,
    /** Keeps the wheel at the end. */
    CONTAIN,
    /** As {@link #CONTAIN}: this engine has no bounce or pull-to-refresh for the two to differ on. */
    NONE
}
