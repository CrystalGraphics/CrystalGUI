package com.crystalgui.app.uibuilder.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * The document as a tree, selecting with the canvas.
 *
 * <pre>{@code
 * HierarchyPanel hierarchy = new HierarchyPanel(builder);
 * }</pre>
 *
 * <p>Rows show the node's {@code id} where it has one and its kind where it does not, which is the way
 * round a designer reads them: a named node is named for a reason and an unnamed one is only ever "the
 * button".</p>
 *
 * <h3>The LIGHT tree, not the composed one</h3>
 *
 * <p>A widget's shadow parts are structure, not content — you select the {@code Button}, never its label
 * — so this walks {@code children()} and never {@code composedChildren()}. Showing the composed tree
 * would fill the panel with parts no document names and none of which can be selected or moved.</p>
 *
 * <p>Selection is the builder's, so clicking a row and clicking the canvas are the same act: both write
 * {@link BuilderSelection}, and the plane keeps the engine's own item set in step.</p>
 */
public final class HierarchyPanel extends UIElement {

    public static final Name NAME = Name.of("hierarchypanel");

    public static final String PANEL_CLASS = "__hierarchy__";

    public static final String ROW_CLASS = "__hierarchy-row__";

    /** The ordinary child the tree lives in. @see #HierarchyPanel */
    public static final String CONTENT_CLASS = "__hierarchy-content__";

    /** On the row whose node is selected. */
    public static final String SELECTED_CLASS = "__selected__";

    /** The open/closed marker. Its appearance is CSS's, off {@code TreeView}'s own state classes. */
    public static final String TWISTY_CLASS = "__twisty__";

    /** The row's name. */
    public static final String LABEL_CLASS = "__label__";

    private final BuilderContext builder;

    private final ConnectionGroup connections = new ConnectionGroup();

    private final TreeView<UIElement> tree;

    /** @see #CONTENT_CLASS */
    private final UIElement content = new UIElement();

    /** Guards the two directions against answering each other. */
    private boolean syncing;

    public HierarchyPanel(BuilderContext builder) {
        super(NAME);
        this.builder = builder;
        addClass(PANEL_CLASS);
        content.addClass(CONTENT_CLASS);

        tree = new TreeView<>(new TreeDataSource<UIElement>() {
            @Override
            public List<UIElement> roots() {
                return List.of(builder.getDocument().root());
            }

            @Override
            public List<UIElement> children(UIElement parent) {
                return new ArrayList<>(parent.children());
            }

            @Override
            public boolean hasChildren(UIElement item) {
                return !item.children().isEmpty();
            }
        });
        tree.setRenderer(new RowRenderer());
        // THE ROOT AND ITS CHILDREN. A tree that opens fully collapsed shows one row and reads as a
        // panel that failed to load; opening everything buries the shape in a document of any size. One
        // level is what both references settle on.
        UIElement root = builder.getDocument().root();
        tree.setExpanded(root, true);
        for (UIElement child : root.children()) tree.setExpanded(child, true);
        // THE FILL IDIOM, at DEFAULT origin so a sheet still decides. Without it the tree lays out at
        // zero height: the rows exist, the panel is the right size, and nothing is drawn -- which reads
        // as "the panel is empty" and is why asserting on visibleRows() could not see it. The project
        // tree states the same three declarations for the same reason.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f).flexDirection(FlexDirection.COLUMN));
        StyleGroup.defaultPipeline(tree.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexBasis(0f).flexGrow(1f));
        StyleGroup.defaultPipeline(content.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexBasis(0f).flexGrow(1f)
                        .flexDirection(FlexDirection.COLUMN));
        // THE WRAPPER GOES IN WHILE EMPTY; the tree is an ordinary child of it afterwards.
        //
        // append(tree) is the obvious line and it is wrong, because markAsInternal() RECURSES. A
        // TreeView is a ListView: it builds its own viewport and recycles rows through
        // addInternalChild/removeInternalChild, and those removals SILENTLY REFUSE an internal child.
        // Stamping the whole subtree turns every removal into a no-op, so the realised window only ever
        // grows and layout takes longer every frame until the window stops responding -- which is what
        // "clicking a row breaks it until I restart" is. ProjectFileTree, QuickPick, ProblemsPanel and
        // ShaderGraphEditor all carry this wrapper; it is the pattern, not a workaround.
        // MULTIPLE, which ListView already implements in full -- Ctrl to toggle, Shift for a range.
        // This is configuration rather than code: the project tree gets the same behaviour from the same
        // line, and chooseRows below has always taken a SET of indices. The panel simply never opted in,
        // so the canvas could hold a set and the tree could only ever show one of it.
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        // NAMES ARE SCROLLED TO, NOT TRUNCATED. A node's id is the only thing this panel says about it,
        // so `#compos...` three rows running identifies nothing -- and unlike a file name there is no
        // extension at the end carrying the useful half. The project tree and the Problems tree make the
        // same call; the sheet's `.__h-scroll__` rule is the other half and cannot be set separately.
        tree.setHorizontalScrolling(true);
        append(content);
        content.append(tree);

    }

    /**
     * Every subscription this panel holds, remade each time it joins a tree.
     *
     * <p><b>Here rather than in the constructor, and paired with {@code disconnected}.</b> That method
     * drops every connection -- which it must, since one outliving its node is what the engine's
     * ownership rule exists to prevent -- so a subscription made once at construction is gone the first
     * time this panel leaves the tree and is never remade. The dock takes a tool window out for
     * ordinary reasons: hiding it, rebuilding a layout, replacing the panel behind a tab.</p>
     *
     * <p>All three go at once, which is why it presents as the panel dying rather than as one feature
     * failing: rows stop selecting anything, the tree stops following the canvas, and it stops rebuilding
     * when the document changes. Nothing re-registers, so it stays dead until the process restarts --
     * reported exactly that way. {@code ResizeHandles} had the same defect for the same reason.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        // SELECTION, not activation: a single click on a row is choosing that node, and activation is
        // the double-click that will open a template. The two are separate signals for exactly this.
        connections.add(tree.onSelectionChanged.connect(this::chooseRows));
        connections.add(builder.builderSelection().onChanged.connect(this::followSelection));
        connections.add(builder.getDocument().onChanged().connect(this::refresh));
        // WHAT IT MISSED WHILE IT WAS OUT. The document may have been edited, and the canvas selection
        // moved, with nothing listening -- so a panel that comes back showing the tree it left with is
        // showing a stale one.
        refresh();
        followSelection();
    }

    /** The tree, for a test and for whoever wants to expand a branch. */
    public TreeView<UIElement> tree() {
        return tree;
    }

    /** Rebuilds the rows from the document, keeping what was expanded. */
    public void refresh() {
        withoutWritingBack(tree::refresh);
    }

    /**
     * Runs something that rebuilds the tree, <b>without letting it answer itself</b>.
     *
     * <p>A refresh re-emits the list's own selection, and that lands in {@link #chooseRows}, which writes
     * the shared selection. So a rebuild triggered by the selection changing writes back whatever the
     * rebuilt list happened to have — the row highlight and the inspector then name different nodes, and
     * neither is what was clicked.</p>
     */
    private void withoutWritingBack(Runnable rebuild) {
        boolean was = syncing;
        syncing = true;
        try {
            rebuild.run();
        } finally {
            syncing = was;
        }
    }

    /**
     * Folds the row a twisty belongs to.
     *
     * <p>The row is found by asking the list which index this element currently holds, never by
     * remembering one: a {@code ListView} recycles a fixed window of templates, so the row a template
     * stands for changes under it as the list scrolls.</p>
     */
    private void foldFromTwisty(UIElement row, MouseEvent.Down event) {
        int index = tree.indexOfRowElement(row);
        TreeRow<UIElement> at = index < 0 ? null : tree.rowAt(index);
        // A LEAF'S TWISTY IS A SPACER, kept so labels line up -- a press on it is the row's.
        if (at == null || !at.expandable()) return;
        // STOPPED before ListView's own row handler, which is on the bubble phase: opening a branch is
        // not choosing it, and a second click there would fold it straight back through activation.
        event.stopPropagation();
        event.preventDefault();
        tree.requestToggleAt(index);
    }

    private void chooseRows(Set<Integer> indices) {
        if (syncing || indices == null) return;
        List<TreeRow<UIElement>> rows = tree.visibleRows();
        List<UIElement> chosen = new ArrayList<>();
        for (int index : indices) {
            if (index >= 0 && index < rows.size()) chosen.add(rows.get(index).item());
        }
        syncing = true;
        try {
            builder.builderSelection().replaceWith(chosen);
        } finally {
            syncing = false;
        }
    }

    /**
     * Scrolls the selected row into view, so a canvas click finds it in a long tree.
     *
     * <p>Only when the selection came from somewhere else — following our own click would fight the
     * pointer, which is the thing every two-way selection gets wrong first.</p>
     */
    private void followSelection() {
        if (syncing) return;
        List<UIElement> nodes = builder.builderSelection().nodes();
        withoutWritingBack(() -> {
            // A CLEARED CANVAS CLEARS THE PANEL. Returning early on an empty selection left the tree
            // highlighting a node nothing was selecting any more -- two answers to one question.
            if (nodes.isEmpty()) {
                tree.clearSelection();
                return;
            }
            for (UIElement node : nodes) {
                for (UIElement at = node.parentElement(); at != null; at = at.parentElement()) {
                    tree.setExpanded(at, true);
                }
            }
            tree.refresh();
            selectRowsFor(nodes);
        });
    }

    /**
     * Puts the list's own highlight on the row for {@code node}.
     *
     * <p>Without it the highlight is whatever survived the rebuild, so selecting on the canvas left the
     * hierarchy pointing at the previous node — one of the two halves of "the row says one thing and the
     * inspector another".</p>
     */
    /**
     * Puts the tree's highlight on every node the canvas holds.
     *
     * <p>{@code select} REPLACES and {@code toggle} adds, so the first row establishes the set and the
     * rest join it — which is also what stops a stale highlight surviving underneath a new selection.</p>
     */
    private void selectRowsFor(List<UIElement> nodes) {
        List<TreeRow<UIElement>> rows = tree.visibleRows();
        boolean first = true;
        for (UIElement node : nodes) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).item() != node) continue;
                if (first) {
                    tree.select(i);
                    first = false;
                } else {
                    tree.toggle(i);
                }
                break;
            }
        }
        // NOTHING ON SCREEN ANSWERED. Every selected node may be inside a collapsed branch, and leaving
        // the previous highlight up would name a node that is not the one selected.
        if (first) tree.clearSelection();
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        connections.disconnectAll();
    }

    /** One row: the name, and whether it is the selected node. */
    private final class RowRenderer implements TreeRenderer<UIElement> {

        /**
         * A twisty and a label.
         *
         * <p>The twisty keeps its box on a leaf and simply draws nothing, so a label at a given depth
         * starts at the same x whether or not its row can be opened — which is what makes a column of
         * siblings line up. What it looks like is entirely CSS: {@code TreeView} puts
         * {@code __expanded__} / {@code __collapsed__} / {@code __leaf__} on the row, so no Java here
         * decides how a row's state is drawn.</p>
         */
        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            UIElement twisty = new UIElement();
            twisty.addClass(TWISTY_CLASS);
            // ITS OWN HIT TARGET. A chevron that only responds to the row's double-click is a picture of
            // a tree control; every reference folds on a single click of the twisty and selects on a click
            // of the row, and the two must not be the same gesture. The press is claimed so it never
            // reaches the list -- opening a branch is not choosing it.
            twisty.events.getGroup(MouseEvent.Down.class)
                    .attachListener((element, event) -> foldFromTwisty(row, event), false, false);
            row.append(twisty);
            UIText label = new UIText();
            label.addClass(LABEL_CLASS);
            row.append(label);
            return row;
        }

        @Override
        public void bind(UIElement node, TreeRow<UIElement> row, int index, UIElement template) {
            for (UIElement child : template.children()) {
                if (child instanceof UIText label) label.setText(describe(node));
            }
            boolean selected = builder.builderSelection().contains(node);
            if (selected != template.hasClass(SELECTED_CLASS)) {
                if (selected) template.addClass(SELECTED_CLASS);
                else template.removeClass(SELECTED_CLASS);
            }
        }
    }

    /** {@code #title} where the node is named, {@code text} where it is not. */
    private static String describe(@Nullable UIElement node) {
        if (node == null) return "";
        String id = node.getId();
        return id == null || id.isEmpty() ? node.tagName() : "#" + id;
    }
}
