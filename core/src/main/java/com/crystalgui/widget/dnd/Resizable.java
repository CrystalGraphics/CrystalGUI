package com.crystalgui.widget.dnd;

import com.crystalgui.ui.dom.UINode;

import javax.annotation.Nullable;

/**
 * What a {@link Resizer} needs to know about the thing it is resizing.
 *
 * <h3>Five hooks where the old engine had five overrides on {@code UIElement}</h3>
 *
 * <p>{@code UIResizer} read {@code resizeContainingBlock()}, {@code resizeOriginLeft()},
 * {@code resizeOriginTop()}, {@code canMoveResizeOrigin()}, {@code applyResizeOrigin()} and
 * {@code onUserResize()} straight off the element, which meant every node in the engine carried six
 * methods for a feature almost none of them had. Here they are an interface a resizable widget
 * implements, so a node that is not resizable declares nothing and the resizer names no widget.</p>
 *
 * <p><b>The origin hooks are the whole reason this is not one method.</b> A <em>leading</em> edge — a
 * top or a left — grows by moving the element rather than by extending it, so the opposite edge stays
 * where it was. That is a position write, and who is allowed to make one differs per widget: a window
 * writes {@code left}/{@code top} directly, while a popover must hand ownership to {@code moveTo}
 * because {@code AnchoredPlacement} is otherwise the single writer of those and the two would fight
 * every frame. It is also why the web only ever offers the bottom-right corner — the one handle that
 * needs to reposition nothing.</p>
 */
public interface Resizable {

    /** The node being resized. Almost always {@code this}. */
    UINode node();

    /**
     * The box a resize is kept inside, or {@code null} for no clamp.
     *
     * <p>Only meaningful for an out-of-flow element, which is the same set that has leading handles at
     * all — for anything in flow, {@code left}/{@code top} are a relative nudge rather than a position
     * and there is nothing to clamp against.</p>
     */
    @Nullable
    default UINode resizeContainingBlock() {
        return node().parent();
    }

    /**
     * Whether a leading edge may move this element's origin.
     *
     * <p>{@code false} keeps the trailing three handles — bottom, right and the corner — which is
     * CSS's own default grabber, and drops the five that would have to reposition anything.</p>
     */
    default boolean canMoveResizeOrigin() {
        return true;
    }

    /**
     * Where the element's left edge sits within its containing block.
     *
     * <p><b>Measured when there is no written inset, never answered as zero.</b> The old engine's
     * default read the {@code left} inset and returned {@code 0} for {@code auto}, which is the same
     * teleport-to-the-corner bug as reading a field: {@code auto} means "wherever the static position
     * put it", and that is only zero for a box with no inset on that axis at all — a panel anchored by
     * {@code right}/{@code bottom} has an {@code auto} {@code left} and is nowhere near it.</p>
     */
    float resizeOriginLeft();

    /** @see #resizeOriginLeft */
    float resizeOriginTop();

    /** Moves the origin, so a leading edge pins the opposite one. */
    void applyResizeOrigin(float left, float top);

    /**
     * A drag has claimed an axis, and the widget must stop sizing that axis itself.
     *
     * <p><b>Records only; it never clears anything.</b> A {@link Resizer} writes at INLINE origin, per
     * spec — and every widget that sizes itself writes higher, so without this the handle would appear
     * dead on that axis: the drag writes a width and the widget writes over it on the next frame. The
     * flag is what lets the widget stand down instead, rather than the handle winning an origin fight
     * it should not win. Withdrawing the widget's own declarations would beat an AUTHOR's
     * {@code !important} in the same stroke, because those share one origin bucket.</p>
     *
     * <p>Two classes go with it so a sheet can see the state — {@link #USER_SIZED_WIDTH_CLASS} and
     * {@link #USER_SIZED_HEIGHT_CLASS}. State a widget flips from its own code belongs on a class
     * rather than a pseudo-class, and this one changes at most twice in a node's life.</p>
     */
    default void markUserSized(boolean width, boolean height) {
    }

    /** @see #markUserSized */
    default boolean isUserSizedWidth() {
        return false;
    }

    /** @see #markUserSized */
    default boolean isUserSizedHeight() {
        return false;
    }

    /** Forgets both, for a widget that has been given genuinely new content to size to. */
    default void clearUserSizing() {
    }

    /** @see #markUserSized */
    String USER_SIZED_WIDTH_CLASS = "__user-sized-width__";

    /** @see #markUserSized */
    String USER_SIZED_HEIGHT_CLASS = "__user-sized-height__";

    /**
     * The drag settled on this geometry.
     *
     * <p>Called LAST, so an implementation sees the size the resize actually achieved and its own
     * writes are not then overwritten by the origin.</p>
     *
     * @param handleDx −1, 0 or +1 — which horizontal edge the pointer was on
     * @param handleDy −1, 0 or +1 — which vertical edge
     */
    default void onUserResize(int handleDx, int handleDy, float width, float height) {
    }
}
