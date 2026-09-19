package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/** <b>A menu taller than the window scrolls</b> inside it, and one that fits is left as it was. */
public class MenuScrollsTest extends UiDocumentTestBase {

    private Menu open(int rows) {
        withDefaultStyles();
        UIElement root = sized("root", W, H);
        document.append(root);
        Menu menu = new Menu();
        for (int i = 0; i < rows; i++) menu.addItem("Group number " + i);
        document.addOverlay(menu, root);
        menu.showAt(10f, 10f, null);
        for (int i = 0; i < 8; i++) frame();
        return menu;
    }

    @Test
    public void aLongMenuStaysInTheWindowAndScrollsToTheKeyboard() {
        Menu menu = open(200);
        Box box = menu.box();
        assertTrue("a 200-row menu is " + box.height() + "px tall in a " + H + "px window", box.height() <= H);

        keyPress(CgKeyCodes.KEY_UP);   // off the top: the last row
        for (int i = 0; i < 8; i++) frame();
        MenuItem last = menu.getItems().get(199);
        assertTrue("Up did not reach the last row", last.isFocused());
        float bottom = Box.originIn(last.box(), box).y + last.box().height();
        assertTrue("the focused last row is scrolled out of sight, at " + bottom, bottom <= box.height() + 0.5f);
    }

    @Test
    public void aShortMenuIsAsTallAsItsRows() {
        Menu menu = open(3);
        MenuItem first = menu.getItems().get(0);
        MenuItem last = menu.getItems().get(2);
        float rows = Box.originIn(last.box(), menu.box()).y + last.box().height()
                - Box.originIn(first.box(), menu.box()).y;
        assertTrue("a three-row menu was squashed or padded: " + menu.box().height(),
                menu.box().height() >= rows && menu.box().height() < rows + 20f);
        for (MenuItem item : menu.getItems()) {
            for (UIElement part : item.composedSubtree()) {
                if (!(part instanceof UIText label) || label.getText().isEmpty()) continue;
                float right = Box.originIn(label.box(), item.box()).x + label.box().width();
                assertTrue(label.getText() + " runs past its row: " + right + " > " + item.box().width(),
                        right <= item.box().width() + 0.5f);
            }
        }
    }
}
