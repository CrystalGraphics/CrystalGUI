package com.crystalgui.workbench.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.collection.tree.TreeViewCommands;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.Tooltip;

/**
 * <b>A view's title actions reach its container's title line and act on what they name</b> — the seam any
 * panel adopts, proven with a panel that is only a tree.
 */
public class TitleActionsTest extends UiDocumentTestBase {

    private static final MenuId NEW_MENU = MenuId.of("test/title-actions/new");
    private static final String NEW_THING = "test.titleActions.newThing";

    /** a → a1 → a1a, b */
    private static final Map<String, List<String>> CHILDREN = Map.of("a", List.of("a1"), "a1", List.of("a1a"));

    /** A panel that is a tree and three buttons, and focusable itself — so its focus target is what decides. */
    static final class TreePanel extends UIElement implements TitleActionsContributor, FocusableView {
        final TreeView<String> tree;
        final ActionButton add;
        final ActionButton expand;
        final ActionButton collapse;

        TreePanel() {
            tree = new TreeView<>(new TreeDataSource<String>() {
                @Override
                public List<String> roots() {
                    return List.of("a", "b");
                }

                @Override
                public List<String> children(String parent) {
                    return CHILDREN.getOrDefault(parent, List.of());
                }

                @Override
                public boolean hasChildren(String item) {
                    return CHILDREN.containsKey(item);
                }
            });
            tree.setItemHeight(10f);
            tree.setSelectionMode(SelectionMode.MULTIPLE);
            tree.layout(l -> l.width(100).height(100));
            tree.setRenderer(new TreeRenderer<String>() {
                @Override
                public UIElement createTemplate() {
                    return new UIElement();
                }

                @Override
                public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                }
            });
            append(tree);
            setFocusPolicy(FocusPolicy.CLICK);
            add = ActionButton.menu("New Thing", NEW_MENU).icon("crystalgui:general/action/add").context(tree);
            expand = ActionButton.command(TreeViewCommands.EXPAND_SELECTED)
                    .icon("crystalgui:general/action/expandAll").context(tree)
                    .hint(TreeViewCommands.EXPAND_ALL, "Press {} to expand all nodes");
            collapse = ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
                    .icon("crystalgui:general/action/collapseAll").context(tree);
        }

        @Override
        public List<ActionButton> titleActions() {
            return List.of(add, expand, collapse);
        }

        @Override
        public UIElement focusTarget() {
            return tree;
        }
    }

    private TreePanel panel;
    private ViewContainer container;

    @Before
    public void mount() {
        CommandRegistry.global().register(Command.of(NEW_THING, "Thing").menu(NEW_MENU, "1_new", 10).run(() -> { }));
        panel = new TreePanel();
        container = new ViewContainer("test.panel", "Panel");
        container.setViews(List.of(new ViewContainerRegistry.ViewEntry("test.panel", "Panel", () -> panel)));
        container.layout(l -> l.width(300).height(200));
        document.append(container);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    @Test
    public void aToggleShowsWhatAPressSwitchesTo() {
        boolean[] rows = {false};
        CommandRegistry.global().register(Command.of("test.titleActions.rows", "Show as Rows")
                .toggledWhen(ctx -> rows[0]).run(() -> rows[0] = !rows[0]));
        ActionButton toggle = ActionButton.command("test.titleActions.rows").icon("crystalgui:general/action/viewRows")
                .whenToggled("crystalgui:general/action/viewCards", "Show as Cards");
        document.append(toggle);
        settle();
        assertEquals("Show as Rows", toggle.tooltip().getBaseText());
        Object cardsFace = toggle.getStyle().getGeneralGroup().overlay();

        toggle.onPressed.emit();
        assertTrue(rows[0]);
        assertEquals("the press did not turn the button to its other face", "Show as Cards", toggle.tooltip().getBaseText());
        settle();
        assertFalse("the icon did not change with the face", cardsFace.equals(toggle.getStyle().getGeneralGroup().overlay()));
    }

    @Test
    public void theActionsSitInOrderBeforeTheOptionsMenuAndHide() {
        assertEquals(List.of(panel.add, panel.expand, panel.collapse), container.titleActions());
        UIElement trailing = panel.add.parentElement().parentElement();
        assertTrue(trailing.hasClass(ViewContainer.TITLE_TRAILING_CLASS));
        List<UIElement> row = trailing.children();
        int actions = row.indexOf(panel.add.parentElement());
        int options = -1;
        int hide = -1;
        for (int i = 0; i < row.size(); i++) {
            if (row.get(i).hasClass(ViewContainer.OPTIONS_CLASS)) options = i;
            if (row.get(i).hasClass(ViewContainer.HIDE_CLASS)) hide = i;
        }
        assertTrue("actions " + actions + ", options " + options + ", hide " + hide,
                actions >= 0 && actions < options && options < hide);
    }

    @Test
    public void aCommandButtonActsOnItsContextAndNamesItsLiveChord() {
        panel.tree.setExpanded("a", true);
        String chord = String.valueOf(Keymap.acceleratorFor(panel.tree, TreeViewCommands.COLLAPSE_ALL));
        assertEquals("Collapse All", panel.collapse.tooltip().getBaseText());
        assertEquals("the tooltip does not name the live chord", chord, panel.collapse.tooltip().getShortcut());
        assertTrue("an action's tooltip is one a pointer crossing the title line must wait for",
                panel.collapse.tooltip().hasClass(Tooltip.WAIT_CLASS));

        panel.collapse.onPressed.emit();
        assertFalse("Collapse All did not reach the tree it names", panel.tree.hasExpanded());
        panel.collapse.refreshEnabled();
        assertFalse("nothing left to collapse, and the button still offers it", panel.collapse.isEnabled());
    }

    @Test
    public void expandSelectedOpensTheWholeSubtreeAndItsTooltipNamesExpandAll() {
        panel.tree.select(0);
        panel.expand.refreshEnabled();
        assertTrue(panel.expand.isEnabled());
        panel.expand.onPressed.emit();
        assertTrue(panel.tree.isExpanded("a"));
        assertTrue("a subtree is more than its first level", panel.tree.isExpanded("a1"));
        String expandAll = String.valueOf(Keymap.acceleratorFor(panel.tree, TreeViewCommands.EXPAND_ALL));
        assertEquals("Press " + expandAll + " to expand all nodes", panel.expand.tooltip().getDescription());
    }

    @Test
    public void aMenuButtonWearsTheGutterAndItsSecondPressClosesTheMenu() {
        assertTrue(panel.add.hasDropdownMark());
        assertFalse(panel.collapse.hasDropdownMark());

        panel.add.onPressed.emit();
        settle();
        Menu open = openMenu();
        assertNotNull("the dropdown never opened", open);
        assertEquals("Thing", open.getItems().get(0).getText());

        panel.add.onPressed.emit();
        settle();
        assertNull("a second press left it open", openMenu());
    }

    @Test
    public void aPressOnTheHeaderPutsTheKeysInTheViewsFocusTarget() {
        document.focus().clear();
        int[] at = centreOf(container.captionChrome());
        press(at[0], at[1]);
        release(at[0], at[1]);
        settle();
        assertSame("the header press did not activate the view", panel.tree, document.focus().focused());
    }

    @Test
    public void theOptionsMenuEndsWithViewMode() {
        UIElement options = null;
        for (UIElement child : panel.add.parentElement().parentElement().children()) {
            if (child.hasClass(ViewContainer.OPTIONS_CLASS)) options = child;
        }
        assertNotNull(options);
        ((ActionButton) options).onPressed.emit();
        settle();
        Menu open = openMenu();
        assertNotNull("⋮ opened nothing", open);
        MenuItem last = open.getItems().get(open.getItems().size() - 1);
        assertEquals("View Mode", last.getText());
        assertEquals("Dock Pinned, Float, Window", 3, last.getSubmenu().getItemCount());
    }

    private Menu openMenu() {
        for (UIElement node : document.composedSubtree()) {
            if (node instanceof Menu menu && menu.isOpen() && menu.getItemCount() > 0) return menu;
        }
        return null;
    }
}
