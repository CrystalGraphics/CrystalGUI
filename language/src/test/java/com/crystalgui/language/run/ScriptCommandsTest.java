package com.crystalgui.language.run;

import com.crystalgui.language.java.ScriptHost;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.Resource;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
        ScriptCommands.register(registry, host, asked -> compiled, Map::of,
                (ref, error) -> failure.set(error), null);

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
        ScriptCommands.register(registry, host, asked -> spinner, Map::of, null, null);

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
        ScriptCommands.register(registry, host, asked -> broken, Map::of,
                (ref, error) -> failure.set(error), null);

        registry.run(ScriptCommands.RUN);
        assertNull("a failed compile was reported as a run failure", failure.get());
        assertTrue(ScriptHostTest.Sink.WRITTEN.isEmpty());
    }

    @Test
    public void aScriptsFailureIsReportedRatherThanSwallowed() throws Exception {
        ScriptHost.Compiled thrower =
                compile("throw new IllegalStateException(\"boom\");\n");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScriptCommands.register(registry, host, asked -> thrower, Map::of,
                (ref, error) -> failure.set(error), null);

        registry.run(ScriptCommands.RUN);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (failure.get() == null && System.nanoTime() < deadline) Thread.sleep(5);

        assertTrue(String.valueOf(failure.get()), failure.get() instanceof IllegalStateException);
    }

    /**
     * <b>A Run can name its subject, and Rerun is the reason.</b>
     *
     * <p>The source used to be a bare {@code Supplier}, which can only answer "whatever is current" — so
     * the rail's Rerun button named a script in its tooltip, went dead without a rail selection, and then
     * ran the active editor regardless. Select {@code A.java} with {@code B.java} on screen and the
     * button said A and ran B, with nothing failing: the wrong script running looks exactly like the
     * right one running when both print.</p>
     */
    @Test(timeout = 30_000)
    public void aRunCanNameTheScriptItIsAbout() throws Exception {
        String sink = ScriptHostTest.Sink.class.getCanonicalName();
        ScriptHost.Compiled named = compile(sink + ".write(\"the one asked for\");\n");

        Resource subject = Resource.of("project", "src/Asked.java");
        AtomicReference<Resource> seen = new AtomicReference<>();
        ScriptCommands.register(registry, host, asked -> {
            seen.set(asked);
            return named;
        }, Map::of, null, null);

        registry.run(ScriptCommands.RUN, new CommandContext(null, subject));
        assertEquals("the subject never reached the compiler", subject, seen.get());

        long deadline = System.nanoTime() + 10_000_000_000L;
        while (ScriptHostTest.Sink.WRITTEN.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("the named script never ran", ScriptHostTest.Sink.WRITTEN.contains("the one asked for"));
    }

    /**
     * <b>And a Run with no subject still means "the current one".</b>
     *
     * <p>The half that must not regress while the half above is added: Shift+F10 and the palette carry no
     * payload, and a source told about a null there is what keeps them meaning the file in front.</p>
     */
    @Test
    public void aRunWithNoSubjectAsksForTheCurrentOne() {
        AtomicReference<Resource> seen = new AtomicReference<>(Resource.of("project", "sentinel"));
        ScriptCommands.register(registry, host, asked -> {
            seen.set(asked);
            return null;
        }, Map::of, null, null);

        registry.run(ScriptCommands.RUN);
        assertNull("a bare Run named a subject it was never given", seen.get());
    }

    /**
     * <b>A payload that is not a script is not a subject.</b>
     *
     * <p>{@code args} is the binding payload any keymap may attach, so a Run that threw — or worse, one
     * that guessed — on an unexpected value would be a command a keymap could break from a config file.
     * Absent is the safe reading, and it is also the true one.</p>
     */
    @Test
    public void anUnrelatedPayloadIsNotMistakenForAScript() {
        AtomicReference<Resource> seen = new AtomicReference<>(Resource.of("project", "sentinel"));
        ScriptCommands.register(registry, host, asked -> {
            seen.set(asked);
            return null;
        }, Map::of, null, null);

        registry.run(ScriptCommands.RUN, new CommandContext(null, "not a resource"));
        assertNull("a string payload was read as a script", seen.get());
    }

    /**
     * <b>A failure names the run that threw</b> — not the last thing anybody compiled.
     *
     * <p>The host reports the ref because the host is the only thing that still knows it: a failure
     * arrives after the invocation has unwound, so a shell reconstructing it from "the file I compiled
     * most recently" attributes the trace to whichever file was compiled last — a <em>different</em> one
     * the moment somebody presses Run on a file that does not build while an older script is still
     * alive. The trace then lands in the transcript under a filename that never executed a line.</p>
     */
    @Test(timeout = 30_000)
    public void aFailureCarriesTheRunItCameFrom() throws Exception {
        ScriptHost.Compiled thrower = compile("throw new IllegalStateException(\"boom\");\n");
        Resource file = Resource.of("project", "src/Thrower.java");
        thrower.withSource(file);

        AtomicReference<ScriptRef> blamed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScriptCommands.register(registry, host, asked -> thrower, Map::of, (ref, error) -> {
            blamed.set(ref);
            failure.set(error);
        }, null);

        registry.run(ScriptCommands.RUN);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (failure.get() == null && System.nanoTime() < deadline) Thread.sleep(5);

        assertTrue(String.valueOf(failure.get()), failure.get() instanceof IllegalStateException);
        assertNotNull("the failure named no run at all", blamed.get());
        assertEquals("the failure was attributed to the wrong file", file, blamed.get().file());
    }

    @Test
    public void unregisteringRemovesBoth() {
        ScriptCommands.register(registry, host, asked -> null, Map::of, null, null);
        assertTrue(registry.contains(ScriptCommands.RUN));
        assertTrue(registry.contains(ScriptCommands.STOP));

        ScriptCommands.unregister(registry);
        assertFalse(registry.contains(ScriptCommands.RUN));
        assertFalse(registry.contains(ScriptCommands.STOP));
    }
}
