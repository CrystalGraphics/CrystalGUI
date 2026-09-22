package com.crystalgui.widget.overlay;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A dialog's answers: which one holds focus when it opens, and Left/Right between them.
 */
public class DialogButtonRowTest extends UiDocumentTestBase {

    private UIElement from;

    @Before
    public void build() {
        from = new UIElement().layout(l -> l.width(400).height(300));
        document.append(from);
        frame();
        document.input().beginFrame();
        document.input().endFrame();
    }

    private void press(int key) {
        document.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event((char) 0, key, true, false, 3L));
        frame();
    }

    private String focusedLabel() {
        assertTrue("a button holds focus", document.focus().focused() instanceof Button);
        return ((Button) document.focus().focused()).getText();
    }

    /** IntelliJ's arrangement: the dialog is the deliberate step, so Enter answers what was asked. */
    @Test
    public void aConfirmationOpensOnItsAction() {
        InputDialog.confirm(from, "Delete", "Delete 'a.java'?", "Delete", () -> { });
        frame();
        assertEquals("Delete", focusedLabel());
    }

    @Test
    public void theArrowsMoveBetweenTheAnswersAndWrap() {
        InputDialog.confirm(from, "Delete", "Delete 'a.java'?", "Delete", () -> { });
        frame();
        press(CgKeyCodes.KEY_RIGHT);
        assertEquals("Cancel", focusedLabel());
        press(CgKeyCodes.KEY_RIGHT);
        assertEquals("wraps past the last answer", "Delete", focusedLabel());
        press(CgKeyCodes.KEY_LEFT);
        assertEquals("wraps past the first", "Cancel", focusedLabel());
    }
}
