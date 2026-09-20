package com.crystalgui.workbench.chrome.menu;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;

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

    /** Opens the main menu under the burger. F10, as in IntelliJ and every Windows menu bar. */
    public static final String SHOW_MAIN_MENU = "view.mainMenu";

    public static final String ABOUT = "help.about";
    public static final String DOCUMENTATION = "help.documentation";

    static void register(CommandRegistry registry) {
        // A COMMAND, so the burger is reachable by key and findable in the palette. Every title beside it
        // is a word with a mnemonic; the burger is a picture, and a picture that only answers the mouse is
        // the one control in the caption with no name at all.
        registry.register(Command.of(SHOW_MAIN_MENU, "Main Menu")
                .binding("F10")
                .enabledWhereData(data -> data.get(MenuBarView.MENU_BAR) != null)
                .runWithData(data -> {
                    MenuBarView bar = data.get(MenuBarView.MENU_BAR);
                    if (bar != null) bar.toggleMainMenu();
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
