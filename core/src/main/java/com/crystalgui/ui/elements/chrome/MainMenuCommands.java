package com.crystalgui.ui.elements.chrome;

import com.crystalgui.ui.UIElement;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.ui.UiDataKeys;

/**
 * The handful of commands that exist only for the menu bar, and the bar's default set of titles.
 *
 * <h3>Why this is so short</h3>
 *
 * <p>Almost nothing belongs here, and that is the measure of whether the design worked. File, Edit, View,
 * Graph and Window are assembled entirely from {@code .menu(...)} declarations on commands that already
 * existed for the keyboard and the palette — the bar is a <em>view</em> over the command set, not a
 * feature with contents of its own. What is left is Help, whose two entries have no other caller.</p>
 *
 * <p>The counter-test is worth stating: if adding a menu item routinely meant editing this file, the bar
 * would be the hard-coded list the plan set out to avoid, and every future contribution would have to be
 * threaded through here by hand.</p>
 */
public final class MainMenuCommands {

    private MainMenuCommands() {
    }

    /** Collapses the menu bar to a burger, or expands it. IntelliJ's New UI option. */
    public static final String TOGGLE_BURGER = "view.menuBarAsBurger";

    public static final String ABOUT = "help.about";
    public static final String DOCUMENTATION = "help.documentation";

    static void register(CommandRegistry registry) {
        registry.register(Command.of(TOGGLE_BURGER, "Main Menu as Burger")
                .menu(MenuId.MAIN_VIEW, "1_appearance", 20)
                // The bar comes from the DATA CONTEXT, never captured: two windows would otherwise share
                // one bar's state, which is the same reason Workbench.SHOW_PROBLEMS reads WORKBENCH rather
                // than closing over a workbench.
                .toggledWhereData(data -> {
                    MenuBarView bar = data.get(UIElement.MENU_BAR);
                    return bar != null && bar.isCollapsed();
                })
                .enabledWhereData(data -> data.get(UIElement.MENU_BAR) != null)
                .runWithData(data -> {
                    MenuBarView bar = data.get(UIElement.MENU_BAR);
                    // An explicit Boolean, which is what turns the automatic width check OFF -- a user who
                    // asks for a burger must keep it when the window is widened again.
                    if (bar != null) bar.setCollapsed(!bar.isCollapsed());
                }));

        registry.register(Command.of(ABOUT, "About")
                .menu(MenuId.MAIN_HELP, "9_about", 20)
                .run(() -> Notifications.show(Notification.info("CrystalGUI")
                        .withDetail("A retained-mode UI engine — DOM, CSS cascade, Taffy layout."))));

        registry.register(Command.of(DOCUMENTATION, "Documentation")
                .menu(MenuId.MAIN_HELP, "1_docs", 10)
                // NO BROWSER. Opening a URL needs a platform service this engine does not have, and
                // inventing one for a single Help entry would be a registration slot earning its keep on
                // nothing -- the same argument CgInputService makes about the clipboard. The path is what
                // is useful in a repository anyway.
                .run(() -> Notifications.show(Notification.info("Documentation")
                        .withDetail("docs/ in the repository — start with CGUI_WIDGETS.md"))));
    }

    /**
     * Adds the six standard menus, in order, with their mnemonics.
     *
     * <p>Six, not VS Code's twelve — Terminal, Debug, Go, Selection, Refactor and Build have no subject
     * here, and a menu that opens onto two items reads as something broken rather than as something
     * small. Their few relevant entries are folded into the six that do.</p>
     *
     * <p>Separate from {@link #register} so an application can take the commands and lay out its own bar,
     * or add a seventh menu to this one. {@code MenuBarView} imposes no set of its own.</p>
     */
    public static MenuBarView install(MenuBarView bar) {
        return bar
                .addMenu(MenuId.MAIN_FILE, "&File")
                .addMenu(MenuId.MAIN_EDIT, "&Edit")
                .addMenu(MenuId.MAIN_VIEW, "&View")
                // THE ONE MENU NEITHER REFERENCE HAS. Everything in it is contributed from
                // com.crystalgui.ui.elements.graph, and the shell imports none of it.
                .addMenu(MenuId.MAIN_GRAPH, "&Graph")
                .addMenu(MenuId.MAIN_WINDOW, "&Window")
                .addMenu(MenuId.MAIN_HELP, "&Help");
    }
}
