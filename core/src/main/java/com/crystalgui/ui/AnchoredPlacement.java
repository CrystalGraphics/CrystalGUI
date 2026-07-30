package com.crystalgui.ui;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.StyleGroup;
import org.joml.Vector2f;

/**
 * Places a <b>top-layer</b> element next to an anchor — the useful subset of CSS Anchor Positioning
 * ({@code position-anchor} + {@code position-area} + {@code position-try-fallbacks: flip-block}).
 *
 * <p>Extracted from {@code Tooltip}, which had it inline, at the point a second consumer appeared
 * ({@code Popover}, and through it menus and dropdowns). Deliberately extracted <em>before</em> the
 * second copy existed rather than after: the identical thing happened with the boundary-event chains,
 * where {@code forEachLeft}/{@code forEachEntered} were written twice and the two subtly disagreed.
 * Everything below is the part that is easy to get wrong twice.</p>
 *
 * <h3>Why anchor geometry comes from the transform chain, not the layout box</h3>
 * <p>{@code runtimeCache.getX()/getY()} are pure layout and know nothing about scrolling — a scroll
 * container offsets its children inside {@code localToWorld} instead, and an ancestor {@code transform:}
 * lives there too. Reading the box would pin a popup to where its anchor <em>would</em> be if nothing
 * had ever scrolled or been transformed. This was a real tooltip bug; it is now impossible to
 * reintroduce in one consumer only.</p>
 *
 * <h3>Flip on the main axis, clamp on the cross axis</h3>
 * <p>{@link Side} picks the main axis. If the popup does not fit on the preferred side <em>and</em>
 * there is genuinely more room opposite, it flips; otherwise it stays put, because flipping into an
 * even smaller gap just moves the overflow to the other edge. The cross axis is only ever clamped —
 * a popup that jumped sides as the pointer crossed a midpoint would be far more distracting than one
 * that slides. That is also what {@code position-try-fallbacks: flip-block} does in practice.</p>
 */
public final class AnchoredPlacement {

    private AnchoredPlacement() {}

    /** Which side of the anchor the popup prefers. Names are physical, not logical — this engine has
     * no writing-mode concept, so {@code block}/{@code inline} would be borrowed vocabulary with
     * nothing behind it. */
    public enum Side {
        BOTTOM, TOP, RIGHT, LEFT;

        boolean isVertical() {
            return this == BOTTOM || this == TOP;
        }

        Side opposite() {
            return switch (this) {
                case BOTTOM -> TOP;
                case TOP -> BOTTOM;
                case RIGHT -> LEFT;
                case LEFT -> RIGHT;
            };
        }
    }

    /** An anchor's box in the root element's coordinate space — what a promoted element's
     * {@code left}/{@code top} resolve against, since promotion reparents its Taffy node to the root. */
    public record Rect(float x, float y, float width, float height) {}

    /**
     * Places {@code popup} beside {@code anchor} and writes {@code left}/{@code top}.
     *
     * <p>No-op when either element is detached, or when they belong to different windows — a stale
     * anchor is the normal state of a popup that has been hidden, not an error.</p>
     */
    public static void place(UIElement popup, UIElement anchor, Side preferred, float gap) {
        UIWindow window = popup.getAttachedWindow();
        if (anchor == null || window == null || anchor.getAttachedWindow() != window) return;
        placeInRect(popup, anchorRectInRoot(anchor, window), preferred, gap);
    }

    /**
     * Places {@code popup} at a <b>point</b> in root space — a zero-sized anchor.
     *
     * <p>This is how a context menu is positioned, and it is the same primitive rather than a second
     * one: the web anchors a right-click menu to the pointer with exactly this degenerate rect.</p>
     */
    public static void placeAtPoint(UIElement popup, float rootX, float rootY, Side preferred, float gap) {
        placeInRect(popup, new Rect(rootX, rootY, 0f, 0f), preferred, gap);
    }

    /** Places {@code popup} against an explicit rect. The other two entry points funnel into this. */
    public static void placeInRect(UIElement popup, Rect anchor, Side preferred, float gap) {
        UIWindow window = popup.getAttachedWindow();
        if (window == null) return;

        var rootCache = window.ui.rootElement.getRuntimeCache();
        Vector2f resolved = resolve(anchor,
                popup.getRuntimeCache().getWidth(), popup.getRuntimeCache().getHeight(),
                rootCache.getWidth(), rootCache.getHeight(), preferred, gap);

        final float left = resolved.x(), top = resolved.y();
        // IMPORTANT origin, like every other widget-driven geometry write here.
        // replaceOrPutCandidate no-ops on an unchanged value, so a stationary popup stops re-dirtying
        // the tree after the first frame rather than forever.
        StyleGroup.importantPipeline(popup.getStyle().getLayoutGroup(), l -> l.left(left).top(top));
    }

    /**
     * The pure geometry, with no element or style involvement — which is what makes flipping and
     * clamping testable headlessly instead of only through a rendered popup.
     */
    public static Vector2f resolve(Rect anchor, float selfW, float selfH,
                                   float availableW, float availableH, Side preferred, float gap) {
        float x, y;
        if (preferred.isVertical()) {
            // Room on each physical side, then mapped onto preferred/opposite — NOT assumed. Hardcoding
            // "preferred room is the room below" is correct for BOTTOM and silently backwards for TOP,
            // which makes a TOP-preferring popup refuse to flip and then clamp to the top edge instead.
            float roomBelow = availableH - (anchor.y() + anchor.height());
            float roomAbove = anchor.y();
            boolean preferBottom = preferred == Side.BOTTOM;
            Side side = chooseSide(preferred, selfH + gap,
                    preferBottom ? roomBelow : roomAbove,
                    preferBottom ? roomAbove : roomBelow);
            y = side == Side.BOTTOM
                    ? anchor.y() + anchor.height() + gap
                    : anchor.y() - selfH - gap;
            x = anchor.x();
        } else {
            float roomRight = availableW - (anchor.x() + anchor.width());
            float roomLeft = anchor.x();
            boolean preferRight = preferred == Side.RIGHT;
            Side side = chooseSide(preferred, selfW + gap,
                    preferRight ? roomRight : roomLeft,
                    preferRight ? roomLeft : roomRight);
            x = side == Side.RIGHT
                    ? anchor.x() + anchor.width() + gap
                    : anchor.x() - selfW - gap;
            y = anchor.y();
        }

        // Clamp both axes into the containing block. The main axis is clamped too, after flipping: if
        // neither side fits, the popup must still be on screen, and the far edge is the lesser evil.
        if (x + selfW > availableW) x = availableW - selfW;
        if (y + selfH > availableH) y = availableH - selfH;
        if (x < 0f) x = 0f;
        if (y < 0f) y = 0f;
        return new Vector2f(x, y);
    }

    /**
     * Flips only when it actually helps: the popup must not fit on the preferred side <em>and</em>
     * the opposite side must have more room. Without the second half, a popup taller than the whole
     * viewport would flip on every frame and never settle anywhere.
     */
    private static Side chooseSide(Side preferred, float needed, float roomPreferred, float roomOpposite) {
        boolean fits = needed <= roomPreferred;
        return (!fits && roomOpposite > roomPreferred) ? preferred.opposite() : preferred;
    }

    /** Anchor box in root space. See the class javadoc for why this reads the transform chain. */
    public static Rect anchorRectInRoot(UIElement anchor, UIWindow window) {
        UIElement root = window.ui.rootElement;
        var rootCache = root.getRuntimeCache();
        var anchorCache = anchor.getRuntimeCache();

        Vector2f anchorWorld = Transform2D.apply(anchorCache.localToWorld.get(),
                anchorCache.getX(), anchorCache.getY());
        Vector2f anchorInRoot = Transform2D.apply(rootCache.worldToLocal.get(),
                anchorWorld.x(), anchorWorld.y());

        return new Rect(anchorInRoot.x() - rootCache.getX(), anchorInRoot.y() - rootCache.getY(),
                anchorCache.getWidth(), anchorCache.getHeight());
    }

    /** Converts a pointer position (physical px, as input reports it) into root space, for
     * {@link #placeAtPoint}. */
    public static Vector2f pointerToRoot(UIWindow window, float pointerX, float pointerY) {
        var rootCache = window.ui.rootElement.getRuntimeCache();
        Vector2f inRoot = Transform2D.apply(rootCache.worldToLocal.get(), pointerX, pointerY);
        return new Vector2f(inRoot.x() - rootCache.getX(), inRoot.y() - rootCache.getY());
    }
}
