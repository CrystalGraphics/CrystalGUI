package com.crystalgui.language.run;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.ScriptPrelude;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>The execution service — M7's exit criteria, one test each.</b>
 *
 * <p>A script runs on an explicit command; a re-run replaces the instance; a stop interrupts a
 * deliberate infinite loop; a hundred compile/run/dispose cycles leave nothing pinned.</p>
 */
public class ScriptHostTest {

    /** What a script's side effects land on — standing in for the application's own API. */
    public static final class Sink {
        public static final List<String> WRITTEN = new ArrayList<>();
        public static final AtomicInteger LOOPS = new AtomicInteger();

        public static void write(String value) {
            WRITTEN.add(value);
        }

        public static void tick() {
            LOOPS.incrementAndGet();
        }

        /** Weak, so the reporting cannot be what keeps a loader alive. */
        public static final List<java.lang.ref.WeakReference<ClassLoader>> LOADERS = new ArrayList<>();

        /**
         * A weak reference to the loader a run used, recorded by the script itself.
         *
         * <p>The only honest way to measure this. {@code ScriptHost} deliberately exposes no accessor
         * for the loader — the whole disposal story is that nothing holds one — so the script reports
         * its own.</p>
         */
        public static void recordLoader(ClassLoader loader) {
            LOADERS.add(new java.lang.ref.WeakReference<>(loader));
        }

        private Sink() {
        }
    }

    private JavaEngine engine;
    private ScriptHost host;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        host = ScriptHost.of(engine);
        Sink.WRITTEN.clear();
        Sink.LOOPS.set(0);
        Sink.LOADERS.clear();
    }

    @After
    public void closeEngine() throws IOException {
        if (host != null) host.close();
        if (engine != null) engine.close();
    }

    private static ScriptPrelude.Wrapped wrap(String body) {
        return ScriptPrelude.forClass("Script").build().wrap(body);
    }

    private static ScriptPrelude.Wrapped wrap(String body, Map<String, String> bindings) {
        return ScriptHost.preludeFor("Script", bindings).wrap(body);
    }

    private ScriptHost.Compiled compileOrFail(ScriptPrelude.Wrapped wrapped) {
        ScriptHost.Compiled compiled = host.compile(wrapped);
        assertTrue("the script did not compile: " + compiled.messages(), compiled.successful());
        return compiled;
    }

    // ── It runs ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aScriptRunsAndItsEffectIsObservable() throws Throwable {
        String sink = Sink.class.getCanonicalName();
        host.run(compileOrFail(wrap(sink + ".write(\"hello from the script\");\n")), Map.of());
        assertEquals(List.of("hello from the script"), Sink.WRITTEN);
    }

    @Test
    public void aScriptSeesTheHostBindingsItDeclared() throws Throwable {
        // The point of the prelude: context the author never declared, in scope for the whole script.
        Map<String, String> types = new LinkedHashMap<>();
        types.put("label", "java.lang.String");
        types.put("count", "int");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", "bound");
        values.put("count", 3);

        host.run(compileOrFail(wrap(
                Sink.class.getCanonicalName() + ".write(label + \"-\" + count);\n", types)), values);
        assertEquals(List.of("bound-3"), Sink.WRITTEN);
    }

    @Test
    public void aBindingTheScriptDoesNotDeclareIsIgnored() throws Throwable {
        // The right way round: the host offers everything it has, a script names what it wants, and
        // adding a binding to the host cannot break a script written before it existed.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("somethingNew", "ignored");

        host.run(compileOrFail(wrap(Sink.class.getCanonicalName() + ".write(\"fine\");\n")), values);
        assertEquals(List.of("fine"), Sink.WRITTEN);
    }

    @Test
    public void aScriptsOwnExceptionReachesTheCallerUnwrapped() throws Throwable {
        // InvocationTargetException names how the script was called and nothing about what went wrong.
        try {
            host.run(compileOrFail(wrap("throw new IllegalStateException(\"from the script\");\n")),
                    Map.of());
            fail("the script's exception did not propagate");
        } catch (IllegalStateException expected) {
            assertEquals("from the script", expected.getMessage());
        }
    }

    @Test
    public void aScriptThatDidNotCompileRefusesToRun() throws Throwable {
        ScriptHost.Compiled broken = host.compile(wrap("this is not java;\n"));
        assertFalse(broken.successful());
        try {
            host.run(broken, Map.of());
            fail("ran a script that did not compile");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("did not compile"));
        }
    }

    // ── Re-run replaces ─────────────────────────────────────────────────────────────────────────

    @Test
    public void aReRunReplacesTheInstanceRatherThanAccumulating() throws Throwable {
        String sink = Sink.class.getCanonicalName();
        ScriptHost.Compiled first = compileOrFail(wrap(sink + ".write(\"one\");\n"));
        ScriptHost.Compiled second = compileOrFail(wrap(sink + ".write(\"two\");\n"));

        host.run(first, Map.of());
        host.run(second, Map.of());

        // Two runs, two effects, in order -- and crucially the first version is not still around to be
        // triggered again. Hot swap is a non-goal (§22); replacement is the whole model.
        assertEquals(List.of("one", "two"), Sink.WRITTEN);
    }

    @Test
    public void eachRunGetsItsOwnLoaderSoTwoVersionsAreTwoTypes() throws Throwable {
        ScriptHost.Compiled first = compileOrFail(wrap("int x = 1;\n"));
        ScriptHost.Compiled second = compileOrFail(wrap("int x = 2;\n"));
        host.run(first, Map.of());
        host.run(second, Map.of());

        // Not directly observable from the host's API on purpose -- the loaders are private and
        // collectable. What IS observable is that both ran without a duplicate-class error, which two
        // versions in one loader could not do.
        assertNotSame(first.key(), second.key());
    }

    // ── Stop ────────────────────────────────────────────────────────────────────────────────────

    @Test(timeout = 30_000)
    public void aStopInterruptsADeliberateInfiniteLoop() throws Throwable {
        // THE ONE THAT JUSTIFIES THE SAFEPOINT PASS. This loop never blocks, so interrupt() alone does
        // nothing to it -- only an injected check inside the loop can end it. Without Safepoints this
        // test hangs until the timeout.
        String sink = Sink.class.getCanonicalName();
        ScriptHost.Compiled spinner = compileOrFail(wrap(
                "while (true) { " + sink + ".tick(); }\n"));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = host.runAsync(spinner, Map.of(), (ref, error) -> failure.set(error));

        // Let it genuinely get going, so the stop lands mid-loop rather than before the first iteration.
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Sink.LOOPS.get() < 1000 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue("the script never started looping", Sink.LOOPS.get() > 0);

        assertTrue("there was nothing to stop", host.stop());
        thread.join(10_000);

        assertFalse("the loop is still running — the safepoint check never fired", thread.isAlive());
        assertNull("stopping reported a failure; it is not one", failure.get());
    }

    @Test(timeout = 30_000)
    public void aStopAlsoReachesAScriptThatIsBlocked() throws Throwable {
        // The other half, from the same interrupt(): a blocked script throws InterruptedException from
        // the JDK. One mechanism covers both, which is why the flag is the thread's own.
        ScriptHost.Compiled sleeper = compileOrFail(wrap("Thread.sleep(60000);\n"));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = host.runAsync(sleeper, Map.of(), (ref, error) -> failure.set(error));
        Thread.sleep(200);

        assertTrue(host.stop());
        thread.join(10_000);
        assertFalse("the sleeping script was not woken", thread.isAlive());
    }

    @Test(timeout = 30_000)
    public void stoppingWhenNothingRunsIsHarmless() {
        assertFalse(host.stop());
        assertFalse(host.isRunning());
    }

    @Test(timeout = 30_000)
    public void startingASecondRunStopsTheFirst() throws Throwable {
        // "Re-run replaces" has to hold for a RUNNING script too, or a spinning one would survive
        // every subsequent run and the host would accumulate threads.
        String sink = Sink.class.getCanonicalName();
        Thread first = host.runAsync(
                compileOrFail(wrap("while (true) { " + sink + ".tick(); }\n")), Map.of(), null);

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Sink.LOOPS.get() < 100 && System.nanoTime() < deadline) Thread.sleep(1);

        host.run(compileOrFail(wrap(sink + ".write(\"second\");\n")), Map.of());
        first.join(10_000);

        assertFalse("the first run outlived the second", first.isAlive());
        assertTrue(Sink.WRITTEN.contains("second"));
    }

    // ── Disposal ────────────────────────────────────────────────────────────────────────────────

    @Test(timeout = 120_000)
    public void aHundredCompileRunDisposeCyclesPinNoClassloaders() throws Throwable {
        // M7's heap assertion. Each run gets its own loader; dropping the reference has to make the
        // loader, its classes and their statics collectable. Anything the host retained -- a cached
        // Class, a listener, a thread -- would keep every one of them alive.
        //
        // The script reports its OWN loader, weakly, because ScriptHost exposes no accessor for it and
        // should not: a getter would hand out exactly the reference the design is about not having.
        String sink = Sink.class.getCanonicalName();
        for (int i = 0; i < 100; i++) {
            ScriptHost.Compiled compiled = compileOrFail(wrap(
                    sink + ".recordLoader(getClass().getClassLoader());\n"
                            + sink + ".write(\"run-" + i + "\");\n"));
            host.run(compiled, Map.of());
        }
        assertEquals(100, Sink.WRITTEN.size());
        assertEquals("the scripts did not report their loaders", 100, Sink.LOADERS.size());

        host.stop();
        for (int attempt = 0; attempt < 10 && liveCount(Sink.LOADERS) > 0; attempt++) {
            System.gc();
            Thread.sleep(150);
        }
        assertEquals("script classloaders are being retained: " + liveCount(Sink.LOADERS) + " of 100",
                0, liveCount(Sink.LOADERS));
    }

    private static int liveCount(List<WeakReference<ClassLoader>> references) {
        int alive = 0;
        for (WeakReference<ClassLoader> reference : references) {
            if (reference.get() != null) alive++;
        }
        return alive;
    }

    // ── Caching ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anIdenticalScriptComesBackFromTheCache() {
        ScriptPrelude.Wrapped wrapped = wrap("int x = 1;\n");
        assertFalse("the first compile came from a cache that was empty",
                compileOrFail(wrapped).fromCache());
        assertTrue("the second compile of identical source did not hit the cache",
                compileOrFail(wrap("int x = 1;\n")).fromCache());
    }

    @Test
    public void changedSourceMissesTheCache() {
        compileOrFail(wrap("int x = 1;\n"));
        assertFalse(compileOrFail(wrap("int x = 2;\n")).fromCache());
    }

    @Test
    public void afailedCompileIsNotCached() {
        // Caching a failure would serve it back after the author fixed the file, which reads as the
        // editor refusing to notice an edit.
        assertFalse(host.compile(wrap("not java;\n")).successful());
        assertFalse(host.compile(wrap("not java;\n")).fromCache());
    }

    @Test
    public void aCachedScriptStillRuns() throws Throwable {
        // The cache stores COMPILE output, and remapping and instrumentation happen after it -- so a
        // cache hit has to go through both on the way out. If it did not, a cached script would run
        // uninstrumented and could never be stopped.
        String sink = Sink.class.getCanonicalName();
        compileOrFail(wrap(sink + ".write(\"cached\");\n"));
        ScriptHost.Compiled second = compileOrFail(wrap(sink + ".write(\"cached\");\n"));
        assertTrue(second.fromCache());

        host.run(second, Map.of());
        assertEquals(List.of("cached"), Sink.WRITTEN);
    }

    @Test(timeout = 30_000)
    public void aCachedScriptIsStillInterruptible() throws Throwable {
        // The sharp end of the note above, asserted rather than reasoned about.
        String sink = Sink.class.getCanonicalName();
        String body = "while (true) { " + sink + ".tick(); }\n";
        compileOrFail(wrap(body));
        ScriptHost.Compiled cached = compileOrFail(wrap(body));
        assertTrue(cached.fromCache());

        AtomicBoolean failed = new AtomicBoolean();
        Thread thread = host.runAsync(cached, Map.of(), (ref, error) -> failed.set(true));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Sink.LOOPS.get() < 100 && System.nanoTime() < deadline) Thread.sleep(1);

        host.stop();
        thread.join(10_000);
        assertFalse("a cached script ran uninstrumented and could not be stopped", thread.isAlive());
        assertFalse(failed.get());
    }
}
