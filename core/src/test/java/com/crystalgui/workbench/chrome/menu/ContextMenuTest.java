package com.crystalgui.workbench.chrome.menu;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.ContextMenu;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.crystalgui.widget.text.UIText;

/**
 * {@link ContextMenu} — a right-click menu built from commands rather than lambdas.
 *
 * <p>The point of the design is that a menu, the palette and the keyboard are three views of one list, so
 * what these assert is that the menu really is derived from the registry: labels, enablement and
 * accelerators all read out of it, and none of them separately maintained.</p>
 */
public class ContextMenuTest extends UiDocumentTestBase {

    private UINode root;
    private CommandRegistry registry;
    private final List<String> ran = new ArrayList<>();

    private boolean canDelete;

    @Before
    public void setUp() {
        ran.clear();
        canDelete = true;
        registry = new CommandRegistry();
        registry.register(Command.of("file.new", "New File").run(c -> ran.add("file.new")));
        registry.register(Command.of("file.rename", "Rename").run(c -> ran.add("file.rename")));
        registry.register(Command.of("file.delete", "Delete")
                .run(c -> ran.add("file.delete"))
                .enabledWhen(c -> canDelete));

        root = new UINode().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        root.keymap().bind("Mod+N", "file.new");
        root.keymap().bind("Delete", "file.delete");
    }

    private Menu build(ContextMenu spec) {
        return spec.build(registry, root);
    }

    private static List<String> labelsOf(Menu menu) {
        List<String> labels = new ArrayList<>();
        for (MenuItem item : menu.getItems()) labels.add(item.getText());
        return labels;
    }

    /** Labels come from the registry, so a command renamed in one place is renamed everywhere. */
    @Test
    public void itemsAreLabelledFromTheRegistry() {
        Menu menu = build(ContextMenu.builder().item("file.new").item("file.rename"));
        assertEquals(List.of("New File", "Rename"), labelsOf(menu));
    }

    /** An override is for menu wording that differs from the palette's, on one id. */
    @Test
    public void anOverrideWins() {
        Menu menu = build(ContextMenu.builder().item("file.new", "New…"));
        assertEquals(List.of("New…"), labelsOf(menu));
    }

    /**
     * <b>Unavailable items are dimmed, never dropped.</b>
     *
     * <p>The palette already cost this once — it copied VS Code's hide-disabled behaviour and listed 1 of
     * 9 commands. A menu that changes shape by availability is also one whose items are never twice in the
     * same place.</p>
     */
    @Test
    public void disabledCommandsAreShownDisabledRatherThanHidden() {
        canDelete = false;
        Menu menu = build(ContextMenu.builder().item("file.rename").item("file.delete"));

        assertEquals("the disabled item was dropped", 2, menu.getItemCount());
        assertTrue(menu.getItems().get(0).isEnabled());
        assertFalse("Delete is enabled while its command says otherwise",
                menu.getItems().get(1).isEnabled());
    }

    /** An unregistered id is a row too — an absent item and a never-listed item look identical. */
    @Test
    public void anUnregisteredCommandStillGetsADisabledRow() {
        Menu menu = build(ContextMenu.builder().item("file.nope"));
        assertEquals(1, menu.getItemCount());
        assertFalse(menu.getItems().get(0).isEnabled());
    }

    /** The keystroke is what a menu teaches, and it is read from the keymap rather than restated. */
    @Test
    public void acceleratorsComeFromTheKeymap() {
        Menu menu = build(ContextMenu.builder().item("file.new").item("file.rename"));

        assertNotNull(menu.getItems().get(0));
        // file.new is bound; file.rename is not, so only one of the two advertises a chord.
        assertTrue("the bound command shows no accelerator",
                hasAccelerator(menu.getItems().get(0)));
        assertFalse("an unbound command invented an accelerator",
                hasAccelerator(menu.getItems().get(1)));
    }

    private static boolean hasAccelerator(MenuItem item) {
        for (UINode child : item.children()) {
            if (child.hasClass(MenuItem.ACCELERATOR_CLASS)) return true;
        }
        // The accelerator is a post-icon, so it may be an internal child rather than a public one.
        return deepOrNull(item, "." + MenuItem.ACCELERATOR_CLASS) != null;
    }

    /** Activating a row runs the command through the registry. */
    @Test
    public void activatingAnItemRunsItsCommand() {
        Menu menu = build(ContextMenu.builder().item("file.rename"));
        menu.getItems().get(0).onPressed.emit();
        assertEquals(List.of("file.rename"), ran);
    }

    /** A disabled row does nothing, even if something manages to activate it. */
    @Test
    public void activatingADisabledItemDoesNothing() {
        canDelete = false;
        Menu menu = build(ContextMenu.builder().item("file.delete"));
        menu.getItems().get(0).onPressed.emit();
        assertEquals(List.of(), ran);
    }

    /**
     * Separators are dropped where they would be leading, trailing or doubled.
     *
     * <p>So a caller may end every group with one and never count. Without it, the common shape — build a
     * group, add a separator, build another group that turns out to be empty — leaves a rule against the
     * bottom edge of the menu.</p>
     */
    @Test
    public void strandedSeparatorsAreDropped() {
        Menu menu = build(ContextMenu.builder()
                .separator()
                .item("file.new")
                .separator()
                .separator()
                .item("file.rename")
                .separator());

        assertEquals("expected exactly one rule, between the two items",
                1, countSeparators(menu));
        assertEquals(List.of("New File", "Rename"), labelsOf(menu));
    }

    private static int countSeparators(Menu menu) {
        int count = 0;
        for (UINode child : menu.itemsContainer().children()) {
            if (!(child instanceof MenuItem)) count++;
        }
        return count;
    }

    @Test
    public void submenusAreBuiltFromTheSameRules() {
        canDelete = false;
        Menu menu = build(ContextMenu.builder()
                .submenu("New", sub -> sub.item("file.new"))
                .item("file.delete"));

        assertEquals(2, menu.getItemCount());
        MenuItem newItem = menu.getItems().get(0);
        assertTrue("the submenu row has no submenu attached", newItem.hasSubmenu());
        assertEquals(List.of("New File"), labelsOf(newItem.getSubmenu()));
        assertFalse("enablement did not reach the outer item", menu.getItems().get(1).isEnabled());
    }

    /**
     * <b>A submenu row's label starts where every other label starts.</b>
     *
     * <p>It did not: the rule reached for {@code justify-content: space-between}, which distributes ALL the
     * children — and a menu row has three, because every item carries a zero-width {@code __mark__}
     * pre-icon whether or not the menu is checkable. The mark took the left edge, the arrow took the right,
     * and the label was spaced into the middle, so {@code New} sat centred above a column of left-aligned
     * verbs.</p>
     *
     * <p>Asserted as "the same x as a plain row", not against a number: the padding is the sheet's to
     * choose, and what must hold is that owning a submenu changes nothing about where the text begins.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void aSubmenuRowsLabelIsNotCentred() {
        Menu menu = new Menu();
        MenuItem plain = menu.addItem("Rename");
        Menu sub = new Menu();
        sub.addItem("File");
        MenuItem parent = menu.addSubmenu("New", sub);
        document.addOverlay(menu, root);
        menu.showAt(0f, 0f, null);
        for (int i = 0; i < 8; i++) frame();

        float plainLabelX = labelXOf(plain);
        float submenuLabelX = labelXOf(parent);
        assertEquals("the submenu row's label is not aligned with the others -- it is centred in the row",
                plainLabelX, submenuLabelX, 0.5f);

        // And the arrow is still hard right, which is what the auto margin buys -- a fix that merely
        // un-centred the label by dropping the rule would leave the arrow tucked against the text.
        UINode arrow = deepOrNull(parent, "." + MenuItem.SUBMENU_ARROW_CLASS);
        assertNotNull("the submenu row has no arrow", arrow);
        float rowRight = parent.box().width();
        float arrowRight = arrow.box().x() + arrow.box().width();
        assertTrue("the arrow is not at the trailing edge -- it sits at " + arrowRight
                        + " in a row " + rowRight + " wide",
                arrowRight >= rowRight - 8f);
    }

    /** Where the row's text actually begins, in the row's own space. */
    private static float labelXOf(MenuItem item) {
        for (UINode child : item.children()) {
            if (child instanceof UIText text && !text.getText().isEmpty()
                    && !child.hasClass(MenuItem.ACCELERATOR_CLASS)) {
                return child.box().x();
            }
        }
        throw new AssertionError("no label found on " + item.getText());
    }

    // ── Contributions: the menu nobody writes ───────────────────────────────────────────────────

    /**
     * <b>A menu assembled from what commands declare, rather than from a literal list.</b>
     *
     * <p>Until this path existed {@link MenuId} had no production users at all: {@code Command.menu(...)}
     * recorded placements nothing read, and the explorer's menu stayed a thirteen-line builder that only
     * its author could add to. These pin the property that made the id worth having — a command declares
     * where it sits, and the menu is a query.</p>
     */
    private CommandRegistry contributed() {
        CommandRegistry local = new CommandRegistry();
        local.register(Command.of("c.paste", "Paste").menu(MENU, "2_clipboard", 20).run(c -> { }));
        local.register(Command.of("c.copy", "Copy").menu(MENU, "2_clipboard", 10).run(c -> { }));
        local.register(Command.of("c.rename", "Rename").menu(MENU, "3_modify", 10).run(c -> { }));
        return local;
    }

    private static final MenuId MENU = MenuId.of("test/contributions");
    private static final MenuId NESTED =
            MenuId.of("test/contributions/new").nestedIn(MENU, "New", "1_new", 0);

    @Test
    public void contributionsComeOutInGroupThenOrder() {
        Menu menu = ContextMenu.of(MENU).build(contributed(), root);
        assertEquals(List.of("Copy", "Paste", "Rename"), labelsOf(menu));
    }

    /** A group boundary is where a separator goes, so no contributor has to ask for one. */
    @Test
    public void aSeparatorFallsOutOfEachGroupBoundary() {
        Menu menu = ContextMenu.of(MENU).build(contributed(), root);
        // Two groups -> exactly one rule between them; leading and trailing ones are dropped by build().
        assertEquals(1, separatorCount(menu));
    }

    /**
     * <b>Disabled contributions are dimmed, not dropped.</b>
     *
     * <p>This class's rule rather than {@code CommandRegistry.menu}'s, which filters. A menu whose items
     * move depending on what happens to apply is a menu whose items are never in the same place twice —
     * the same reasoning the header records for the palette that listed 1 of 9.</p>
     */
    @Test
    public void aDisabledContributionIsShownDimmedRatherThanOmitted() {
        CommandRegistry local = contributed();
        local.register(Command.of("c.rename", "Rename").menu(MENU, "3_modify", 10)
                .enabledWhen(c -> false).run(c -> { }));

        Menu menu = ContextMenu.of(MENU).build(local, root);
        assertEquals(List.of("Copy", "Paste", "Rename"), labelsOf(menu));
        assertFalse(itemNamed(menu, "Rename").isEnabled());
    }

    /** A submenu is its own MenuId, so anything can contribute into it without touching the parent. */
    @Test
    public void aSubmenuIsItselfContributedInto() {
        CommandRegistry local = contributed();
        local.register(Command.of("c.newFile", "File…").menu(NESTED, "1", 10).run(c -> { }));

        Menu menu = ContextMenu.of(MENU).build(local, root);
        assertEquals(List.of("New", "Copy", "Paste", "Rename"), labelsOf(menu));
    }

    /**
     * A submenu nobody contributed to is dropped, because it would open onto nothing.
     *
     * <p>Distinct from one whose items are merely disabled, which still opens and shows them dimmed: an
     * empty submenu is a registration that never happened, a disabled one is an answer.</p>
     */
    @Test
    public void anUncontributedSubmenuIsDropped() {
        Menu menu = ContextMenu.of(MENU).build(contributed(), root);
        assertFalse("an empty New submenu should not be offered", labelsOf(menu).contains("New"));
    }

    /** The point of the whole thing: a stranger adds an item without the menu's owner knowing. */
    @Test
    public void aForeignCommandCanContributeWithoutTouchingTheMenu() {
        CommandRegistry local = contributed();
        local.register(Command.of("other.thing", "Something Else")
                .menu(MENU, "2_clipboard", 15).run(c -> { }));

        assertEquals(List.of("Copy", "Something Else", "Paste", "Rename"),
                labelsOf(ContextMenu.of(MENU).build(local, root)));
    }

    private static MenuItem itemNamed(Menu menu, String label) {
        for (MenuItem item : menu.getItems()) {
            if (label.equals(item.getText())) return item;
        }
        throw new AssertionError("no item labelled " + label + " in " + labelsOf(menu));
    }

    private static int separatorCount(Menu menu) {
        int found = 0;
        for (UINode child : menu.itemsContainer().children()) {
            if (!(child instanceof MenuItem)) found++;
        }
        return found;
    }
}
