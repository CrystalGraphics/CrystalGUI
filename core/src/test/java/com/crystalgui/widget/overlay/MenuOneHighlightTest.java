package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;

/**
 * <b>Exactly one row of a menu is highlighted at a time.</b>
 *
 * <p>There is one active row and the sheet draws it from {@code :focus} and {@code :hover} together —
 * they are one state arriving two ways. That only holds while the two cannot disagree, and this is the
 * case where they did.</p>
 */
public class MenuOneHighlightTest extends UiDocumentTestBase {

    private static final ReadOnlyVec2f ORIGIN = new ReadOnlyVec2f(new org.joml.Vector2f());

    /**
     * <b>Opening a submenu and then hovering an ordinary row leaves only the hovered one lit.</b>
     *
     * <p>A Popover hands focus back to whatever opened it, so closing the submenu refocuses the row it
     * came from. While focus was claimed BEFORE that close, the restore landed last and put focus back on
     * the parent: the pointer's row kept {@code :hover}, the parent regained {@code :focus}, and the menu
     * showed two highlighted rows.</p>
     */
    @Test
    public void openingASubmenuThenHoveringAnotherRowLeavesOneHighlight() {
        withDefaultStyles();
        UIElement root = sized("root", 400, 300);
        document.append(root);

        Menu menu = new Menu();
        Menu child = new Menu();
        child.addItem("Shader Graph");
        MenuItem parent = menu.addSubmenu("New", child);
        MenuItem plain = menu.addItem("Go to File...");
        document.addOverlay(menu, root);
        menu.showAt(10f, 10f, null);
        for (int i = 0; i < 4; i++) frame();

        // THE REAL ROUTE: hovering a row with a submenu schedules it, exactly as a sweep does.
        document.input().send(parent, new MouseEvent.Enter(parent, ORIGIN));
        for (int i = 0; i < 240 && !child.isOpen(); i++) frame();
        assertTrue("the submenu is up", child.isOpen());

        document.input().send(plain, new MouseEvent.Enter(plain, ORIGIN));
        for (int i = 0; i < 4; i++) frame();

        assertFalse("the submenu closed behind the pointer", child.isOpen());
        assertTrue("the hovered row is the focused one", plain.isFocused());
        assertFalse("and the row that opened the submenu is not", parent.isFocused());
    }
}
