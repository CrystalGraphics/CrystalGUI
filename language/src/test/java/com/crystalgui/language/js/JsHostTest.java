package com.crystalgui.language.js;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.JsExecutor;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.run.console.ConsoleFilter;
import com.crystalgui.language.run.console.RunConsole;
import com.crystalgui.language.run.ScriptCommands;
import com.crystalgui.language.run.exec.ScriptInput;
import com.crystalgui.language.run.ScriptRef;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.language.run.ScriptRuntimes;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.syntax.Language;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>The JavaScript execution service — {@code ScriptHostTest}'s twin, one claim each.</b>
 *
 * <p>A script runs and its effect is observable; a re-run replaces the scope; a stop reaches a spinning
 * loop, a script blocked in a Java call, and one blocked reading input; a hundred runs pin nothing. And the
 * things Java has no counterpart for: the console globals format values the way every JavaScript console
 * does, and {@code Java.type} resolves against the host's own loader.</p>
 */
public class JsHostTest {

    /** What a script's side effects land on — reached through {@code Java.type} or as a binding. */
    public static final class Sink {
        public static final List<String> WRITTEN = new ArrayList<>();
        public static final AtomicInteger LOOPS = new AtomicInteger();
        public static final List<WeakReference<Object>> SCOPES = new ArrayList<>();

        public static void write(String value) {
            WRITTEN.add(value);
        }

        public static void tick() {
            LOOPS.incrementAndGet();
        }

        /** Weak, so the recording cannot be what keeps a scope alive. */
        public static void recordScope(Object scope) {
            SCOPES.add(new WeakReference<>(scope));
        }

        private Sink() {
        }
    }

    private static final String SINK = "Java.type('" + Sink.class.getName() + "')";

    private JsHost host;

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @Before
    public void openHost() {
        host = new JsHost(JsLanguage.executor());
        Sink.WRITTEN.clear();
        Sink.LOOPS.set(0);
        Sink.SCOPES.clear();
    }

    @After
    public void closeHost() {
        if (host != null) host.close();
    }

    private ScriptRuntime.Compiled compileOrFail(String source) {
        ScriptRuntime.Compiled compiled = host.compileScript("Script.js", source, Map.of());
        assertTrue("the script did not compile: " + compiled.messages(), compiled.successful());
        return compiled;
    }

    // ── It runs ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aScriptRunsAndItsEffectIsObservable() throws Throwable {
        host.run(compileOrFail(SINK + ".write('hello from the script');\n"), Map.of());
        assertEquals(List.of("hello from the script"), Sink.WRITTEN);
    }

    /**
     * {@code Java.type} resolves against the <b>host's</b> loader — the one that defined this test.
     *
     * <p>The executor is defined by the band loader, child-first, and if it handed Rhino its own loader
     * as the application loader then a class the script named would be the child's copy: a second
     * {@code Sink} with its own static list, and the assertion above would see nothing written while the
     * script wrote happily into a class nobody else can see. That the list has an entry is this claim.</p>
     */
    @Test
    public void javaTypeResolvesTheHostsOwnClass() throws Throwable {
        host.run(compileOrFail("var type = " + SINK + "; type.write(String(type));\n"), Map.of());
        assertEquals(1, Sink.WRITTEN.size());
    }

    @Test
    public void aScriptSeesTheHostBindings() throws Throwable {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", "bound");
        values.put("count", 3);
        values.put("Sink", Sink.class);
        host.run(compileOrFail("Sink.write(label + '-' + count);\n"), values);
        assertEquals(List.of("bound-3"), Sink.WRITTEN);
    }

    @Test
    public void aScriptsOwnExceptionReachesTheCallerAndSaysWhere() throws Throwable {
        ScriptRuntime.Compiled compiled = compileOrFail("var a = 1;\nthrow new Error('from the script');\n");
        try {
            host.run(compiled, Map.of());
            fail("the script's exception did not propagate");
        } catch (Throwable expected) {
            JsExecutor.Failure where = JsLanguage.executor().describe(expected);
            assertNotNull("the engine did not recognise its own exception: " + expected, where);
            assertEquals("Script.js", where.sourceName());
            assertEquals(2, where.line());
            assertEquals("Error: from the script", where.message());
        }
    }

    @Test
    public void aJavaExceptionThrownThroughTheScriptIsDescribedToo() throws Throwable {
        ScriptRuntime.Compiled compiled = compileOrFail("Java.type('java.lang.Integer').parseInt('x');\n");
        try {
            host.run(compiled, Map.of());
            fail("the Java exception did not propagate");
        } catch (Throwable expected) {
            JsExecutor.Failure where = JsLanguage.executor().describe(expected);
            assertNotNull(where);
            assertEquals(1, where.line());
            assertTrue(where.message(), where.message().contains("NumberFormatException"));
        }
    }

    @Test
    public void aScriptThatDidNotCompileRefusesToRun() {
        ScriptRuntime.Compiled broken = host.compileScript("Script.js", "function (", Map.of());
        assertFalse(broken.successful());
        try {
            host.run(broken, Map.of());
            fail("ran a script that did not compile");
        } catch (Throwable expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("did not compile"));
        }
    }

    // ── Re-run replaces ─────────────────────────────────────────────────────────────────────────

    @Test
    public void aReRunGetsAFreshScope() throws Throwable {
        // "Replace" in a language with no loader: nothing the previous run defined is reachable.
        host.run(compileOrFail("var leftover = 'from the first run';\n"), Map.of());
        host.run(compileOrFail(SINK + ".write(typeof leftover);\n"), Map.of());
        assertEquals(List.of("undefined"), Sink.WRITTEN);
    }

    // ── Stop ────────────────────────────────────────────────────────────────────────────────────

    @Test(timeout = 30_000)
    public void aStopInterruptsADeliberateInfiniteLoop() throws Throwable {
        // THE ONE THAT JUSTIFIES THE INSTRUCTION OBSERVER. This loop never blocks, so interrupt() alone
        // does nothing to it -- only Rhino looking at the flag between instructions can end it.
        ScriptRuntime.Compiled spinner = compileOrFail("var s = " + SINK + "; while (true) { s.tick(); }\n");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = host.runAsync(spinner, Map.of(), (ref, error) -> failure.set(error));

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Sink.LOOPS.get() < 1000 && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue("the script never started looping", Sink.LOOPS.get() > 0);

        assertTrue("there was nothing to stop", host.stop());
        thread.join(10_000);
        assertFalse("the loop is still running — the observer never fired", thread.isAlive());
        assertNull("stopping reported a failure; it is not one", failure.get());
    }

    @Test(timeout = 30_000)
    public void aStopCannotBeCaughtByTheScript() throws Throwable {
        // The reason the stop is an Error: Rhino refuses a script's catch one, so no try can swallow it.
        ScriptRuntime.Compiled spinner = compileOrFail(
                "var s = " + SINK + ";\n"
                        + "while (true) { try { s.tick(); } catch (e) { s.write('caught'); } }\n");
        Thread thread = host.runAsync(spinner, Map.of(), null);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Sink.LOOPS.get() < 100 && System.nanoTime() < deadline) Thread.sleep(1);

        host.stop();
        thread.join(10_000);
        assertFalse("the catch swallowed the stop", thread.isAlive());
        assertFalse("the script's catch saw the stop", Sink.WRITTEN.contains("caught"));
    }

    @Test(timeout = 30_000)
    public void aStopAlsoReachesAScriptBlockedInAJavaCall() throws Throwable {
        // The other half, from the same call: a blocked script is woken by the interrupt.
        ScriptRuntime.Compiled sleeper = compileOrFail("Java.type('java.lang.Thread').sleep(60000);\n");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = host.runAsync(sleeper, Map.of(), (ref, error) -> failure.set(error));
        Thread.sleep(200);

        assertTrue(host.stop());
        thread.join(10_000);
        assertFalse("the sleeping script was not woken", thread.isAlive());
        assertNull("a stop is not a failure, whichever way it arrived", failure.get());
    }

    @Test(timeout = 30_000)
    public void aStopAlsoReachesAScriptWaitingForInput() throws Throwable {
        // readLine() blocks on the console's input row, which is System.in routed by the marker -- so
        // the script needs a source attached, and the stream needs routing.
        InputStream realIn = System.in;
        RunConsole console = new RunConsole().attach(new TextBuffer());
        System.setIn(ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console));
        try {
            ScriptRuntime.Compiled reader = compileOrFail(
                    "var line = readLine();\n" + SINK + ".write('after: ' + line);\n")
                    .withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Script.js"));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = host.runAsync(reader, Map.of(), (ref, error) -> failure.set(error));

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!console.isAwaitingInput() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue("the script never asked for input", console.isAwaitingInput());

            assertTrue(host.stop());
            thread.join(10_000);
            assertFalse("the waiting script was not woken", thread.isAlive());
            assertNull(failure.get());
            // AND IT DID NOT RUN ON with a null in hand: the stop ended it at the read.
            assertTrue("the script continued past a stopped read: " + Sink.WRITTEN,
                    Sink.WRITTEN.isEmpty());
        } finally {
            System.setIn(realIn);
        }
    }

    @Test(timeout = 30_000)
    public void aScriptCanReadALineTheConsoleWasGiven() throws Throwable {
        InputStream realIn = System.in;
        RunConsole console = new RunConsole().attach(new TextBuffer());
        System.setIn(ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console));
        try {
            ScriptRuntime.Compiled reader = compileOrFail(
                    "var line = readLine();\n" + SINK + ".write('got ' + line);\n")
                    .withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Script.js"));
            Thread thread = host.runAsync(reader, Map.of(), null);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!console.isAwaitingInput() && System.nanoTime() < deadline) Thread.sleep(2);
            console.submitInput("typed");
            thread.join(10_000);
            assertEquals(List.of("got typed"), Sink.WRITTEN);
        } finally {
            System.setIn(realIn);
        }
    }

    @Test(timeout = 30_000)
    public void stoppingWhenNothingRunsIsHarmless() {
        assertFalse(host.stop());
        assertFalse(host.isRunning());
    }

    @Test(timeout = 30_000)
    public void startingASecondRunStopsTheFirst() throws Throwable {
        Thread first = host.runAsync(
                compileOrFail("var s = " + SINK + "; while (true) { s.tick(); }\n"), Map.of(), null);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Sink.LOOPS.get() < 100 && System.nanoTime() < deadline) Thread.sleep(1);

        host.run(compileOrFail(SINK + ".write('second');\n"), Map.of());
        first.join(10_000);
        assertFalse("the first run outlived the second", first.isAlive());
        assertTrue(Sink.WRITTEN.contains("second"));
    }

    @Test(timeout = 30_000)
    public void theSessionsHearEveryState() throws Throwable {
        RunSessions sessions = new RunSessions();
        host.reportTo(sessions);
        Resource file = Resource.of(Resource.SCHEME_PROJECT, "src/Script.js");
        List<String> states = new ArrayList<>();
        sessions.onDidChange.connect(script -> {
            RunSessions.Session session = sessions.sessionOf(script);
            if (session != null) states.add(session.state().name());
        });

        host.run(compileOrFail("var x = 1;\n").withSource(file), Map.of());
        assertEquals(List.of("RUNNING", "FINISHED"), states);

        states.clear();
        try {
            host.run(compileOrFail("throw new Error('x');\n").withSource(file), Map.of());
        } catch (Throwable expected) {
            // reported below
        }
        assertEquals(List.of("RUNNING", "FAILED"), states);
    }

    // ── Disposal ────────────────────────────────────────────────────────────────────────────────

    @Test(timeout = 120_000)
    public void aHundredRunsPinNoScopes() throws Throwable {
        // The heap assertion. Each run gets its own scope, and nothing the host or the executor holds
        // may keep one alive once the run is over -- a retained scope is every binding, every value the
        // script built, and every Java object it touched.
        for (int i = 0; i < 100; i++) {
            host.run(compileOrFail("var s = " + SINK + "; s.recordScope(this); s.write('run-" + i + "');\n"),
                    Map.of());
        }
        assertEquals(100, Sink.WRITTEN.size());
        assertEquals("the scripts did not report their scopes", 100, Sink.SCOPES.size());

        host.stop();
        for (int attempt = 0; attempt < 10 && liveCount(Sink.SCOPES) > 0; attempt++) {
            System.gc();
            Thread.sleep(150);
        }
        assertEquals("script scopes are being retained: " + liveCount(Sink.SCOPES) + " of 100",
                0, liveCount(Sink.SCOPES));
    }

    private static int liveCount(List<WeakReference<Object>> references) {
        int alive = 0;
        for (WeakReference<Object> reference : references) {
            if (reference.get() != null) alive++;
        }
        return alive;
    }

    // ── The console globals ─────────────────────────────────────────────────────────────────────

    /** {@code console.log} through the bridge — the text as the host receives it, level by consumer. */
    private List<String> logged(String source, List<String> errors) throws Throwable {
        List<String> out = new ArrayList<>();
        JsExecutor executor = JsLanguage.executor();
        JsExecutor.Compiled compiled = executor.compile("Format.js", source);
        assertTrue(compiled.messages().toString(), compiled.successful());
        executor.run(compiled, Map.of(), out::add, errors::add, null, name -> true);
        return out;
    }

    @Test
    public void consoleLogFormatsValuesTheWayEveryJavaScriptConsoleDoes() throws Throwable {
        List<String> out = logged(
                "console.log('plain');\n"
                        + "console.log(1, 'two', true, null, undefined);\n"
                        + "console.log([1, 'a', [2]]);\n"
                        + "console.log({ a: 1, b: 'x', 'c-d': { e: 2 } });\n"
                        + "console.log(function named() {});\n"
                        + "console.log(Java.type('java.util.ArrayList'));\n"
                        + "print('printed');\n", new ArrayList<>());
        assertEquals(List.of(
                "plain",
                "1 two true null undefined",
                "[ 1, 'a', [Array] ]",
                "{ a: 1, b: 'x', 'c-d': [Object] }",
                "[Function: named]",
                "[JavaClass java.util.ArrayList]",
                "printed"), out);
    }

    @Test
    public void consoleWarnAndErrorGoToTheErrorConsumer() throws Throwable {
        List<String> errors = new ArrayList<>();
        List<String> out = logged("console.warn('careful');\nconsole.error('broken');\nconsole.info('fyi');\n",
                errors);
        assertEquals(List.of("careful", "broken"), errors);
        assertEquals(List.of("fyi"), out);
    }

    @Test
    public void aJavaStringComesBackAsAJavaScriptString() throws Throwable {
        // The wrap factory's primitive setting: without it `list.get(0)` is a wrapped java.lang.String
        // that is not === 'one' and has no .length.
        List<String> out = logged(
                "var list = new java.util.ArrayList(); list.add('one');\n"
                        + "console.log(list.get(0) === 'one', list.get(0).length);\n", new ArrayList<>());
        assertEquals(List.of("true 3"), out);
    }

    // ── The Run command picks the runtime by file ───────────────────────────────────────────────

    /**
     * {@code ScriptCommands} against two runtimes: the file's language chooses, and the JavaScript one is
     * what runs a {@code .js}. The other runtime is a stub that records being asked, which is enough —
     * {@code ScriptCommandsTest} already proves the command against a real Java host.
     */
    @Test(timeout = 30_000)
    public void theRunCommandPicksTheJavaScriptRuntimeForAJsFile() throws Exception {
        AtomicInteger javaAsked = new AtomicInteger();
        ScriptRuntime java = new StubRuntime(Language.JAVA, javaAsked);
        ScriptRuntimes runtimes = ScriptRuntimes.of(java, host);
        CommandRegistry registry = new CommandRegistry();
        ScriptRuntime.Compiled compiled = compileOrFail(SINK + ".write('via the command');\n");
        ScriptCommands.register(registry, runtimes, asked -> compiled, Map::of, null, null);

        assertSame(host, runtimes.forFile("Main.js"));
        assertSame(java, runtimes.forFile("Main.java"));
        assertTrue(registry.run(ScriptCommands.RUN));

        long deadline = System.nanoTime() + 10_000_000_000L;
        while (Sink.WRITTEN.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(List.of("via the command"), Sink.WRITTEN);
        assertEquals("the Java runtime was asked to run a JavaScript file", 0, javaAsked.get());
        // AND ONE FILTER OF EACH KIND, even though both runtimes offer the JVM one.
        int jvmFilters = 0;
        for (ConsoleFilter filter : ScriptRuntimes.of(host, host).consoleFilters()) {
            if (filter.getClass().getSimpleName().equals("JavaStackFrameFilter")) jvmFilters++;
        }
        assertEquals(1, jvmFilters);
    }

    /** A runtime that only says which language it is. */
    private static final class StubRuntime implements ScriptRuntime {
        private final Language language;
        private final AtomicInteger asked;

        StubRuntime(Language language, AtomicInteger asked) {
            this.language = language;
            this.asked = asked;
        }

        @Override
        public Language language() {
            return language;
        }

        @Override
        public ScriptRuntime reportTo(RunSessions sessions) {
            return this;
        }

        @Override
        public Compiled compileScript(String scriptName, String source, Map<String, String> bindingTypes) {
            asked.incrementAndGet();
            throw new UnsupportedOperationException();
        }

        @Override
        public Thread runAsync(Compiled compiled, Map<String, Object> bindings,
                               BiConsumer<ScriptRef, Throwable> onFailure) {
            asked.incrementAndGet();
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean stop() {
            return false;
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public List<ConsoleFilter> consoleFilters() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
