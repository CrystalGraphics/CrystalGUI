package com.crystalgui.ui.elements.tree;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.event.KeyboardEvent;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A tree, built as a <b>flattened {@link ListView}</b>.
 *
 * <p>The currently-visible nodes are flattened into a linear model and handed to the list; expanding a
 * node re-flattens. That is the entire mechanism, and it is the shape
 * <a href="https://github.com/microsoft/vscode/wiki/Lists-And-Trees">VS Code arrives at</a> through four
 * layers ({@code IndexTree} &rarr; {@code ObjectTree} &rarr; {@code AsyncDataTree}, all over its list).
 * We want the outcome rather than the layering.</p>
 *
 * <p><b>Everything 6.1.3 fought for is inherited, not re-fought.</b> A tree over a hundred thousand nodes
 * realises a dozen rows, focus survives recycling, selection is index-based, and the scrollbar reflects
 * the flattened count — none of that is code in this class.</p>
 *
 * <pre>{@code
 * TreeView<Path> tree = new TreeView<>(source);
 * tree.setRenderer(new TreeRenderer<Path>() {
 *     public UIElement createTemplate() { ... }
 *     public void bind(Path item, TreeRow<Path> row, int i, UIElement t) { ... }
 * });
 * }</pre>
 */
public class TreeView<T> extends ListView<TreeRow<T>> {

    public static final String EXPANDED_CLASS = "__expanded__";
    public static final String COLLAPSED_CLASS = "__collapsed__";
    public static final String LEAF_CLASS = "__leaf__";

    @Getter
    private final TreeDataSource<T> source;

    /**
     * Which nodes are open. A {@code LinkedHashSet} of the caller's own items, so expansion survives a
     * re-flatten — the flattened rows are rebuilt wholesale and cannot carry it.
     *
     * <p>Identity is the caller's {@code equals}. A source handing out fresh equal objects per call still
     * works; one handing out unequal objects for the same node will appear to collapse on every refresh,
     * which is worth knowing before blaming the tree.</p>
     */
    private final Set<T> expanded = new LinkedHashSet<>();

    @Getter
    private float indentPerDepth = 12f;

    @Nullable
    private TreeRenderer<T> treeRenderer;

    /** Fires when a node is expanded or collapsed, with the item and its new state. */
    public final Signal.Pair<T, Boolean> onExpandChanged = new Signal.Pair<>();

    public TreeView(TreeDataSource<T> source) {
        // The list model is OURS, not the caller's: it holds flattened rows and is rebuilt on every
        // expansion change. That is why this constructor takes a source rather than an ObservableList —
        // a caller handing in a list would be handing in something we overwrite.
        super(new ObservableList<>());
        this.source = source;
        refresh();
    }

    public TreeView<T> setRenderer(TreeRenderer<T> renderer) {
        this.treeRenderer = renderer;
        // Adapts the tree renderer to the list one, and applies everything the renderer should not have to
        // know: indentation from depth, and the state classes a theme draws the twisty from.
        super.setRenderer(renderer == null ? null : new ListRenderer<TreeRow<T>>() {
            @Override
            public UIElement createTemplate() {
                return renderer.createTemplate();
            }

            @Override
            public void bind(TreeRow<T> row, int index, UIElement template) {
                final float indent = row.depth() * indentPerDepth;
                StyleGroup.defaultPipeline(template.getStyle().getLayoutGroup(), l -> l.paddingLeft(indent));
                template.removeClass(EXPANDED_CLASS);
                template.removeClass(COLLAPSED_CLASS);
                template.removeClass(LEAF_CLASS);
                template.addClass(!row.expandable() ? LEAF_CLASS
                        : row.expanded() ? EXPANDED_CLASS : COLLAPSED_CLASS);
                renderer.bind(row.item(), row, index, template);
            }

            @Override
            public void unbind(UIElement template) {
                renderer.unbind(template);
            }
        });
        return this;
    }

    /** Indentation per level, in logical pixels. Applied by the view rather than the renderer, so a row
     * template is the same at every depth. */
    public TreeView<T> setIndentPerDepth(float indent) {
        this.indentPerDepth = Math.max(0f, indent);
        refresh();
        return this;
    }

    // ── Expansion ───────────────────────────────────────────────────────────

    public boolean isExpanded(T item) {
        return expanded.contains(item);
    }

    public TreeView<T> setExpanded(T item, boolean open) {
        if (item == null) return this;
        if (open && !source.hasChildren(item)) return this;
        boolean changed = open ? expanded.add(item) : expanded.remove(item);
        if (!changed) return this;
        refresh();
        onExpandChanged.emit(item, open);
        return this;
    }

    public TreeView<T> toggleExpanded(T item) {
        return setExpanded(item, !isExpanded(item));
    }

    /** By flattened row index — what a renderer's own twisty listener calls, since a row knows its index
     * long before it knows anything about the tree. */
    public TreeView<T> toggleExpandedAt(int index) {
        TreeRow<T> row = rowAt(index);
        return row == null ? this : toggleExpanded(row.item());
    }

    /** Opens every sibling at {@code index}'s level — the APG's optional {@code *}. Cheap here because it
     * is one re-flatten rather than one per node. */
    public TreeView<T> expandSiblingsOf(int index) {
        TreeRow<T> row = rowAt(index);
        if (row == null) return this;
        List<T> siblings = row.parentIndex() < 0
                ? source.roots()
                : source.children(getModel().get(row.parentIndex()).item());
        boolean changed = false;
        for (T sibling : siblings) {
            if (source.hasChildren(sibling)) changed |= expanded.add(sibling);
        }
        if (changed) refresh();
        return this;
    }

    public TreeView<T> collapseAll() {
        if (expanded.isEmpty()) return this;
        expanded.clear();
        refresh();
        return this;
    }

    /**
     * The flattened index a realised row element currently represents, or -1.
     *
     * <p>What a renderer's own twisty listener needs: the element it is attached to represents a
     * <em>different</em> row every time it is recycled, so a listener cannot capture an index and must ask
     * at click time. Delegates to the list's realised-row map rather than duplicating it.</p>
     */
    public int indexOfRowElement(@Nullable UIElement rowElement) {
        if (rowElement == null) return -1;
        for (var entry : realisedRows().entrySet()) {
            if (entry.getValue() == rowElement) return entry.getKey();
        }
        return -1;
    }

    @Nullable
    public TreeRow<T> rowAt(int index) {
        return index >= 0 && index < getModel().size() ? getModel().get(index) : null;
    }

    // ── Flattening ──────────────────────────────────────────────────────────

    /**
     * Rebuilds the flattened model from the source and the expansion set.
     *
     * <p>Wholesale rather than incrementally, and that is a deliberate trade: an incremental splice would
     * be less work on a big tree, but every operation would need its own correct splice computation, which
     * is exactly the "sub-optimal API" VS Code's own wiki says {@code IndexTree} suffers from. Rebuilding
     * is one code path that cannot get out of step, and the list on the other side is virtualised — so the
     * cost is a list of records, not a list of elements.</p>
     */
    public void refresh() {
        List<TreeRow<T>> flattened = new ArrayList<>();
        for (T root : source.roots()) flatten(root, 0, -1, flattened);

        ObservableList<TreeRow<T>> model = getModel();
        model.clear();
        for (TreeRow<T> row : flattened) model.add(row);
    }

    private void flatten(T item, int depth, int parentIndex, List<TreeRow<T>> out) {
        boolean expandable = source.hasChildren(item);
        boolean open = expandable && expanded.contains(item);
        int myIndex = out.size();
        out.add(new TreeRow<>(item, depth, expandable, open, parentIndex));
        if (!open) return;
        for (T child : source.children(item)) flatten(child, depth + 1, myIndex, out);
    }

    // ── Keyboard, per the APG tree pattern ──────────────────────────────────

    /**
     * Left and Right; everything else falls through to {@link ListView}.
     *
     * <p>Up/Down/Home/End already move through <em>visible</em> nodes without opening anything, because
     * the model IS the visible set — so the APG's wording for those is satisfied by the flattening rather
     * than by any code here.</p>
     *
     * <p>The asymmetry is the part implementations get wrong, so it is spelled out:</p>
     * <ul>
     *   <li><b>Right</b> on a collapsed node opens it and <b>does not move focus</b>; on an open node
     *       moves to the first child; on a leaf does nothing.</li>
     *   <li><b>Left</b> on an open node closes it; otherwise moves to the parent.</li>
     * </ul>
     */
    @Override
    protected boolean handleNavigationKey(KeyboardEvent.Down event) {
        int index = getFocusedIndex() < 0 ? 0 : getFocusedIndex();
        TreeRow<T> row = rowAt(index);
        if (row == null) return super.handleNavigationKey(event);

        switch (event.getKeyCode()) {
            case CgKeyCodes.KEY_RIGHT -> {
                if (!row.expandable()) return true;          // a leaf: consumed, but nothing happens
                if (!row.expanded()) {
                    setExpanded(row.item(), true);           // focus deliberately stays put
                } else if (index + 1 < getModel().size()) {
                    moveFocusTo(index + 1, false, false);    // the first child is the next flattened row
                }
                return true;
            }
            case CgKeyCodes.KEY_LEFT -> {
                if (row.expanded()) setExpanded(row.item(), false);
                else if (row.parentIndex() >= 0) moveFocusTo(row.parentIndex(), false, false);
                return true;
            }
            case CgKeyCodes.KEY_MULTIPLY -> {
                expandSiblingsOf(index);
                return true;
            }
            default -> {
                return super.handleNavigationKey(event);
            }
        }
    }

    /** Unmodifiable view of the flattened rows, in display order. */
    public List<TreeRow<T>> visibleRows() {
        List<TreeRow<T>> out = new ArrayList<>(getModel().size());
        for (int i = 0; i < getModel().size(); i++) out.add(getModel().get(i));
        return Collections.unmodifiableList(out);
    }
}
