package com.crystalgui.ui.elements;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;

import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * The placeholder showing where a dragged thing would land in a list — tabs, stripe buttons, rows.
 *
 * <pre>
 * private final InsertionMarker insertion = new InsertionMarker(InsertionMarker.Axis.HORIZONTAL);
 * // once:
 * insertion.parkIn(this);
 * // while a drag is over it:
 * int index = insertion.showFor(items, screenX, screenY);
 * // when it ends:
 * insertion.hide();
 * </pre>
 *
 * <h3>Why this is a widget and not thirty lines per consumer</h3>
 *
 * <p>{@code DockGroup} had written it for its tab strip, and the stripes needed the same thing rotated
 * ninety degrees. That is the second consumer, which is where {@code DragGhost} was extracted from too, and
 * for the same reason: the interesting part is not the box, it is the two rules underneath it, and both are
 * silently wrong rather than visibly broken.</p>
 *
 * <ol>
 *   <li><b>The first item whose midpoint is past the pointer.</b> Every tab strip in every editor uses this
 *       rule, and it is why dropping on the left half of a neighbour lands <em>before</em> it. Comparing
 *       against an item's leading edge instead means the last half of every item does nothing.</li>
 *   <li><b>Both ends are reachable, and the far end is the one that gets lost.</b> The index runs
 *       {@code [0, size]} — one more than there are items — so a pointer past the last item appends rather
 *       than replacing it. Written as a loop that returns an item's index and never {@code size}, a list
 *       can be inserted into everywhere except the end.</li>
 * </ol>
 *
 * <p>The clamp at the near end falls out of the same arithmetic: a pointer above the first item is past no
 * midpoint, so the answer is 0 and the marker sits at that item's leading edge rather than off the list.</p>
 *
 * <h3>Absolutely positioned, never inserted between the items</h3>
 *
 * <p>Making room by re-parenting would be a structural change to the very subtree a drag is live over — the
 * rule this codebase has paid for three times, most recently on a table header that could not be clicked
 * again after a sort. The marker floats over the list instead and nothing in the list moves.</p>
 */
public class InsertionMarker extends UIElement {

    /** One shared class, so a theme colours every insertion caret in the engine once. */
    public static final String MARKER_CLASS = "__insertion__";

    /**
     * The gap left between the slot and its neighbours, in logical pixels.
     *
     * <p>So the placeholder reads as sitting <em>between</em> two items rather than as covering one.</p>
     */
    public static final float GAP = 2f;

    /** Which way the list runs. */
    public enum Axis {
        /** A row — tabs. The caret is a vertical bar between two items. */
        HORIZONTAL,
        /** A column — a stripe of buttons. The caret is a horizontal bar between two items. */
        VERTICAL
    }

    /**
     * Whether the marker takes space or floats over the list.
     *
     * <p>Not a style preference — they answer different questions. {@link #OVERLAY} draws where a drop
     * would land; {@link #IN_FLOW} <b>opens the gap</b>, so the list rearranges under the pointer and you
     * are looking at the arrangement you are about to get. Both references use in-flow for the lists you
     * reorder by hand, and it is the difference between "it goes there" and "it looks like this".</p>
     */
    public enum Mode {
        /**
         * Absolutely positioned over the list. Nothing moves.
         *
         * <p>The right answer when the marker cannot be a sibling of the items — {@code DockGroup} keeps
         * its caret beside the {@code TabView} rather than inside its strip, so it has nothing to be
         * inserted between.</p>
         */
        OVERLAY,
        /**
         * An ordinary child at the insertion index, so everything after it shifts.
         *
         * <p>Safe here specifically because it inserts a <em>sibling</em> and detaches nothing: the rule
         * this codebase keeps paying for is about rebuilding or removing the elements a drag is live on,
         * and the drag source is hidden for the duration anyway.</p>
         */
        IN_FLOW
    }

    private final Axis axis;
    private Mode mode = Mode.OVERLAY;
    private int index = -1;

    /** @see Mode */
    public InsertionMarker mode(Mode value) {
        this.mode = value == null ? Mode.OVERLAY : value;
        return this;
    }

    public InsertionMarker(Axis axis) {
        this.axis = axis == null ? Axis.HORIZONTAL : axis;
        addClass(MARKER_CLASS);
        markAsInternal();
        setHitTest(false);
        hide();
    }

    /** Puts the marker in {@code host}'s tree, once. Idempotent. */
    public InsertionMarker parkIn(UIElement host) {
        if (host == null || getParent() == host) return this;
        host.addInternalChild(this);
        return this;
    }

    /** The index the marker is currently showing, or {@code -1} when hidden. */
    public int index() {
        return index;
    }

    /**
     * Where a drop at this screen point would insert among {@code items} — {@code [0, items.size()]}.
     *
     * <p>Pure geometry, so it can be asked without showing anything. {@code items} must be siblings laid
     * out along {@link #axis}; the marker's own host supplies the coordinate space.</p>
     */
    public int indexFor(UIElement host, List<? extends UIElement> items, float screenX, float screenY) {
        if (host == null || items == null || items.isEmpty()) return 0;
        var local = host.screenToLocal(screenX, screenY);
        float along = axis == Axis.HORIZONTAL ? local.x : local.y;

        // WALKED IN LAID-OUT ORDER, not in the order the caller happened to build the list.
        //
        // "The first item whose midpoint is past the pointer" is only a rule about a SORTED sequence: hand
        // it a list whose second entry is drawn last and it returns early on that one, and the answer stops
        // moving however far down you drag. The caller's list is sorted by a model field, which agrees with
        // the layout right up until something is mid-move -- which is exactly when this is being asked.
        for (UIElement item : ordered(items)) {
            if (along < start(item) + extent(item) / 2f) return items.indexOf(item);
        }
        return items.size();
    }

    /**
     * {@code items} in the order they are actually laid out.
     *
     * <p><b>Used by every method here, and that consistency is the point.</b> A caller sorts its list by a
     * model field — a stripe by {@code order} — which agrees with the layout right up until something is
     * mid-move, which is exactly when this class is being asked. Mixing the two spaces is what pinned the
     * gap in place: the midpoint walk read screen order while the re-parent read list order, so "past the
     * last item" resolved to a sibling index in the middle of the run and the gap never reached the end.</p>
     */
    private List<? extends UIElement> ordered(List<? extends UIElement> items) {
        List<UIElement> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Float.compare(start(a), start(b)));
        return sorted;
    }

    private float start(UIElement item) {
        var cache = item.getRuntimeCache();
        return axis == Axis.HORIZONTAL ? cache.getX() : cache.getY();
    }

    private float extent(UIElement item) {
        var cache = item.getRuntimeCache();
        return axis == Axis.HORIZONTAL ? cache.getWidth() : cache.getHeight();
    }

    /**
     * Shows the caret where a drop at this screen point would land, and returns that index.
     *
     * <p>Hides itself for an empty list rather than drawing a caret in a void — there is nothing to insert
     * relative to, and a bar floating in an empty rail reads as a rendering fault.</p>
     */
    public int showFor(UIElement host, List<? extends UIElement> items, float screenX, float screenY) {
        int at = indexFor(host, items, screenX, screenY);
        showAt(host, items, at);
        return at;
    }

    /** Draws the slot at the boundary {@code at} would insert at. @see #showFor */
    public void showAt(UIElement host, List<? extends UIElement> items, int at) {
        if (host == null || items == null || items.isEmpty()) {
            hide();
            return;
        }
        int wanted = Math.max(0, Math.min(at, items.size()));
        if (mode == Mode.IN_FLOW) {
            showInFlow(host, items, wanted);
            return;
        }
        index = wanted;
        boolean append = index >= items.size();
        var edge = items.get(append ? items.size() - 1 : index).getRuntimeCache();

        // THE SIZE OF THE ITEM IT WOULD SIT BESIDE, not a hairline. A caret says only where; a slot says
        // WHAT -- and at the moment you are carrying something, "a thing this size goes here" is the
        // question. Both references draw a placeholder the size of the item: IntelliJ opens a tab-width
        // gap in a strip and a button-sized dashed square in a stripe.
        //
        // Taken from the NEIGHBOUR rather than from the dragged element, which this class cannot see and
        // should not need to: in every list worth reordering the items are uniform, and where they are not
        // it is the space at the boundary that is being described.
        float across = axis == Axis.HORIZONTAL ? edge.getY() : edge.getX();
        float thickness = axis == Axis.HORIZONTAL ? edge.getHeight() : edge.getWidth();
        float extent = axis == Axis.HORIZONTAL ? edge.getWidth() : edge.getHeight();
        float along = axis == Axis.HORIZONTAL ? edge.getX() : edge.getY();
        if (append) along += extent;

        // IN THE CELL, not straddling the seam between two of them.
        //
        // Centring on the boundary was the obvious reading of "between these two" and it looks wrong the
        // moment you try it: half the slot hangs off the first item and out of the list entirely. What the
        // slot is actually showing is the position the dragged item would OCCUPY -- and since nothing here
        // shifts to make room, that position is exactly the cell its neighbour is standing in.
        float slot = Math.max(0f, extent - GAP);
        along += GAP / 2f;

        float hostStart = axis == Axis.HORIZONTAL
                ? host.getRuntimeCache().getX() : host.getRuntimeCache().getY();
        float hostExtent = axis == Axis.HORIZONTAL
                ? host.getRuntimeCache().getWidth() : host.getRuntimeCache().getHeight();
        // CLAMPED INSIDE THE HOST, which is what the append position needs: past the last item is past the
        // end of a rail whose bottom group is pinned to its foot, so an unclamped slot is drawn off the
        // bottom of the stripe and looks like it vanished.
        float local = Math.max(0f, Math.min(along - hostStart, Math.max(0f, hostExtent - slot)));

        float acrossLocal = across - (axis == Axis.HORIZONTAL
                ? host.getRuntimeCache().getY() : host.getRuntimeCache().getX());
        float left = axis == Axis.HORIZONTAL ? local : acrossLocal;
        float top = axis == Axis.HORIZONTAL ? acrossLocal : local;
        float width = axis == Axis.HORIZONTAL ? slot : thickness;
        float height = axis == Axis.HORIZONTAL ? thickness : slot;
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(left).top(top).width(width).height(height));
        StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.opacity(1f));
    }

    /**
     * Opens a gap at {@code at} by becoming an ordinary child there.
     *
     * <p>Re-parented only when the index actually changes, so a pointer resting between two items costs
     * nothing. The size is the neighbour's, as in the overlay case — a gap the width of the thing that
     * would fill it.</p>
     */
    private void showInFlow(UIElement host, List<? extends UIElement> items, int at) {
        var edge = items.get(Math.min(at, items.size() - 1)).getRuntimeCache();
        float thickness = axis == Axis.HORIZONTAL ? edge.getHeight() : edge.getWidth();
        float extent = axis == Axis.HORIZONTAL ? edge.getWidth() : edge.getHeight();
        float width = axis == Axis.HORIZONTAL ? extent : thickness;
        float height = axis == Axis.HORIZONTAL ? thickness : extent;

        if (at != index || getParent() != host) {
            // REMOVED FIRST, then the target index is read. Sibling indices shift when this leaves the
            // list, so computing the destination while still in it puts the gap one place off -- and only
            // when moving downwards, which is the half that looks like a rounding error.
            host.removeInternalChild(this);
            int dom = at >= items.size()
                    ? items.get(items.size() - 1).getSiblingIndex() + 1
                    : items.get(at).getSiblingIndex();
            host.insertInternalChildAt(this, Math.max(0, dom));
        }
        index = at;
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.RELATIVE).display(TaffyDisplay.FLEX)
                .left(0f).top(0f).width(width).height(height));
        StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.opacity(1f));
    }

    /**
     * Clears the slot.
     *
     * <p>Zeroed as well as faded, and kept in the tree rather than removed — the same two rules the dock's
     * drop preview follows. A box at {@code opacity: 0} still has a rect for an outline to paint into, and
     * taking it out of the tree is a structural change to a subtree a drag is live over.</p>
     */
    public void hide() {
        index = -1;
        if (mode == Mode.IN_FLOW) {
            // DISPLAY NONE, not a zero box: an in-flow child of zero size still occupies a flex slot and
            // still takes the container's gap, so the list stays one item's spacing too long after a drag.
            StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l.display(TaffyDisplay.NONE));
            StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.opacity(0f));
            return;
        }
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(0f).height(0f));
        StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.opacity(0f));
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
