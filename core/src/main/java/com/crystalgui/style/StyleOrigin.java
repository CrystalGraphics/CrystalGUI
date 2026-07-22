package com.crystalgui.style;


public enum StyleOrigin {
    DEFAULT(0),
    STYLESHEET(2),
    INLINE(3),
    IMPORTANT(4),
    // Ranks above IMPORTANT: transitions/animations must be able to override an !important value
    // mid-flight, matching CSS Cascade Level 4/5 semantics. See stylesheet+transitions plan, Design
    // Decision 1 — without this, a transition triggered on an !important-origin property change would
    // tick every frame computing values that computeCandidateSlot never actually selects.
    ANIMATION(5),
    ;
    public final int priority;

    StyleOrigin(int priority) {
        this.priority = priority;
    }
}
