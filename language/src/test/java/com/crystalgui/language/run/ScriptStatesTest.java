package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.3 — the states the rail lists and the running indicator marks, driven by real runs.
 *
 * <p>Asserted through {@link ScriptHost} rather than by calling {@code RunSessions} directly, because
 * the claim is not that the map stores what it is told — {@code RunSessionsTest} covers that — but that
 * the host actually tells it, on every path a script can leave by.</p>
 */
public class ScriptStatesTest {

    private static final Resource FILE = Resource.of(Resource.SCHEME_PROJECT, "src/Script.java");

    private JavaEngine engine;
    private ScriptHost host;
    private RunSessions sessions;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        sessions = new RunSessions();
        host = ScriptHost.of(engine).reportTo(sessions);
    }

    @After
    public void closeEngine() throws Exception {
        if (host != null) host.close();
        if (engine != null) engine.close();
    }

    private ScriptHost.Compiled compile(String body) {
        ScriptHost.Compiled compiled = host.compileSource("Script", body, Map.of());
        assertTrue(String.valueOf(compiled.messages()), compiled.successful());
        return compiled.withSource(FILE);
    }

    /**
     * <b>A one-shot ends {@code FINISHED}</b> — not {@code LIVE}, and not back to {@code COMPILED}.
     *
     * <p>The distinction the enum exists for. {@code COMPILED} would lose the fact that it ran at all,
     * and {@code LIVE} would tell the indicator to keep marking a script that will never do anything
     * again.</p>
     */
    @Test
    public void aScriptThatReturnsEndsFinished() throws Throwable {
        host.run(compile("int x = 1 + 1;"), Map.of());
        assertEquals(RunState.FINISHED, sessions.stateOf(FILE));
        assertFalse("nothing will fire again, so it is not active", sessions.isActive(FILE));
    }

    /** And it passed through {@code RUNNING} on the way, which is what the rail shows mid-run. */
    @Test
    public void theRunIsAnnouncedBeforeItFinishes() throws Throwable {
        List<RunState> seen = new ArrayList<>();
        sessions.onDidChange.connect(file -> seen.add(sessions.stateOf(file)));

        host.run(compile("int x = 1 + 1;"), Map.of());

        assertEquals("both transitions, in order", List.of(RunState.RUNNING, RunState.FINISHED), seen);
    }

    /**
     * <b>A throw is {@code FAILED}</b>, and the exception still reaches the caller.
     *
     * <p>Reporting must not swallow: the Run command turns a failure into a notification, and a state
     * that was recorded but never thrown would leave the rail correct and the user uninformed.</p>
     */
    @Test
    public void aScriptThatThrowsEndsFailed() {
        ScriptHost.Compiled compiled = compile("throw new IllegalStateException(\"boom\");");
        try {
            host.run(compiled, Map.of());
            fail("the exception should have reached the caller");
        } catch (Throwable thrown) {
            assertEquals("boom", thrown.getMessage());
        }
        assertEquals(RunState.FAILED, sessions.stateOf(FILE));
    }

    /**
     * <b>Being stopped is not failing.</b>
     *
     * <p>Somebody asked for this one, so it is {@code STOPPED} — and the distinction has to be drawn
     * inside the host, because {@code runAsync} deliberately swallows {@link ScriptStoppedException}
     * and would otherwise report nothing at all for a script the user just killed.</p>
     */
    @Test
    public void aStoppedScriptIsStoppedAndNotFailed() throws Throwable {
        // A LOOP WITH NO EXIT, which is exactly what the safepoints exist for. It ends only because
        // stop() interrupts it, so this also proves the kill flag still works with reporting in place.
        host.runAsync(compile("while (true) { }"), Map.of(), failure -> { });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sessions.stateOf(FILE) != RunState.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals("the script should be running by now", RunState.RUNNING, sessions.stateOf(FILE));

        assertTrue("stop should have found something to stop", host.stop());

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sessions.stateOf(FILE) == RunState.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(RunState.STOPPED, sessions.stateOf(FILE));
    }

    /**
     * A host nobody is watching records nothing and fails at nothing.
     *
     * <p>The dedicated-server shape: scripts run, no rail exists, and the absence has to be free rather
     * than something every caller works around.</p>
     */
    @Test
    public void aHostWithNoSessionsStillRuns() throws Throwable {
        ScriptHost quiet = ScriptHost.of(engine);
        try {
            ScriptHost.Compiled compiled = quiet.compileSource("Script", "int x = 2;", Map.of());
            assertTrue(compiled.successful());
            quiet.run(compiled.withSource(FILE), Map.of());
        } finally {
            quiet.close();
        }
        assertNull("nothing should have been recorded", sessions.stateOf(FILE));
    }

    /** A script with no source attached cannot be attributed, so nothing is recorded for it either. */
    @Test
    public void aScriptWithNoSourceIsNotTracked() throws Throwable {
        ScriptHost.Compiled compiled = host.compileSource("Script", "int x = 3;", Map.of());
        assertTrue(compiled.successful());
        host.run(compiled, Map.of());
        assertTrue(sessions.scripts().isEmpty());
    }
}
