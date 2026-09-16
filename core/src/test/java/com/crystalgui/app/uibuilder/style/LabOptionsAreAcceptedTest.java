package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * <b>A lab may only offer values the property accepts.</b>
 *
 * <p>Every control in a lab writes CSS text, and a write the property's own parser refuses is silently
 * dropped: the dropdown snaps back, the slider returns to where it was, and nothing anywhere says why. That
 * is what {@code font-size: 34px} did — {@code font-size} is a plain float here and its parser refuses a
 * unit — so this asks the parsers directly rather than trusting the lists.</p>
 */
public class LabOptionsAreAcceptedTest {

    @Test
    public void everyKeywordTheTypeLabOffersParses() {
        accepts(StylePropertyRegistry.FONT_WEIGHT, TypographyLab.WEIGHTS);
        accepts(StylePropertyRegistry.FONT_STYLE, TypographyLab.STYLES);
        accepts(StylePropertyRegistry.PAINT_ORDER, TypographyLab.ORDERS);
        accepts(StylePropertyRegistry.FONT_FAMILY, TypographyLab.FACES);
    }

    /** A number written for a property is written the way that property spells one. */
    @Test
    public void everyNumberALabWritesParses() {
        accepts(StylePropertyRegistry.FONT_SIZE,
                List.of(CssValues.length(StylePropertyRegistry.FONT_SIZE, 34)));
        accepts(StylePropertyRegistry.TEXT_STROKE_WIDTH,
                List.of(CssValues.length(StylePropertyRegistry.TEXT_STROKE_WIDTH, 3)));
        accepts(StylePropertyRegistry.OPACITY, List.of(CssValues.length(StylePropertyRegistry.OPACITY, 0.5)));
    }

    private static void accepts(StyleProperty<?> property, List<String> values) {
        for (String value : values) {
            assertTrue(property.name + " refuses the lab's own value: " + value,
                    DeclarationEditors.parses(property, value));
        }
    }
}
