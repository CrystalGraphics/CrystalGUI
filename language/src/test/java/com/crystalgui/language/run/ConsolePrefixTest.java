package com.crystalgui.language.run;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
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
        assertEquals(0, ConsolePrefix.width(ConsolePrefix.Style.NONE));
    }

    /** {@code [22:59:20] } — zero-padded, because 9:5:3 does not align with anything. */
    @Test
    public void theTimeIsFixedWidthAndZeroPadded() {
        assertEquals("[22:59:20] ", ConsolePrefix.of(ConsolePrefix.Style.TIME, at(22, 59, 20), null));
        assertEquals("[09:05:03] ", ConsolePrefix.of(ConsolePrefix.Style.TIME, at(9, 5, 3), null));
        assertEquals("[00:00:00] ", ConsolePrefix.of(ConsolePrefix.Style.TIME, at(0, 0, 0), null));
    }

    /** The origin follows it, and the message column is the same on both lines. */
    @Test
    public void theOriginIsPaddedSoTheMessageColumnHolds() {
        String withOrigin = ConsolePrefix.of(ConsolePrefix.Style.FULL, at(22, 59, 20), "Ask.java:24");
        String without = ConsolePrefix.of(ConsolePrefix.Style.FULL, at(22, 59, 20), null);

        assertTrue("the origin is not in the stamp", withOrigin.contains("Ask.java:24"));
        assertEquals("a line with no origin does not line up with one that has it",
                withOrigin.length(), without.length());
        assertEquals(withOrigin.length(), ConsolePrefix.width(ConsolePrefix.Style.FULL));
        assertTrue("the stamp runs into the message", withOrigin.endsWith(" "));
    }

    /**
     * <b>A long origin pushes its own line out and leaves the rest aligned.</b>
     *
     * <p>The alternative is truncating, and there is nothing here to truncate: shortening
     * {@code VeryLongScriptName.java:1204} means dropping either the file or the line, and both halves
     * are the answer to "where did this come from". It would also want an ellipsis, which this codebase
     * has twice been bitten by assuming a font has.</p>
     */
    @Test
    public void aLongOriginOverflowsRatherThanBeingCut() {
        String stamp = ConsolePrefix.of(ConsolePrefix.Style.FULL, at(1, 2, 3),
                "VeryLongScriptName.java:1204");
        assertTrue("the origin was truncated", stamp.contains("VeryLongScriptName.java:1204"));
        assertTrue("and it should push past the column rather than fitting in it",
                stamp.length() > ConsolePrefix.width(ConsolePrefix.Style.FULL));
    }

    /** TIME stops at the time — the origin is the thing FULL adds. */
    @Test
    public void timeAloneCarriesNoOrigin() {
        String stamp = ConsolePrefix.of(ConsolePrefix.Style.TIME, at(22, 59, 20), "Ask.java:24");
        assertEquals("[22:59:20] ", stamp);
    }
}
