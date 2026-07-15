package com.crystalgui.style;


public enum StyleOrigin {
    DEFAULT(0),
    STYLESHEET(2),
    INLINE(3),
    ANIMATION(4),
    IMPORTANT(5),
    ;
    public final int priority;

    StyleOrigin(int priority) {
        this.priority = priority;
    }
}
