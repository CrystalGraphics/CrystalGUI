package com.crystalgui.ui;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ContextMenu} — a right-click menu built from commands rather than lambdas.
 *
 * <p>The point of the design is that a menu, the palette and the keyboard are three views of one list, so
 * what these assert is that the menu really is derived from the registry: labels, enablement and
 * accelerators all read out of it, and none of them separately maintained.</p>
 */
public class ContextMenuTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;
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

        root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
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
        for (UIElement child : item.getChildren()) {
            if (child.hasClass(MenuItem.ACCELERATOR_CLASS)) return true;
        }
        // The accelerator is a post-icon, so it may be an internal child rather than a public one.
        return item.querySelector("." + MenuItem.ACCELERATOR_CLASS) != null;
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
        for (UIElement child : menu.itemsContainer().getChildren()) {
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
}
