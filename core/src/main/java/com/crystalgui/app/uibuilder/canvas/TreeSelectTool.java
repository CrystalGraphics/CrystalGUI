package com.crystalgui.app.uibuilder.canvas;

import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.mode.SelectTool;
import com.crystalgui.widget.surface.mode.Tool;

/**
 * The builder's Select: the engine's, plus a drag that positions an out-of-flow node.
 *
 * <p>Composition rather than a fork. Everything a selection gesture means — the press rule, the marquee,
 * Shift to toggle — is the engine's and identical here; what a tree adds is that a press on a
 * <b>positioned</b> node is a move, and a press on anything else is not.</p>
 *
 * <p>The engine's own move gesture is refused outright by {@link TreePolicy#movesItems()}, because it
 * writes plane coordinates and a node inside a laid-out tree has none. This writes the inset the node is
 * anchored by instead. A press on an <em>in-flow</em> node is left to fall through to selection: dragging
 * one means reorder or reparent, which is L4.6's gesture and not this one.</p>
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
        if (!MoveOutOfFlow.isMovable(item)) return consumed;
        MoveOutOfFlow move = moveGesture();
        if (move == null) return consumed;
        return move.begin(item, rawX, rawY) || consumed;
    }

    @Override
    public boolean pointerMoved(float rawX, float rawY, int modifiers) {
        return select.pointerMoved(rawX, rawY, modifiers);
    }

    @Override
    public boolean pointerUp(float rawX, float rawY, int button, int modifiers) {
        return select.pointerUp(rawX, rawY, button, modifiers);
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
}
