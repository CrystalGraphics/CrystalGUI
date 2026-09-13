package com.crystalgui.widget.collection.tree;

import java.util.List;

import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.ui.input.keymap.Keymap;

/**
 * Expand Selected, Expand All and Collapse All, for every {@link TreeView} — IntelliJ's {@code TreeExpander}
 * actions.
 *
 * <p>Registered and bound by the tree itself on its first attach, and resolved from {@link TreeView#KEY}, so
 * a key pressed in any tree, or a title action naming that tree as its context, acts on it.</p>
 *
 * <pre>{@code
 * ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
 *         .icon("crystalgui:general/action/collapseAll")
 *         .context(tree);
 * }</pre>
 */
public final class TreeViewCommands {

    public static final String EXPAND_SELECTED = "tree.expandSelected";
    public static final String EXPAND_ALL = "tree.expandAll";
    public static final String COLLAPSE_ALL = "tree.collapseAll";

    private TreeViewCommands() {
    }

    /** Registers into {@link CommandRegistry#global()}. Idempotent. */
    public static void register() {
        CommandRegistry.global().contribute(TreeViewCommands.class, TreeViewCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(EXPAND_SELECTED, "Expand Selected")
                .enabledWhereData(data -> canExpandSelected(treeIn(data)))
                .runWithData(data -> {
                    TreeView<?> tree = treeIn(data);
                    if (canExpandSelected(tree)) expandSelected(tree);
                }));
        registry.register(Command.of(EXPAND_ALL, "Expand All")
                .enabledWhereData(data -> treeIn(data) != null)
                .runWithData(data -> {
                    TreeView<?> tree = treeIn(data);
                    if (tree != null) expandAll(tree);
                }));
        registry.register(Command.of(COLLAPSE_ALL, "Collapse All")
                .enabledWhereData(data -> treeIn(data) != null && treeIn(data).hasExpanded())
                .runWithData(data -> {
                    TreeView<?> tree = treeIn(data);
                    if (tree != null) tree.collapseAll();
                }));
    }

    /** Mod+= expands the selection, Mod+Shift+= everything, Mod+- collapses — and the numpad's +/- alike. */
    public static void bindKeys(Keymap keymap) {
        keymap.bind("Mod+Equals", EXPAND_SELECTED);
        keymap.bind("Mod+Add", EXPAND_SELECTED);
        keymap.bind("Mod+Shift+Equals", EXPAND_ALL);
        keymap.bind("Mod+Shift+Add", EXPAND_ALL);
        keymap.bind("Mod+Minus", COLLAPSE_ALL);
        keymap.bind("Mod+Subtract", COLLAPSE_ALL);
    }

    private static TreeView<?> treeIn(DataContext data) {
        return data.get(TreeView.KEY);
    }

    private static boolean canExpandSelected(TreeView<?> tree) {
        if (tree == null) return false;
        for (int index : tree.getSelectedIndices()) {
            TreeRow<?> row = tree.rowAt(index);
            if (row != null && row.expandable()) return true;
        }
        return false;
    }

    private static <T> void expandSelected(TreeView<T> tree) {
        List<T> selected = tree.selectedItems();
        tree.expandSubtrees(selected);
    }

    private static <T> void expandAll(TreeView<T> tree) {
        tree.expandSubtrees(tree.roots());
    }
}
