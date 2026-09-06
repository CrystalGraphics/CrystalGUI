package com.crystalgui.ui.service;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;
import org.joml.Vector2f;
import com.crystalgui.core.data.Transform2D;

/**
 * Places a <b>hosted</b> node next to an anchor — the useful subset of CSS Anchor Positioning
 * ({@code position-anchor} + {@code position-area} + {@code position-try-fallbacks: flip-block}).
 *
 * <p>Extracted from {@code Tooltip} at the point a second consumer appeared, and deliberately
 * <em>before</em> the second copy existed rather than after: the identical thing happened with the
 * boundary-event chains, where {@code forEachLeft}/{@code forEachEntered} were written twice and the
 * two subtly disagreed. Everything below is the part that is easy to get wrong twice.</p>
 *
 * <h3>Anchor geometry comes from the BOX's world matrix, not its layout rect</h3>
 *
 * <p>{@code box.x()}/{@code y()} are pure layout and know nothing about scrolling — a scroll container
 * offsets its children inside {@code localToWorld} instead, and an ancestor {@code transform:} lives
 * there too. Reading the layout rect pins a popup to where its anchor <em>would</em> be if nothing had
 * ever scrolled or been transformed, which was a real tooltip bug.</p>
 *
 * <p>On this engine that is one call rather than two matrix applications: {@link Box#worldX()} is
 * already the transformed, scrolled origin, because layout composes every box's matrix top-down in the
 * one pass. The old version had to apply the anchor's {@code localToWorld} and then the root's
 * {@code worldToLocal} by hand, which is the same arithmetic spelled out.</p>
 *
 * <h3>Flip on the main axis, clamp on the cross axis</h3>
 *
 * <p>{@link Side} picks the main axis. If the popup does not fit on the preferred side <em>and</em>
 * there is genuinely more room opposite, it flips; otherwise it stays put, because flipping into an
 * even smaller gap just moves the overflow to the other edge. The cross axis is only ever clamped — a
 * popup that jumped sides as the pointer crossed a midpoint would be far more distracting than one
 * that slides. That is also what {@code position-try-fallbacks: flip-block} does in practice.</p>
 *
 * <p><b>It LEFT-ALIGNS on the cross axis; it does not centre.</b> Correct for what it was written for —
 * a dropdown hangs from its button's left edge — and wrong for anything that is a label for the thing
 * beneath it. A consumer wanting a centred panel centres it after resolving and re-clamps; never here,
 * because every menu in the engine depends on this.</p>
 */
public final class AnchoredPlacement {

    private AnchoredPlacement() {
    }

    /**
     * Which side of the anchor the popup prefers.
     *
     * <p>Names are physical, not logical — this engine has no writing-mode concept, so
     * {@code block}/{@code inline} would be borrowed vocabulary with nothing behind it.</p>
     */
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

    /**
     * An anchor's box in the document's coordinate space — what a hosted node's {@code left}/{@code top}
     * resolve against, since hosting moves its box under the top layer.
     */
    public record Rect(float x, float y, float width, float height) {
    }

    /**
     * Places {@code popup} beside {@code anchor} and writes {@code left}/{@code top}.
     *
     * <p>No-op when either node is detached, when they belong to different documents, or before either
     * has been laid out — a stale anchor is the normal state of a popup that has been hidden, not an
     * error.</p>
     */
    public static void place(UIElement popup, @Nullable UIElement anchor, Side preferred, float gap) {
        UIDocument document = popup.document();
        if (anchor == null || document == null || anchor.document() != document) return;
        Rect rect = anchorRectInRoot(anchor, document);
        if (rect != null) placeInRect(popup, rect, preferred, gap);
    }

    /**
     * Places {@code popup} at a <b>point</b> in document space — a zero-sized anchor.
     *
     * <p>How a context menu is positioned, and the same primitive rather than a second one: the web
     * anchors a right-click menu to the pointer with exactly this degenerate rect.</p>
     */
    public static void placeAtPoint(UIElement popup, float rootX, float rootY, Side preferred, float gap) {
        placeInRect(popup, new Rect(rootX, rootY, 0f, 0f), preferred, gap);
    }

    /** Places {@code popup} against an explicit rect. The other two entry points funnel into this. */
    public static void placeInRect(UIElement popup, Rect anchor, Side preferred, float gap) {
        UIDocument document = popup.document();
        if (document == null) return;
        Box self = popup.box();
        Box root = document.box();
        if (self == null || root == null) return;

        Vector2f resolved = resolve(anchor, self.width(), self.height(),
                root.width(), root.height(), preferred, gap);

        final float left = resolved.x();
        final float top = resolved.y();
        // INLINE, not IMPORTANT. This is the ONE writer of a hosted popup's left/top -- anything else
        // writing them fights placement every frame -- so it needs only to outrank the sheet, which
        // INLINE does. The engine may not write at IMPORTANT at all: it sits above every author origin
        // including `!important`, and it is what made the old cascade the engine's only mutable box
        // model. replaceOrPutCandidate no-ops on an unchanged value, so a stationary popup stops
        // re-dirtying the tree after the first frame rather than forever.
        StyleGroup.inlinePipeline(popup.getStyle().getLayoutGroup(), l -> l.left(left).top(top));
    }

    /**
     * The pure geometry, with no node and no style involvement — which is what makes flipping and
     * clamping testable headlessly rather than only through a rendered popup.
     */
    public static Vector2f resolve(Rect anchor, float selfW, float selfH,
                                   float availableW, float availableH, Side preferred, float gap) {
        float x;
        float y;
        if (preferred.isVertical()) {
            // Room on each PHYSICAL side, then mapped onto preferred/opposite -- not assumed.
            // Hardcoding "preferred room is the room below" is correct for BOTTOM and silently
            // backwards for TOP, which makes a TOP-preferring popup refuse to flip and then clamp to
            // the top edge instead.
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

        // Clamp both axes into the containing block. The main axis is clamped too, AFTER flipping: if
        // neither side fits the popup must still be on screen, and the far edge is the lesser evil.
        if (x + selfW > availableW) x = availableW - selfW;
        if (y + selfH > availableH) y = availableH - selfH;
        if (x < 0f) x = 0f;
        if (y < 0f) y = 0f;
        return new Vector2f(x, y);
    }

    /**
     * Flips only when it actually helps: the popup must not fit on the preferred side <em>and</em> the
     * opposite side must have more room. Without the second half a popup taller than the whole viewport
     * would flip on every frame and never settle anywhere.
     */
    private static Side chooseSide(Side preferred, float needed, float roomPreferred, float roomOpposite) {
        boolean fits = needed <= roomPreferred;
        return (!fits && roomOpposite > roomPreferred) ? preferred.opposite() : preferred;
    }

    /**
     * An anchor's box in document space, or null before either has been laid out.
     *
     * <p>See the class javadoc for why this reads the world matrix rather than the layout rect.</p>
     */
    @Nullable
    public static Rect anchorRectInRoot(UIElement anchor, UIDocument document) {
        Box box = anchor.box();
        Box root = document.box();
        if (box == null || root == null) return null;
        // THROUGH THE ROOT'S INVERSE, not by subtracting its origin.
        //
        // `worldX()` is in SURFACE pixels with the root transform baked in -- uiScale included --
        // while `left`/`top` are LOGICAL and are scaled again on the way back out. Subtracting the
        // root's own origin cancels its translation and leaves its SCALE, so every popup was placed
        // at uiScale times its anchor's distance down the page: a popover anchored halfway down
        // landed off the bottom of the screen. The old engine records the same trap from the other
        // side -- position a popup from the layout chain, never from `localToWorld` -- and the
        // resolution here is neither: convert THROUGH the containing block's inverse, which undoes
        // the scale and every ancestor transform and scroll in one step, and is the only reading that
        // stays right at any uiScale.
        Vector2f inRoot = Transform2D.apply(root.worldToLocal(), box.worldX(), box.worldY());
        return new Rect(inRoot.x(), inRoot.y(), box.width(), box.height());
    }

    /** Converts a pointer position (surface px, as input reports it) into document space. */
    public static Vector2f pointerToRoot(UIDocument document, float pointerX, float pointerY) {
        Box root = document.box();
        if (root == null) return new Vector2f(pointerX, pointerY);
        return Transform2D.apply(root.worldToLocal(), pointerX, pointerY);
    }
}
