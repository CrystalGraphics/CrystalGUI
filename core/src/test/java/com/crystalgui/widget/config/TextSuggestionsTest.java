package com.crystalgui.widget.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.config.control.TextControl;

/**
 * <b>A field's suggestion list opens with the field and goes with the first press away from it.</b>
 *
 * <p>Closing the list handed focus back to the field it was opened from, whose focus reopened the list, so the
 * first press away did nothing and the field went on showing a caret it no longer typed into.</p>
 */
public class TextSuggestionsTest extends UiDocumentTestBase {

    @Test
    public void theFirstPressAwayClosesTheListAndTheField() {
        UIElementRegistry.bootstrap();
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        TextControl control = new TextControl(
                ConfigDescriptor.text("f", "F").suggestions(() -> List.of("Alpha", "Beta", "Gamma")), "Alpha");
        control.layout(l -> l.width(200));
        root.append(control);
        document.append(root);
        frame();

        int[] at = centreOf(control.field());
        click(at[0], at[1]);
        frame();
        assertTrue("focusing the field lists the suggestions", control.suggestionList().isOpen());

        click(350, 350);
        frame();
        assertFalse("one press away closes the list", control.suggestionList().isOpen());
        assertFalse("and leaves the field", control.field().isFocused());
    }
}
