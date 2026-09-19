package com.crystalgui.style.property.visual.stacking;

/**
 * CSS Compositing's {@code isolation}: {@link #ISOLATE} makes a box a stacking context and does nothing else — the
 * way to keep a child's negative {@code z-index} above its parent's background without an opacity or a transform.
 */
public enum Isolation {
    AUTO,
    ISOLATE
}
