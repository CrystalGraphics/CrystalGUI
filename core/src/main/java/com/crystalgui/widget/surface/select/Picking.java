package com.crystalgui.widget.surface.select;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.WorldRect;

/**
 * What is under a point, and what a rectangle touches.
 *
 * <pre>{@code
 * UIElement item = ctx.picking().itemAt(worldX, worldY);      // through the policy
 * List<UIElement> caught = ctx.picking().touching(band);
 * }</pre>
 *
 * <p><b>It picks rather than hit-tests.</b> An editor turns hit-testing off on what it is editing so the
 * thing being designed does not react to being designed, and then still has to know what is under the
 * pointer — "what is there" and "what would take this click" are different questions.</p>
 *
 * <p>Touching, not enclosing: a node bigger than the viewport would be unselectable by any band the user
 * could draw. No vendor documents which rule they use; this is the one that keeps working at any zoom.</p>
 */
public final class Picking {

    private final Surfaces surfaces;

    /** Fires when the item under the pointer changes, with the new one or null. */
    public final Signal.Value<UIElement> onDidChangeHover = new Signal.Value<>();

    @Nullable
    private UIElement hovered;

    /** What a picker needs from the surface it serves — kept narrow so it is testable without one. */
    public interface Surfaces {

        @Nullable
        UIDocument window();

        /** Everything on the plane. */
        List<UIElement> items();

        WorldRect boundsOf(UIElement item);

        /** The item an arbitrary element belongs to, or null — the consumer's policy. */
        @Nullable
        UIElement itemFor(@Nullable UIElement hit);

        /** The consumer's correction to what the box tree found — see {@code SurfacePolicy.pickAt}. */
        @Nullable
        default UIElement pickAt(@Nullable UIElement painted, float rawX, float rawY) {
            return painted;
        }
    }

    public Picking(Surfaces surfaces) {
        this.surfaces = surfaces;
    }

    /**
     * The element under a raw pointer position, reaching into {@code hit-test: false} subtrees.
     *
     * <p>Raw pixels, as a {@code MouseEvent} delivers them — not world units, because the box tree is
     * what answers and it works in surface space.</p>
     */
    @Nullable
    public UIElement elementAt(float rawX, float rawY) {
        UIDocument window = surfaces.window();
        if (window == null) return null;
        Box box = window.boxes().pick(rawX, rawY, ignored -> false);
        return surfaces.pickAt(box == null ? null : box.node(), rawX, rawY);
    }

    /** The item under a raw pointer position, or null — {@link #elementAt} through the policy. */
    @Nullable
    public UIElement itemAt(float rawX, float rawY) {
        return surfaces.itemFor(elementAt(rawX, rawY));
    }

    /** Every item the rectangle touches, in plane order. */
    public List<UIElement> touching(WorldRect band) {
        List<UIElement> caught = new ArrayList<>();
        for (UIElement item : surfaces.items()) {
            // THROUGH THE POLICY, exactly as itemAt is. A thing on the plane is not automatically
            // something a gesture may take: a UI builder places its ARTBOARD there -- the page the tree
            // is laid out on -- and answers null for it, because selecting the page is not an edit. Asking
            // items() directly let a marquee catch it anyway, so clicking empty canvas put eight resize
            // handles on the page frame and the inspector described the frame instead of the document.
            if (surfaces.itemFor(item) != item) continue;
            if (overlaps(band, surfaces.boundsOf(item))) caught.add(item);
        }
        return caught;
    }

    /** What the pointer is over now. */
    @Nullable
    public UIElement hovered() {
        return hovered;
    }

    /** Called by the mode as the pointer moves; announces only when the answer changed. */
    public void setHovered(@Nullable UIElement item) {
        if (hovered == item) return;
        hovered = item;
        onDidChangeHover.emit(item);
    }

    private static boolean overlaps(WorldRect a, WorldRect b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }
}
