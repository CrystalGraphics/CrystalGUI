package com.crystalgui.widget.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>Only a button with text wears the workbench's outline.</b>
 *
 * <p>An icon-only button is chrome — a panel's hide mark, an activity bar's stripe, a tab's close — and
 * the outline meant for a labelled control put an empty box around each of them. A sheet cannot ask
 * whether a label is empty, so the button says it.</p>
 */
public class ButtonLabelledClassTest {

    @Test
    public void anIconOnlyButtonIsNotLabelled() {
        assertFalse("a button built with no text is chrome, not a control",
                new Button("").hasClass(Button.LABELLED_CLASS));
        assertTrue("a button with a label is a control", new Button("Preview")
                .hasClass(Button.LABELLED_CLASS));
    }

    /** It follows the text, since a caller may set or clear it later. */
    @Test
    public void theClassFollowsTheText() {
        Button button = new Button("");
        button.setText("Save");
        assertTrue("gaining a label makes it a control", button.hasClass(Button.LABELLED_CLASS));

        button.setText("");
        assertFalse("and losing one gives the outline back", button.hasClass(Button.LABELLED_CLASS));
    }
}
