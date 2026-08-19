package com.crystalgui.language.run;

import com.crystalgui.language.run.console.ConsolePrefix;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConsolePrefix} — the stamp in front of a console line.
 *
 * <p>Times are built from a {@code LocalDateTime} in the running machine's own zone rather than from a
 * fixed epoch value, because the formatter reads the reader's zone: a test asserting on a hard-coded
 * millisecond count passes in London and fails everywhere else, which is the kind of failure that gets
 * blamed on the machine rather than on the test.</p>
 */
public class ConsolePrefixTest {

    private static long at(int hour, int minute, int second) {
        return LocalDateTime.now()
                .withHour(hour).withMinute(minute).withSecond(second).withNano(0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** NONE is a real option, not a courtesy: it is what IntelliJ's console does. */
    @Test
    public void nothingIsStampedWhenNothingWasAskedFor() {
        assertEquals("", ConsolePrefix.of(ConsolePrefix.Style.NONE, at(22, 59, 20), "Ask.java:24"));
        assertEquals("", ConsolePrefix.of(null, at(22, 59, 20), "Ask.java:24"));
    }

    /** {@code [22:59:20] } — zero-padded, because 9:5:3 does not align with anything. */
    @Test
    public void theTimeIsFixedWidthAndZeroPadded() {
        assertEquals("[22:59:20] ", ConsolePrefix.of(ConsolePrefix.Style.TIME, at(22, 59, 20), null));
        assertEquals("[09:05:03] ", ConsolePrefix.of(ConsolePrefix.Style.TIME, at(9, 5, 3), null));
        assertEquals("[00:00:00] ", ConsolePrefix.of(ConsolePrefix.Style.TIME, at(0, 0, 0), null));
    }

    /** Each part bracketed, one space between them, and one space before the message. */
    @Test
    public void theStampIsItsPartsAndSingleSpaces() {
        assertEquals("[22:59:20] [Ask.java:24] ",
                ConsolePrefix.of(ConsolePrefix.Style.FULL, at(22, 59, 20), "Ask.java:24"));
    }

    /**
     * <b>A line with no origin is not indented past one.</b>
     *
     * <p>The first version reserved a fixed column and padded into it, on the usual logging-framework
     * argument about the eye following the left edge of the text. It bought alignment that was already
     * there — almost every line has an origin, so the column was near-constant anyway — and it charged
     * the rare line without one sixteen columns of whitespace, which reads as an empty field rather than
     * as an absent one.</p>
     */
    @Test
    public void aLineWithNoOriginIsNotIndented() {
        String stamp = ConsolePrefix.of(ConsolePrefix.Style.FULL, at(22, 59, 20), null);
        assertEquals("[22:59:20] ", stamp);
        assertFalse("an absent origin should leave no empty brackets", stamp.contains("[]"));
    }

    /**
     * <b>Nothing is truncated.</b>
     *
     * <p>Shortening {@code VeryLongScriptName.java:1204} means dropping either the file or the line, and
     * both halves are the answer to "where did this come from". It would also want an ellipsis, which
     * this codebase has twice been bitten by assuming a font has.</p>
     */
    @Test
    public void aLongOriginIsNotCut() {
        assertEquals("[01:02:03] [VeryLongScriptName.java:1204] ",
                ConsolePrefix.of(ConsolePrefix.Style.FULL, at(1, 2, 3), "VeryLongScriptName.java:1204"));
    }

    /** TIME stops at the time — the origin is the thing FULL adds. */
    @Test
    public void timeAloneCarriesNoOrigin() {
        String stamp = ConsolePrefix.of(ConsolePrefix.Style.TIME, at(22, 59, 20), "Ask.java:24");
        assertEquals("[22:59:20] ", stamp);
    }
}
