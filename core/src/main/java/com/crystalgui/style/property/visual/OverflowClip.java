package com.crystalgui.style.property.visual;

public enum OverflowClip {
    /**
     * No clipping.
     */
    NONE,
    /**
     * Clip the element to the content bounds.
     */
    SCISSOR,
    /**
     * Mask the element with the given mask {@link com.lowdragmc.lowdraglib2.gui.ui.style.PropertyRegistry#MASK}.
     */
    MASK;

    public boolean isClipped() {
        return this != NONE;
    }

    public boolean isMask() {
        return this == MASK;
    }

    public boolean isScissor() {
        return this == SCISSOR;
    }
}