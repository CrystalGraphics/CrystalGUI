package com.crystalgui.ui.input;

import com.crystalgui.ui.UIElement;
import org.joml.Vector2f;

/**
 * Minimal source-driven positional drag tracking — NOT general drag-and-drop (no drop-target
 * concept, no DRAG_ENTER/LEAVE). One active drag at a time, matching CrystalGUI's single-cursor
 * input model. Owned 1:1 by a {@link UIInputHandler}; reach it via
 * {@code element.getAttachedWindow().getInputHandler().getDragController()}.
 *
 * <h3>Coordinate space</h3>
 * <p>Every coordinate handed to a {@link DragListener} is in the <b>drag source's local space</b>,
 * converted here via {@link UIElement#screenToLocal}. Raw input is in physical pixels while element
 * geometry is in logical units — at the default {@code uiScale} of 2 they differ by a factor of two —
 * so a listener comparing raw coordinates against layout values would be wrong everywhere but the
 * top-left corner. Converting once, here, keeps every consumer correct by default.</p>
 *
 * <p>Deltas are the difference of two <em>separately converted</em> endpoints, not a converted
 * difference: the transform carries translation, which would corrupt a vector transformed directly.</p>
 */
public final class UIDragController {

    public interface DragListener {
        /** Fired once per frame while the drag is active (ticked from {@link UIInputHandler#endFrame()},
         * unconditionally — matches this engine's existing immediate-mode philosophy of recomputing
         * freely each frame rather than gating on movement). All coordinates are in the drag
         * source's local space. */
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
     * if they need one.
     *
     * <p>Pass the <b>raw</b> pointer position (i.e. straight from {@code MouseEvent.getPosition()});
     * the conversion to the source's local space happens here.</p> */
    public void startDrag(UIElement source, float mouseX, float mouseY, DragListener listener) {
        this.source = source;
        this.listener = listener;
        Vector2f start = source.screenToLocal(mouseX, mouseY);
        this.startX = start.x();
        this.startY = start.y();
    }

    public boolean isDragging() {
        return source != null;
    }

    public UIElement getSource() {
        return source;
    }

    void tick(float mouseX, float mouseY) {
        if (listener == null) return;
        Vector2f local = source.screenToLocal(mouseX, mouseY);
        listener.onDragUpdate(local.x(), local.y(), startX, startY, local.x() - startX, local.y() - startY);
    }

    void endDrag(float mouseX, float mouseY) {
        if (listener != null) {
            Vector2f local = source.screenToLocal(mouseX, mouseY);
            listener.onDragEnd(local.x(), local.y());
        }
        source = null;
        listener = null;
    }
}
