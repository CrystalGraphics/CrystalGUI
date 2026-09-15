package com.crystalgui.widget.config.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;

/** <b>A list's + adds a row without writing a blank value</b>, and the row is written once it is typed into. */
public class ArrayControlTest extends UiDocumentTestBase {

    @Test
    public void addingARowWaitsForItsValueBeforeCommitting() {
        Property<List<Object>> bound = Property.of(List.of());
        ArrayControl list = new ArrayControl(ConfigDescriptor.of("sizes", "Sizes", ConfigDescriptor.Kind.ARRAY), List.of());
        list.bind(bound);
        document.append(list);
        frame();

        pressAdd(list);
        assertEquals("the row is there", 1, list.size());
        assertEquals("and nothing blank was written", List.of(), bound.get());

        TextControl entry = null;
        for (UIElement each : list.composedSubtree()) {
            if (each instanceof TextControl text) entry = text;
        }
        assertNotNull(entry);
        entry.field().setText("800x480");
        entry.field().commit();
        assertEquals(List.of("800x480"), bound.get());
    }

    private static void pressAdd(ArrayControl list) {
        for (UIElement each : list.composedSubtree()) {
            if (each instanceof Button button && button.hasClass("__add__")) {
                button.onPressed.emit();
                return;
            }
        }
        throw new AssertionError("no + button");
    }
}
