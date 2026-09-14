package com.crystalgui.app.uibuilder.library;

import java.util.List;
import java.util.function.Supplier;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ActionButton;

/**
 * The Library's commands and its title line.
 *
 * <pre>{@code
 * Disposable library = LibraryActions.register(CommandRegistry.global());   // the feature, once
 * }</pre>
 */
public final class LibraryActions {

    /** Cards or compact rows. */
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

    /** The rows toggle, acting on whatever {@code panel} answers when pressed. */
    public static List<ActionButton> titleActions(Supplier<UIElement> panel) {
        return List.of(ActionButton.command(TOGGLE_ROWS).icon("crystalgui:general/action/viewRows").context(panel));
    }
}
