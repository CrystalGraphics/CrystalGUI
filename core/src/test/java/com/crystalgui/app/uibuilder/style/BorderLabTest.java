package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;

/** The corners lab shows the rows its mode edits, and reads its radii back as the shorthand a person writes. */
public class CornersLabTest extends UiDocumentTestBase {

    private StyleFields open(UIElement node) {
        UIElement anchor = new UIElement().layout(l -> l.width(40).height(16));
        document.append(anchor);
        document.append(node);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();
        StyleFields fields = StyleFields.on(null, StyleTarget.inline(), node);
        CornersLab.open(anchor, fields);
        for (int i = 0; i < 4; i++) frame();
        return fields;
    }

    private Configurator row(String id) {
        for (UIElement each : document.topLayerNode().composedSubtree()) {
            if (each instanceof Configurator row && row.control() != null
                    && id.equals(row.control().descriptor().id())) {
                return row;
            }
        }
        return null;
    }

    private boolean shown(String id) {
        Configurator row = row(id);
        assertNotNull(id, row);
        return !Boolean.TRUE.equals(row.get(Attribute.HIDDEN));
    }

    @Test
    public void equalCornersOpenLinkedOnOneRadius() {
        open(new UIElement());
        assertTrue(shown("lab.radius"));
        assertFalse(shown("lab.corner.0"));
        assertFalse(shown("lab.ellipse.0"));
    }

    @Test
    public void theHandlesOpenInTheirCorners() {
        open(new UIElement());
        List<Float> xs = new ArrayList<>();
        for (UIElement each : document.topLayerNode().composedSubtree()) {
            if (each.hasClass(CornerBox.PUCK_CLASS) && each.box() != null) xs.add(each.box().x());
        }
        assertEquals("four handles", 4, xs.size());
        assertTrue("spread to the corners, not stacked in the middle: " + xs,
                xs.stream().distinct().count() > 1);
    }

    @Test
    public void differingCornersOpenOnePerCorner() {
        UIElement node = new UIElement();
        StyleFields fields = StyleFields.on(null, StyleTarget.inline(), node);
        fields.value("border-top-left-radius-x").set("8px");
        fields.value("border-top-left-radius-y").set("8px");
        open(node);
        assertFalse(shown("lab.radius"));
        assertTrue(shown("lab.corner.0"));
    }

    @Test
    public void theShorthandSaysWhatDiffers() {
        assertEquals("8px", CornersLab.shorthand(new double[] {8, 8, 8, 8, 8, 8, 8, 8}));
        assertEquals("8px 0px", CornersLab.shorthand(new double[] {8, 8, 0, 0, 8, 8, 0, 0}));
        assertEquals("8px / 4px", CornersLab.shorthand(new double[] {8, 4, 8, 4, 8, 4, 8, 4}));
    }

    @Test
    public void theShorthandExpandsAsCssReadsIt() {
        assertEquals("[1px, 1px, 2px, 2px, 3px, 3px, 2px, 2px]",
                Arrays.toString(StyleFields.radiusLonghands("1px 2px 3px")));
        assertEquals("[4px, 2px, 4px, 2px, 4px, 2px, 4px, 2px]",
                Arrays.toString(StyleFields.radiusLonghands("4px / 2px")));
    }

    @Test
    public void anElementsEightCornersAreOneRow() {
        UIElement node = new UIElement();
        document.append(node);
        StyleFields fields = StyleFields.on(null, StyleTarget.inline(), node);
        fields.value(StyleFields.BORDER_RADIUS).set("6px 2px");
        long rows = fields.declared().stream().filter(d -> d.name().startsWith("border")).count();
        assertEquals("one row for the corners", 1, rows);
        assertEquals("6px 2px", fields.valueOf(StyleFields.BORDER_RADIUS));
    }
}
