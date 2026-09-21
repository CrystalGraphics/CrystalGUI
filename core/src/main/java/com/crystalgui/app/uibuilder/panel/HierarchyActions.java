package com.crystalgui.app.uibuilder.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.app.uibuilder.BuilderCommands;
import com.crystalgui.app.uibuilder.document.NodeSelectors;
import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.app.uibuilder.library.LibraryCatalog;
import com.crystalgui.app.uibuilder.library.LibraryGroups;
import com.crystalgui.core.command.ActionIcons;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeEditing;
import com.crystalgui.widget.collection.tree.TreeViewCommands;
import com.crystalgui.widget.composite.ActionButton;

/**
 * What the Hierarchy offers beyond its rows: the title line's buttons, and the row menu's New ▸, edit rows,
 * Copy and Paste Attributes, and Copy Selector.
 *
 * <pre>{@code
 * Disposable hierarchy = HierarchyActions.register(CommandRegistry.global());   // the feature, once
 * }</pre>
 */
public final class HierarchyActions {

    /** Unfolds to the canvas selection and scrolls it in — the title line's locate. */
    public static final String SELECT_IN_HIERARCHY = "uibuilder.selectInHierarchy";

    /** Puts a selector for each selected node on the clipboard — the Hierarchy's Copy Path. @see NodeSelectors */
    public static final String COPY_SELECTOR = "uibuilder.copySelector";

    private HierarchyActions() {
    }

    /** Registers the commands and contributes New ▸ and the edit rows. Dispose with the feature. */
    public static Disposable register(CommandRegistry registry) {
        registry.contribute(HierarchyActions.class, HierarchyActions::declare);
        // THE TREE COMMANDS THE TITLE LINE NAMES, which a tree registers only once one attaches -- with no
        // builder in front there is none, and the buttons would name raw ids.
        TreeViewCommands.register();
        Disposable editRows = TreeEditing.contributeMenu(registry, HierarchyPanel.CONTEXT_MENU);
        Disposable newRows = registry.contributeMenu(HierarchyPanel.NEW_MENU, (menu, context) -> {
            // THE LIBRARY'S COMMON, so the two lists of what a document starts from cannot drift apart.
            List<Name> kinds = LibraryGroups.COMMON.kinds();
            LibraryCatalog catalog = LibraryCatalog.current();
            List<MenuEntry> rows = new ArrayList<>(kinds.size());
            boolean enabled = context.data().get(HierarchyPanel.HIERARCHY) != null;
            for (int i = 0; i < kinds.size(); i++) {
                LibraryCatalog.Entry entry = catalog.entry(kinds.get(i));
                if (entry == null) continue;
                KindGlyphs.Glyph glyph = KindGlyphs.ofKind(entry.kind());
                // UNREGISTERED: one row per kind has no business in the palette. @see MenuContributor
                Command insert = Command.of("uibuilder.new." + i, glyph.words())
                        .icon(glyph.icon(), glyph.role().cssClass())
                        .runWithData(data -> {
                            HierarchyPanel panel = data.get(HierarchyPanel.HIERARCHY);
                            if (panel != null) panel.insertNew(entry.build());
                        })
                        .enabledWhereData(data -> data.get(HierarchyPanel.HIERARCHY) != null);
                rows.add(new MenuEntry.Item(insert, "1_kinds", i, enabled, false, false));
            }
            return rows;
        });
        // THE BUILDER'S ATTRIBUTE COMMANDS, beside Copy Selector -- the panel answers the builder's keys, so they
        // act on the row's node as they do on the canvas.
        Disposable attributeRows = registry.contributeMenu(HierarchyPanel.CONTEXT_MENU, (menu, context) -> {
            List<MenuEntry> rows = new ArrayList<>(2);
            attributeRow(rows, registry, BuilderCommands.COPY_ATTRIBUTES, 10, context);
            attributeRow(rows, registry, BuilderCommands.PASTE_ATTRIBUTES, 20, context);
            return rows;
        });
        return () -> {
            attributeRows.dispose();
            newRows.dispose();
            editRows.dispose();
        };
    }

    private static void attributeRow(List<MenuEntry> rows, CommandRegistry registry, String id, int order,
                                     CommandContext context) {
        Command command = registry.get(id);
        if (command != null) rows.add(new MenuEntry.Item(command, "3_attributes", order, command.isEnabled(context), false, false));
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(SELECT_IN_HIERARCHY, "Select Canvas Selection")
                .enabledWhereData(data -> data.get(HierarchyPanel.HIERARCHY) != null)
                .runWithData(data -> {
                    HierarchyPanel panel = data.get(HierarchyPanel.HIERARCHY);
                    if (panel != null) panel.revealSelection();
                }));
        registry.register(Command.of(COPY_SELECTOR, "Copy Selector")
                // BETWEEN THE CLIPBOARD AND MODIFY GROUPS, where the explorer's Copy Path sits.
                .menu(HierarchyPanel.CONTEXT_MENU, "3_paths", 10)
                .enabledWhereData(data -> {
                    HierarchyPanel panel = data.get(HierarchyPanel.HIERARCHY);
                    return panel != null && !panel.selectedNodes().isEmpty();
                })
                .runWithData(data -> {
                    HierarchyPanel panel = data.get(HierarchyPanel.HIERARCHY);
                    if (panel == null || panel.selectedNodes().isEmpty()) return;
                    List<String> selectors = new ArrayList<>();
                    for (UIElement node : panel.selectedNodes()) {
                        selectors.add(NodeSelectors.cssPath(node, panel.documentRoot()));
                    }
                    String text = String.join("\n", selectors);
                    CgPlatform.input().setClipboard(text);
                    Notifications.show(Notification.info("Copied").withDetail(text));
                }));
    }

    /**
     * New ▸, Select Canvas Selection, Expand Selected and Collapse All, acting on whatever {@code tree} answers
     * when pressed — the hierarchy's tree, or null while no builder is in front.
     */
    public static List<ActionButton> titleActions(Supplier<UIElement> tree) {
        return List.of(
                ActionButton.menu("New Element", HierarchyPanel.NEW_MENU)
                        .icon(ActionIcons.ADD).context(tree),
                ActionButton.command(SELECT_IN_HIERARCHY)
                        .icon(ActionIcons.LOCATE).context(tree),
                ActionButton.command(TreeViewCommands.EXPAND_SELECTED)
                        .icon(ActionIcons.EXPAND_ALL).context(tree)
                        .hint(TreeViewCommands.EXPAND_ALL, "Press {} to expand all nodes"),
                ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
                        .icon(ActionIcons.COLLAPSE_ALL).context(tree));
    }
}
