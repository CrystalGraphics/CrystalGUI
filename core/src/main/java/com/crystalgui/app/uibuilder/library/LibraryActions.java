package com.crystalgui.app.uibuilder.library;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeViewCommands;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.widget.overlay.InputDialog;

/**
 * The Library's commands, its menus and its title line: the rows toggle, then Expand All and Collapse All over its
 * categories; a card's Add to Group ▸ and Remove from Group; a user's group's New Group Inside, Rename and Delete.
 *
 * <pre>{@code
 * Disposable library = LibraryActions.register(CommandRegistry.global());   // the feature, once
 * }</pre>
 *
 * <p>Every command resolves the panel, the card and the group from the data context — {@link LibraryPanel#LIBRARY},
 * {@link LibraryPanel#ENTRY}, {@link LibraryPanel#GROUP} — so the right-clicked card is what it acts on.</p>
 */
public final class LibraryActions {

    /** Cards or compact rows. Checked while showing rows. */
    public static final String TOGGLE_ROWS = "uibuilder.library.toggleRows";

    /** Asks for a name and makes a group — holding the right-clicked card, when there is one. */
    public static final String NEW_GROUP = "uibuilder.library.newGroup";

    /** Asks for a name and makes a group inside the right-clicked one. */
    public static final String NEW_SUBGROUP = "uibuilder.library.newSubgroup";

    public static final String RENAME_GROUP = "uibuilder.library.renameGroup";
    public static final String DELETE_GROUP = "uibuilder.library.deleteGroup";
    public static final String REMOVE_FROM_GROUP = "uibuilder.library.removeFromGroup";

    /** A card's Add to Group ▸: each of the user's groups, then New Group…. */
    public static final MenuId ADD_TO_GROUP_MENU = MenuId.of("uibuilder/library/add-to-group");

    private LibraryActions() {
    }

    /** Registers the commands and contributes the menus. Dispose with the feature. */
    public static Disposable register(CommandRegistry registry) {
        registry.contribute(LibraryActions.class, LibraryActions::declare);
        List<Disposable> menus = List.of(
                registry.contributeMenu(LibraryPanel.CARD_MENU, (menu, context) -> List.of(
                        new MenuEntry.Submenu(ADD_TO_GROUP_MENU, "Add to Group", "1_groups", 0),
                        row(registry, REMOVE_FROM_GROUP, "1_groups", 10, context))),
                registry.contributeMenu(ADD_TO_GROUP_MENU, LibraryActions::addToGroupRows),
                registry.contributeMenu(LibraryPanel.GROUP_MENU, (menu, context) -> List.of(
                        row(registry, NEW_SUBGROUP, "0_new", 0, context),
                        row(registry, RENAME_GROUP, "1_group", 0, context),
                        row(registry, DELETE_GROUP, "1_group", 10, context))),
                registry.contributeMenu(LibraryPanel.PANEL_MENU, (menu, context) -> List.of(
                        row(registry, NEW_GROUP, "1_new", 0, context))));
        return () -> menus.forEach(Disposable::dispose);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(TOGGLE_ROWS, "Show as Rows")
                .enabledWhereData(data -> data.get(LibraryPanel.LIBRARY) != null)
                .toggledWhereData(data -> {
                    LibraryPanel panel = data.get(LibraryPanel.LIBRARY);
                    return panel != null && panel.isRows();
                })
                .runWithData(data -> {
                    LibraryPanel panel = data.get(LibraryPanel.LIBRARY);
                    if (panel != null) panel.setRows(!panel.isRows());
                }));
        registry.register(Command.of(NEW_GROUP, "New Group…")
                .enabledWhereData(data -> data.get(LibraryPanel.LIBRARY) != null)
                .run(context -> {
                    LibraryPanel panel = context.data().get(LibraryPanel.LIBRARY);
                    if (panel == null) return;
                    LibraryCatalog.Entry card = context.data().get(LibraryPanel.ENTRY);
                    UserLibrary user = panel.userLibrary();
                    InputDialog.ask(UIElement.sourceOf(context), "New Group", "Name", "", name -> {
                        if (user.createGroup(name) && card != null && !card.isStarter()) {
                            user.addToGroup(UserLibrary.path(name), card.kind());
                        }
                    });
                }));
        registry.register(Command.of(NEW_SUBGROUP, "New Group Inside…")
                .enabledWhereData(data -> userGroup(data) != null)
                .run(context -> {
                    LibraryCatalog.Group group = userGroup(context.data());
                    LibraryPanel panel = context.data().get(LibraryPanel.LIBRARY);
                    if (group == null || panel == null) return;
                    InputDialog.ask(UIElement.sourceOf(context), "New Group in \u201C" + group.name() + "\u201D",
                            "Name", "", name -> panel.userLibrary().createGroup(UserLibrary.pathIn(group.label(), name)));
                }));
        registry.register(Command.of(RENAME_GROUP, "Rename Group…")
                .enabledWhereData(data -> userGroup(data) != null)
                .run(context -> {
                    LibraryCatalog.Group group = userGroup(context.data());
                    LibraryPanel panel = context.data().get(LibraryPanel.LIBRARY);
                    if (group == null || panel == null) return;
                    // THE NAME, not the path: renaming stays inside the parent. A name with a slash in it nests deeper.
                    InputDialog.ask(UIElement.sourceOf(context), "Rename Group", "Name", group.name(),
                            name -> panel.userLibrary().renameGroup(group.label(),
                                    UserLibrary.pathIn(group.parent(), name)));
                }));
        registry.register(Command.of(DELETE_GROUP, "Delete Group")
                .enabledWhereData(data -> userGroup(data) != null)
                .run(context -> {
                    LibraryCatalog.Group group = userGroup(context.data());
                    LibraryPanel panel = context.data().get(LibraryPanel.LIBRARY);
                    if (group == null || panel == null) return;
                    int inside = panel.userLibrary().subgroupCount(group.label());
                    String detail = inside == 0 ? "Its kinds stay in the Library."
                            : "Its " + inside + (inside == 1 ? " group goes" : " groups go")
                                    + " with it. Their kinds stay in the Library.";
                    InputDialog.askYesNo(UIElement.sourceOf(context), "Delete Group",
                            "Delete the group \u201C" + group.name() + "\u201D?", detail,
                            () -> panel.userLibrary().deleteGroup(group.label()), () -> { });
                }));
        registry.register(Command.of(REMOVE_FROM_GROUP, "Remove from Group")
                .enabledWhereData(data -> {
                    LibraryCatalog.Group group = groupToRemoveFrom(data);
                    LibraryCatalog.Entry card = cardToRemove(data);
                    return group != null && card != null && !card.isStarter() && group.kinds().contains(card.kind());
                })
                .runWithData(data -> {
                    LibraryCatalog.Group group = groupToRemoveFrom(data);
                    LibraryCatalog.Entry card = cardToRemove(data);
                    LibraryPanel panel = data.get(LibraryPanel.LIBRARY);
                    if (group != null && card != null && panel != null) {
                        panel.userLibrary().removeFromGroup(group.label(), card.kind());
                    }
                }));
    }

    /** One row per user group, dimmed where the card already is, then New Group…. */
    private static List<MenuEntry> addToGroupRows(MenuId menu, CommandContext context) {
        LibraryPanel panel = context.data().get(LibraryPanel.LIBRARY);
        LibraryCatalog.Entry card = context.data().get(LibraryPanel.ENTRY);
        List<MenuEntry> rows = new ArrayList<>();
        if (panel != null && card != null && !card.isStarter()) {
            List<LibraryCatalog.Group> groups = panel.userLibrary().groups();
            for (int i = 0; i < groups.size(); i++) {
                LibraryCatalog.Group group = groups.get(i);
                // UNREGISTERED: a row per group has no business in the palette. @see MenuContributor
                // THE PATH, spaced, since two groups in different parents may share a name.
                Command add = Command.of("uibuilder.library.addTo." + i,
                                group.label().replace(LibraryCatalog.Group.SEPARATOR, " / "))
                        .runWithData(data -> panel.userLibrary().addToGroup(group.label(), card.kind()));
                rows.add(new MenuEntry.Item(add, "1_groups", i, !group.kinds().contains(card.kind()), false, false));
            }
        }
        Command create = CommandRegistry.global().get(NEW_GROUP);
        if (create != null) rows.add(new MenuEntry.Item(create, "2_new", 0, create.isEnabled(context), false, false));
        return rows;
    }

    private static MenuEntry row(CommandRegistry registry, String id, String group, int order, CommandContext context) {
        Command command = registry.get(id);
        return new MenuEntry.Item(command, group, order, command != null && command.isEnabled(context), false, false);
    }

    /** The card a menu was opened on, else — from a key — the panel's selection. */
    @Nullable
    private static LibraryCatalog.Entry cardToRemove(DataContext data) {
        LibraryCatalog.Entry card = data.get(LibraryPanel.ENTRY);
        LibraryPanel panel = data.get(LibraryPanel.LIBRARY);
        return card != null || panel == null ? card : panel.selected();
    }

    /**
     * The user's group a menu was opened in, else — from a key — the one the selected card was picked in, read again
     * so a card just taken out is not taken out twice.
     */
    @Nullable
    private static LibraryCatalog.Group groupToRemoveFrom(DataContext data) {
        if (data.get(LibraryPanel.GROUP) != null) return userGroup(data);
        LibraryPanel panel = data.get(LibraryPanel.LIBRARY);
        LibraryCatalog.Group picked = panel == null ? null : panel.selectedGroup();
        return picked == null || !picked.user() ? null : panel.userLibrary().group(picked.label());
    }

    /** The user's group the context is in, or null for a shipped group or none. */
    @Nullable
    private static LibraryCatalog.Group userGroup(DataContext data) {
        LibraryCatalog.Group group = data.get(LibraryPanel.GROUP);
        return group != null && group.user() ? group : null;
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
