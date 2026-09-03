package com.crystalgui.widget.dnd;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import com.crystalgui.ui.box.Box;

/**
 * The placeholder showing where a dragged thing would land in a list — tabs, stripe buttons, rows.
 *
 * <pre>
 * private final InsertionMarker insertion =
 *         new InsertionMarker(InsertionMarker.Axis.HORIZONTAL).mode(InsertionMarker.Mode.IN_FLOW);
 * // once, in the container the items are laid out in:
 * insertion.parkIn(rail);
 * // on the drag's first TICK -- hides the item and opens the gap where it stood:
 * insertion.withdraw(host, items, dragged);
 * // while the pointer is over the list:
 * int index = insertion.showFor(host, items, screenX, screenY);
 * // when the pointer leaves it -- the item stays hidden:
 * insertion.hide();
 * // when the gesture ends, however it ended:
 * insertion.restore();
 * </pre>
 *
 * <h3>Withdrawal is part of the gesture, not the caller's business</h3>
 *
 * <p>{@link #withdraw} and {@link #restore} live here because the three rules they carry are invisible
 * from a call site and every one of them is silent when broken — the same reason the two geometry rules
 * below are. The stripe rail wrote them first and the editor's tab strip needed all three verbatim,
 * which is the second consumer that makes something worth having once.</p>
 *
 * <ol>
 *   <li><b>The thing being carried leaves the list it is being inserted into.</b> Otherwise the list
 *       momentarily shows it twice — once where it is and once where it is going — and, worse, a hidden
 *       item left in the list has no box, so every midpoint test after it answers against a cell that is
 *       not there and the index stops moving as you drag past it.</li>
 *   <li><b>The gap opens in the cell it just left, immediately.</b> Not cosmetic: hiding the item
 *       collapses the list by one cell, so a pointer that has not moved is suddenly sitting in its
 *       neighbour's cell. The symptom is an item that shuffles one place along when you press and
 *       release without dragging at all, and a drag of exactly one place that appears to do nothing
 *       because the two cancel.</li>
 *   <li><b>Hidden, never detached.</b> {@code UIInputHandler.forgetElement} cancels a drag whose source
 *       leaves the tree, so removing the item would end the gesture on its first frame.</li>
 * </ol>
 *
 * <p>The gap is then sized from the withdrawn item itself rather than from its neighbour — see
 * {@link #withdraw}. That is what makes this usable for a list whose items differ in size, which a tab
 * strip is and a stripe of identical icon buttons is not.</p>
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

    public static final Name NAME = Name.of("insertionmarker");

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

    /**
     * The container an {@link Mode#IN_FLOW} gap inserts itself into — where {@link #parkIn} put it.
     *
     * <p>Kept apart from the {@code host} every other method takes, which is the COORDINATE frame. They
     * are the same element for the stripe rail and are not for a tab strip, where the geometry is asked
     * of the group and the gap has to become a sibling of the tabs two levels below it.</p>
     */
    @Nullable
    private UIElement flowParent;

    /** The item hidden for the duration of a drag. @see #withdraw */
    @Nullable
    private UIElement withdrawn;

    /** Its size when it was withdrawn — along the axis, and across it. @see #withdraw */
    private float withdrawnExtent;
    private float withdrawnThickness;

    /** @see Mode */
    public InsertionMarker mode(Mode value) {
        this.mode = value == null ? Mode.OVERLAY : value;
        // RE-HIDDEN IN THE MODE JUST CHOSEN. The constructor's hide() necessarily ran before the mode
        // was, so an IN_FLOW marker began life absolutely positioned at 0x0 -- out of flow, invisible,
        // and not the resting state its own hide() describes. Harmless and worth not having.
        hide();
        return this;
    }

    /**
     * The no-argument constructor the registry's factory needs — horizontal, which is the axis a
     * strip of tabs and a row of buttons both use.
     */
    public InsertionMarker() {
        this(Axis.HORIZONTAL);
    }

    public InsertionMarker(Axis axis) {
        super(NAME);
        this.axis = axis == null ? Axis.HORIZONTAL : axis;
        addClass(MARKER_CLASS);
        setHitTest(false);
        hide();
    }

    /**
     * Puts the marker in {@code host}'s tree, once. Idempotent.
     *
     * <p>For {@link Mode#IN_FLOW} this is the container the gap opens <em>inside</em>, so it has to be
     * the items' own parent — the gap is a sibling of the things it makes room between.</p>
     */
    public InsertionMarker parkIn(UIElement host) {
        if (host == null) return this;
        this.flowParent = host;
        if (parent() == host) return this;
        // A LIGHT child of the host, not one of its parts: the marker belongs to whoever
        // parked it and the host has no idea it is there. Putting it in the host's shadow
        // tree would make it the host's own structure, which it is not.
        host.append(this);
        return this;
    }

    /**
     * Takes {@code source} out of the list for the duration of a drag, and opens the gap where it stood.
     *
     * <p>Idempotent, so it can be called from every tick of a drag — which is where it belongs. <b>The
     * first drag TICK, never the mouse-down</b>: a payload drag fires nothing until the pointer has
     * passed its activation threshold, so a press that never really moved stays an ordinary click.
     * Hiding on the press makes the item vanish the instant you touch it and come back if you let go.</p>
     *
     * <p><b>The gap is the size of the thing being carried</b>, measured here, before it is hidden. The
     * alternative — taking it from whichever neighbour the gap sits beside — is what this class did when
     * a stripe of identical 20px buttons was its only consumer, and it is invisible there. In a tab strip
     * the items are all different widths, so a gap borrowed from a neighbour states the space the tab
     * will take and is wrong by the difference between two filenames.</p>
     */
    public InsertionMarker withdraw(UIElement host, List<? extends UIElement> items, UIElement source) {
        if (source == null || source == withdrawn) return this;
        restore();
        withdrawn = source;
        withdrawnExtent = extent(source);
        withdrawnThickness = thickness(source);
        // DISPLAY NONE, NOT A DETACH -- see the class note. A zero-size box would not do either: an
        // in-flow child of zero size still occupies a flex slot and still takes the container's gap.
        StyleGroup.inlinePipeline(source.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.NONE));
        int wasAt = items == null ? -1 : items.indexOf(source);
        if (wasAt >= 0) showAt(host, items, wasAt);
        return this;
    }

    /**
     * Puts the withdrawn item back and closes the gap. Idempotent, and safe when nothing was withdrawn.
     *
     * <p>Call it from every ending a drag has — dropped, cancelled, released over nothing. Deliberately
     * <b>not</b> what {@link #hide} does: the pointer leaving the list closes the gap while the gesture
     * is still running, and the item has to stay hidden for as long as it is being carried.</p>
     *
     * <p>Restores {@code display: flex}, which is where every consumer of this class starts from — a
     * list you can reorder is a flex row or column by construction. Withdrawing the candidate instead
     * would be the tidier repair and cannot be done here: removing a layout candidate re-resolves the
     * property through {@code TaffyBridge}, and by the time some of these endings run the node is gone.</p>
     *
     * <p>Skipped for an item that has left the tree, which a drop routinely does — its list is rebuilt
     * around the panel's new home. Writing a layout property to a detached element reaches
     * {@code TaffyBridge} with no node behind it, and there is nothing to put back either: the element
     * being restored is one nobody can see.</p>
     */
    public InsertionMarker restore() {
        UIElement source = withdrawn;
        withdrawn = null;
        withdrawnExtent = 0f;
        withdrawnThickness = 0f;
        hide();
        if (source != null && source.document() != null) {
            StyleGroup.inlinePipeline(source.getStyle().getLayoutGroup(),
                    l -> l.display(TaffyDisplay.FLEX));
        }
        return this;
    }

    /** The item currently taken out of the list, or {@code null}. @see #withdraw */
    @Nullable
    public UIElement withdrawn() {
        return withdrawn;
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
        var local = host.toLocal(screenX, screenY);
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
        // WITHOUT THE ONE BEING CARRIED, for every caller at once. It is hidden for the duration of the
        // drag so it has no box, and a zero-extent entry makes every midpoint test after it answer
        // against a cell that is not there. It is also simply not part of the list being inserted into.
        if (withdrawn != null) sorted.remove(withdrawn);
        sorted.sort((a, b) -> Float.compare(start(a), start(b)));
        return sorted;
    }

    private float start(UIElement item) {
        var cache = item.box();
        return axis == Axis.HORIZONTAL ? cache.x() : cache.y();
    }

    private float extent(UIElement item) {
        var cache = item.box();
        return axis == Axis.HORIZONTAL ? cache.width() : cache.height();
    }

    /** The item's size ACROSS the axis — a tab's height, a stripe button's width. */
    private float thickness(UIElement item) {
        var cache = item.box();
        return axis == Axis.HORIZONTAL ? cache.height() : cache.width();
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

        // PLACED AGAINST A VISIBLE NEIGHBOUR. `items` still contains the withdrawn one -- callers pass
        // their real list and this class does the removing -- and it has no box to measure or sit beside.
        List<? extends UIElement> visible = ordered(items);
        if (visible.isEmpty()) {
            hide();
            return;
        }
        int before = 0;
        for (UIElement item : visible) {
            if (items.indexOf(item) < index) before++;
        }
        boolean append = before >= visible.size();
        var edge = visible.get(append ? visible.size() - 1 : before).box();

        // THE SIZE OF THE ITEM IT WOULD SIT BESIDE, not a hairline. A caret says only where; a slot says
        // WHAT -- and at the moment you are carrying something, "a thing this size goes here" is the
        // question. Both references draw a placeholder the size of the item: IntelliJ opens a tab-width
        // gap in a strip and a button-sized dashed square in a stripe.
        //
        // Taken from the NEIGHBOUR rather than from the dragged element, which this class cannot see and
        // should not need to: in every list worth reordering the items are uniform, and where they are not
        // it is the space at the boundary that is being described.
        float across = axis == Axis.HORIZONTAL ? edge.y() : edge.x();
        float neighbourExtent = axis == Axis.HORIZONTAL ? edge.width() : edge.height();
        float thickness = withdrawn != null
                ? withdrawnThickness : (axis == Axis.HORIZONTAL ? edge.height() : edge.width());
        float extent = withdrawn != null ? withdrawnExtent : neighbourExtent;
        float along = axis == Axis.HORIZONTAL ? edge.x() : edge.y();
        // THE NEIGHBOUR'S width to step PAST it, the carried item's to SIZE the slot. The two are the
        // same number only in a list of identical items, which is what hid the difference while a stripe
        // of 20px buttons was the only consumer.
        if (append) along += neighbourExtent;

        // IN THE CELL, not straddling the seam between two of them.
        //
        // Centring on the boundary was the obvious reading of "between these two" and it looks wrong the
        // moment you try it: half the slot hangs off the first item and out of the list entirely. What the
        // slot is actually showing is the position the dragged item would OCCUPY -- and since nothing here
        // shifts to make room, that position is exactly the cell its neighbour is standing in.
        float slot = Math.max(0f, extent - GAP);
        along += GAP / 2f;

        // A host nothing has laid out has nowhere to put a marker -- and the alternative, treating its
        // box as the origin, draws the slot in the corner of the screen rather than not at all.
        Box hostBox = host.box();
        if (hostBox == null) return;

        float hostStart = axis == Axis.HORIZONTAL
                ? hostBox.x() : hostBox.y();
        float hostExtent = axis == Axis.HORIZONTAL
                ? hostBox.width() : hostBox.height();
        // CLAMPED INSIDE THE HOST, which is what the append position needs: past the last item is past the
        // end of a rail whose bottom group is pinned to its foot, so an unclamped slot is drawn off the
        // bottom of the stripe and looks like it vanished.
        float local = Math.max(0f, Math.min(along - hostStart, Math.max(0f, hostExtent - slot)));

        float acrossLocal = across - (axis == Axis.HORIZONTAL
                ? hostBox.y() : hostBox.x());
        float left = axis == Axis.HORIZONTAL ? local : acrossLocal;
        float top = axis == Axis.HORIZONTAL ? acrossLocal : local;
        float width = axis == Axis.HORIZONTAL ? slot : thickness;
        float height = axis == Axis.HORIZONTAL ? thickness : slot;
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(left).top(top).width(width).height(height));
        StyleGroup.inlinePipeline(getStyle().getGeneralGroup(), g -> g.opacity(1f));
    }

    /**
     * Opens a gap at {@code at} by becoming an ordinary child there.
     *
     * <p>Re-parented only when the index actually changes, so a pointer resting between two items costs
     * nothing. The size is the neighbour's, as in the overlay case — a gap the width of the thing that
     * would fill it.</p>
     */
    private void showInFlow(UIElement host, List<? extends UIElement> items, int at) {
        float thickness = withdrawnThickness;
        float extent = withdrawnExtent;
        if (withdrawn == null) {
            // NOTHING IS BEING CARRIED THROUGH THIS MARKER -- a tab dragged in from another window, say,
            // whose own strip did the withdrawing. The neighbour is then the only estimate available.
            var edge = items.get(Math.min(at, items.size() - 1)).box();
            thickness = axis == Axis.HORIZONTAL ? edge.height() : edge.width();
            extent = axis == Axis.HORIZONTAL ? edge.width() : edge.height();
        }
        float width = axis == Axis.HORIZONTAL ? extent : thickness;
        float height = axis == Axis.HORIZONTAL ? thickness : extent;

        // THE PARKED CONTAINER, not the coordinate host -- see flowParent. A tab strip asks the group
        // about geometry and needs the gap to become a sibling of the tabs, two levels down from it.
        UIElement slot = flowParent != null ? flowParent : host;
        if (at != index || parent() != slot) {
            // REMOVED FIRST, then the target index is read. Sibling indices shift when this leaves the
            // list, so computing the destination while still in it puts the gap one place off -- and only
            // when moving downwards, which is the half that looks like a rounding error.
            slot.remove(this);
            // ASKED OF THE ITEM ITSELF rather than counted, so a hidden one still answers: the withdrawn
            // item keeps its DOM slot, which is exactly where the gap that replaces it belongs.
            int dom = at >= items.size()
                    ? slot.indexOf(items.get(items.size() - 1)) + 1
                    : slot.indexOf(items.get(at));
            slot.insertAt(Math.max(0, dom), this);
        }
        index = at;
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.RELATIVE).display(TaffyDisplay.FLEX)
                .left(0f).top(0f).width(width).height(height));
        StyleGroup.inlinePipeline(getStyle().getGeneralGroup(), g -> g.opacity(1f));
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
            StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.display(TaffyDisplay.NONE));
            StyleGroup.inlinePipeline(getStyle().getGeneralGroup(), g -> g.opacity(0f));
            return;
        }
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(0f).height(0f));
        StyleGroup.inlinePipeline(getStyle().getGeneralGroup(), g -> g.opacity(0f));
    }
}
