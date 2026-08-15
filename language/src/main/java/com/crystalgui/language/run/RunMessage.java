package com.crystalgui.language.run;

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

    /**
     * What {@link RunConsole} folds on — <b>the origin AND the text</b>.
     *
     * <h3>This keyed on the origin alone, and that was wrong</h3>
     *
     * <p>The argument was that a handler printing {@code tick 1}, {@code tick 2}, {@code tick 3}
     * produces a different string every time and so would never fold — while being exactly the thing
     * that has to. The premise is true and the conclusion does not follow: those three lines are not one
     * message repeating, they are <b>three messages</b>, and folding them into a row that shows only the
     * newest does not compress the transcript, it <em>deletes</em> two thirds of it.</p>
     *
     * <p>It showed the first time a real script ran. {@code RunTest.java} prints its output through a
     * helper, so every line in the file shared that helper's origin — and thirteen distinct results
     * collapsed into one row reading {@code ×13}, with twelve of them gone. A console that loses output
     * is worse than a console that scrolls.</p>
     *
     * <p>So the key is the text too, which is Unity's rule ("only the first instance of <em>recurring</em>
     * messages"). The origin stays in the key as an extra separation: two call sites printing the same
     * string are two facts, and keeping them apart is strictly more information than Unity offers. The
     * per-tick flood that motivated the original rule is answered by the ring, which is where a bound
     * belongs.</p>
     *
     * <p>Null when there is nothing safe to fold on. @see #origin</p>
     */
    @Nullable
    String collapseKey() {
        return origin == null ? null : script + "\u0000" + level + "\u0000" + origin + "\u0000" + text;
    }

    /** Roughly what this costs the ring, in characters. @see RunConsole */
    int weight() {
        return text.length() + (origin == null ? 0 : origin.length()) + script.length();
    }
}
