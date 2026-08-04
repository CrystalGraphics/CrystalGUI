package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Two crashes-or-freezes reported from the harness within a minute of each other, both mine, both from
 * the same afternoon's work.
 *
 * <h3>1. A popup could not be opened under a root that refuses children</h3>
 *
 * <p>{@code TopLayer.add} requires an element to be attached before it can be promoted, so a popover must
 * be parented first — and every call site reached for {@code window.ui.rootElement}. That is fine until
 * the root is a composite. {@code CrystalEditor} returns {@code acceptsPublicChildren() == false}, so
 * right-clicking the Project panel threw {@code UnsupportedOperationException} straight out of the
 * mouse-down dispatch. The command palette had the identical latent bug and would have thrown on
 * {@code Ctrl+Shift+P} in the same window.</p>
 *
 * <h3>2. A folder would not expand</h3>
 *
 * <p>Covered in {@link ProjectFileTreeTest} rather than here, because it needs a workspace.</p>
 */
public class ExplorerInteractionTest extends UiTestBase {

    /** A root shaped like {@code CrystalEditor}: refuses public children, holds one internal wrapper. */
    private static final class RefusingRoot extends UIElement {
        final UIElement content = new UIElement();

        RefusingRoot() {
            layout(l -> l.widthPercent(100f).heightPercent(100f).flexDirection(FlexDirection.COLUMN));
            addInternalChild(content);
        }

        @Override
        public boolean acceptsPublicChildren() {
            return false;
        }
    }

    /**
     * <b>A popup finds somewhere legal to live even when the root refuses it.</b>
     *
     * <p>Asserted through {@link Popover#hostFor} rather than by catching the exception, because what
     * matters is not that it stops throwing but <em>where</em> it lands: the nearest accepting ancestor,
     * so the menu inherits the cascade of the panel it was opened in and dies with it.</p>
     */
    @Test
    public void aPopupHostIsFoundWhenTheRootRefusesChildren() {
        RefusingRoot root = new RefusingRoot();
        // A composite that refuses children, like the widgets a row actually sits inside.
        UIElement leaf = new RefusingRoot();
        root.content.addChild(leaf);

        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        assertSame("the host should be the nearest ancestor that accepts children, not the root",
                root.content, Popover.hostFor(window, leaf));
    }

    /** With nothing to be near — a window-level popup like the palette — the root is still the answer. */
    @Test
    public void aWindowLevelPopupFallsBackToTheRoot() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(800, 600);

        assertSame(root, Popover.hostFor(window, null));
    }

    /**
     * <b>The whole path, through a real right-click.</b>
     *
     * <p>This is the one that would have caught it: the unit above tests the helper, and the helper is not
     * where the bug was — the bug was a call site that never asked. Driving the actual press is what makes
     * that difference visible.</p>
     */
    @Test
    public void rightClickingUnderARefusingRootOpensAMenuRatherThanThrowing() {
        RefusingRoot root = new RefusingRoot();
        UIElement panel = new UIElement().layout(l -> l.width(200).height(100));
        root.content.addChild(panel);

        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        CommandRegistry registry = new CommandRegistry();
        registry.register(com.crystalgui.core.command.Command.of("test.thing", "Thing"));
        ContextMenu.attach(panel, registry, element -> ContextMenu.builder().item("test.thing"));

        for (int i = 0; i < 3; i++) window.updateWithoutPainting();

        // Move first: the hit test runs against the pointer, and a press with no prior position has
        // nothing to resolve a target from.
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(50, 50, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();

        rightClickAt(window, 50, 50, 1L);
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();

        Menu opened = findMenu(root);
        assertNotNull("no menu was opened by the right-click", opened);
        assertTrue("the menu was built but never shown", opened.isOpen());

        // A SECOND press while the first menu is open. This threw out of Taffy:
        //   Index (is 2) should be < child_count (1)
        // -- promotion had reparented the first menu's Taffy node to the root, so the host's DOM child
        // count and its Taffy child count had drifted apart.
        rightClickAt(window, 60, 60, 2L);
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();

        assertEquals("a second right-click left the first menu in the tree", 1, countMenus(root));
    }

    private static void rightClickAt(UIWindow window, int x, int y, long serial) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, CgMouseCodes.RIGHT_BUTTON, true, 0f, serial));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private static int countMenus(UIElement from) {
        int count = from instanceof Menu ? 1 : 0;
        for (UIElement child : from.getChildren()) count += countMenus(child);
        return count;
    }

    private static Menu findMenu(UIElement from) {
        if (from instanceof Menu menu) return menu;
        for (UIElement child : from.getChildren()) {
            Menu found = findMenu(child);
            if (found != null) return found;
        }
        return null;
    }
}
