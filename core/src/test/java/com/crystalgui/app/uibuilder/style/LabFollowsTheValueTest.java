package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.control.SliderControl;

/**
 * <b>A lab shows the declaration as it is, whoever changed it.</b>
 *
 * <p>An undo, an edit in the sheet's text or a row elsewhere changes the value under an open lab. Each lab
 * used to parse the value once into fields of its own, so it went on showing the old one — and its next drag
 * wrote the old one back.</p>
 */
public class LabFollowsTheValueTest extends UiDocumentTestBase {

    @Test
    public void aShadowLabFollowsAnEditItDidNotMake() {
        Property<String> css = Property.of("0 1px 2px #000000");
        UIElement anchor = new UIElement().layout(l -> l.width(40).height(16));
        document.append(anchor);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();

        ShadowLab.open(anchor, StylePropertyRegistry.TEXT_SHADOW, css);
        for (int i = 0; i < 4; i++) frame();
        SliderControl blur = blurRow();
        assertNotNull("the lab has a blur row", blur);
        assertEquals("2.00px", blur.number().field().getText());

        css.set("0 1px 9px #000000");   // what an undo does
        frame();

        assertEquals("the row SHOWS the value as it is now", "9.00px", blur.number().field().getText());
    }

    private SliderControl blurRow() {
        for (UIElement each : document.composedSubtree()) {
            if (each instanceof SliderControl slider && "lab.blur".equals(slider.descriptor().id())) return slider;
        }
        return null;
    }
}
