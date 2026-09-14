package com.crystalgui.app.uibuilder.library;

import java.util.List;
import java.util.function.Supplier;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeViewCommands;
import com.crystalgui.widget.composite.ActionButton;

/**
 * The Library's commands and its title line: the rows toggle, then Expand All and Collapse All over its categories.
 *
 * <pre>{@code
 * Disposable library = LibraryActions.register(CommandRegistry.global());   // the feature, once
 * }</pre>
 */
public final class LibraryActions {

    /** Cards or compact rows. Checked while showing rows. */
    public static final String TOGGLE_ROWS = "uibuilder.library.toggleRows";

    private LibraryActions() {
    }

    public static Disposable register(CommandRegistry registry) {
        return registry.register(Command.of(TOGGLE_ROWS, "Show as Rows")
                .enabledWhereData(data -> data.get(LibraryPanel.LIBRARY) != null)
                .toggledWhereData(data -> {
                    LibraryPanel panel = data.get(LibraryPanel.LIBRARY);
                    return panel != null && panel.isRows();
                })
                .runWithData(data -> {
                    LibraryPanel panel = data.get(LibraryPanel.LIBRARY);
                    if (panel != null) panel.setRows(!panel.isRows());
                }));
    }

    /**
     * The rows toggle on whatever {@code panel} answers when pressed, and the tree's own Expand All and Collapse All
     * on its category tree. Expand All rather than the Hierarchy's Expand Selected: a card is never a category.
     */
    public static List<ActionButton> titleActions(Supplier<LibraryPanel> panel) {
        Supplier<UIElement> tree = () -> {
            LibraryPanel library = panel.get();
            return library == null ? null : library.tree();
        };
        return List.of(
                ActionButton.command(TOGGLE_ROWS).icon("crystalgui:general/action/viewRows")
                        .whenToggled("crystalgui:general/action/viewCards", "Show as Cards").context(panel::get),
                ActionButton.command(TreeViewCommands.EXPAND_ALL)
                        .icon("crystalgui:general/action/expandAll").context(tree),
                ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
                        .icon("crystalgui:general/action/collapseAll").context(tree));
    }
}
