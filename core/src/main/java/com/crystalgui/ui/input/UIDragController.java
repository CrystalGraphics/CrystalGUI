package com.crystalgui.ui.input;

import com.crystalgui.ui.UIElement;

/**
 * Minimal source-driven positional drag tracking — NOT general drag-and-drop (no drop-target
 * concept, no DRAG_ENTER/LEAVE). One active drag at a time, matching CrystalGUI's single-cursor
 * input model. Owned 1:1 by a {@link UIInputHandler}; reach it via
 * {@code element.getAttachedWindow().getInputHandler().getDragController()}.
 */
public final class UIDragController {

    public interface DragListener {
        /** Fired once per frame while the drag is active (ticked from {@link UIInputHandler#endFrame()},
         * unconditionally — matches this engine's existing immediate-mode philosophy of recomputing
         * freely each frame rather than gating on movement). */
        void onDragUpdate(float mouseX, float mouseY, float startX, float startY, float deltaX, float deltaY);

        /** Fired once when the drag ends (button-0 release), wherever the mouse is at that point —
         * not gated on being back over the drag source. */
        default void onDragEnd(float mouseX, float mouseY) {}
    }

    private UIElement source;
    private DragListener listener;
    private float startX, startY;

    /** Called by the drag source's own {@code onMouseDown} listener, after it has already decided
     * (via its own hit-test / hover state) that this press should start a drag rather than an
     * ordinary click. No built-in drag threshold — callers do their own small-px-delta check first
     * if they need one. */
    public void startDrag(UIElement source, float mouseX, float mouseY, DragListener listener) {
        this.source = source;
        this.listener = listener;
        this.startX = mouseX;
        this.startY = mouseY;
    }

    public boolean isDragging() {
        return source != null;
    }

    public UIElement getSource() {
        return source;
    }

    void tick(float mouseX, float mouseY) {
        if (listener != null) listener.onDragUpdate(mouseX, mouseY, startX, startY, mouseX - startX, mouseY - startY);
    }

    void endDrag(float mouseX, float mouseY) {
        if (listener != null) listener.onDragEnd(mouseX, mouseY);
        source = null;
        listener = null;
    }
}
