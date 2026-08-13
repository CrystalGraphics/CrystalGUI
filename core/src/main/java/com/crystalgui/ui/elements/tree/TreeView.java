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

    /** The tree-level renderer, which {@link com.crystalgui.ui.elements.tree.TreeSearch} decorates. */
    @Getter
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

    /**
     * Every expanded item, in the order they were opened.
     *
     * <p>A copy: the live set is what {@link #refresh} reads, and handing it out would let a caller
     * collapse a node without the view ever hearing about it. Insertion-ordered so a saved session
     * restores parents before their children, which a lazily-listed tree needs — a folder cannot be
     * expanded before the listing that reveals it has arrived.</p>
     */
    public java.util.List<T> expandedItems() {
        return new java.util.ArrayList<>(expanded);
    }

    /**
     * Replaces the whole expansion state at once, in one re-flatten.
     *
     * <p>For the two callers that have a <em>set</em> rather than a node: restoring a saved session, and
     * {@link TreeSearch} opening a filtered tree and putting it back afterwards. Going through
     * {@link #setExpanded} for each would re-flatten once per node, which on a filtered tree is a re-flatten
     * per surviving branch on every keystroke.</p>
     *
     * <p>Insertion order is preserved, because a lazily-listed tree needs parents before children — a
     * folder cannot be expanded before the listing that reveals it has arrived.</p>
     */
    public TreeView<T> setExpandedItems(java.util.Collection<T> items) {
        expanded.clear();
        if (items != null) expanded.addAll(items);
        refresh();
        return this;
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

    /**
     * Asks for a fold, applied on the next frame — <b>what a chevron press must use</b>.
     *
     * <h3>Why folding from inside an event is not safe, and why every caller got it wrong</h3>
     *
     * <p>{@link #setExpanded} re-flattens immediately, and a re-flatten replaces the model — so
     * {@code ListView} recycles every realised row, <em>including the one whose listener is currently
     * running</em>. Called straight from a chevron's {@code onMouseDown}, a fold pulls the element out
     * from under the event still being dispatched through it.</p>
     *
     * <p>Both consumers reached the same conclusion and each hand-rolled the deferral: a {@code pending}
     * field drained from somewhere. That is three chances to get it wrong and all three were taken — a
     * single-slot field drops a second click in the same frame, {@code onLayoutChanged} never fires for a
     * press that moves no geometry, and a private ticker guarded by a {@code ticking} flag stops
     * re-registering the moment its panel is detached and re-attached, which a dock does routinely. The
     * result is a chevron that works sometimes.</p>
     *
     * <p>So the deferral belongs here, once: a <b>queue</b>, so rapid clicks all land, drained from the
     * tick {@code ListView} already owns and already keeps alive, so there is no second ticker lifecycle
     * to get wrong. One refresh per frame however many folds arrived.</p>
     */
    public TreeView<T> requestToggle(T item) {
        if (item == null) return this;
        pendingToggles.add(item);
        // The tick is what applies it, so a tree that is idle must be woken -- otherwise the first fold
        // after a quiet period waits for something else to start the ticker.
        ensureTicking();
        return this;
    }

    /** Folds asked for during event dispatch, applied on the next frame. @see #requestToggle */
    private final List<T> pendingToggles = new ArrayList<>();

    @Override
    public boolean tickFrame(float deltaSeconds) {
        drainPendingToggles();
        return super.tickFrame(deltaSeconds);
    }

    /** @see #requestToggle */
    private void drainPendingToggles() {
        if (pendingToggles.isEmpty()) return;
        List<T> asked = new ArrayList<>(pendingToggles);
        pendingToggles.clear();
        boolean moved = false;
        // What holds focus BEFORE the re-flatten, because afterwards its row may not exist to ask.
        T focusedItem = null;
        int focusedAt = getFocusedIndex();
        if (focusedAt >= 0) {
            TreeRow<T> row = rowAt(focusedAt);
            if (row != null) focusedItem = row.item();
        }
        T closed = null;
        for (T item : asked) {
            if (!source.hasChildren(item)) continue;
            boolean open = !expanded.contains(item);
            if (open) expanded.add(item);
            else { expanded.remove(item); closed = item; }
            onExpandChanged.emit(item, open);
            moved = true;
        }
        // ONE re-flatten however many folds arrived, which is the point of queueing them rather than
        // applying each as it lands.
        if (moved) refresh();

        // COLLAPSING A NODE MOVES FOCUS TO THAT NODE — the ARIA tree pattern, and the same rule the editor
        // already applies to folding a block the caret is in: a focus owner that is no longer on screen
        // cannot be painted, scrolled to or typed at, so the fold has to hand focus somewhere.
        //
        // Without it, folding a heading whose child held focus left the whole window with NO focus owner:
        // no ring anywhere, and consumeKeyboardEvent dispatches nothing at all while focus is null, so the
        // arrows could not walk back out of the thing that had just been collapsed.
        //
        // Set here rather than after the next layout, because this runs from TreeView's tick BEFORE
        // ListView's — so updateWindow sees the corrected index in the SAME frame and restores focus with
        // no gap. A frame later would be one frame of nothing focused, which is the flash this and
        // ListView.updateWindow were both written to close.
        if (closed != null && focusedItem != null && visibleIndexOf(focusedItem) < 0) {
            int at = visibleIndexOf(closed);
            if (at >= 0) setFocusedIndex(at);
        }
    }

    /** Where {@code item} sits in the flattened rows, or {@code -1} when it is not on screen. */
    private int visibleIndexOf(T item) {
        for (int i = 0; i < getModel().size(); i++) {
            TreeRow<T> row = getModel().get(i);
            if (row != null && java.util.Objects.equals(row.item(), item)) return i;
        }
        return -1;
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

        // SELECTION FOLLOWS ITEMS, not indices — captured before the rebuild and restored after.
        //
        // Two things make this necessary rather than nice. A re-flatten is a clear followed by one add
        // per row, and ListView clamps the selection to the model on every change: the clear reports size
        // zero, so the clamp discarded everything before a single row had been re-added. Expanding a
        // folder therefore deselected it — the click had selected it correctly and the expand threw the
        // answer away, which read as folders not being selectable at all.
        //
        // And even without that, indices do not survive a re-flatten. Folding a directory above the
        // selected file renumbers every row beneath it, so an index-based selection silently moves to a
        // different file. Every tree that keeps a selection across expansion tracks items for this reason.
        List<T> selectedItems = new ArrayList<>();
        for (int index : getSelectedIndices()) {
            TreeRow<T> row = rowAt(index);
            if (row != null) selectedItems.add(row.item());
        }

        // ONE announcement, not one per row. A ListView rebuilds its realised window on every change, so
        // adding a flattened tree row by row rebuilt it once per row -- and each rebuild discarded the
        // horizontal scroll extent and re-measured it, which is what made the scrollbar flicker on every
        // refresh.
        getModel().setAll(flattened);

        // CLEARED FIRST, and this is the half that was missing. ListView's clamp only discards indices that
        // are now OUT OF RANGE -- an index that is still in range survives and quietly points at a
        // different row. Restoring the remembered items on top of that leaves BOTH: the stale index and the
        // real one, selected together.
        //
        // It showed as the file tree gaining a selected row on every flip of the search mode. Nothing was
        // additive; each flip left one more index behind, so the selection grew by one and looked like
        // repeated clicking. The remembered items above are the whole truth about what is selected, so
        // anything the clamp happened to leave is noise.
        clearSelection();

        if (selectedItems.isEmpty()) return;
        for (int index = 0; index < flattened.size(); index++) {
            // toggle(), because it is the additive one -- select() replaces, so restoring a multi-selection
            // through it would leave only the last row. Anything no longer in the tree simply drops out,
            // which is what a deleted or collapsed-away row should do.
            if (selectedItems.contains(flattened.get(index).item()) && !isSelected(index)) toggle(index);
        }
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
