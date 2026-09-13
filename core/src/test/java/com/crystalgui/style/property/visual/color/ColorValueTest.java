package com.crystalgui.style.property.visual.color;

import com.crystalgui.render.texture.svg.SvgColor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The one CSS {@code <color>} parser: every style property and the SVG renderer read colours through it,
 * so a form it misses is missing everywhere at once.
 */
public class ColorValueTest {

    @Test
    public void everyNamedColourParsesCaseInsensitively() {
        assertEquals(0xFFFFD700, ColorValue.parseColor("gold").intValue());
        assertEquals(0xFF663399, ColorValue.parseColor("RebeccaPurple").intValue());
        assertEquals(0xFF7FFF00, ColorValue.parseColor("chartreuse").intValue());
        assertNull(ColorValue.parseColor("notacolour"));
    }

    @Test
    public void hexTakesAllFourLengthsAndRefusesBadDigits() {
        assertEquals(0x8844CCFF, ColorValue.parseColor("#4cf8").intValue());
        assertEquals(0xFF44CCFF, ColorValue.parseColor("#4cf").intValue());
        assertNull(ColorValue.parseColor("#ggg"));
        assertNull(ColorValue.parseColor("#12345"));
    }

    @Test
    public void functionalFormsTakeSpacesSlashesPercentagesAndClamp() {
        assertEquals(0x80FF0000, ColorValue.parseColor("rgb(255 0 0 / 50%)").intValue());
        assertEquals(0x80FF0000, ColorValue.parseColor("rgba(100%, 0%, 0%, 0.5)").intValue());
        assertEquals(0xFFFF0000, ColorValue.parseColor("rgb(300, -4, 0)").intValue());
        assertNull(ColorValue.parseColor("rgb(1, 2)"));
    }

    /** A shadow's {@code 0 0 8px red} starts with a length; read as a colour it fails the whole list. */
    @Test
    public void anIntegerIsAnArgbLiteralToAPropertyAndNotACssColour() {
        assertEquals(-1, ColorValue.parseColor("-1").intValue());
        assertNull(ColorValue.parseCssColor("0"));
        assertNull(SvgColor.parseColor("-1"));
        assertEquals(0xFFFFD700, SvgColor.parseColor("gold").intValue());
    }
}
