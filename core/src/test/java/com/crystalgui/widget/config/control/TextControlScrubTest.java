package com.crystalgui.widget.config.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/** A text field's label scrubs the number it holds and keeps the unit; a keyword it cannot move stays as it is. */
public class TextControlScrubTest extends UiDocumentTestBase {

    private Property<String> mount(String value) {
        Property<String> held = Property.of(value);
        TextControl control = new TextControl(ConfigDescriptor.text("w", "width"), value);
        control.bind(held);
        UIText label = new UIText("width");
        label.layout(l -> l.width(40).height(20));
        control.adoptLabel(label);
        UIElement root = new UIElement().layout(l -> l.width(400).height(100));
        root.append(label);
        root.append(control);
        document.append(root);
        frame();

        int[] at = centreOf(label);
        press(at[0], at[1]);
        frame();
        for (int dx = 10; dx <= 60; dx += 10) {
            move(at[0] + dx, at[1]);
            frame();
        }
        release(at[0] + 60, at[1]);
        frame();
        return held;
    }

    @Test
    public void aLengthScrubsAndKeepsItsUnit() {
        Property<String> width = mount("72px");
        assertNotEquals("the drag moved it", "72px", width.get());
        assertTrue("and it is still pixels: " + width.get(), width.get().endsWith("px"));
    }

    @Test
    public void aKeywordDoesNotScrub() {
        assertEquals("auto", mount("auto").get());
    }
}
