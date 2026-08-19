package com.crystalgui.language.run;

import com.crystalgui.language.java.exec.ScriptHost;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.console.RunConsole;
import com.crystalgui.language.run.console.RunLevel;
import com.crystalgui.language.run.exec.ScriptOutput;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.1, end to end — a real compiled script's output reaching a real console.
 *
 * <h3>Why this exists beside {@code ScriptOutputTest}</h3>
 *
 * <p>That one proves the routing rules against a hand-set marker. This one proves the marker is actually
 * <em>set</em>, by compiling and running a script through {@link ScriptHost} the way the Run command
 * does. The two are not the same claim, and the gap between them is exactly where "the console works but
 * nothing ever appears in it" lives — a bracket nobody applied.</p>
 */
public class ScriptConsoleTest {

    private JavaEngine engine;
    private ScriptHost host;
    private PrintStream realOut;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        host = ScriptHost.of(engine);
        realOut = System.out;
    }

    @After
    public void closeEngine() throws Exception {
        System.setOut(realOut);
        if (host != null) host.close();
        if (engine != null) engine.close();
    }

    /**
     * <b>A script's {@code System.out.println} lands in the console, attributed and located.</b>
     *
     * <p>The whole §9.5.1 claim in one assertion: no process boundary exists, so this only works if
     * {@code ScriptHost} set the thread-local marker around the invocation and the replacement stream
     * consulted it.</p>
     */
    @Test
    public void aScriptsPrintlnReachesTheConsole() throws Throwable {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        System.setOut(new PrintStream(
                ScriptOutput.routed(passthrough, RunLevel.OUT, console), true, StandardCharsets.UTF_8));

        ScriptHost.Compiled compiled = host.compileSource("Script",
                "System.out.println(\"from the script\");", Map.of());
        assertTrue(String.valueOf(compiled.messages()), compiled.successful());

        compiled.withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Script.java"));
        host.run(compiled, Map.of());

        assertEquals("exactly the script's line, and nothing else", 1, drained(console));
        assertEquals("from the script", lineText(console, 0));
        assertEquals("Script.java", lineScript(console, 0));
        assertEquals("nothing leaked to the real stream", 0, passthrough.size());

        // AND IT KNOWS WHICH LINE SAID IT. The whole path has to hold for this: ECJ has to emit a line
        // number table (`-g`), `Safepoints` has to preserve it through the ASM rewrite, the ref has to
        // name the class the frames actually carry, and the walk has to find it. Any one of those
        // failing leaves the stamp's origin column blank -- which is what shipped, and which looks like
        // the column being decorative rather than like four things having to agree.
        console.drain();
        assertNotNull("the line that printed it was not recorded", console.lineAt(0).origin());
        assertTrue("the origin should name the script's own file: " + console.lineAt(0).origin(),
                console.lineAt(0).origin().startsWith("Script.java:"));
    }

    /**
     * <b>And the same for a file that declares its own class, which is the ordinary case.</b>
     *
     * <p>A snippet goes through {@code ScriptPrelude.wrap} and a real {@code Main.java} through
     * {@code compilationUnit}, and the two build the script's identity from different halves — the ref is
     * made from the wrapper's {@code className} while the class is loaded under its {@code binaryName}.
     * Those agree for a class in the default package and stop agreeing the moment one declares a package,
     * at which point the stack frames carry a name the ref has never heard of and every origin comes back
     * null. The symptom is a blank column rather than an error.</p>
     */
    @Test
    public void aDeclaredClassAlsoRecordsWhereItPrintedFrom() throws Throwable {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        System.setOut(new PrintStream(
                ScriptOutput.routed(new ByteArrayOutputStream(), RunLevel.OUT, console), true,
                StandardCharsets.UTF_8));

        ScriptHost.Compiled compiled = host.compileSource("Main",
                "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(\"declared\");\n"
                        + "    }\n"
                        + "}\n", Map.of());
        assertTrue(String.valueOf(compiled.messages()), compiled.successful());

        compiled.withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Main.java"));
        host.run(compiled, Map.of());

        console.drain();
        assertEquals("declared", lineText(console, 0));
        assertEquals("the line that printed it was not recorded", "Main.java:3",
                console.lineAt(0).origin());
    }

    /**
     * <b>And for one that declares a package — where the two names genuinely differ.</b>
     *
     * <p>{@code Compiled.withSource} built the ref from {@code className} while {@code prepare} loads the
     * class by {@code binaryName}, so a packaged script's frames read {@code demo.Main} against a ref
     * saying {@code Main}. {@code ScriptRef.owns} then matched nothing and every line lost its origin.</p>
     */
    @Test
    public void aPackagedClassStillRecordsWhereItPrintedFrom() throws Throwable {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        System.setOut(new PrintStream(
                ScriptOutput.routed(new ByteArrayOutputStream(), RunLevel.OUT, console), true,
                StandardCharsets.UTF_8));

        ScriptHost.Compiled compiled = host.compileSource("Main",
                "package demo;\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(\"packaged\");\n"
                        + "    }\n"
                        + "}\n", Map.of());
        assertTrue(String.valueOf(compiled.messages()), compiled.successful());

        compiled.withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Main.java"));
        host.run(compiled, Map.of());

        console.drain();
        assertEquals("packaged", lineText(console, 0));
        assertEquals("a packaged script lost its origin", "Main.java:4",
                console.lineAt(0).origin());
    }

    /**
     * <b>And output from outside the run does not.</b>
     *
     * <p>Asserted in the same fixture as the capture, because either alone passes against a stream that
     * captures everything or one that captures nothing — and "captures everything" is the failure that
     * would swallow Minecraft's logging.</p>
     */
    @Test
    public void outputBeforeAndAfterTheRunIsNotCaptured() throws Throwable {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        System.setOut(new PrintStream(
                ScriptOutput.routed(passthrough, RunLevel.OUT, console), true, StandardCharsets.UTF_8));

        System.out.println("before");
        ScriptHost.Compiled compiled = host.compileSource("Script",
                "System.out.println(\"during\");", Map.of());
        assertTrue(compiled.successful());
        compiled.withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Script.java"));
        host.run(compiled, Map.of());
        System.out.println("after");

        assertEquals(1, drained(console));
        assertEquals("during", lineText(console, 0));
        String outside = passthrough.toString(StandardCharsets.UTF_8);
        assertTrue(outside.contains("before"));
        assertTrue(outside.contains("after"));
        assertFalse("the script's line must not be in both", outside.contains("during"));
    }

    /**
     * <b>A script with no ref routes nowhere</b> — the dedicated-server and test case.
     *
     * <p>The marker is opt-in on the compiled script, so a host with no console attaches nothing and
     * every {@code println} goes where it always went. That absence has to be the default, or a headless
     * host would be quietly buffering output nobody will ever read.</p>
     */
    @Test
    public void aScriptWithNoRefIsNotCaptured() throws Throwable {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        System.setOut(new PrintStream(
                ScriptOutput.routed(passthrough, RunLevel.OUT, console), true, StandardCharsets.UTF_8));

        ScriptHost.Compiled compiled = host.compileSource("Script",
                "System.out.println(\"unattributed\");", Map.of());
        assertTrue(compiled.successful());
        host.run(compiled, Map.of());

        assertEquals(0, drained(console));
        assertTrue(passthrough.toString(StandardCharsets.UTF_8).contains("unattributed"));
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
