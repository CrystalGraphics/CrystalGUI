package com.crystalgui.language.run;

import java.util.concurrent.TimeUnit;

/**
 * How long a run has been going, written the way IntelliJ writes it.
 *
 * <h3>Seconds are the smallest unit, and that is a display decision</h3>
 *
 * <p>A counter showing milliseconds changes ten times faster than the eye can read and turns a quiet rail
 * into a flicker. IntelliJ shows {@code 6 min, 32 sec}; the resolution people actually use it at is "is
 * this still going, and roughly how long has it been".</p>
 *
 * <p><b>Below a second it says {@code <1 sec} rather than {@code 0 sec}.</b> Zero reads as "not started",
 * which is precisely the wrong thing to say about the one state where a script is most alive.</p>
 *
 * <h3>Two units, never three</h3>
 *
 * <p>{@code 1 hr, 4 min} and not {@code 1 hr, 4 min, 9 sec} — the seconds are noise beside the hours, and
 * the row is a rail the width of a filename. The same rule IntelliJ's own label follows.</p>
 */
public final class RunElapsed {
    private RunElapsed() {
    }

    /** Formats a duration in nanoseconds. Never null, never empty. */
    public static String format(long nanos) {
        long totalSeconds = TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, nanos));
        if (totalSeconds < 1) return "<1 sec";

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) return hours + " hr" + (minutes > 0 ? ", " + minutes + " min" : "");
        if (minutes > 0) return minutes + " min" + (seconds > 0 ? ", " + seconds + " sec" : "");
        return seconds + " sec";
    }
}
