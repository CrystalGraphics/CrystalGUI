package com.crystalgui.app.uibuilder.inspect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;

/**
 * <b>A length is a number and a unit</b> — the commonest value in CSS, and the one the panel had no gizmo for at
 * all. The units are the property's own, asked of its parser rather than listed here.
 */
public class LengthFieldTest {

    @Test
    public void theUnitsAreThePropertysOwn() {
        assertEquals("a size takes all three and auto",
                List.of("px", "%", "em", "auto"), LengthField.unitsOf(LayoutProperties.WIDTH));
        assertEquals("an inset the same", List.of("px", "%", "em", "auto"), LengthField.unitsOf(LayoutProperties.LEFT));
        assertEquals("an outline's width is a length-percent, and takes neither em nor auto",
                List.of("px", "%"), LengthField.unitsOf(StylePropertyRegistry.OUTLINE_WIDTH));

        assertTrue("a bare number is not a length", LengthField.unitsOf(StylePropertyRegistry.OPACITY).isEmpty());
        assertTrue("nor is a PAIR of them: gap reads 12px and writes `12px 12px`",
                LengthField.unitsOf(LayoutProperties.GAP).isEmpty());
        assertFalse("though one axis of it is", LengthField.unitsOf(LayoutProperties.ROW_GAP).isEmpty());
        assertTrue("nor is a colour", LengthField.unitsOf(StylePropertyRegistry.COLOR).isEmpty());
        assertTrue("nor a keyword", LengthField.unitsOf(StylePropertyRegistry.OVERFLOW).isEmpty());
    }

    @Test
    public void theUnitIsPressedRatherThanSpelled() {
        Property<String> css = Property.of("12px");
        LengthField field = new LengthField("t.width", LengthField.unitsOf(LayoutProperties.WIDTH));
        field.bind(css);

        field.cycle();
        assertEquals("the number is kept, since 12px and 12% are not the same length", "12%", css.get());
        field.cycle();
        assertEquals("12em", css.get());
        field.cycle();
        assertEquals("a keyword carries no number", "auto", css.get());
        field.cycle();
        assertEquals("and round again, with the number it was left with", "12px", css.get());
    }

    /**
     * <b>A unit change keeps the LENGTH.</b> Switching a 593px inset to a percentage kept the number, so the
     * element went five screens down and the control looked broken — 593px of a 1080px parent is 54.9% of it.
     */
    @Test
    public void aUnitChangeConvertsThroughWhatItCanMeasure() {
        Property<String> css = Property.of("300px");
        LengthField field = new LengthField("t.top", LengthField.unitsOf(LayoutProperties.TOP))
                .against(() -> 1000d, () -> 10d);
        field.bind(css);

        field.cycle();
        assertEquals("a third of the parent", "30%", css.get());
        field.cycle();
        assertEquals("and thirty ems of a ten-pixel type", "30em", css.get());
        field.cycle();
        assertEquals(LengthField.AUTO, css.get());
        field.cycle();
        assertEquals("back to where it started, not to a number it happened to carry", "300px", css.get());
    }

    /** Nothing to measure against is not an excuse to invent a base: the number is kept, as it always was. */
    @Test
    public void whatCannotBeMeasuredKeepsItsNumber() {
        Property<String> css = Property.of("12px");
        LengthField field = new LengthField("t.width", LengthField.unitsOf(LayoutProperties.WIDTH));
        field.bind(css);

        field.cycle();
        assertEquals("12%", css.get());
    }

    @Test
    public void aValueWrittenElsewhereIsRead() {
        Property<String> css = Property.of("auto");
        LengthField field = new LengthField("t.height", LengthField.unitsOf(LayoutProperties.HEIGHT));
        field.bind(css);

        css.set("50%");
        field.cycle();
        assertEquals("with nothing to measure against, the number is kept", "50em", css.get());
    }
}
