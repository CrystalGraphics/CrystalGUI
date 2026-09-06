package com.crystalgui.widget.surface.mode;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.canvas.WorldRect;
import com.crystalgui.widget.surface.Surface;
import com.crystalgui.widget.surface.SurfacePolicy;
import com.crystalgui.widget.surface.edit.Edits;

/**
 * Dragging what is selected: every item moves by the same delta, and the whole gesture is one undo step.
 *
 * <p>Started by the Select tool on a press that landed on an item. What a move <em>writes</em> is the
 * consumer's — {@link SurfacePolicy#moveEdit} turns the finished gesture into one edit, and a consumer
 * whose moves are not undoable returns null.</p>
 *
 * <p>Two things here are easy to get wrong and both were learned in the graph. Every item moves by the
 * same delta from <b>its own</b> origin rather than tracking the pointer, or a selection dragged by one
 * member loses its shape. And the delta comes from the drag rather than from re-reading the layout at
 * the end: the last move writes a position Taffy has not resolved yet, so asking afterwards reports the
 * one before it and a short drag records nothing at all.</p>
 */
public final class MoveGesture {

    /** On an item while it is being dragged, so a theme can lift it. */
    public static final String MOVING_CLASS = "__moving__";

    private final Surface surface;
    private final SurfacePolicy policy;
    private final Edits edits;

    private boolean active;

    public MoveGesture(Surface surface, SurfacePolicy policy, Edits edits) {
        this.surface = surface;
        this.policy = policy;
        this.edits = edits;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Begins a drag of {@code moving} from a raw pointer position.
     *
     * @return whether a drag started — false when there is nothing to move
     */
    public boolean begin(float rawX, float rawY, List<UIElement> moving) {
        if (moving.isEmpty()) return false;

        List<UIElement> items = List.copyOf(moving);
        List<float[]> origins = new ArrayList<>(items.size());
        for (UIElement item : items) {
            WorldRect at = surface.boundsOf(item);
            origins.add(new float[]{at.x(), at.y()});
        }
        float[] delta = {0f, 0f};

        active = true;
        for (UIElement item : items) item.addClass(MOVING_CLASS);

        Drag.start(surface.element(), rawX, rawY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                // Moved in place: positions are rewritten, nothing is rebuilt. Rebuilding here would
                // detach the very element the drag is anchored to.
                delta[0] = dx / surface.zoom();
                delta[1] = dy / surface.zoom();
                for (int i = 0; i < items.size(); i++) {
                    float[] origin = origins.get(i);
                    surface.move(items.get(i), origin[0] + delta[0], origin[1] + delta[1]);
                }
            }

            @Override
            public void onDragEnd(float mx, float my) {
                record(items, origins, delta[0], delta[1]);
                finish(items);
            }

            @Override
            public void onDragCancel() {
                for (int i = 0; i < items.size(); i++) {
                    float[] origin = origins.get(i);
                    surface.move(items.get(i), origin[0], origin[1]);
                }
                finish(items);
            }
        });
        return true;
    }

    private void record(List<UIElement> items, List<float[]> origins, float dx, float dy) {
        if (dx == 0f && dy == 0f) return;
        List<SurfacePolicy.Move> moves = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            float[] origin = origins.get(i);
            moves.add(new SurfacePolicy.Move(items.get(i), origin[0], origin[1],
                    origin[0] + dx, origin[1] + dy));
        }
        // ONE STEP for the whole drag, however many items it moved. Recorded rather than applied: the
        // move already happened, frame by frame, as the pointer went.
        edits.gesture("move", () -> edits.record(policy.moveEdit(moves)));
    }

    private void finish(List<UIElement> items) {
        active = false;
        for (UIElement item : items) item.removeClass(MOVING_CLASS);
    }
}
