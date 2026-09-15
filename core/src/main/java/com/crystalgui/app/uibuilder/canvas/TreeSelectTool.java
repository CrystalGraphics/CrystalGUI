package com.crystalgui.app.uibuilder.canvas;

import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.mode.SelectTool;
import com.crystalgui.widget.surface.mode.Tool;

/**
 * The builder's Select: the engine's, plus the two drags a tree has — a positioned node is moved, and an
 * in-flow node is reordered or reparented.
 *
 * <p>Composition rather than a fork. Everything a selection gesture means — the press rule, the marquee,
 * Shift to toggle — is the engine's and identical here; what a tree adds is what a drag on a node does.</p>
 *
 * <p>The engine's own move gesture is refused outright by {@link TreePolicy#movesItems()}, because it
 * writes plane coordinates and a node inside a laid-out tree has none. {@link MoveOutOfFlow} writes the
 * inset a positioned node is anchored by instead, and {@link ReorderInFlow} moves an in-flow node through
 * the tree.</p>
 */
public final class TreeSelectTool implements Tool {

    /** The id the builder makes current. Distinct from the engine's, since it is a different tool. */
    public static final String ID = "crystalgui:uibuilder.select";

    private final SurfaceContext ctx;

    private final SelectTool select;


    public TreeSelectTool(SurfaceContext ctx) {
        this.ctx = ctx;
        this.select = new SelectTool(ctx);
    }

    /** The engine's tool underneath, for a test that wants to reach the marquee. */
    public SelectTool select() {
        return select;
    }

    @Override
    public boolean pointerDown(float rawX, float rawY, int button, int modifiers) {
        boolean consumed = select.pointerDown(rawX, rawY, button, modifiers);
        if (button != CgMouseCodes.LEFT_BUTTON) return consumed;

        // AFTER the selection, so the drag moves what the press just picked -- and only then, because
        // "press an already-selected node and drag them all" is the engine's rule and it has to have run.
        UIElement item = ctx.picking().itemAt(rawX, rawY);
        if (ctx instanceof BuilderSurface surface) surface.noteBlankPress(item == null && modifiers == 0, rawX, rawY);
        if (MoveOutOfFlow.isMovable(item)) {
            MoveOutOfFlow move = moveGesture();
            return move != null && move.begin(item, rawX, rawY) || consumed;
        }
        ReorderInFlow reorder = reorderGesture();
        return reorder != null && reorder.begin(item, rawX, rawY) || consumed;
    }

    @Override
    public boolean pointerMoved(float rawX, float rawY, int modifiers) {
        return select.pointerMoved(rawX, rawY, modifiers);
    }

    @Override
    public boolean pointerUp(float rawX, float rawY, int button, int modifiers) {
        boolean consumed = select.pointerUp(rawX, rawY, button, modifiers);
        if (button == CgMouseCodes.LEFT_BUTTON && ctx instanceof BuilderSurface surface) surface.releaseBlankPress(rawX, rawY);
        return consumed;
    }

    @Override
    public boolean keyPressed(int key, int modifiers, boolean repeat) {
        return select.keyPressed(key, modifiers, repeat);
    }

    @Override
    public void activated() {
        select.activated();
    }

    @Override
    public void deactivated() {
        select.deactivated();
    }

    private MoveOutOfFlow moveGesture() {
        return ctx instanceof BuilderSurface surface ? surface.moveGesture() : null;
    }

    private ReorderInFlow reorderGesture() {
        return ctx instanceof BuilderSurface surface ? surface.reorderGesture() : null;
    }
}
