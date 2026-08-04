package com.crystalgui.ui.elements.canvas;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;

import javax.annotation.Nullable;

/**
 * Makes a floating panel draggable by a handle, and keeps it inside its container.
 *
 * <h3>Extracted because the second consumer arrived</h3>
 * <p>All of this lived inside {@code MainPreviewPanel}, and every paragraph below is a bug that was
 * shipped once. The Blackboard needs exactly the same behaviour, and re-deriving it would have meant
 * making all three mistakes again — which is the point at which a private method becomes a class.</p>
 *
 * <h3>Three things that are not obvious and cost a session each</h3>
 *
 * <p><b>{@code getX()} is not in the same space as {@code left}.</b> It is expressed in the frame
 * {@code screenToLocal} maps into, which carries the root transform, while {@code left} is an inset
 * inside the containing block. Assigning one to the other teleports the panel by the difference on the
 * first press, before the pointer has moved at all. What {@code left} means is the offset within the
 * containing block, so that is what is measured.</p>
 *
 * <p><b>{@code resizeOriginLeft()} is not the answer either</b> for a panel the stylesheet anchors by
 * {@code right}/{@code bottom}: its {@code left} inset is unset and reads 0, which is the same teleport
 * wearing a different hat.</p>
 *
 * <p><b>Clamping only while dragging is not enough.</b> The position is written once and then stays, so
 * shrinking the container slides its edge past a panel that never moved — which looks like the panel
 * sinking behind a border, points at z-order, and is nowhere near it. {@link #reclampIfPlaced()} is what
 * a container calls when it resizes.</p>
 */
public final class CanvasOverlayMove {

    /** The panel being moved — used only for geometry, never mutated except through the style pipeline. */
    private final UIElement panel;

    private final ContainingBlock block;

    /** Panel origin at the moment a move began, in the containing block's own space. */
    private float dragLeft;
    private float dragTop;

    /** True once dragged, so the position is the user's rather than the stylesheet's. @see #reclampIfPlaced */
    private boolean placed;

    /**
     * How to find the box a panel is positioned inside.
     *
     * <p>An interface because {@code UIElement.resizeContainingBlock()} is {@code protected}, so only the
     * panel itself can answer — which is correct: whether an element is out of flow, and against what, is
     * its own business rather than a helper's guess.</p>
     */
    @FunctionalInterface
    public interface ContainingBlock {
        @Nullable
        UIElement get();
    }

    private CanvasOverlayMove(UIElement panel, ContainingBlock block) {
        this.panel = panel;
        this.block = block;
    }

    /**
     * Installs the gesture.
     *
     * @param handle what starts a move — a title bar, typically. It must be hit-testable, and anything
     *               inside it that should not start a move must take the press itself
     */
    public static CanvasOverlayMove install(UIElement panel, UIElement handle, ContainingBlock block) {
        CanvasOverlayMove move = new CanvasOverlayMove(panel, block);
        handle.onMouseDown.attachListener((element, event) -> {
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            // A synthesized activation press (Space/Enter on a focused element) carries the cursor's
            // position, which may be nowhere near the handle. Honouring one would teleport the panel.
            if (!handle.containsScreenPoint(rawX, rawY)) return;

            UIWindow window = panel.getAttachedWindow();
            UIElement container = block.get();
            if (window == null || container == null) return;

            move.dragLeft = panel.getRuntimeCache().getX() - container.getRuntimeCache().getX();
            move.dragTop = panel.getRuntimeCache().getY() - container.getRuntimeCache().getY();
            window.getInputHandler().getDragController().startDrag(handle, rawX, rawY,
                    (mouseX, mouseY, startX, startY, deltaX, deltaY) -> move.moveBy(deltaX, deltaY));
            event.stopPropagation();
        }, false, true);
        return move;
    }

    private void moveBy(float deltaX, float deltaY) {
        placed = true;
        placeAt(dragLeft + deltaX, dragTop + deltaY);
    }

    /**
     * Writes a position, clamped to the containing block.
     *
     * <p>The same clamp {@code UIResizer} applies to a resize. Without it the panel goes straight out of
     * the canvas — and since a viewport is {@code overflow: hidden}, it does not end up somewhere awkward,
     * it is simply <b>gone</b>, with no edge left to grab it back by. Unity states the same guarantee for
     * its Blackboard: you cannot drag it off the graph and lose it.</p>
     *
     * <p>The stylesheet's {@code right}/{@code bottom} are left in place rather than cleared. Both insets
     * on an axis is well-defined when the size is definite — Taffy resolves in favour of the start edge —
     * so writing {@code left} is what takes over, and a panel that is never moved keeps its corner anchor.</p>
     */
    public void placeAt(float wantedLeft, float wantedTop) {
        UIElement container = block.get();
        if (container == null) return;

        float maxLeft = Math.max(0f,
                container.getRuntimeCache().getWidth() - panel.getRuntimeCache().getWidth());
        float maxTop = Math.max(0f,
                container.getRuntimeCache().getHeight() - panel.getRuntimeCache().getHeight());
        float left = Math.max(0f, Math.min(maxLeft, wantedLeft));
        float top = Math.max(0f, Math.min(maxTop, wantedTop));

        // No-ops when unchanged: replaceOrPutCandidate drops an identical value, which is what lets this
        // run every frame without re-dirtying layout forever.
        StyleGroup.inlinePipeline(panel.getStyle().getLayoutGroup(), l -> l.left(left).top(top));
    }

    /**
     * Re-clamps after the container changed size. Call it per frame; it is cheap and idempotent.
     *
     * <p>Only once the panel has actually been moved — before that it is anchored by the stylesheet's
     * {@code right}/{@code bottom}, which already tracks a resizing viewport correctly, and writing
     * {@code left}/{@code top} would take that over and pin it to a corner it was never dragged to.</p>
     *
     * @param originLeft the panel's current left inset, from the caller's {@code resizeOriginLeft()}
     */
    public void reclampIfPlaced(float originLeft, float originTop) {
        if (!placed) return;
        placeAt(originLeft, originTop);
    }

    public boolean isPlaced() {
        return placed;
    }
}
