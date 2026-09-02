package com.crystalgui.widget.collection.table;

import com.crystalgui.core.collection.table.SortOrder;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * A table: a <b>sorted view</b> over a source list, with columns, a pinned header and draggable
 * dividers — built on {@link ListView}, exactly as {@code TreeView} is.
 *
 * <h3>Row-focused, not cell-focused</h3>
 * <p>The ARIA {@code grid} role puts focus on a <em>cell</em> and moves it with the arrows; the
 * {@code table} role is static. This is the third thing, and the one a file browser's details view
 * actually is: a <b>list that happens to have columns</b>. Arrows move between rows, selection selects
 * rows, and nobody has ever wanted to arrow into the "date modified" column.</p>
 *
 * <p>So the entire keyboard and selection contract is inherited from {@code ListView} unchanged.
 * Cell-level focus is the {@code grid} role and remains a later addition rather than a different design
 * — {@link TableColumn} already knows how to render a cell, so making cells focusable is additive. The
 * genuinely cell-shaped case, a property inspector, is closer to a form and belongs to the Configurator.</p>
 *
 * <h3>Sorting never touches the source</h3>
 * <p>Clicking a header must not reorder somebody else's data. The caller's {@link ObservableList} is
 * read-only here; the list underneath sees a derived view, the same shape as {@code TreeView}'s flattened
 * model. That is also what makes a third click able to restore the original order — it was never lost.</p>
 */
public class TableView<T> extends ListView<T> {

    public static final Name NAME = Name.of("tableview");

    public static final String HEADER_CLASS = "__header__";
    public static final String HEADER_CELL_CLASS = "__header-cell__";
    public static final String DIVIDER_CLASS = "__divider__";
    public static final String CELL_CLASS = "__cell__";
    public static final String SORTED_ASC_CLASS = "__sorted-asc__";
    public static final String SORTED_DESC_CLASS = "__sorted-desc__";

    @Getter
    private final ObservableList<T> source;

    private final List<TableColumn<T>> columns = new ArrayList<>();
    private final UINode header = new UINode();

    /**
     * The live header cells, parallel to {@link #columns}.
     *
     * <p>Held so the header can be <b>updated</b> rather than rebuilt. Rebuilding destroys and recreates
     * elements, and doing that from a mouse handler destroys the element the mouse is currently
     * interacting with — which is how the header froze: a divider drag rebuilt the header on every
     * update, so the dragged divider was detached while the pointer was still captured on it, and a
     * captured pointer routes <em>every</em> subsequent event to that target. Clicks and drags both died.
     * Clicking a header to sort did the same to the cell under the cursor.</p>
     */
    private final List<UINode> headerCells = new ArrayList<>();

    /** The live dividers, parallel to {@link #headerCells} minus the last — repositioned, never rebuilt. */
    private final List<UINode> headerDividers = new ArrayList<>();

    @Getter @Nullable
    private TableColumn<T> sortedColumn;
    @Getter
    private SortOrder sortOrder = SortOrder.NONE;

    /**
     * Selection, held as <b>items</b>.
     *
     * <p>{@code ListView} selects by index, which is right for it and right for the Tree — a
     * {@code TreeRow} is a record rebuilt on every flatten, so it has no stable identity to key on. A
     * table's rows are the caller's own objects and <em>do</em>, and the difference is not academic:
     * sorting reorders every index, so index-based selection would leave the user having selected three
     * files and, one header click later, owning three different ones.</p>
     */
    private final Set<T> selectedItems = new LinkedHashSet<>();

    /** Guards the sync between this item-based selection and the list's index-based one. */
    private boolean syncingSelection;

    private final Connection sourceConnection;

    public TableView(ObservableList<T> source) {
        super(NAME, new ObservableList<>());
        this.source = source;

        header.addClass(HEADER_CLASS);
        // Exempt from the scroll translate, which is how a scroll container's own scrollbars stay put —
        // a header that scrolled away with the content would be useless, and making it row 0 of the list
        // instead would poison every index in the selection model.
        header.setScrollExempt(true);
        StyleGroup.defaultPipeline(header.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).top(0).left(0)
                        .flexDirection(FlexDirection.ROW).height(HEADER_HEIGHT).widthPercent(100f));
        appendStructural(header);

        sourceConnection = source.onChange(change -> {
            selectedItems.removeIf(item -> !contains(source, item));
            rebuild();
        });

        // Item-based selection is kept in step with the list's index-based one in both directions: the
        // user clicks or arrows (indices), and a re-sort moves those indices under them (items).
        onSelectionChanged.connect(indices -> {
            if (syncingSelection) return;
            selectedItems.clear();
            for (int index : indices) {
                if (index >= 0 && index < getModel().size()) selectedItems.add(getModel().get(index));
            }
        });

        rebuild();
    }

    /** Room for the header, which sits over the rows rather than in them. */
    private static final float HEADER_HEIGHT = 16f;

    // ── Columns ─────────────────────────────────────────────────────────────

    public TableView<T> addColumn(TableColumn<T> column) {
        columns.add(column);
        rebuildHeader();
        installRowRenderer();
        invalidateWindow();
        return this;
    }

    public List<TableColumn<T>> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    /**
     * Each column's width for the current viewport — fixed columns at their own width, flexible ones
     * sharing whatever is left.
     */
    public List<Float> resolvedWidths() {
        float fixed = 0f;
        float totalWeight = 0f;
        for (TableColumn<T> column : columns) {
            fixed += column.getWidth();
            totalWeight += column.getWeight();
        }
        float leftover = Math.max(0f, (box() == null ? 0f : box().clientWidth()) - fixed);
        List<Float> out = new ArrayList<>(columns.size());
        for (TableColumn<T> column : columns) out.add(column.resolvedWidth(leftover, totalWeight));
        return out;
    }

    // ── Sorting ─────────────────────────────────────────────────────────────

    /** Cycles a column: none → ascending → descending → none. */
    public TableView<T> toggleSort(TableColumn<T> column) {
        if (column == null || !column.isSortable()) return this;
        if (sortedColumn != column) {
            sortedColumn = column;
            sortOrder = SortOrder.ASCENDING;
        } else {
            sortOrder = sortOrder.next();
            if (sortOrder == SortOrder.NONE) sortedColumn = null;
        }
        rebuild();
        // Classes only. A full rebuild here would destroy the header cell whose mouse-down called us.
        updateSortClasses();
        onSortChanged.emit(sortedColumn, sortOrder);
        return this;
    }

    public final Signal.Pair<TableColumn<T>, SortOrder> onSortChanged = new Signal.Pair<>();

    /**
     * Rebuilds the visible order from the source and the current sort.
     *
     * <p>Selection is re-derived <b>from the items</b> afterwards, which is the whole reason it is stored
     * that way — the indices it had a moment ago now point at different rows.</p>
     */
    private void rebuild() {
        List<T> ordered = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) ordered.add(source.get(i));

        if (sortedColumn != null && sortOrder != SortOrder.NONE) {
            Comparator<T> comparator = sortedColumn.getComparator();
            if (comparator != null) {
                ordered.sort(sortOrder == SortOrder.DESCENDING ? comparator.reversed() : comparator);
            }
        }

        ObservableList<T> model = getModel();
        model.clear();
        for (T item : ordered) model.add(item);

        restoreSelectionFromItems();
    }

    private void restoreSelectionFromItems() {
        if (selectedItems.isEmpty()) return;
        syncingSelection = true;
        try {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < getModel().size(); i++) {
                if (selectedItems.contains(getModel().get(i))) indices.add(i);
            }
            setSelectedIndices(indices);
        } finally {
            syncingSelection = false;
        }
    }

    private static <T> boolean contains(ObservableList<T> list, T item) {
        for (int i = 0; i < list.size(); i++) {
            if (Objects.equals(list.get(i), item)) return true;
        }
        return false;
    }

    /** Selected rows as the caller's own objects — stable across a re-sort, unlike indices. */
    public Set<T> getSelectedItems() {
        return Collections.unmodifiableSet(selectedItems);
    }

    public void dispose() {
        super.dispose();
        sourceConnection.disconnect();
    }

    /**
     * Sets a column's width, as a divider drag does — clamped to its minimum and clearing any weight.
     *
     * <p>Public because the drag is not the only caller that matters: restoring a saved layout, a
     * "fit to contents" action and a test all want the same operation, and having the maths live only
     * inside a mouse handler would mean reimplementing it for each.</p>
     */
    /**
     * Re-derives everything that depends on the columns, after one has been reconfigured in place.
     *
     * <p>{@link TableColumn}'s fluent setters are meant to be used before {@link #addColumn}, and that
     * covers nearly every caller. This is for the ones that cannot: restoring a saved column layout,
     * a "fit to contents" action, or a column made flexible in response to something. Without it a
     * width change is invisible until some unrelated relayout happens to rebuild the header.</p>
     */
    public TableView<T> refreshColumns() {
        rebuildHeader();
        installRowRenderer();
        invalidateWindow();
        return this;
    }

    public TableView<T> resizeColumnTo(TableColumn<T> column, float width) {
        if (column == null || !column.isResizable()) return this;
        column.applyDraggedWidth(Math.min(width, maxWidthFor(column)));
        // Widths only, for the same reason — and this one runs every frame of a drag, so rebuilding
        // would also be churning a dozen elements sixty times a second.
        updateHeaderWidths();
        invalidateWindow();
        return this;
    }

    /**
     * The widest a column may be dragged: everything except the room its neighbours need.
     *
     * <p>Without a ceiling a drag simply pushed the columns after it off the right-hand edge, where there
     * is no divider left to drag them back with — the same trap {@link TableColumn#getMinWidth()} closes
     * at the other end. Reserving each remaining column's <em>minimum</em> rather than its current width
     * is the generous reading: a drag can still squeeze its neighbours, it just cannot evict them.</p>
     */
    private float maxWidthFor(TableColumn<T> column) {
        float reserved = 0f;
        for (TableColumn<T> other : columns) {
            if (other == column) continue;
            // A FIXED neighbour keeps its whole width — it is not going to shrink to make room, so
            // reserving only its minimum would let the drag push the total past the viewport and the last
            // column off the edge anyway. A FLEXIBLE one genuinely will give way, down to its minimum.
            reserved += other.getWeight() > 0f ? other.getMinWidth() : other.getWidth();
        }
        return Math.max(column.getMinWidth(), (box() == null ? 0f : box().clientWidth()) - reserved);
    }

    /**
     * Half a divider's grab width, used to centre it on the boundary it straddles.
     *
     * <p>Dividers are positioned <b>absolutely</b>, deliberately: an in-flow divider consumes header
     * width the rows do not have, so the header ran {@code (columns - 1) × width} wider than the rows
     * and every header cell after the first sat progressively right of the column beneath it. Small
     * enough to read as a styling wobble rather than a layout bug, which is why it survived — "Kind"
     * three pixels right of "folder" looks like padding.</p>
     *
     * <p>Out of flow also matches what a divider <em>is</em>: a grab handle straddling a boundary, not
     * a column of its own.</p>
     */
    private static final float DIVIDER_HALF_WIDTH = 1.5f;

    // ── The header ──────────────────────────────────────────────────────────

    /**
     * Rebuilds the header cells and the dividers between them.
     *
     * <p>Wholesale, for the reason {@code TreeView} re-flattens wholesale: one code path that cannot get
     * out of step, over a handful of elements that exist once rather than per row.</p>
     */
    private void rebuildHeader() {
        // removeInternalChild one by one: clearAllChildren deliberately refuses to touch internal
        // children, which is the guard that stops a caller wiping a widget's own structure.
        for (UINode child : new ArrayList<>(header.children())) header.remove(child);
        headerCells.clear();
        headerDividers.clear();
        List<Float> widths = resolvedWidths();

        for (int i = 0; i < columns.size(); i++) {
            final TableColumn<T> column = columns.get(i);
            final float width = i < widths.size() ? widths.get(i) : column.getWidth();

            UINode cell = new UINode();
            cell.addClass(HEADER_CELL_CLASS);
            if (sortedColumn == column && sortOrder == SortOrder.ASCENDING) cell.addClass(SORTED_ASC_CLASS);
            if (sortedColumn == column && sortOrder == SortOrder.DESCENDING) cell.addClass(SORTED_DESC_CLASS);
            StyleGroup.defaultPipeline(cell.getStyle().getLayoutGroup(), l -> l.width(width));

            UIText label = new UIText(column.getHeader());
            label.setHitTest(false);
            cell.append(label);
            if (column.isSortable()) {
                cell.onMouseDown.attachListener((el, event) -> toggleSort(column), false, false);
            }
            header.append(cell);
            headerCells.add(cell);

            if (column.isResizable() && i < columns.size() - 1) {
                UINode divider = newDivider(column, i);
                header.append(divider);
                headerDividers.add(divider);
            } else {
                // A placeholder keeps the list index-aligned with the columns, so repositioning does not
                // have to re-derive which columns happened to get one.
                headerDividers.add(null);
            }
        }
        positionDividers(widths);
    }

    /** Writes the current resolved widths into the existing header cells, and re-centres the dividers. */
    private void updateHeaderWidths() {
        List<Float> widths = resolvedWidths();
        for (int i = 0; i < headerCells.size() && i < widths.size(); i++) {
            final float width = widths.get(i);
            StyleGroup.defaultPipeline(headerCells.get(i).getStyle().getLayoutGroup(), l -> l.width(width));
        }
        positionDividers(widths);
    }

    /** Centres each divider on the boundary between its column and the next. */
    private void positionDividers(List<Float> widths) {
        float offset = 0f;
        for (int i = 0; i < headerDividers.size() && i < widths.size(); i++) {
            offset += widths.get(i);
            UINode divider = headerDividers.get(i);
            if (divider == null) continue;
            final float left = offset - DIVIDER_HALF_WIDTH;
            StyleGroup.defaultPipeline(divider.getStyle().getLayoutGroup(), l -> l.left(left));
        }
    }

    /** Moves the sort markers between existing header cells. */
    private void updateSortClasses() {
        for (int i = 0; i < headerCells.size() && i < columns.size(); i++) {
            UINode cell = headerCells.get(i);
            cell.removeClass(SORTED_ASC_CLASS);
            cell.removeClass(SORTED_DESC_CLASS);
            if (columns.get(i) != sortedColumn) continue;
            if (sortOrder == SortOrder.ASCENDING) cell.addClass(SORTED_ASC_CLASS);
            if (sortOrder == SortOrder.DESCENDING) cell.addClass(SORTED_DESC_CLASS);
        }
    }

    /**
     * A draggable divider between two columns.
     *
     * <p><b>Not the CSS {@code resize} capability</b>, and the distinction is the point: {@code resize}
     * changes one element's own size by dragging its edges, whereas a column divider redistributes width
     * between two neighbours. That is what {@code SplitView}'s divider does, so this borrows its
     * mechanism — a positional drag through {@code UIDragController} with no activation threshold, since
     * a divider must track the very first pixel.</p>
     */
    private UINode newDivider(TableColumn<T> column, int columnIndex) {
        UINode divider = new UINode();
        divider.addClass(DIVIDER_CLASS);
        // Out of flow — see DIVIDER_HALF_WIDTH. positionDividers() supplies the left inset.
        StyleGroup.defaultPipeline(divider.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).top(0f).bottom(0f));
        divider.onMouseDown.attachListener((el, event) -> {
            var window = document();
            if (window == null) return;
            // Snapshot at grab time: reading the live width each frame would compound the delta and the
            // divider would race away from the cursor — the same reason UIResizer snapshots.
            final float startWidth = resolvedWidths().get(columnIndex);
            Drag.start(el, event.getPosition().x(), event.getPosition().y(),
                    (mx, my, sx, sy, dx, dy) -> resizeColumnTo(column, startWidth + dx));
        }, false, false);
        return divider;
    }

    // ── Rows ────────────────────────────────────────────────────────────────

    /**
     * Builds the row renderer from the columns.
     *
     * <p>A row is a flex row of cells, one per column, each sized from {@link #resolvedWidths()}. Cells
     * come from a column's own {@link TableCellRenderer} when it has one and are plain text otherwise —
     * which is nearly always, so the common case costs a caller nothing.</p>
     */
    private void installRowRenderer() {
        setRenderer(new ListRenderer<T>() {
            @Override
            public UINode createTemplate() {
                UINode row = new UINode();
                StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                        l -> l.flexDirection(FlexDirection.ROW));
                for (TableColumn<T> column : columns) {
                    UINode cell;
                    if (column.getCellRenderer() != null) {
                        cell = column.getCellRenderer().createTemplate();
                    } else {
                        cell = new UINode();
                        UIText text = new UIText("");
                        text.setHitTest(false);
                        cell.append(text);
                    }
                    cell.addClass(CELL_CLASS);
                    cell.setHitTest(false);   // the ROW takes the click, so focus lands on it
                    // Cells CLIP. A cell whose text is wider than its column spills straight over its
                    // neighbour — narrow the Name column and its text draws on top of Size, which reads
                    // as garbage rather than as an overflow. Ellipsis rather than a hard cut so the
                    // truncation is legible as truncation.
                    StyleGroup.defaultPipeline(cell.getStyle().getGeneralGroup(),
                            g -> g.overflow(Overflow.HIDDEN));
                    for (UINode inner : cell.children()) {
                        StyleGroup.defaultPipeline(inner.getStyle().getGeneralGroup(),
                                g -> g.whiteSpace(WhiteSpace.NOWRAP)
                                        .textOverflow(TextOverflow.ELLIPSIS));
                    }
                    row.append(cell);
                }
                return row;
            }

            @Override
            public void bind(T item, int index, UINode template) {
                List<Float> widths = resolvedWidths();
                for (int i = 0; i < columns.size() && i < template.children().size(); i++) {
                    TableColumn<T> column = columns.get(i);
                    UINode cell = template.children().get(i);
                    final float width = i < widths.size() ? widths.get(i) : column.getWidth();
                    StyleGroup.defaultPipeline(cell.getStyle().getLayoutGroup(), l -> l.width(width));
                    if (column.getCellRenderer() != null) {
                        column.getCellRenderer().bind(item, index, cell);
                    } else {
                        ((UIText) cell.children().get(0)).setText(column.getValue().apply(item));
                    }
                }
            }
        });
    }

    /**
     * Rows start below the header, which occupies the top of the scrollport and does not scroll.
     *
     * <p>The viewport a TABLE virtualises against is not its client box: the header is inside the
     * scrollport and out of the scroll, so counting it would realise one row too few at the bottom
     * on every frame.</p>
     */
    @Override
    protected float viewportHeight() {
        Box box = box();
        return box == null ? 0f : Math.max(0f, box.clientHeight() - HEADER_HEIGHT);
    }

    @Override
    protected float rowOffset() {
        return HEADER_HEIGHT;
    }

    /**
     * Rebuilds the header whenever the table's own width changes.
     *
     * <p>Not optional, and the failure is ugly: a flexible column's width comes from
     * {@link #(box() == null ? 0f : box().clientWidth())}, which is <b>zero</b> until the first layout — so a header built at
     * {@code addColumn} time gave every flexible column no width at all, and the header cells then sat at
     * completely different offsets from the cells below them. The rows escaped it only because they bind
     * after layout.</p>
     *
     * <p>Guarded on an actual width change rather than run on every settled layout, since rebuilding
     * allocates a header cell and a divider per column.</p>
     */
    /**
     * A standing post-layout hook, which is what the {@code onLayoutChanged} override became.
     *
     * <p>Guarded on an actual width change rather than run on every settled layout, since rebuilding
     * allocates a header cell and a divider per column.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            rebuildHeaderIfResized();
            return true;
        });
    }

    private void rebuildHeaderIfResized() {
        float width = (box() == null ? 0f : box().clientWidth());
        if (Math.abs(width - headerBuiltForWidth) > 0.5f) {
            headerBuiltForWidth = width;
            updateHeaderWidths();
            invalidateWindow();
        }
    }

    /**
     * -1, not {@code NaN}.
     *
     * <p>It was NaN, and the guard below is {@code Math.abs(width - headerBuiltForWidth) > 0.5f} — every
     * comparison against NaN is <b>false</b>, so the rebuild never fired on the first layout and the
     * header kept the widths it was built with before the table had any size. Flexible columns had
     * resolved to zero, so the header cells all stacked at the same x and whichever drew last swallowed
     * the clicks. A sentinel that participates in arithmetic cannot fail that way.</p>
     */
    private float headerBuiltForWidth = -1f;
}
