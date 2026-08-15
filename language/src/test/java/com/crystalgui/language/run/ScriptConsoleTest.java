package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
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
import java.util.List;
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
        RunConsole console = new RunConsole();
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        System.setOut(new PrintStream(
                ScriptOutput.routed(passthrough, RunLevel.OUT, console), true, StandardCharsets.UTF_8));

        ScriptHost.Compiled compiled = host.compileSource("Script",
                "System.out.println(\"from the script\");", Map.of());
        assertTrue(String.valueOf(compiled.messages()), compiled.successful());

        compiled.withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Script.java"));
        host.run(compiled, Map.of());

        List<RunConsole.Entry> entries = console.entries();
        assertEquals("exactly the script's line, and nothing else", 1, entries.size());
        assertEquals("from the script", entries.get(0).text());
        assertEquals("Script.java", entries.get(0).script());
        assertEquals("nothing leaked to the real stream", 0, passthrough.size());
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
        RunConsole console = new RunConsole();
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

        assertEquals(1, console.size());
        assertEquals("during", console.entries().get(0).text());
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
        RunConsole console = new RunConsole();
        ByteArrayOutputStream passthrough = new ByteArrayOutputStream();
        System.setOut(new PrintStream(
                ScriptOutput.routed(passthrough, RunLevel.OUT, console), true, StandardCharsets.UTF_8));

        ScriptHost.Compiled compiled = host.compileSource("Script",
                "System.out.println(\"unattributed\");", Map.of());
        assertTrue(compiled.successful());
        host.run(compiled, Map.of());

        assertEquals(0, console.size());
        assertTrue(passthrough.toString(StandardCharsets.UTF_8).contains("unattributed"));
    }
}
