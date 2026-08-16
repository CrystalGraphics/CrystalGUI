package com.crystalgui.language.run;

import javax.annotation.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The stamp in front of a line — {@code [22:59:20] Ask.java:24  what the script said}.
 *
 * <h3>A console that decorates output is not showing what the program printed</h3>
 *
 * <p>Worth saying first, because it is the argument against doing this at all: IntelliJ's Run console
 * shows the process's bytes and nothing else, and so does VS Code's output panel. Adding a prefix means
 * copying a line gives you text the script never emitted, and a script printing its own aligned table
 * has that table pushed sideways.</p>
 *
 * <p>It earns its place here because these scripts are <b>event-driven</b>. A handler firing on a tick
 * prints the same sentence at 10:14 and at 10:47, and without a clock the transcript cannot say whether
 * anything is still happening — which is the first question anybody asks of a live script. The origin
 * answers the second: <em>which</em> of the four places that print this reached me. Both are already
 * known — {@code ScriptOutput} walks the stack for every line — and were being thrown away.</p>
 *
 * <p>So it is a setting, and {@link Style#NONE} is a real option rather than a courtesy. Somebody
 * reading a script's own formatted output wants the bytes.</p>
 *
 * <h3>Padded, never truncated</h3>
 *
 * <p>A jittering message column is worse than a wide one — the eye follows the left edge of the text, and
 * a prefix that changes width moves it on every line. Every logging framework pads for the same reason.
 * But none of them can truncate <em>here</em>: shortening {@code VeryLongScript.java:1204} means dropping
 * either the file or the line, and both halves are the answer to "where did this come from". A name too
 * long for the column pushes its own line out and leaves every other line aligned, which is the failure
 * that costs least — and it needs no ellipsis glyph, which this codebase has been bitten by twice.</p>
 */
public final class ConsolePrefix {

    private ConsolePrefix() {
    }

    /** What a line is stamped with. */
    public enum Style {
        /** The bytes the script printed, and nothing else — IntelliJ's own behaviour. */
        NONE,
        /** {@code [22:59:20] } — is this still happening, and when did it happen. */
        TIME,
        /** {@code [22:59:20] Ask.java:24  } — and which line said it. */
        FULL
    }

    /** {@code [22:59:20] } — eleven columns, fixed. */
    private static final int TIME_WIDTH = 11;

    /**
     * Columns reserved for {@code Ask.java:24}, including the gap after it.
     *
     * <p>Wide enough for the ordinary case — a file name of ten or so characters and a line number of
     * up to four — and deliberately not wider. Every column here is one the message does not get.</p>
     */
    private static final int ORIGIN_WIDTH = 16;

    /** The prefix for one line, or {@code ""} when nothing is stamped. */
    public static String of(Style style, long epochMillis, @Nullable String origin) {
        if (style == null || style == Style.NONE) return "";
        StringBuilder out = new StringBuilder(TIME_WIDTH + ORIGIN_WIDTH);
        appendTime(out, epochMillis);
        if (style == Style.FULL) appendOrigin(out, origin);
        return out.toString();
    }

    /** How wide {@link #of} would be for a line with no origin — what a caller pads chrome to. */
    public static int width(Style style) {
        if (style == null || style == Style.NONE) return 0;
        return style == Style.FULL ? TIME_WIDTH + ORIGIN_WIDTH : TIME_WIDTH;
    }

    /**
     * {@code [HH:mm:ss] }, in the reader's own zone.
     *
     * <p>Wall time rather than the {@code nanoTime} the rail's clock uses, and the two are not
     * interchangeable: a duration wants a monotonic source that survives an NTP correction, while a
     * timestamp has to agree with the clock in the corner of the screen.</p>
     *
     * <p>No milliseconds. They change ten times faster than anyone reads, and the question this answers
     * is "is this still going" rather than "how long did that take" — which the rail already answers
     * properly, and to which a millisecond field would be a worse answer wearing more digits.</p>
     */
    private static void appendTime(StringBuilder out, long epochMillis) {
        LocalTime time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime();
        out.append('[');
        two(out, time.getHour());
        out.append(':');
        two(out, time.getMinute());
        out.append(':');
        two(out, time.getSecond());
        out.append("] ");
    }

    private static void appendOrigin(StringBuilder out, @Nullable String origin) {
        int start = out.length();
        if (origin != null && !origin.isEmpty()) out.append(origin);
        // PADDED TO THE COLUMN, or past it if the name is long -- @see the class note. The trailing gap
        // is part of the width, so a name that exactly fills it still has one space after it.
        while (out.length() - start < ORIGIN_WIDTH - 1) out.append(' ');
        out.append(' ');
    }

    private static void two(StringBuilder out, int value) {
        if (value < 10) out.append('0');
        out.append(value);
    }
}
