package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.style.CssComments;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * Switching a layer off keeps it in the value as a comment, and the value has to survive that — as a declaration the
 * engine still reads, and as something that can itself be switched off.
 */
public class CssValueSwitchesTest {

    /**
     * <b>A stack off at both ends is not itself off.</b> It begins and ends with a comment marker, which read as one
     * comment around the whole value: striking those outer markers stranded the two inside it, and what was written
     * back was not a transform at all.
     */
    @Test
    public void aStackOffAtEitherEndIsNotItselfOff() {
        String both = "/* translate(40px, 0px) */ scale(2) /* rotate(0deg) */";
        assertFalse("a value that merely begins and ends with a comment is not itself off", CssValues.isOff(both));

        List<String> ops = CssValues.functionStack(both);
        assertEquals("three ops, two of them off", 3, ops.size());
        assertEquals("scale(2)", ops.get(1));
        assertTrue(CssValues.isOff(ops.get(0)) && CssValues.isOff(ops.get(2)));
        assertEquals("and it writes back as it was read", both, CssValues.joinFunctionStack(ops));

        // AND THE ENGINE STILL READS IT: a comment is whitespace to a tokenizer, so what is left is scale(2).
        assertNotNull("the declaration is still a transform",
                StylePropertyRegistry.TRANSFORM.valueParser.parse(both).compute());
    }

    /**
     * <b>A value carrying a switched-off layer can itself be switched off.</b> CSS comments do not nest: the value's
     * own closed the one the declaration was wrapped in, leaving {@code *}{@code /} loose in the sheet — the engine
     * refused the lot and the eye on the row looked like it did nothing.
     */
    @Test
    public void aValueWithASwitchedOffLayerCanItselfBeSwitchedOff() {
        String value = "none /* translate(4px, -4px) */";
        String off = CssValues.switched(value, false);

        assertTrue("one comment, and it closes at the end", CssValues.isOff(off));
        assertEquals("nothing is left outside it", "", CssComments.strip(off).trim());
        assertEquals("and it comes back as it went in", value, CssValues.bodyOf(off));
    }

    /** The escape is its own inverse, whatever a value happens to carry. */
    @Test
    public void whatIsWrittenIntoACommentComesBackOutOfIt() {
        for (String value : List.of("scale(2)", "none /* a */ /* b */", "a */ b", "/* only */")) {
            assertEquals(value, CssValues.fromComment(CssValues.forComment(value)));
        }
    }
}
