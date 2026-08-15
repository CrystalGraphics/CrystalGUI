package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
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
            new ScriptRef(Resource.of(Resource.SCHEME_PROJECT, "src/Main.java"),
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
        RunConsole console = new RunConsole();
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        PrintStream stream = routedTo(console, passthrough);

        stream.println("the game says something");

        assertEquals("the console must not have taken it", 0, console.size());
        assertTrue(passthrough.toString(StandardCharsets.UTF_8).contains("the game says something"));
    }

    /** And inside a script, the same call reaches the console instead. */
    @Test
    public void outputFromInsideAScriptIsCaptured() {
        RunConsole console = new RunConsole();
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        PrintStream stream = routedTo(console, passthrough);

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            stream.println("hello from the script");
        } finally {
            ScriptOutput.exit(previous);
        }

        assertEquals(1, console.size());
        assertEquals("hello from the script", console.entries().get(0).text());
        assertEquals("Main.java", console.entries().get(0).script());
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
        RunConsole console = new RunConsole();
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            stream.print("a");
            stream.print("b");
            assertEquals("nothing complete yet", 0, console.size());
            stream.println("c");
        } finally {
            ScriptOutput.exit(previous);
        }

        assertEquals(1, console.size());
        assertEquals("abc", console.entries().get(0).text());
    }

    /**
     * A carriage return must not survive into the row.
     *
     * <p>It is invisible, and it would make two otherwise identical rows differ for a reason nobody
     * looking at the console could see.</p>
     *
     * <p><b>Collapsing off, because both lines leave the same call site</b> and would otherwise fold
     * into one row — which is the collapse rule working, and would hide the line splitting this is
     * about. Two rules, two tests.</p>
     */
    @Test
    public void windowsLineEndingsDoNotLeaveAStrayCarriageReturn() {
        RunConsole console = new RunConsole().setCollapsing(false);
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            stream.print("one\r\ntwo\r\n");
        } finally {
            ScriptOutput.exit(previous);
        }

        List<RunConsole.Entry> entries = console.entries();
        assertEquals(2, entries.size());
        assertEquals("one", entries.get(0).text());
        assertEquals("two", entries.get(1).text());
    }

    /**
     * <b>The marker restores rather than clears</b>, so a script calling another script's function does
     * not send the rest of its own output to the game log.
     */
    @Test
    public void nestingRestoresTheOuterScript() {
        ScriptRef inner = new ScriptRef(Resource.of(Resource.SCHEME_PROJECT, "src/Other.java"),
                "other.Other");

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
        RunConsole console = new RunConsole();
        PrintStream stream = routedTo(console, new ByteArrayOutputStream());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            printThroughAHelper(stream);
        } finally {
            ScriptOutput.exit(previous);
        }

        RunConsole.Entry entry = console.entries().get(0);
        assertNotNull("the script's frame should have been found", entry.origin());
        assertTrue("the origin names the script's file: " + entry.origin(),
                entry.origin().startsWith("Main.java:"));
        assertTrue("and it is navigable", entry.isNavigable());
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
        ScriptRef script = new ScriptRef(Resource.of(Resource.SCHEME_PROJECT, "src/Main.java"),
                "scripts.Main");
        assertTrue(script.owns("scripts.Main"));
        assertTrue(script.owns("scripts.Main$1"));
        assertTrue(script.owns("scripts.Main$$Lambda$14"));
        assertFalse("a different script that merely shares a prefix", script.owns("scripts.MainOther"));
        assertFalse(script.owns("java.io.PrintStream"));
    }

    /** The explicit binding a language's own `print` uses, which knows its level without inferring it. */
    @Test
    public void theExplicitBindingNeedsAScriptToBeRunning() {
        RunConsole console = new RunConsole();
        ScriptOutput.install(console);           // idempotent; sets the console the binding writes to

        ScriptOutput.write(RunLevel.WARN, "ignored, nothing is running");
        assertEquals(0, console.size());

        ScriptRef previous = ScriptOutput.enter(SCRIPT);
        try {
            ScriptOutput.write(RunLevel.WARN, "a warning");
        } finally {
            ScriptOutput.exit(previous);
        }
        assertEquals(1, console.size());
        assertEquals(RunLevel.WARN, console.entries().get(0).level());
    }
}
