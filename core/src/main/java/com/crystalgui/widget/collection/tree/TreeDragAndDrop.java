package com.crystalgui.widget.collection.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.service.DragData;
import com.crystalgui.ui.service.Input;
import com.crystalgui.widget.dnd.DragGhost;

/**
 * {@link TreeEditing}'s drag: rows carry the selection, and the row under the pointer says where it lands.
 *
 * <p>VS Code's {@code FileDragAndDrop} for any tree. In an ordered tree a row's top quarter is <em>before</em>
 * it, its bottom quarter <em>after</em> and its middle <em>into</em> it when it can take children, as
 * {@code listView.ts} splits it; after an open container with children means its first child. In an unordered
 * tree a drop lands into the row's item, or into its parent when the item is a leaf. The model decides whether
 * a drop is allowed and performs it; the modifier it names makes it a copy.</p>
 *
 * <p><b>After the last row of a group, the pointer's X picks the depth</b> — Atlassian pragmatic-drag-and-drop's
 * {@code hitbox/tree-item.ts} (Apache-2.0) {@code reparent} instruction. Below a container's last child the line
 * could mean "its last child" or "after the container", and a vertical split cannot tell them apart: over the row
 * it stays in, left of its indent it steps out a level per indent, down to the outermost group the row ends.
 * The line starts at the level it lands in, so the two read differently.</p>
 *
 * <p><b>Under the last row is outside its group.</b> Mid-tree the next row's top quarter already says "after the
 * group"; at the end of the tree the empty space below says it — one level out just under it, a level more every
 * half row-height further down — and X takes it further.</p>
 */
final class TreeDragAndDrop<T> {

    /** On the row a drop would land inside. */
    static final String DROP_INTO_CLASS = "__drop-target__";

    /** On the row a drop would land before. */
    static final String DROP_BEFORE_CLASS = "__drop-before__";

    /** On the row a drop would land after. */
    static final String DROP_AFTER_CLASS = "__drop-after__";

    /** On a tree's ghost: a row's icon and label in one capsule, where a rail button's ghost is a chip. */
    static final String ROW_GHOST_CLASS = "__row-ghost__";

    private final TreeEditing<T> editing;

    private final DragGhost ghost = new DragGhost();

    @Nullable
    private UIElement markedRow;

    @Nullable
    private String markedClass;

    private int markedLevel = -1;

    /** How long a drag rests on a closed branch before it opens — VS Code's explorer. */
    static final float AUTO_EXPAND_SECONDS = 0.5f;

    /** How far inside the tree's top or bottom edge a drag scrolls it, in the tree's logical pixels. */
    static final float SCROLL_BAND = 24f;

    /** The most a drag scrolls the tree in one 60 Hz frame. */
    static final float SCROLL_MAX_STEP = 10f;

    /** Bumped when a drag leaves or drops, so the hook of an earlier drag ends itself. */
    private int generation;

    private boolean hovering;

    /** The pointer, in surface pixels, as the last Over left it. */
    private float pointerX;

    private float pointerY;

    /** The closed branch the drag would land in, and how long it has rested there. */
    @Nullable
    private T opening;

    private float openingSeconds;

    /**
     * The items being carried, and the editing they came from — so a drag from another tree is ignored — plus what
     * the model offers anything else. @see TreeEditModel#transfer
     */
    private record Payload(TreeEditing<?> from, List<?> items, @Nullable Object transfer) implements DragData {

        @Nullable
        @Override
        public <D> D as(Class<D> type) {
            return type.isInstance(transfer) ? type.cast(transfer) : null;
        }
    }

    /**
     * Where a drop would land, and which row and mark say so.
     *
     * @param level the depth whose indent a before/after line starts at, or -1 for a drop into the row
     */
    record Spot<T>(UIElement row, String mark, TreeEditModel.Target<T> target, int level) {

        Spot(UIElement row, String mark, TreeEditModel.Target<T> target) {
            this(row, mark, target, -1);
        }
    }

    /**
     * A before/after line's left offset with no level to show: the user-agent sheet's {@code outline-offset: -1px}
     * on {@code *}, which keeps an outline inside a clipping panel.
     */
    private static final float LINE_BASE_OFFSET = -1f;

    /** How far under the last row, in row-heights, each further level out is. */
    static final float LEVEL_STEP_ROWS = 0.5f;

    TreeDragAndDrop(TreeEditing<T> editing, UIElement host) {
        this.editing = editing;
        ghost.addClass(ROW_GHOST_CLASS);
        ghost.parkIn(host);
    }

    /**
     * Makes a row draggable from anywhere in it but its rename {@code field}, with a ghost copying its {@code icon}
     * and {@code label}. Once per row, from {@code createTemplate}.
     */
    void installRow(UIElement row, @Nullable UIElement icon, UIElement label, UIElement field) {
        // BUBBLING, as the list's own selection does: a press lands on the row's label or icon as often as on the
        // row, and a renderer need not make its parts unhittable for the row to drag.
        row.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            // NEVER FROM THE KEYBOARD: Enter and Space arrive as a synthesized press at the resting cursor, and
            // a drag armed by one can never be released.
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON || event.getDetail() == Input.KEYBOARD_DETAIL) return;
            // THE RENAME FIELD'S PRESS IS ITS OWN: a row drag pushed above its selection drag would take its moves.
            if (isInside((UIElement) event.getTarget(), field)) return;
            TreeEditModel<T> model = editing.model();
            UIDocument window = editing.tree().document();
            T item = editing.itemForRow(row);
            if (model == null || window == null || item == null || !model.canEdit(item)) return;
            List<T> selection = editing.selection();
            List<T> carried = new ArrayList<>(selection.contains(item) ? selection : List.of(item));
            carried.removeIf(each -> !model.canEdit(each));
            if (carried.isEmpty()) return;
            // ONE ITEM IS THE PRESSED ROW, which is always editable and always carried.
            if (carried.size() == 1) ghost.follow(window, icon, label);
            else ghost.follow(window, null, carried.size() + " items");
            Drag.start(row, event.getPosition().x(), event.getPosition().y(), CgMouseCodes.LEFT_BUTTON,
                    new Payload(editing, carried, model.transfer(carried)), Drag.DEFAULT_THRESHOLD_PX,
                    (x, y, sx, sy, dx, dy) -> { });
        }, false, true);
    }

    private static boolean isInside(@Nullable UIElement node, UIElement ancestor) {
        for (UIElement at = node; at != null; at = at.composedParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }

    /** Makes the tree take drops from its own rows. Once. */
    void installDropTarget() {
        TreeView<T> tree = editing.tree();
        tree.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            TreeEditModel<T> model = editing.model();
            Object payload = event.getPayload();
            List<T> items = carried(payload);
            if (model == null || payload == null) return;
            Spot<T> spot = spotFor((UIElement) event.getTarget(), event.getPosition().x(), event.getPosition().y());
            boolean accepted = spot != null && (items != null ? accepts(items, spot)
                    : model.canDropForeign(payload, spot.target().parent()));
            // A PAYLOAD FROM OUTSIDE that the model refuses leaves the rows unmarked: another tree's drag is not about this one.
            if (items == null && !accepted) {
                mark(null);
                return;
            }
            mark(spot);
            pointerX = event.getPosition().x();
            pointerY = event.getPosition().y();
            aimAt(spot);
            startHovering();
            // ACCEPTED BY preventDefault, re-asked on every move, so wandering over a refusal stops accepting.
            if (accepted) event.preventDefault();
        }, false, true);
        tree.events.getGroup(DragEvent.Leave.class).attachListener((element, event) -> {
            // FROM THE TREE ITSELF, not a row: a Leave from each row the pointer crosses would stop the hook
            // on every move.
            if (event.getTarget() != tree) return;
            stopHovering();
            mark(null);
        }, false, true);
        tree.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            stopHovering();
            mark(null);
            List<T> items = carried(event.getPayload());
            TreeEditModel<T> model = editing.model();
            if (model == null) return;
            Spot<T> spot = spotFor((UIElement) event.getTarget(), event.getPosition().x(), event.getPosition().y());
            if (spot == null) return;
            if (items == null) {
                Object foreign = event.getPayload();
                if (foreign != null && model.canDropForeign(foreign, spot.target().parent())) {
                    model.dropForeign(foreign, spot.target());
                }
                return;
            }
            if (!accepts(items, spot)) return;
            // AT DROP TIME, so the destination is picked first and the key held after.
            boolean copy = (CgPlatform.input().getCurrentModifiers() & model.copyModifier()) != 0;
            if (copy) model.copy(items, spot.target());
            else model.move(items, spot.target());
        }, false, true);
    }

    // ── While a drag is over the tree ───────────────────────────────────────────────────────────
    //
    // A Drag sends Over only when the pointer MOVES, and both of these are about a pointer held still: over a
    // closed folder, and against the edge. So a per-frame hook runs them, from the first Over until the drag
    // leaves the tree, drops, or stops.

    /** Aims the auto-expand at {@code spot}'s row when the drop would land inside that row and it is closed. */
    void aimAt(@Nullable Spot<T> spot) {
        T candidate = null;
        if (spot != null) {
            TreeRow<T> row = rowOf(spot.row());
            if (row != null && row.expandable() && !row.expanded()
                    && Objects.equals(spot.target().parent(), row.item())) {
                candidate = row.item();
            }
        }
        if (Objects.equals(candidate, opening)) return;
        opening = candidate;
        openingSeconds = 0f;
    }

    private void startHovering() {
        if (hovering) return;
        UIDocument window = editing.tree().document();
        if (window == null) return;
        hovering = true;
        int mine = ++generation;
        window.animation().every(editing.tree(), delta -> {
            if (mine != generation) return false;
            if (window.input().mode(Drag.class) == null) {
                stopHovering();
                mark(null);
                return false;
            }
            tickHover(delta);
            return true;
        });
    }

    private void stopHovering() {
        generation++;
        hovering = false;
        opening = null;
        openingSeconds = 0f;
    }

    /** One frame of a drag resting over the tree: scroll against an edge, then open a branch rested on. */
    void tickHover(float deltaSeconds) {
        TreeView<T> tree = editing.tree();
        Box box = tree.box();
        if (box == null) return;
        float frames = Math.max(0f, deltaSeconds) * 60f;
        float step = scrollStep(tree.toLocal(pointerX, pointerY).y, box.clientHeight()) * frames;
        if (step != 0f) {
            float before = box.scrollTop();
            box.setScroll(box.scrollLeft(), before + step);
            // THE ROWS MOVED UNDER A STILL POINTER, and no Over is coming to say which one it is on now. Only when
            // they did: a tree already at its end clamps the step to nothing.
            if (box.scrollTop() != before) reaim();
        }
        if (opening == null) return;
        openingSeconds += Math.max(0f, deltaSeconds);
        if (openingSeconds < AUTO_EXPAND_SECONDS) return;
        T item = opening;
        opening = null;
        // OFF FIRST: opening re-flattens, and the marked row may come back showing another item.
        mark(null);
        tree.setExpanded(item, true);
    }

    /** Re-resolves the row under the still pointer after the rows moved. */
    private void reaim() {
        UIDocument window = editing.tree().document();
        if (window == null) return;
        Box under = window.boxes().hitTest(pointerX, pointerY, b -> window.focus().isInert(b.node()));
        Spot<T> spot = under == null ? null : spotFor(under.node(), pointerX, pointerY);
        mark(spot);
        aimAt(spot);
    }

    /**
     * How far one 60 Hz frame scrolls with the pointer {@code y} logical pixels down a viewport {@code height}
     * tall — VS Code's {@code listView.ts} {@code animateDragAndDropScrollTop} (MIT): 0.3 of the depth into the
     * band, capped, negative at the top. Its 35 px band and 14 px cap sized to this engine's denser rows.
     */
    static float scrollStep(float y, float height) {
        if (height <= SCROLL_BAND * 2f) return 0f;
        if (y < SCROLL_BAND) return Math.max(-SCROLL_MAX_STEP, 0.3f * (y - SCROLL_BAND));
        float lower = height - SCROLL_BAND;
        if (y > lower) return Math.min(SCROLL_MAX_STEP, 0.3f * (y - lower));
        return 0f;
    }

    @Nullable
    private TreeRow<T> rowOf(UIElement row) {
        int index = editing.tree().indexOfRowElement(row);
        return index < 0 ? null : editing.tree().rowAt(index);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private List<T> carried(@Nullable Object payload) {
        return payload instanceof Payload carried && carried.from() == editing ? (List<T>) carried.items() : null;
    }

    private boolean accepts(List<T> items, Spot<T> spot) {
        TreeEditModel<T> model = editing.model();
        return model != null && model.canDrop(items, spot.target().parent());
    }

    /** Where a drop over {@code hit} at surface point ({@code x}, {@code y}) lands, or null off the rows. */
    @Nullable
    Spot<T> spotFor(@Nullable UIElement hit, float x, float y) {
        TreeEditModel<T> model = editing.model();
        if (model == null) return null;
        UIElement row = editing.rowElementFor(hit);
        if (row == null) return belowTheRows(model, x, y);
        T item = editing.itemForRow(row);
        Box box = row.box();
        if (item == null || box == null) return null;
        Vector2f local = row.toLocal(x, y);
        return spotAt(model, row, item, local.y / Math.max(1f, box.height()), local.x);
    }

    /**
     * The empty space under the last row: after the group that row ends, one level out per half row-height down.
     *
     * <p>Dragging UNDER a group is how a person says "not in it" — mid-tree the next row's top quarter already
     * means that, and at the end of the tree there was no row to say it and so no drop at all. Its own bottom
     * quarter stays its parent's last child; X steps further out from here as it does on the row.</p>
     */
    @Nullable
    private Spot<T> belowTheRows(TreeEditModel<T> model, float x, float y) {
        if (!model.isOrdered()) return null;
        TreeView<T> tree = editing.tree();
        UIElement row = tree.realisedRows().get(tree.getModel().size() - 1);
        T item = row == null ? null : editing.itemForRow(row);
        Box box = row == null ? null : row.box();
        if (item == null || box == null || model.parentOf(item) == null) return null;
        Vector2f local = row.toLocal(x, y);
        float height = Math.max(1f, box.height());
        if (local.y < height) return null;
        int depth = depthOf(row);
        // A LEVEL A HALF ROW-HEIGHT: the first gap under the row is one level out, the next two, so a deep group is
        // left one level at a time by dragging further down. A whole row a level was too much travel.
        float step = height * LEVEL_STEP_ROWS;
        int out = 1 + (int) Math.floor((local.y - height) / step);
        return after(model, row, item, depth, Math.min(levelAt(local.x, depth), depth - out));
    }

    /** As {@link #spotAt(TreeEditModel, UIElement, Object, float, float)}, with the pointer over the row's label. */
    Spot<T> spotAt(TreeEditModel<T> model, UIElement row, T item, float fraction) {
        return spotAt(model, row, item, fraction, Float.POSITIVE_INFINITY);
    }

    /**
     * The spot for a pointer {@code fraction} of the way down {@code row} and {@code x} pixels in from its left edge.
     *
     * <p>Package-private so the geometry is testable without a pointer.</p>
     */
    Spot<T> spotAt(TreeEditModel<T> model, UIElement row, T item, float fraction, float x) {
        T parent = model.parentOf(item);
        boolean container = model.isContainer(item);
        if (!model.isOrdered()) {
            T into = container || parent == null ? item : parent;
            return new Spot<>(row, DROP_INTO_CLASS, new TreeEditModel.Target<>(into, -1));
        }
        if (parent == null) return new Spot<>(row, DROP_INTO_CLASS, new TreeEditModel.Target<>(item, -1));
        int index = model.indexOf(item);
        int depth = depthOf(row);
        if (fraction < 0.25f || (!container && fraction < 0.5f)) {
            return new Spot<>(row, DROP_BEFORE_CLASS, new TreeEditModel.Target<>(parent, index), depth);
        }
        if (fraction > 0.75f || !container) {
            // AFTER AN OPEN CONTAINER is before its first child: that row is what sits under the line.
            if (container && isOpenWithChildren(row)) {
                return new Spot<>(row, DROP_AFTER_CLASS, new TreeEditModel.Target<>(item, 0), depth + 1);
            }
            return after(model, row, item, depth, levelAt(x, depth));
        }
        return new Spot<>(row, DROP_INTO_CLASS, new TreeEditModel.Target<>(item, -1));
    }

    /** {@code tree-item.ts}' {@code reparent}: the level asked for is how many whole indents {@code x} is in. */
    private int levelAt(float x, int depth) {
        float indent = editing.tree().getIndentPerDepth();
        return indent <= 0f ? depth : (int) Math.floor(x / indent);
    }

    /**
     * After {@code item} at level {@code wanted}: its own, or — when it is its parent's last child, and that parent
     * its own parent's, and so on — after one of those ancestors instead, held between the outermost group this row
     * ends and the row's own depth.
     */
    private Spot<T> after(TreeEditModel<T> model, UIElement row, T item, int depth, int wanted) {
        // ONLY OUT OF A GROUP THIS ROW ENDS: the row below sits at the shallowest level the line may reach, since
        // stepping past it would put the drop after something the line is drawn above.
        int floor = depthBelow(row);
        T at = item;
        T parent = model.parentOf(item);
        int level = depth;
        while (level > wanted && level > floor) {
            T grandparent = model.parentOf(parent);
            if (grandparent == null) break;   // the root takes no siblings
            at = parent;
            parent = grandparent;
            level--;
        }
        return new Spot<>(row, DROP_AFTER_CLASS, new TreeEditModel.Target<>(parent, model.indexOf(at) + 1), level);
    }

    /** The depth of the row after {@code row}, or 0 when it is the last: every group deeper than that ends here. */
    private int depthBelow(UIElement row) {
        int index = editing.tree().indexOfRowElement(row);
        TreeRow<T> next = index < 0 ? null : editing.tree().rowAt(index + 1);
        return next == null ? 0 : next.depth();
    }

    /** The row's depth as the tree draws it, which is what its indent is measured from. */
    private int depthOf(UIElement row) {
        TreeRow<T> at = rowOf(row);
        return at == null ? 0 : at.depth();
    }

    private boolean isOpenWithChildren(UIElement row) {
        TreeRow<T> at = rowOf(row);
        return at != null && at.expandable() && at.expanded();
    }

    /**
     * Moves the mark to {@code spot}'s row, or clears it.
     *
     * <p>Held rather than re-derived: the row to take it off may have scrolled out, or been recycled to show
     * another item, by the time the pointer moves on.</p>
     */
    private void mark(@Nullable Spot<T> spot) {
        UIElement row = spot == null ? null : spot.row();
        String mark = spot == null ? null : spot.mark();
        int level = spot == null ? -1 : spot.level();
        if (row == markedRow && level == markedLevel
                && (mark == null ? markedClass == null : mark.equals(markedClass))) return;
        if (markedRow != null) {
            if (markedClass != null) markedRow.removeClass(markedClass);
            markedRow.getStyle().removeCandidates(StylePropertyRegistry.OUTLINE_OFFSET_LEFT,
                    slot -> slot.origin() == StyleOrigin.INLINE);
        }
        markedRow = row;
        markedClass = mark;
        markedLevel = level;
        if (row == null || mark == null) return;
        row.addClass(mark);
        // THE LINE STARTS WHERE THE DROP LANDS, at the indent of its level: "last child" and "after the group" are
        // two lines rather than one.
        if (level >= 0) {
            float inset = level * editing.tree().getIndentPerDepth();
            row.getStyle().replaceOrPutCandidate(StylePropertyRegistry.OUTLINE_OFFSET_LEFT, StyleSlot.of(
                    StylePropertyRegistry.OUTLINE_OFFSET_LEFT, StyleOrigin.INLINE, 0, 0L,
                    LengthPercent.px(LINE_BASE_OFFSET - inset)));
        }
    }
}
