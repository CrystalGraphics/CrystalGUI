package com.crystalgui.app.uibuilder.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeEditing;
import com.crystalgui.widget.collection.tree.TreeViewCommands;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

/**
 * What the Hierarchy offers beyond its rows: the title line's buttons, New ▸ and the row menu's edit rows.
 *
 * <pre>{@code
 * Disposable hierarchy = HierarchyActions.register(CommandRegistry.global());   // the feature, once
 * }</pre>
 */
public final class HierarchyActions {

    /** Unfolds to the canvas selection and scrolls it in — the title line's locate. */
    public static final String SELECT_IN_HIERARCHY = "uibuilder.selectInHierarchy";

    /** A kind New ▸ offers: what the row says, and a fresh node of it. */
    record Starter(String label, Supplier<UIElement> build) {
    }

    /**
     * A starter set, until the Library (L4.8) lists every buildable kind with search: the registry also holds
     * every workbench and desktop kind, which have no place in a document.
     */
    static final List<Starter> STARTERS = List.of(
            new Starter("Element", UIElement::new),
            new Starter("Text", () -> new UIText("Text")),
            new Starter("Button", () -> new Button("Button")),
            new Starter("Text Field", TextField::new),
            new Starter("Checkbox", Checkbox::new),
            new Starter("Switch", Switch::new),
            new Starter("Slider", Slider::new));

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
            List<MenuEntry> rows = new ArrayList<>(STARTERS.size());
            boolean enabled = context.data().get(HierarchyPanel.HIERARCHY) != null;
            for (int i = 0; i < STARTERS.size(); i++) {
                Starter starter = STARTERS.get(i);
                // UNREGISTERED: one row per kind has no business in the palette. @see MenuContributor
                Command insert = Command.of("uibuilder.new." + i, starter.label())
                        .runWithData(data -> {
                            HierarchyPanel panel = data.get(HierarchyPanel.HIERARCHY);
                            if (panel != null) panel.insertNew(starter.build().get());
                        })
                        .enabledWhereData(data -> data.get(HierarchyPanel.HIERARCHY) != null);
                rows.add(new MenuEntry.Item(insert, "1_kinds", i, enabled, false, false));
            }
            return rows;
        });
        return () -> {
            newRows.dispose();
            editRows.dispose();
        };
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(SELECT_IN_HIERARCHY, "Select Canvas Selection")
                .enabledWhereData(data -> data.get(HierarchyPanel.HIERARCHY) != null)
                .runWithData(data -> {
                    HierarchyPanel panel = data.get(HierarchyPanel.HIERARCHY);
                    if (panel != null) panel.revealSelection();
                }));
    }

    /**
     * New ▸, Select Canvas Selection, Expand Selected and Collapse All, acting on whatever {@code tree} answers
     * when pressed — the hierarchy's tree, or null while no builder is in front.
     */
    public static List<ActionButton> titleActions(Supplier<UIElement> tree) {
        return List.of(
                ActionButton.menu("New Element", HierarchyPanel.NEW_MENU)
                        .icon("crystalgui:general/action/add").context(tree),
                ActionButton.command(SELECT_IN_HIERARCHY)
                        .icon("crystalgui:general/action/locate").context(tree),
                ActionButton.command(TreeViewCommands.EXPAND_SELECTED)
                        .icon("crystalgui:general/action/expandAll").context(tree)
                        .hint(TreeViewCommands.EXPAND_ALL, "Press {} to expand all nodes"),
                ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
                        .icon("crystalgui:general/action/collapseAll").context(tree));
    }
}
