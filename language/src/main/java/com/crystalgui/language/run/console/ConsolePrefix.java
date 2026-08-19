package com.crystalgui.language.run.console;

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
 * <h3>Nothing is padded, and a line with no origin is not indented past one</h3>
 *
 * <p>The first version reserved a fixed column for the origin, on the usual logging-framework argument
 * that the eye follows the left edge of the text and a prefix that changes width moves it. That is true
 * and it was the wrong trade here. <b>Almost every line has an origin</b> — the stack walk finds one for
 * anything a script printed itself — so the column was near-constant anyway, and the padding bought
 * alignment that was already there. What it cost was the rare line <em>without</em> one: a callback the
 * engine invoked, indented sixteen columns to line up with a field it does not have, which reads as an
 * empty cell rather than as an absent one.</p>
 *
 * <p>So a stamp is its parts and a single space between them. A four-digit line number moves the message
 * by one column and nothing else does. Nothing is ever truncated either: shortening
 * {@code VeryLongScript.java:1204} means dropping either the file or the line, and both halves are the
 * answer to "where did this come from" — it would also want an ellipsis, which this codebase has been
 * bitten by assuming a font has.</p>
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

    /** The prefix for one line, or {@code ""} when nothing is stamped. */
    public static String of(Style style, long epochMillis, @Nullable String origin) {
        if (style == null || style == Style.NONE) return "";
        StringBuilder out = new StringBuilder(28);
        appendTime(out, epochMillis);
        // BRACKETED, LIKE THE CLOCK BESIDE IT. Both are the console's own words about a line rather than
        // the line itself, and they should read as one prefix rather than as a stamp followed by a stray
        // filename -- which is how `[23:20:30] Main.java:465 hello` reads, as though the script had
        // printed the name. Logback brackets its thread for the same reason.
        //
        // ABSENT, NOT EMPTY, when there is no origin: neither a pair of brackets saying only that
        // something is missing, nor the whitespace where one would have gone. @see the class note
        if (style == Style.FULL && origin != null && !origin.isEmpty()) {
            out.append('[').append(origin).append("] ");
        }
        return out.toString();
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

    private static void two(StringBuilder out, int value) {
        if (value < 10) out.append('0');
        out.append(value);
    }
}
