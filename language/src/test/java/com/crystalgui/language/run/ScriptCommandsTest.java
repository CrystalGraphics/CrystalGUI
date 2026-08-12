package com.crystalgui.language.run;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.ScriptPrelude;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Run and Stop as commands — the affordance, not the mechanism.
 *
 * <p>{@code ScriptHostTest} proves a script runs and stops. This proves the two <em>commands</em> reach
 * that, which is a different claim: a keybinding, a menu row and a palette entry all point at a command
 * id, and a command that is registered but wired to nothing looks identical to one that works until
 * somebody presses it.</p>
 */
public class ScriptCommandsTest {

    private JavaEngine engine;
    private ScriptHost host;
    private CommandRegistry registry;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        host = ScriptHost.of(engine);
        // A REGISTRY OF ITS OWN, not the global. Registration is process-wide, and a test that used
        // the global one would leak its commands into every later test in the JVM -- a trap this
        // codebase has already paid for with LanguageRegistry.
        registry = new CommandRegistry();
        ScriptHostTest.Sink.WRITTEN.clear();
        ScriptHostTest.Sink.LOOPS.set(0);
    }

    @After
    public void closeEngine() throws IOException {
        if (host != null) host.close();
        if (engine != null) engine.close();
    }

    private ScriptHost.Compiled compile(String body) {
        ScriptHost.Compiled compiled = host.compile(ScriptPrelude.forClass("Script").build().wrap(body));
        assertTrue("the script did not compile: " + compiled.messages(), compiled.successful());
        return compiled;
    }

    @Test(timeout = 30_000)
    public void theRunCommandRunsTheScript() throws Exception {
        String sink = ScriptHostTest.Sink.class.getCanonicalName();
        ScriptHost.Compiled compiled = compile(sink + ".write(\"via the command\");\n");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScriptCommands.register(registry, host, () -> compiled, Map::of, failure::set);

        assertTrue("the Run command is not registered", registry.contains(ScriptCommands.RUN));
        assertTrue("Run did not execute", registry.run(ScriptCommands.RUN));

        long deadline = System.nanoTime() + 10_000_000_000L;
        while (ScriptHostTest.Sink.WRITTEN.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("the script never ran", ScriptHostTest.Sink.WRITTEN.contains("via the command"));
        assertNull(failure.get());
    }

    @Test(timeout = 30_000)
    public void theStopCommandStopsARunawayScript() throws Exception {
        // Run is ASYNC on purpose: a script with a slow loop must not freeze the frame that is meant to
        // offer Stop, or the one affordance that could rescue the situation is the one unreachable.
        String sink = ScriptHostTest.Sink.class.getCanonicalName();
        ScriptHost.Compiled spinner = compile("while (true) { " + sink + ".tick(); }\n");
        ScriptCommands.register(registry, host, () -> spinner, Map::of, null);

        registry.run(ScriptCommands.RUN);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (ScriptHostTest.Sink.LOOPS.get() < 100 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue("the runaway script never started", ScriptHostTest.Sink.LOOPS.get() > 0);

        assertTrue(registry.run(ScriptCommands.STOP));

        deadline = System.nanoTime() + 10_000_000_000L;
        while (host.isRunning() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse("Stop did not reach the script", host.isRunning());
    }

    @Test
    public void runDoesNothingForAScriptThatDidNotCompile() {
        // Not an error and not a dialog: the diagnostics already say what is wrong, in the editor, at
        // the line. Running would add a second report of the same thing in a worse place.
        ScriptHost.Compiled broken = host.compile(
                ScriptPrelude.forClass("Script").build().wrap("this is not java;\n"));
        assertFalse(broken.successful());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScriptCommands.register(registry, host, () -> broken, Map::of, failure::set);

        registry.run(ScriptCommands.RUN);
        assertNull("a failed compile was reported as a run failure", failure.get());
        assertTrue(ScriptHostTest.Sink.WRITTEN.isEmpty());
    }

    @Test
    public void aScriptsFailureIsReportedRatherThanSwallowed() throws Exception {
        ScriptHost.Compiled thrower =
                compile("throw new IllegalStateException(\"boom\");\n");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScriptCommands.register(registry, host, () -> thrower, Map::of, failure::set);

        registry.run(ScriptCommands.RUN);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (failure.get() == null && System.nanoTime() < deadline) Thread.sleep(5);

        assertTrue(String.valueOf(failure.get()), failure.get() instanceof IllegalStateException);
    }

    @Test
    public void unregisteringRemovesBoth() {
        ScriptCommands.register(registry, host, () -> null, Map::of, null);
        assertTrue(registry.contains(ScriptCommands.RUN));
        assertTrue(registry.contains(ScriptCommands.STOP));

        ScriptCommands.unregister(registry);
        assertFalse(registry.contains(ScriptCommands.RUN));
        assertFalse(registry.contains(ScriptCommands.STOP));
    }
}
