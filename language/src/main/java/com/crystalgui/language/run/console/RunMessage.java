package com.crystalgui.language.run.console;

import com.crystalgui.fs.Resource;

import javax.annotation.Nullable;

/**
 * One line a running script produced, with enough about where it came from to be filtered, collapsed
 * and navigated.
 *
 * @param script the script that produced it — the identity everything else in the panel filters by.
 *               Never null: a line with no owner is a line the console cannot attribute, and §9.5.1's
 *               thread-local marker exists precisely so that state is unreachable
 * @param origin {@code foo.js:12} — the call site, or null when the producer could not name one.
 *               <b>Load-bearing beyond display:</b> it is the key {@link RunConsole} collapses on, and
 *               a null origin is deliberately never collapsed — folding two unrelated unattributed
 *               lines together because they share "nowhere" would merge messages that have nothing to
 *               do with each other
 * @param file   the resource {@code origin} points into, when it is one this workspace can open, so a
 *               double-click can navigate. Null for a frame outside the workspace — a JDK or engine
 *               frame in a stack trace is worth <em>showing</em> and cannot be opened
 * @param line   the 1-based line within {@code file}, or 0 when unknown
 * @param level  ordinary output, a warning, or an error
 * @param text   the line itself, already rendered by the producer. No formatting is applied here:
 *               whatever the script printed is what the console shows
 */
public record RunMessage(String script, @Nullable String origin, @Nullable Resource file, int line,
                         RunLevel level, String text) {

    /** The common case — output with a known call site and no navigable file. */
    public static RunMessage of(String script, RunLevel level, String text) {
        return new RunMessage(script, null, null, 0, level, text);
    }

    /** Output whose call site is known and navigable. */
    public static RunMessage at(String script, Resource file, int line, RunLevel level, String text) {
        return new RunMessage(script, file.name() + ":" + line, file, line, level, text);
    }

    /** Whether a double-click on this line has somewhere to go. */
    public boolean isNavigable() {
        return file != null && line > 0;
    }

    // COLLAPSING IS GONE, AND SO IS WHAT IT NEEDED. `collapseKey()` folded repeated lines into a `×N`
    // row and `weight()` sized one for the ring; both went with the ListView, because a text area has
    // nowhere to put a badge without becoming a list again, and the ring measures the document rather
    // than guessing at a message's cost. They outlived their only caller by a release -- which is how a
    // method nobody calls becomes a thing the next person reads and reasons about. The argument for
    // keying on origin AND text is not lost with them: it is in `plan_m9_5.md` §9.5.4, where a decision
    // belongs.
}
