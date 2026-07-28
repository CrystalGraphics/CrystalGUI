package com.crystalgui.style;


public enum StyleOrigin {
    DEFAULT(0),
    // The engine's own user-agent stylesheet (StyleSheet.DEFAULT / default.css) — the browser
    // analogue, giving every widget functional geometry so it works with no theme loaded.
    //
    // A distinct origin, NOT just "registered first", because origin is the FIRST thing
    // StyleSlot.compareTo weighs. Sharing STYLESHEET would lose two ways: sourceOrder restarts at 0
    // per sheet (so a big default.css out-ranks an author sheet's early rules), and specificity is
    // compared before sourceOrder anyway (so a more specific UA rule would beat a less specific
    // author one — e.g. `splitview.__vertical__ .__divider__` here vs a theme's plain
    // `splitview .__divider__`). Sitting below STYLESHEET means an author rule always wins, at any
    // specificity, exactly as a browser's UA sheet behaves.
    USER_AGENT(1),
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
