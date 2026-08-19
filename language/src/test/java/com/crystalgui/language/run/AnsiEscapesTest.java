package com.crystalgui.language.run;

import com.crystalgui.language.run.console.AnsiEscapes;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * {@link AnsiEscapes} — what a console shows when the thing printing thinks it is a terminal.
 *
 * <p>Nothing in the engine emits these and plenty of ordinary Java does: a logging framework with colour
 * on, a progress bar, anything ported from a command-line tool. The console showed them literally, which
 * reads as corrupted output rather than as a console that does not speak ANSI.</p>
 */
public class AnsiEscapesTest {

    /**
     * Built from code points rather than written as literals.
     *
     * <p>A raw {@code 0x1B} in a source file is invisible in every editor and in every diff, so a test
     * asserting on one cannot be read — and {@code ""} is worse, because Java translates unicode
     * escapes before the lexer sees them and the rule for which ones are legal inside a literal is a
     * trap nobody should have to remember.</p>
     */
    private static final String ESC = String.valueOf((char) 0x1B);
    private static final String BEL = String.valueOf((char) 0x07);

    /** The common case must not allocate — this runs on every line a script prints. */
    @Test
    public void ordinaryTextIsReturnedUntouched() {
        String plain = "nothing to see here";
        assertSame("a line with no escapes was copied for no reason", plain, AnsiEscapes.strip(plain));
        assertSame("", AnsiEscapes.strip(""));
        assertNull(AnsiEscapes.strip(null));
    }

    /** Colour is the reason anybody notices this, and it is a CSI sequence like any other. */
    @Test
    public void colourCodesAreRemovedAndTheWordsSurvive() {
        assertEquals("ERROR something failed",
                AnsiEscapes.strip(ESC + "[31mERROR" + ESC + "[0m something failed"));
        assertEquals("bold and bright",
                AnsiEscapes.strip(ESC + "[1;38;5;208mbold and bright" + ESC + "[m"));
    }

    /**
     * <b>Not just colour.</b>
     *
     * <p>A rule matching only {@code ESC[…m} would leave cursor moves and erases behind — and those are
     * exactly what a progress bar emits, so the one case most likely to flood a console with escapes is
     * the case a colour-only rule would miss entirely.</p>
     */
    @Test
    public void cursorAndEraseCodesAreRemovedToo() {
        assertEquals("done", AnsiEscapes.strip(ESC + "[2K" + ESC + "[1Gdone"));
        assertEquals("start", AnsiEscapes.strip(ESC + "[?25lstart" + ESC + "[?25h"));
    }

    /** A window title is set with OSC, ends with BEL, and would otherwise dump a path into the row. */
    @Test
    public void anOscSequenceIsRemovedUpToItsTerminator() {
        assertEquals("building",
                AnsiEscapes.strip(ESC + "]0;/home/me/project" + BEL + "building"));
        assertEquals("building",
                AnsiEscapes.strip(ESC + "]0;/home/me/project" + ESC + "\\building"));
    }

    /**
     * <b>An unterminated sequence takes the rest of the line with it.</b>
     *
     * <p>Which happens for real: the partial-line cap cuts at 64KB wherever it lands, and a line can end
     * mid-escape. Half a sequence is not text anybody meant to show, and leaving the fragment would put
     * it on screen at precisely the moment the output was already being mangled.</p>
     */
    @Test
    public void aHalfWrittenSequenceIsNotShownAsText() {
        assertEquals("cut here ", AnsiEscapes.strip("cut here " + ESC + "[38;5;"));
        assertEquals("cut here ", AnsiEscapes.strip("cut here " + ESC));
        assertEquals("cut here ", AnsiEscapes.strip("cut here " + ESC + "]0;unterminated"));
    }

    /** Several sequences in one line, with text between them, all go and all the text stays. */
    @Test
    public void everySequenceInALineIsRemoved() {
        assertEquals("red green blue", AnsiEscapes.strip(
                ESC + "[31mred " + ESC + "[32mgreen " + ESC + "[34mblue" + ESC + "[0m"));
    }
}
