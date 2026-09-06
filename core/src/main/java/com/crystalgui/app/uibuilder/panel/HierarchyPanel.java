package com.crystalgui.app.uibuilder.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.text.UIText;

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

    /** On the row whose node is selected. */
    public static final String SELECTED_CLASS = "__selected__";

    private final BuilderContext builder;

    private final ConnectionGroup connections = new ConnectionGroup();

    private final TreeView<UIElement> tree;

    /** Guards the two directions against answering each other. */
    private boolean syncing;

    public HierarchyPanel(BuilderContext builder) {
        super(NAME);
        this.builder = builder;
        addClass(PANEL_CLASS);

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
        tree.setExpanded(builder.getDocument().root(), true);
        append(tree);

        // SELECTION, not activation: a single click on a row is choosing that node, and activation is
        // the double-click that will open a template. The two are separate signals for exactly this.
        connections.add(tree.onSelectionChanged.connect(this::chooseRows));
        connections.add(builder.builderSelection().onChanged.connect(this::followSelection));
        connections.add(builder.getDocument().onChanged().connect(this::refresh));
    }

    /** The tree, for a test and for whoever wants to expand a branch. */
    public TreeView<UIElement> tree() {
        return tree;
    }

    /** Rebuilds the rows from the document, keeping what was expanded. */
    public void refresh() {
        tree.refresh();
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
        UIElement node = builder.builderSelection().node();
        if (node == null) return;
        for (UIElement at = node.parentElement(); at != null; at = at.parentElement()) {
            tree.setExpanded(at, true);
        }
        tree.refresh();
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        connections.disconnectAll();
    }

    /** One row: the name, and whether it is the selected node. */
    private final class RowRenderer implements TreeRenderer<UIElement> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            row.append(new UIText());
            return row;
        }

        @Override
        public void bind(UIElement node, TreeRow<UIElement> row, int index, UIElement template) {
            UIElement first = template.children().isEmpty() ? null : template.children().get(0);
            if (first instanceof UIText label) label.setText(describe(node));
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
