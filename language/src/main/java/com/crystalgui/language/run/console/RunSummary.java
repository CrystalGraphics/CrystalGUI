package com.crystalgui.language.run.console;

import com.crystalgui.language.run.RunState;

import javax.annotation.Nullable;

/**
 * How a run ended, in one line — the console's closing boundary.
 *
 * <h3>Every reference draws one, and the absence was not neutral</h3>
 *
 * <p>IntelliJ writes {@code Process finished with exit code 0}; VS Code's task terminal writes
 * {@code [Done] exited with code=0 in 0.53 seconds}. The console here opened a run with a divider and
 * closed it with nothing, which costs two things that are only obvious once they are missing. With
 * <em>All output</em> showing and two scripts interleaved there was no way to tell where one run's
 * output ended and the next began. And a run that printed nothing at all was indistinguishable from a
 * run that never started — the transcript was identical either way, which is precisely the case somebody
 * is looking at the console to diagnose.</p>
 *
 * <h3>No exit code, because there is no process</h3>
 *
 * <p>{@link RunState} already makes this argument: a script is loaded into this JVM and an
 * event-driven one never ends at all. So the line names the <em>state</em> and the <em>duration</em>,
 * which are the two things that genuinely exist here — and the duration is the one an exit code cannot
 * give you.</p>
 *
 * <h3>Only terminal states get one</h3>
 *
 * <p>{@link RunState#LIVE} is the interesting case and the reason this returns null rather than a
 * fallback string. A script that registered handlers and returned has <em>not</em> ended: it is sitting
 * there waiting to fire, which is what the rail's ticking clock says. Writing "finished" under it would
 * be the exact falsehood {@code RunState} exists to avoid, and writing anything at all would put a
 * boundary in the transcript that the next handler's output would immediately appear below.</p>
 */
public final class RunSummary {

    private RunSummary() {
    }

    /**
     * The closing line for a run, or null when the run has not ended.
     *
     * @param script  the file's name, as the opening divider spells it
     * @param state   what it ended as
     * @param elapsed how long it lasted, in nanoseconds
     */
    @Nullable
    public static String of(@Nullable String script, @Nullable RunState state, long elapsed) {
        String verb = verbFor(state);
        if (verb == null) return null;
        String name = script == null || script.isEmpty() ? "Script" : script;
        // "finished IN" and "stopped/failed AFTER" -- a completed run lasted that long, while an
        // interrupted one merely got that far. English carries the distinction for free and it is the
        // difference between reporting a duration and reporting how much of one there was.
        String preposition = state == RunState.FINISHED ? " in " : " after ";
        return name + " " + verb + preposition + RunElapsed.format(elapsed);
    }

    @Nullable
    private static String verbFor(@Nullable RunState state) {
        if (state == null) return null;
        switch (state) {
            case FINISHED: return "finished";
            case STOPPED: return "stopped";
            case FAILED: return "failed";
            // COMPILED is a state a run has not begun from, and RUNNING and LIVE are both states it is
            // still in -- LIVE most of all. @see RunState#LIVE
            default: return null;
        }
    }
}
