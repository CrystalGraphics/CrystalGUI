package com.crystalgui.app.uibuilder.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.glyph.GlyphView;
import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.TreeDropRules;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.collection.tree.TreeClipboard;
import com.crystalgui.widget.collection.tree.TreeEditing;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeSearch;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * The document as a tree, selecting with the canvas, and edited like a file tree.
 *
 * <pre>{@code
 * HierarchyPanel hierarchy = new HierarchyPanel(builder);
 * HierarchyActions.register(CommandRegistry.global());   // once, by the feature: New ▸ and the edit rows
 * }</pre>
 *
 * <p>Rows show the node's {@code id} where it has one and its kind where it does not, which is the way
 * round a designer reads them: a named node is named for a reason and an unnamed one is only ever "the
 * button".</p>
 *
 * <p>Drag a row before, into or after another (Alt copies), and cut, copy, paste, duplicate, delete and F2
 * rename the id over the selection — {@link TreeEditing} over {@link HierarchyEditModel}, the kit the
 * Project panel uses. Ctrl+F finds a row by what it shows.</p>
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
public final class HierarchyPanel extends UIElement implements DataProvider, UndoScope {

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

    /** The field a row's id is renamed in. */
    public static final String RENAME_CLASS = "__rename__";

    /** The right-click menu on a row. The feature contributes the kit's rows to it. */
    public static final MenuId CONTEXT_MENU = MenuId.of("uibuilder/hierarchy/context");

    /** New ▸ — the kinds to insert. The title line's + drops it down; the row menu nests it. @see HierarchyActions */
    public static final MenuId NEW_MENU =
            MenuId.of("uibuilder/hierarchy/context/new").nestedIn(CONTEXT_MENU, "New", "1_new", 0);

    /** This panel, for a command that acts on one. */
    public static final DataKey<HierarchyPanel> HIERARCHY = DataKey.create("uibuilder.hierarchy", HierarchyPanel.class);

    /** One for every hierarchy, so a node cut in one document pastes — as a copy — into another. */
    private static final TreeClipboard<UIElement> CLIPBOARD = new TreeClipboard<>();

    private final BuilderContext builder;

    private final TreeView<UIElement> tree;

    private final TreeEditing<UIElement> editing;

    /** @see #CONTENT_CLASS */
    private final UIElement content = new UIElement();

    /** Guards the two directions against answering each other. */
    private boolean syncing;

    /** The root the expansion was last stated against. @see #followRoot */
    @Nullable
    private UIElement shownRoot;

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
        followRoot();
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
        // MULTIPLE, which ListView already implements in full -- Ctrl to toggle, Shift for a range.
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        // NAMES ARE SCROLLED TO, NOT TRUNCATED. A node's id is the only thing this panel says about it,
        // so `#compos...` three rows running identifies nothing -- and unlike a file name there is no
        // extension at the end carrying the useful half. The project tree and the Problems tree make the
        // same call; the sheet's `.__h-scroll__` rule is the other half and cannot be set separately.
        tree.setHorizontalScrolling(true);
        // THE WRAPPER GOES IN WHILE EMPTY; the tree is an ordinary child of it afterwards. markAsInternal()
        // recurses, and a ListView's row recycling silently refuses to remove an internal child -- the
        // realised window then only grows. ProjectFileTree, QuickPick and ProblemsPanel carry it too.
        append(content);
        content.append(tree);

        this.editing = new TreeEditing<>(tree, this, this::itemForRow, () -> withoutWritingBack(tree::refresh),
                CLIPBOARD);
        editing.setModel(new HierarchyEditModel(builder));
        editing.attachContextMenu(CommandRegistry.global(), () -> ContextMenu.of(CONTEXT_MENU));
        tree.setRenderer(new RowRenderer());
        // AFTER the renderer, which it wraps to mark the matched letters in each row's label.
        TreeSearch<UIElement> search = TreeSearch.installOn(tree, content, TreeSearch.byText(HierarchyPanel::describe),
                node -> builder.builderSelection().replaceWith(List.of(node)));
        search.input().setPlaceholder("Find element");

        // DECLARED HERE AND HELD BY THE ENGINE. Each is remade on every attach and dropped on every
        // detach, which a dock does for ordinary reasons -- hiding the panel, rebuilding a layout,
        // replacing what is behind a tab. @see UINode#whileConnected
        //
        // SELECTION, not activation: a single click on a row is choosing that node, and activation is
        // the double-click that will open a template.
        whileConnected(() -> tree.onSelectionChanged.connect(this::chooseRows));
        whileConnected(() -> builder.builderSelection().onChanged.connect(this::followSelection));
        whileConnected(() -> builder.getDocument().onChanged().connect(this::followSelection));
        // WHAT IT MISSED WHILE IT WAS OUT: the document may have been edited and the canvas selection
        // moved, with nothing listening, so a panel that comes back showing the tree it left with is
        // showing a stale one.
        onConnected(this::followSelection);
        // OWNED BY THE PANEL, so it stops with it; and remade on every attach, since a hook dropped on detach
        // never comes back by itself.
        onConnected(() -> document().animation().every(this, this::refreshGlyphs));
    }

    /** The tree, for a test and for whoever wants to expand a branch. */
    public TreeView<UIElement> tree() {
        return tree;
    }

    /** What {@code node}'s realised row draws as its glyph now, or null when the row is not on screen. For a test. */
    @Nullable
    public KindGlyphs.Glyph glyphOnRow(UIElement node) {
        for (Map.Entry<Integer, UIElement> realised : tree.realisedRows().entrySet()) {
            TreeRow<UIElement> row = tree.rowAt(realised.getKey());
            GlyphView glyph = glyphs.get(realised.getValue());
            if (row != null && row.item() == node && glyph != null) return glyph.shown();
        }
        return null;
    }

    /** Drag, clipboard, duplicate, delete and rename over this tree's selection. */
    public TreeEditing<UIElement> editing() {
        return editing;
    }

    /**
     * Inserts {@code node} into the selected container, after a selected leaf in its parent, or at the end of
     * the root with nothing selected — one undo step, and the new node selected.
     */
    public void insertNew(UIElement node) {
        UIElement root = builder.getDocument().root();
        List<UIElement> selected = builder.builderSelection().nodes();
        UIElement anchor = selected.isEmpty() ? root : selected.get(selected.size() - 1);
        UIElement parent = TreeDropRules.isContainer(root, anchor) ? anchor : anchor.parentElement();
        if (parent == null) return;
        int index = parent == anchor ? parent.children().size() : parent.indexOf(anchor) + 1;
        builder.getDocument().apply(new BuilderEdit.Insert(parent, node, index));
        builder.builderSelection().replaceWith(List.of(node));
    }

    /** The nodes selected on the canvas and in this tree, which are one selection. */
    public List<UIElement> selectedNodes() {
        return builder.builderSelection().nodes();
    }

    /** The root of the document this panel shows. */
    public UIElement documentRoot() {
        return builder.getDocument().root();
    }

    /** Unfolds to the canvas selection, highlights it and scrolls the first of it in — the title line's locate. */
    public void revealSelection() {
        List<UIElement> nodes = builder.builderSelection().nodes();
        withoutWritingBack(() -> {
            expandTo(nodes);
            tree.refresh();
            selectRowsFor(nodes);
        });
        List<Integer> rows = new ArrayList<>(tree.getSelectedIndices());
        if (!rows.isEmpty()) tree.scrollToIndex(Collections.min(rows));
    }

    /** The node whose id is being renamed, or null. */
    @Nullable
    public UIElement renaming() {
        return editing.rows().item();
    }

    /**
     * The document's history: this panel is a view of the same document the canvas is, so Ctrl+Z here undoes
     * a rename or a drop as it does there.
     */
    @Override
    public UndoStack undoStack() {
        return builder.getDocument().history();
    }

    @Override
    public Object getData(DataKey<?> key) {
        if (key == HIERARCHY) return this;
        // THE BUILDER'S OWN KEYS, so its commands on a row's menu -- Copy and Paste Attributes -- act on this
        // document and its selection, as they do from the canvas.
        if ((key == BuilderEditor.UI_BUILDER || key == BuilderEditor.BUILDER_SELECTION)
                && builder instanceof DataProvider surface) {
            return surface.getData(key);
        }
        // THE HISTORY TOO. The walk stops at the first provider, and one answering only its own key hid the
        // document's history from every command asked from the panel.
        return undoScopeData(key);
    }

    /** The node a row element stands for now, or null. */
    @Nullable
    private UIElement itemForRow(UIElement row) {
        int index = tree.indexOfRowElement(row);
        TreeRow<UIElement> at = index < 0 ? null : tree.rowAt(index);
        return at == null ? null : at.item();
    }

    /**
     * States the expansion against the document's current root.
     *
     * <p>First the root and its children: fully collapsed reads as a panel that failed to load, fully open
     * buries the shape. After that, a reload or a restored backup mints a new root, and what was open stays
     * open at the same place in the new tree — IntelliJ's {@code TreeState} by path, a child index being a
     * node's only path that survives a re-parse.</p>
     */
    private void followRoot() {
        UIElement root = builder.getDocument().root();
        if (root == shownRoot) return;
        UIElement previous = shownRoot;
        shownRoot = root;
        List<UIElement> open = new ArrayList<>();
        if (previous == null) {
            open.add(root);
            for (UIElement child : root.children()) {
                if (!child.children().isEmpty()) open.add(child);
            }
        } else {
            for (UIElement item : tree.expandedItems()) {
                UIElement same = samePlace(item, previous, root);
                if (same != null) open.add(same);
            }
        }
        tree.setExpandedItems(open);
    }

    /** The node at {@code node}'s child-index path under {@code from}, followed under {@code to}; or null. */
    @Nullable
    private static UIElement samePlace(UIElement node, UIElement from, UIElement to) {
        List<Integer> path = new ArrayList<>();
        UIElement at = node;
        for (; at != null && at != from; at = at.parentElement()) {
            UIElement parent = at.parentElement();
            if (parent == null) return null;
            path.add(parent.indexOf(at));
        }
        if (at == null) return null;
        UIElement same = to;
        for (int i = path.size() - 1; i >= 0; i--) {
            List<UIElement> children = same.children();
            int index = path.get(i);
            if (index < 0 || index >= children.size()) return null;
            same = children.get(index);
        }
        return same;
    }

    private void expandTo(List<UIElement> nodes) {
        for (UIElement node : nodes) {
            for (UIElement at = node.parentElement(); at != null; at = at.parentElement()) {
                tree.setExpanded(at, true);
            }
        }
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
     * Rebuilds the rows and reveals the selection — unfolding what hides it and scrolling to it — when the
     * canvas selection or the document changes.
     *
     * <p>A document change reveals too: a move can take a selected node into a folded branch without the
     * selection changing at all. Never for the panel's own click, which following would fight the
     * pointer — the thing every two-way selection gets wrong first.</p>
     */
    private void followSelection() {
        if (syncing) return;
        List<UIElement> nodes = builder.builderSelection().nodes();
        withoutWritingBack(() -> {
            followRoot();
            expandTo(nodes);
            tree.refresh();
            // A CLEARED CANVAS CLEARS THE PANEL, which selectRowsFor does when no row answers.
            selectRowsFor(nodes);
        });
    }

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

    /** One row: the name, and whether it is the selected node. */
    private final class RowRenderer implements TreeRenderer<UIElement> {

        /**
         * A twisty, a label and the rename field.
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
            // ONCE PER TEMPLATE: `attach` adds a listener pair, and the words are the glyph's region, rewritten
            // per node. The wait is the explorer's -- an icon crossed on the way to the label.
            Tooltip tip = Tooltip.attach(row, "");
            tip.addClass(Tooltip.WAIT_CLASS);
            GlyphView glyph = new GlyphView(tip);
            row.append(glyph.element());
            glyphs.put(row, glyph);
            UIText label = new UIText();
            label.addClass(LABEL_CLASS);
            row.append(label);
            TextField field = new TextField();
            field.addClass(RENAME_CLASS);
            editing.installRow(row, glyph.element(), label, field);
            row.append(field);
            return row;
        }

        @Override
        public void bind(UIElement node, TreeRow<UIElement> row, int index, UIElement template) {
            UIText label = null;
            TextField field = null;
            for (UIElement child : template.children()) {
                if (child instanceof UIText text) label = text;
                else if (child instanceof TextField editor) field = editor;
            }
            if (label != null) label.setText(describe(node));
            if (label != null && field != null) editing.bindRow(template, label, field, node);
            GlyphView glyph = glyphs.get(template);
            if (glyph != null) glyph.show(node);
            boolean selected = builder.builderSelection().contains(node);
            if (selected != template.hasClass(SELECTED_CLASS)) {
                if (selected) template.addClass(SELECTED_CLASS);
                else template.removeClass(SELECTED_CLASS);
            }
        }
    }

    /** Each template's glyph. @see RowRenderer#createTemplate */
    private final Map<UIElement, GlyphView> glyphs = new HashMap<>();

    /**
     * Re-asks each realised row's glyph once a frame.
     *
     * <p>A glyph follows COMPUTED style, which an inline-style or class edit only changes on the next frame and a
     * sheet or theme switch changes with no document change at all — so re-binding on the edit would read the
     * old layout. Asking per frame over what is on screen covers all three with nothing to subscribe to.</p>
     */
    private boolean refreshGlyphs(float deltaSeconds) {
        for (Map.Entry<Integer, UIElement> realised : tree.realisedRows().entrySet()) {
            GlyphView glyph = glyphs.get(realised.getValue());
            TreeRow<UIElement> row = tree.rowAt(realised.getKey());
            if (glyph != null && row != null) glyph.show(row.item());
        }
        return true;
    }

    /** {@code #title} where the node is named, {@code text} where it is not. */
    static String describe(@Nullable UIElement node) {
        if (node == null) return "";
        String id = node.getId();
        return id == null || id.isEmpty() ? node.tagName() : "#" + id;
    }
}
