package com.crystalgui.widget.collection.tree;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;
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
 */
final class TreeDragAndDrop<T> {

    /** On the row a drop would land inside. */
    static final String DROP_INTO_CLASS = "__drop-target__";

    /** On the row a drop would land before. */
    static final String DROP_BEFORE_CLASS = "__drop-before__";

    /** On the row a drop would land after. */
    static final String DROP_AFTER_CLASS = "__drop-after__";

    private final TreeEditing<T> editing;

    private final DragGhost ghost = new DragGhost();

    @Nullable
    private UIElement markedRow;

    @Nullable
    private String markedClass;

    /** The items being carried, and the editing they came from — so a drag from another tree is ignored. */
    private record Payload(TreeEditing<?> from, List<?> items) {
    }

    /** Where a drop would land, and which row and mark say so. */
    record Spot<T>(UIElement row, String mark, TreeEditModel.Target<T> target) {
    }

    TreeDragAndDrop(TreeEditing<T> editing, UIElement host) {
        this.editing = editing;
        ghost.parkIn(host);
    }

    /** Makes a row draggable. Once per row, from {@code createTemplate}. */
    void installRow(UIElement row) {
        row.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            // NEVER FROM THE KEYBOARD: Enter and Space arrive as a synthesized press at the resting cursor, and
            // a drag armed by one can never be released.
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON || event.getDetail() == Input.KEYBOARD_DETAIL) return;
            TreeEditModel<T> model = editing.model();
            UIDocument window = editing.tree().document();
            T item = editing.itemForRow(row);
            if (model == null || window == null || item == null || !model.canEdit(item)) return;
            List<T> selection = editing.selection();
            List<T> carried = new ArrayList<>(selection.contains(item) ? selection : List.of(item));
            carried.removeIf(each -> !model.canEdit(each));
            if (carried.isEmpty()) return;
            ghost.follow(window, carried.size() == 1 ? model.iconOf(carried.get(0)) : null,
                    carried.size() == 1 ? model.labelOf(carried.get(0)) : carried.size() + " items");
            Drag.start(row, event.getPosition().x(), event.getPosition().y(), CgMouseCodes.LEFT_BUTTON,
                    new Payload(editing, carried), Drag.DEFAULT_THRESHOLD_PX,
                    (x, y, sx, sy, dx, dy) -> { });
        }, false, false);
    }

    /** Makes the tree take drops from its own rows. Once. */
    void installDropTarget() {
        TreeView<T> tree = editing.tree();
        tree.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            List<T> items = carried(event.getPayload());
            if (items == null) return;
            Spot<T> spot = spotFor((UIElement) event.getTarget(), event.getPosition());
            mark(spot);
            // ACCEPTED BY preventDefault, re-asked every frame, so wandering over a refusal stops accepting.
            if (spot != null && accepts(items, spot)) event.preventDefault();
        }, false, true);
        tree.events.getGroup(DragEvent.Leave.class).attachListener((element, event) -> mark(null), false, true);
        tree.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            mark(null);
            List<T> items = carried(event.getPayload());
            TreeEditModel<T> model = editing.model();
            if (items == null || model == null) return;
            Spot<T> spot = spotFor((UIElement) event.getTarget(), event.getPosition());
            if (spot == null || !accepts(items, spot)) return;
            // AT DROP TIME, so the destination is picked first and the key held after.
            boolean copy = (CgPlatform.input().getCurrentModifiers() & model.copyModifier()) != 0;
            if (copy) model.copy(items, spot.target());
            else model.move(items, spot.target());
        }, false, true);
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

    /** Where a drop over {@code hit} at surface {@code position} lands, or null off the rows. */
    @Nullable
    Spot<T> spotFor(@Nullable UIElement hit, ReadOnlyVec2f position) {
        TreeEditModel<T> model = editing.model();
        UIElement row = editing.rowElementFor(hit);
        T item = row == null ? null : editing.itemForRow(row);
        Box box = row == null ? null : row.box();
        if (model == null || item == null || box == null) return null;
        Vector2f local = row.toLocal(position.x(), position.y());
        return spotAt(model, row, item, local.y / Math.max(1f, box.height()));
    }

    /**
     * The spot for a pointer {@code fraction} of the way down {@code row}.
     *
     * <p>Package-private so the geometry is testable without a pointer.</p>
     */
    Spot<T> spotAt(TreeEditModel<T> model, UIElement row, T item, float fraction) {
        T parent = model.parentOf(item);
        boolean container = model.isContainer(item);
        if (!model.isOrdered()) {
            T into = container || parent == null ? item : parent;
            return new Spot<>(row, DROP_INTO_CLASS, new TreeEditModel.Target<>(into, -1));
        }
        if (parent == null) return new Spot<>(row, DROP_INTO_CLASS, new TreeEditModel.Target<>(item, -1));
        int index = model.indexOf(item);
        if (fraction < 0.25f || (!container && fraction < 0.5f)) {
            return new Spot<>(row, DROP_BEFORE_CLASS, new TreeEditModel.Target<>(parent, index));
        }
        if (fraction > 0.75f || !container) {
            // AFTER AN OPEN CONTAINER is before its first child: that row is what sits under the line.
            if (container && isOpenWithChildren(row)) {
                return new Spot<>(row, DROP_AFTER_CLASS, new TreeEditModel.Target<>(item, 0));
            }
            return new Spot<>(row, DROP_AFTER_CLASS, new TreeEditModel.Target<>(parent, index + 1));
        }
        return new Spot<>(row, DROP_INTO_CLASS, new TreeEditModel.Target<>(item, -1));
    }

    private boolean isOpenWithChildren(UIElement row) {
        TreeView<T> tree = editing.tree();
        int index = tree.indexOfRowElement(row);
        TreeRow<T> at = index < 0 ? null : tree.rowAt(index);
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
        if (row == markedRow && (mark == null ? markedClass == null : mark.equals(markedClass))) return;
        if (markedRow != null && markedClass != null) markedRow.removeClass(markedClass);
        markedRow = row;
        markedClass = mark;
        if (row != null && mark != null) row.addClass(mark);
    }
}
