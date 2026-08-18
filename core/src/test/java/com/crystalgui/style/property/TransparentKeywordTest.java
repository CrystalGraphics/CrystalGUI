package com.crystalgui.style.property;

import com.crystalgui.style.property.visual.color.ColorValue;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * {@code transparent} is a colour, not the absence of one.
 *
 * <p>CSS Color 4 defines the keyword as exactly {@code rgba(0, 0, 0, 0)}. Parsing it to {@code null}
 * instead is not a near-miss: a null makes {@code StyleValue} log a warning and <b>drop the
 * declaration</b>, so the property falls back through the cascade and the surface keeps whatever
 * background it was written to clear. Twelve declarations in the user-agent sheet were doing exactly
 * that, one warning each, on every client that loaded the default sheet.</p>
 */
public class TransparentKeywordTest {

    @Test
    public void transparentIsZeroAlphaBlack() {
        Integer parsed = ColorValue.parseColor("transparent");
        assertNotNull("`transparent` failed to parse — the declaration would be dropped", parsed);
        assertEquals("CSS Color 4 defines it as rgba(0,0,0,0)", 0x00000000, parsed.intValue());
    }

    /** Whitespace and case are the parser's job, not the stylesheet author's. */
    @Test
    public void transparentIsCaseAndWhitespaceInsensitive() {
        assertEquals(Integer.valueOf(0), ColorValue.parseColor("  TRANSPARENT "));
    }

    /**
     * The keyword must not shadow ordinary parsing.
     *
     * <p>Guards the shape of the fix rather than the value: an early return placed carelessly — before
     * the {@code #} branch, or matching on {@code startsWith} — would swallow real colours.</p>
     */
    @Test
    public void ordinaryColoursStillParse() {
        assertEquals(0xFF102030, ColorValue.parseColor("#102030").intValue());
        assertEquals(0xFFFFFFFF, ColorValue.parseColor("#fff").intValue());
        assertEquals(0x80FF0000, ColorValue.parseColor("#FF000080").intValue());
        assertEquals(0xFF0A141E, ColorValue.parseColor("rgb(10, 20, 30)").intValue());
        assertEquals(0x00000000, ColorValue.parseColor("rgba(0, 0, 0, 0)").intValue());
    }

    /** An unknown keyword still degrades rather than throwing. */
    @Test
    public void anUnknownKeywordIsStillNull() {
        org.junit.Assert.assertNull(ColorValue.parseColor("chartreuse"));
    }
}
