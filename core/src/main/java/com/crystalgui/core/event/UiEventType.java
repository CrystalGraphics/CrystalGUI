package com.crystalgui.core.event;

/**
 * All UI event types for phases 0–3.
 *
 * <p>Each type knows whether it bubbles through the DOM tree or is
 * dispatched directly to the target only.</p>
 */
public enum UiEventType {

    // ── Pointer events (bubbling) ──
    MOUSE_DOWN(true),
    MOUSE_UP(true),
    MOUSE_MOVE(true),
    MOUSE_WHEEL(true),
    CLICK(true),
    DOUBLE_CLICK(true),

    // ── Pointer events (direct-target only) ──
    MOUSE_ENTER(false),
    MOUSE_LEAVE(false),

    // ── Keyboard events (bubbling) ──
    KEY_DOWN(true),
    KEY_UP(true),
    KEY_TYPED(true),

    // ── Focus events (direct-target only) ──
    FOCUS_IN(false),
    FOCUS_OUT(false);

    
    private final boolean bubbles;

    UiEventType(boolean bubbles) {
        this.bubbles = bubbles;
    }

    /** Returns true if this event type propagates through capture/bubble phases. */
    public boolean bubbles() {
        return bubbles;
    }
}
