package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.1 — the reconstructed output boundary.
 *
 * <p>Every test here builds its own routed stream rather than calling {@code install()}, which replaces
 * the JVM's {@code System.out} process-wide and would swallow the test runner's own output.</p>
 */
public class ScriptOutputTest {

    private static final ScriptRef SCRIPT =
            ScriptRef.ofClass(Resource.of(Resource.SCHEME_PROJECT, "src/Main.java"),
                    ScriptOutputTest.class.getName());

    @After
    public void clearMarker() {
        ScriptOutput.exit(null);
    }

    private static PrintStream routedTo(RunConsole console, ByteArrayOutputStream passthrough) {
        return new PrintStream(ScriptOutput.routed(passthrough, RunLevel.OUT, console), true,
                StandardCharsets.UTF_8);
    }

    /**
     * <b>The whole point: the game's own logging must not be captured.</b>
     *
     * <p>This is what makes the design legal at all. An in-process console that stole every
     * {@code System.out} would take Minecraft's logging and every other mod's along with the script's,
     * which is a far worse outcome than having no console.</p>
     */
    @Test
    public void outputFromOutsideAScriptPassesStraightThrough() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        PrintStream stream = routedTo(console, passthrough);

        stream.println("the game says something");

        assertEquals("the console must not have taken it", 0, drained(console));
        assertTrue(passthrough.toString(StandardCharsets.UTF_8).contains("the game says something"));
    }

    /** And inside a script, the same call reaches the console instead. */
    @Test
    public void outputFromInsideAScriptIsCaptured() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        PrintStream stream = routedTo(console, passthrough);

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            stream.println("hello from the script");
        } finally {
            ScriptOutput.exit(previous);
        }

        assertEquals(1, drained(console));
        assertEquals("hello from the script", lineText(console, 0));
        assertEquals("Main.java", lineScript(console, 0));
        assertEquals("nothing should have leaked to the real stream", 0, passthrough.size());
    }

    /**
     * <b>A partial line is not a row.</b>
     *
     * <p>{@code PrintStream} with autoflush flushes after a bare {@code print} as well as after a
     * {@code println}, so emitting on flush would turn one line built from three calls into three rows.
     * The stream splits on the newline and on nothing else.</p>
     */
    @Test
    public void aLineBuiltFromSeveralPrintsIsOneRow() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            stream.print("a");
            stream.print("b");
            assertEquals("nothing complete yet", 0, drained(console));
            stream.println("c");
        } finally {
            ScriptOutput.exit(previous);
        }

        assertEquals(1, drained(console));
        assertEquals("abc", lineText(console, 0));
    }

    /**
     * <b>...but an unfinished line survives the end of the run.</b>
     *
     * <p>The other half of the rule above, and the two are easy to state as one and get wrong. Splitting
     * only on the newline is right <em>while a script is running</em> and wrong at the moment it stops
     * being able to write one: a script whose last statement is a bare {@code print} had its final output
     * sitting in a buffer that nothing would ever empty. Losing output is the one thing a console must
     * not do, and this loses it silently — there is no row, no warning, and no way to tell from the
     * transcript that anything was said.</p>
     */
    @Test
    public void anUnfinishedLineIsFlushedWhenTheRunEnds() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            stream.print("the last thing it said");
            assertEquals("a partial line is not a row while the script is still going", 0,
                    drained(console));
        } finally {
            ScriptOutput.exit(previous);
        }

        assertEquals("the final partial line was lost", 1, drained(console));
        assertEquals("the last thing it said", lineText(console, 0));
    }

    /**
     * <b>A line that never ends is still bounded.</b>
     *
     * <p>The buffer empties on a newline, so a script printing megabytes without one — a loop of bare
     * {@code print}, a serialiser writing a whole document — would hold all of it with the console
     * showing nothing at all. The cap turns "unbounded and invisible" into "very long and visible",
     * which are different failures and only one of them is a leak.</p>
     */
    @Test
    public void aLineThatNeverEndsIsCutRatherThanBufferedForever() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            // Comfortably past the 64KB cap, and not one newline in it.
            for (int i = 0; i < 200; i++) stream.print("0123456789012345678901234567890123456789".repeat(25));
            assertTrue("the cap never fired; the whole thing is still in memory", drained(console) > 0);
        } finally {
            ScriptOutput.exit(previous);
        }
    }

    /**
     * A carriage return must not survive into the row.
     *
     * <p>It is invisible, and it would make two otherwise identical rows differ for a reason nobody
     * looking at the console could see.</p>
     *
     * <p>Two lines from one call site, which the list version had to turn collapsing off to assert —
     * folding is gone with the list, so the rule is now simply that a newline ends a line and a return
     * is not part of it.</p>
     */
    @Test
    public void windowsLineEndingsDoNotLeaveAStrayCarriageReturn() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            stream.print("one\r\ntwo\r\n");
        } finally {
            ScriptOutput.exit(previous);
        }

        assertEquals(2, drained(console));
        assertEquals("one", lineText(console, 0));
        assertEquals("two", lineText(console, 1));
    }

    /**
     * <b>The marker restores rather than clears</b>, so a script calling another script's function does
     * not send the rest of its own output to the game log.
     */
    @Test
    public void nestingRestoresTheOuterScript() {
        ScriptRef inner = ScriptRef.ofClass(Resource.of(Resource.SCHEME_PROJECT, "src/Other.java"), "other.Other");

        ScriptRef beforeOuter = ScriptOutput.enter(SCRIPT);
        ScriptRef beforeInner = ScriptOutput.enter(inner);
        assertEquals(inner, ScriptOutput.current());
        ScriptOutput.exit(beforeInner);
        assertEquals("the outer script is still running", SCRIPT, ScriptOutput.current());
        ScriptOutput.exit(beforeOuter);
        assertNull(ScriptOutput.current());
    }

    /**
     * <b>The line reported is the script's own, not the helper's.</b>
     *
     * <p>A script calling something that prints should be told which of <em>its</em> lines caused the
     * output. This test's own class stands in for the script, and the message is emitted from a nested
     * helper — the frame the walk must skip past.</p>
     */
    @Test
    public void theOriginIsTheScriptsOwnFrameAndNotTheHelpers() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            printThroughAHelper(stream);
        } finally {
            ScriptOutput.exit(previous);
        }

        console.drain();
        RunConsole.Line line = console.lineAt(0);
        assertNotNull("nothing was appended at all", line);
        assertTrue("the script's frame should have been found, and be navigable", line.isNavigable());
        assertEquals("the line names the script's own file", "Main.java", line.file().name());
    }

    /** Not a lambda and not inlined — a real frame between the script and the write. */
    private static void printThroughAHelper(PrintStream stream) {
        stream.println("printed from a helper");
    }

    /**
     * <b>A lambda's output still belongs to its script.</b>
     *
     * <p>A lambda compiles to a synthetic class beside the script's, so a name-equality test would
     * report it as belonging to nobody — putting exactly the output people wrap in lambdas outside the
     * collapse rule.</p>
     */
    @Test
    public void aLambdaIsStillTheScript() {
        ScriptRef.ClassOrigin script = new ScriptRef.ClassOrigin("scripts.Main");
        assertTrue(script.owns("scripts.Main"));
        assertTrue(script.owns("scripts.Main$1"));
        assertTrue(script.owns("scripts.Main$$Lambda$14"));
        assertFalse("a different script that merely shares a prefix", script.owns("scripts.MainOther"));
        assertFalse(script.owns("java.io.PrintStream"));
    }

    /** The explicit binding a language's own `print` uses, which knows its level without inferring it. */
    @Test
    public void theExplicitBindingNeedsAScriptToBeRunning() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        ScriptOutput.install(console);           // idempotent; sets the console the binding writes to

        ScriptOutput.write(RunLevel.WARN, "ignored, nothing is running");
        assertEquals(0, drained(console));

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            ScriptOutput.write(RunLevel.WARN, "a warning");
        } finally {
            ScriptOutput.exit(previous);
        }
        assertEquals(1, drained(console));
        assertEquals(RunLevel.WARN, lineLevel(console, 0));
    }

    // ── Reading the transcript ──────────────────────────────────────────────────────────────────
    //
    // DRAIN FIRST, EVERY TIME. Appending only enqueues -- a TextBuffer may not be written from the
    // thread a script prints on -- so a test that asserted without draining would be asserting about a
    // document nothing had written to yet, and would pass for the wrong reason when the expectation was
    // zero.

    private static int drained(RunConsole console) {
        console.drain();
        return console.lineCount();
    }

    private static String lineText(RunConsole console, int row) {
        console.drain();
        return console.lineAt(row).text();
    }

    private static String lineScript(RunConsole console, int row) {
        console.drain();
        return console.lineAt(row).script();
    }

    private static RunLevel lineLevel(RunConsole console, int row) {
        console.drain();
        return console.lineAt(row).level();
    }
}
