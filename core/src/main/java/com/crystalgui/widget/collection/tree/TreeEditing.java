package com.crystalgui.widget.collection.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.ClipboardActions;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.list.RowEditing;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.ContextMenu;

/**
 * <b>A tree whose items the user edits</b> — drag to move or copy, cut, copy, paste, duplicate, rename and
 * delete, over the whole selection, from keys, the Edit menu and a context menu. What the Project panel does
 * to files, for any tree.
 *
 * <pre>{@code
 * private static final TreeClipboard<Node> CLIPBOARD = new TreeClipboard<>();
 *
 * editing = new TreeEditing<>(tree, this, this::itemForRow, tree::refresh, CLIPBOARD);
 * editing.setModel(new NodeModel());                              // performs the verbs; see TreeEditModel
 * editing.attachContextMenu(CommandRegistry.global(), () -> ContextMenu.of(NODE_MENU));
 * TreeEditing.contributeMenu(CommandRegistry.global(), NODE_MENU); // once: its six rows in that menu
 *
 * // the renderer
 * public UIElement createTemplate() { ... editing.installRow(row, field); ... }
 * public void bind(Node item, TreeRow<Node> row, int i, UIElement template) {
 *     editing.bindRow(template, label, field, item);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Keys are bound on the tree's own keymap, so they are live only while the tree has focus: Mod+X, Mod+C,
 *       Mod+V, Mod+D, F2, Delete. {@link TreeEditCommands} holds the commands.</li>
 *   <li>Every verb acts on the selection, outermost items only, in tree order. With no model set every verb is
 *       disabled.</li>
 *   <li>A cut is performed at paste; until then its rows carry {@link #CUT_CLASS}.</li>
 *   <li>{@code host} is where the drag ghost parks, and must be an ancestor of the tree or the tree's owner.</li>
 * </ul>
 */
public final class TreeEditing<T> {

    /** The editing of the tree a command was invoked from. Answered by the tree itself. */
    @SuppressWarnings("rawtypes")
    public static final DataKey<TreeEditing> KEY = DataKey.create("treeEditing", TreeEditing.class);

    /** On a row whose item a cut is holding. */
    public static final String CUT_CLASS = "__cut__";

    private final TreeView<T> tree;

    private final Function<UIElement, T> itemForRow;

    private final Runnable rebuild;

    private final TreeClipboard<T> clipboard;

    private final RowEditing<T> rows;

    private final TreeDragAndDrop<T> dragAndDrop;

    @Nullable
    private TreeEditModel<T> model;

    /**
     * @param host       where the drag ghost parks — the tree's owner
     * @param itemForRow the item a realised row element shows now, or null
     * @param rebuild    rebinds the visible rows; deferred where an edit can begin from a handler on a row
     * @param clipboard  shared by every tree of this kind, so a cut in one pastes in another
     */
    public TreeEditing(TreeView<T> tree, UIElement host, Function<UIElement, T> itemForRow, Runnable rebuild,
                       TreeClipboard<T> clipboard) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.itemForRow = Objects.requireNonNull(itemForRow, "itemForRow");
        this.rebuild = Objects.requireNonNull(rebuild, "rebuild");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.rows = new RowEditing<>(tree, itemForRow, rebuild);
        this.dragAndDrop = new TreeDragAndDrop<>(this, host);
        TreeEditCommands.register();
        TreeEditCommands.bindKeys(tree.keymap());
        tree.putData(KEY, this);
        tree.setClipboardActions(clipboardActions);
        dragAndDrop.installDropTarget();
    }

    /** Sets what performs the verbs; null disables them. */
    public TreeEditing<T> setModel(@Nullable TreeEditModel<T> model) {
        this.model = model;
        return this;
    }

    @Nullable
    public TreeEditModel<T> model() {
        return model;
    }

    public TreeView<T> tree() {
        return tree;
    }

    /** The in-row field rename runs through — also what a consumer names a new entry with. */
    public RowEditing<T> rows() {
        return rows;
    }

    public TreeClipboard<T> clipboard() {
        return clipboard;
    }

    // ── The selection ────────────────────────────────────────────────────────────────────────────

    /** The selected items in tree order, without any that sit inside another selected item. */
    public List<T> selection() {
        List<Integer> indices = new ArrayList<>(tree.getSelectedIndices());
        Collections.sort(indices);
        List<TreeRow<T>> visible = tree.visibleRows();
        List<T> selected = new ArrayList<>(indices.size());
        for (int index : indices) {
            if (index >= 0 && index < visible.size()) selected.add(visible.get(index).item());
        }
        return outermost(selected);
    }

    private List<T> outermost(List<T> items) {
        TreeEditModel<T> m = model;
        if (m == null || items.size() < 2) return items;
        Set<T> all = Collections.newSetFromMap(new IdentityHashMap<>());
        all.addAll(items);
        List<T> kept = new ArrayList<>(items.size());
        for (T item : items) {
            boolean inside = false;
            for (T at = m.parentOf(item); at != null && !inside; at = m.parentOf(at)) inside = all.contains(at);
            if (!inside) kept.add(item);
        }
        return kept;
    }

    private boolean allEditable(List<T> items) {
        TreeEditModel<T> m = model;
        if (m == null || items.isEmpty()) return false;
        for (T item : items) {
            if (!m.canEdit(item)) return false;
        }
        return true;
    }

    // ── The verbs ────────────────────────────────────────────────────────────────────────────────

    public boolean canCut() {
        return allEditable(selection());
    }

    /** Holds the selection to be moved by the next paste, and dims its rows. */
    public void cut() {
        put(TreeClipboard.Mode.CUT);
    }

    public boolean canCopy() {
        return allEditable(selection());
    }

    public void copy() {
        put(TreeClipboard.Mode.COPY);
    }

    private void put(TreeClipboard.Mode mode) {
        TreeEditModel<T> m = model;
        List<T> selected = selection();
        if (m == null || !allEditable(selected)) return;
        clipboard.put(selected, mode, m.clipboardText(selected));
        rebuild.run();
    }

    public boolean canPaste() {
        TreeEditModel<T> m = model;
        if (m == null || clipboard.isEmpty()) return false;
        TreeEditModel.Target<T> target = m.pasteTarget(selection());
        return target != null && m.canDrop(clipboard.items(), target.parent());
    }

    /** Moves what a cut holds, or copies what a copy holds, to where the selection says. */
    public void paste() {
        TreeEditModel<T> m = model;
        if (!canPaste() || m == null) return;
        TreeEditModel.Target<T> target = m.pasteTarget(selection());
        boolean moving = clipboard.mode() == TreeClipboard.Mode.CUT;
        List<T> held = clipboard.consumeIfCut();
        if (moving) m.move(held, target);
        else m.copy(held, target);
        rebuild.run();
    }

    public boolean canDuplicate() {
        TreeEditModel<T> m = model;
        List<T> selected = selection();
        return m != null && allEditable(selected) && m.canDuplicate(selected)
                && m.parentOf(selected.get(selected.size() - 1)) != null;
    }

    /** Copies of the selection, just after its last item in that item's parent. */
    public void duplicate() {
        TreeEditModel<T> m = model;
        if (!canDuplicate() || m == null) return;
        List<T> selected = selection();
        T last = selected.get(selected.size() - 1);
        T parent = m.parentOf(last);
        int index = m.indexOf(last);
        m.copy(selected, new TreeEditModel.Target<>(parent, index < 0 ? -1 : index + 1));
    }

    public boolean canDelete() {
        return allEditable(selection());
    }

    public void delete() {
        TreeEditModel<T> m = model;
        List<T> selected = selection();
        if (m == null || !allEditable(selected)) return;
        m.delete(selected);
    }

    public boolean canRename() {
        List<T> selected = selection();
        return !rows.isEditing() && selected.size() == 1 && allEditable(selected);
    }

    /** Opens the selected item's name for editing in its row. */
    public void renameSelected() {
        if (!canRename()) return;
        rename(selection().get(0));
    }

    /** Opens {@code item}'s name for editing in its row. */
    public void rename(T item) {
        TreeEditModel<T> m = model;
        if (m == null || !m.canEdit(item)) return;
        String name = m.nameOf(item);
        RowEditing.Edit<T> edit = RowEditing.Edit.of(item, name, typed -> m.rename(item, typed))
                .accepting(typed -> m.acceptsName(item, typed))
                .conflicting(m.noun(), typed -> m.freeNameFor(item, typed));
        // SCROLLED TO FIRST: an edit on a row nobody can see has no visible subject.
        List<TreeRow<T>> visible = tree.visibleRows();
        for (int i = 0; i < visible.size(); i++) {
            if (Objects.equals(visible.get(i).item(), item)) {
                tree.scrollToIndex(i);
                break;
            }
        }
        int stemEnd = m.renameSelectionEnd(item, name);
        rows.begin(stemEnd > 0 ? edit.selecting(0, stemEnd) : edit);
    }

    /** Cut, Copy and Paste for the Edit menu, which asks the focused element rather than naming a tree. */
    public ClipboardActions clipboardActions() {
        return clipboardActions;
    }

    private final ClipboardActions clipboardActions = new ClipboardActions() {
        @Override
        public boolean canCut() {
            return TreeEditing.this.canCut();
        }

        @Override
        public void cut() {
            TreeEditing.this.cut();
        }

        @Override
        public boolean canCopy() {
            return TreeEditing.this.canCopy();
        }

        @Override
        public void copy() {
            TreeEditing.this.copy();
        }

        @Override
        public boolean canPaste() {
            return TreeEditing.this.canPaste();
        }

        @Override
        public void paste() {
            TreeEditing.this.paste();
        }
    };

    // ── Rows ─────────────────────────────────────────────────────────────────────────────────────

    /** Wires a row once, from {@code createTemplate}: its drag, and its rename field. */
    public void installRow(UIElement row, TextField field) {
        rows.installEditor(row, field);
        dragAndDrop.installRow(row);
    }

    /** Puts a row's state in line with {@code item}, from every {@code bind}: the rename field, and the cut mark. */
    public void bindRow(UIElement row, UIElement label, TextField field, T item) {
        rows.apply(row, label, field, item);
        boolean cut = clipboard.isCut(item);
        if (cut != row.hasClass(CUT_CLASS)) {
            if (cut) row.addClass(CUT_CLASS);
            else row.removeClass(CUT_CLASS);
        }
    }

    /** The item a realised row element shows now, or null. */
    @Nullable
    T itemForRow(UIElement row) {
        return itemForRow.apply(row);
    }

    /** The realised row element {@code hit} is inside, or null. */
    @Nullable
    UIElement rowElementFor(@Nullable UIElement hit) {
        for (UIElement at = hit; at != null; at = at.parentElement()) {
            if (tree.indexOfRowElement(at) >= 0 && tree.indexOfRowElement(at.parentElement()) < 0) return at;
        }
        return null;
    }

    // ── Menus ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens {@code menu} on a right-click over the tree, making the clicked row the subject: a row outside the
     * selection becomes the selection, and one inside it keeps it, as Windows Explorer does.
     */
    public void attachContextMenu(CommandRegistry registry, Supplier<ContextMenu> menu) {
        tree.suppressDefaultContextMenu();
        ContextMenu.attach(tree, registry, element -> {
            int index = tree.indexOfRowElement(element);
            if (index >= 0 && !tree.getSelectedIndices().contains(index)) tree.select(index);
            return menu.get();
        });
    }

    /** Puts Cut, Copy, Paste and Duplicate, then Rename and Delete, into {@code menu}. Once per menu. */
    public static Disposable contributeMenu(CommandRegistry registry, MenuId menu) {
        return TreeEditCommands.contributeTo(registry, menu);
    }
}
