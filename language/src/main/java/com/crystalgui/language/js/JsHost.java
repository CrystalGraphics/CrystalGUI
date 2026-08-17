package com.crystalgui.language.js;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.AnalysedLanguageServices;
import com.crystalgui.language.engine.bridge.JsExecutor;
import com.crystalgui.language.run.ConsoleFilter;
import com.crystalgui.language.run.JavaStackFrameFilter;
import com.crystalgui.language.run.RunLevel;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.language.run.RunState;
import com.crystalgui.language.run.ScriptOutput;
import com.crystalgui.language.run.ScriptRef;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.language.run.ScriptStoppedException;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.syntax.Language;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * The JavaScript execution service — {@code ScriptRuntime}, over Rhino.
 *
 * <h3>Host side of a two-sided runtime</h3>
 *
 * <p>This is what the Run panel holds; {@link RhinoExecutor} is what names Rhino, and it is defined by
 * the band loader. The split is the same law {@code ScriptHost} obeys from the Java side, and it is
 * enforced from both ends: {@code language.run} is not parent-first, so the child could not implement
 * {@code ScriptRuntime} even if it wanted to, and {@code RunShellIsEngineNeutralTest} refuses the shell
 * any knowledge of either engine.</p>
 *
 * <p>So everything crossing between them is a JDK type — the console is two {@code Consumer}s, input is
 * a {@code Supplier}, the sandbox is a {@code Predicate}. The wiring to {@code ScriptOutput},
 * {@code ScriptInput} and the policy object all happens on this side.</p>
 *
 * <h3>What a run is</h3>
 *
 * <p>Exactly what a Java run is, minus the loader: a daemon thread named {@code cgui-script-js}, the
 * output marker set around the whole invocation, {@code RunSessions} told {@code RUNNING} and then one
 * of {@code FINISHED}/{@code STOPPED}/{@code FAILED}, the previous run stopped before the next starts,
 * and the live-run reference cleared only if it is still ours. Then one thing Java does not do yet: a
 * failure with a line is handed to the document's services as a <b>runtime problem</b>, so the thrown
 * exception squiggles the line it came from and not only the console row.</p>
 *
 * <h3>Where output goes</h3>
 *
 * <p>{@code console.log} and {@code print} arrive here as text with the level <em>known</em>, and go out
 * through {@code ScriptOutput.write} — the entry point written for exactly a language's own logging
 * binding, which does not need its level inferred from a stream. {@code System.out} inside a Java call
 * from the script is still routed by the marker, so a Java library the script uses prints into the same
 * console. A run with no source attached — a test, a dedicated server — has no marker, and its output
 * goes to the process's own streams, which is where a Java script's goes in the same situation.</p>
 */
public final class JsHost implements ScriptRuntime {

    /** The extension a JavaScript file is named with. @see #compileScript */
    private static final String[] EXTENSIONS = {".js", ".mjs", ".cjs"};

    private final JsExecutor executor;

    /**
     * Where a runtime problem is hopped to the UI thread, or null to report inline (tests, a host with
     * no UI). @see #publishRuntimeProblems
     */
    @Nullable
    private final JobScheduler scheduler;

    /** The live run, if any. Replaced wholesale; never mutated. */
    private final AtomicReference<Running> running = new AtomicReference<>();

    /**
     * Where run states are reported, or null for a host nobody is watching — a dedicated server that
     * runs scripts and has no rail to show them in, or a test.
     */
    @Nullable
    private RunSessions sessions;

    public JsHost(JsExecutor executor) {
        this(executor, null);
    }

    public JsHost(JsExecutor executor, @Nullable JobScheduler scheduler) {
        this.executor = executor;
        this.scheduler = scheduler;
    }

    @Override
    public Language language() {
        return Language.JAVASCRIPT;
    }

    @Override
    public JsHost reportTo(@Nullable RunSessions target) {
        this.sessions = target;
        return this;
    }

    // ── Compile ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Compiles what a Run is about.
     *
     * <p>The file's <b>whole name</b> reaches the engine, extension included — the opposite of the Java
     * side, which strips it to derive a class name. Rhino has no such requirement and puts the name
     * verbatim into every stack frame, so {@code Main.js} is what a runtime error will say and therefore
     * what the console's link filter has to match. Stripping it would break the link and nothing
     * else, which is exactly the kind of fault that survives a release.</p>
     */
    @Override
    public Compiled compileScript(String scriptName, String source, Map<String, String> bindingTypes) {
        String name = scriptName == null || scriptName.isEmpty() ? "script.js" : scriptName;
        // BINDING TYPES ARE IGNORED, and that is not an omission. A binding is declared to the Java
        // compiler as a typed field because Java needs one; JavaScript takes the VALUE at run time and
        // has nothing to declare, so the type half of `ScriptBindings` simply does not apply here. The
        // parameter stays on the seam because the seam serves both.
        return new JsCompiled(this, executor.compile(name, source == null ? "" : source));
    }

    // ── Run ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs a compiled script on the calling thread, replacing whatever was running.
     *
     * <p>The synchronous shape, for a caller that wants the answer and the exception in hand — tests,
     * and a host driving scripts from its own loop. The Run panel uses {@link #runAsync}.</p>
     */
    public Object run(ScriptRuntime.Compiled compiled, Map<String, Object> bindings) throws Throwable {
        Running prepared = prepare(own(compiled), bindings);
        stop();
        running.set(prepared);
        prepared.thread = Thread.currentThread();
        try {
            return prepared.invoke();
        } finally {
            // CLEARED ONLY IF STILL OURS. A run that started another run -- or a stop that landed while
            // this was finishing -- must not have its state wiped by this one's completion.
            running.compareAndSet(prepared, null);
        }
    }

    /**
     * Runs on a fresh daemon thread and returns it, so a caller can {@link #stop} a runaway script.
     *
     * <p>Daemon, because a script that will not die must never be the reason the game cannot exit. That
     * is the backstop under the instruction observer rather than a substitute for it.</p>
     *
     * @param onFailure told which run threw as well as what it threw — the ref is carried for the reason
     *                  {@code ScriptHost.runAsync} records: a failure arrives after the invocation has
     *                  unwound, and "the last thing compiled" is a different file the moment somebody
     *                  presses Run on one that does not build while an older script is still alive
     */
    @Override
    public Thread runAsync(ScriptRuntime.Compiled compiled, Map<String, Object> bindings,
                           @Nullable BiConsumer<ScriptRef, Throwable> onFailure) {
        Running prepared = prepare(own(compiled), bindings);
        stop();
        running.set(prepared);

        ScriptRef ref = compiled.ref();
        Thread thread = new Thread(() -> {
            try {
                prepared.invoke();
            } catch (ScriptStoppedException stopped) {
                // Asked for. Not a failure and not worth reporting as one.
            } catch (Throwable failed) {
                if (onFailure != null) onFailure.accept(ref, failed);
            } finally {
                running.compareAndSet(prepared, null);
            }
        }, "cgui-script-js");
        thread.setDaemon(true);
        prepared.thread = thread;
        thread.start();
        return thread;
    }

    /**
     * A compilation this host made — the only kind it can run.
     *
     * <p>The seam hands back the general type, and the shell hands it straight back; a compilation from
     * a different runtime reaching this method is a wiring fault, named as one rather than as a
     * {@code ClassCastException} deep in the executor.</p>
     */
    private static JsCompiled own(ScriptRuntime.Compiled compiled) {
        if (compiled instanceof JsCompiled) return (JsCompiled) compiled;
        throw new IllegalArgumentException("not a JavaScript compilation: " + compiled);
    }

    private Running prepare(JsCompiled compiled, Map<String, Object> bindings) {
        if (!compiled.successful()) {
            throw new IllegalStateException("cannot run a script that did not compile: "
                    + compiled.messages());
        }
        return new Running(compiled.engineCompiled(), bindings == null ? Map.of() : bindings,
                compiled.ref());
    }

    // ── Stop ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Asks the running script to stop, and forgets it.
     *
     * <p>Reaches a spinning script through Rhino's instruction observer and a blocked one through the
     * thread's interrupt status — the executor does both from one call, by thread. Returns immediately;
     * <b>this does not join</b>, because a script that refuses to stop must not be able to block the
     * thread that asked it to.</p>
     *
     * @return whether there was something to stop
     */
    @Override
    public boolean stop() {
        Running current = running.getAndSet(null);
        if (current == null) return false;
        current.stopRequested = true;
        Thread thread = current.thread;
        if (thread != null) executor.stop(thread);
        return true;
    }

    @Override
    public boolean isRunning() {
        Running current = running.get();
        return current != null && (current.thread == null || current.thread.isAlive());
    }

    /**
     * What in this runtime's output a click can navigate: Rhino's own frames first, then the JVM's.
     *
     * <p>Both, because a JavaScript failure has Java frames under it — Rhino's interpreter, and any Java
     * method the script called — and a link on those is as real as one on {@code Main.js:12}. Rhino's
     * first because its format is the more specific; {@code ScriptRuntimes} keeps one filter of each
     * kind, so the JVM one is not doubled when the Java runtime offers it too.</p>
     */
    @Override
    public List<ConsoleFilter> consoleFilters() {
        return List.of(new RhinoStackFrameFilter(), new JavaStackFrameFilter());
    }

    @Override
    public void close() {
        stop();
    }

    /** Whether {@code fileName} is a script this runtime would compile — for a caller with no registry. */
    public static boolean isJavaScript(@Nullable String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    // ── One run ─────────────────────────────────────────────────────────────────────────────────

    /** One run: its script, its bindings, its file, and the thread it is on. */
    private final class Running {
        private final JsExecutor.Compiled compiled;
        private final Map<String, Object> bindings;
        @Nullable private final ScriptRef ref;
        volatile Thread thread;
        volatile boolean stopRequested;

        Running(JsExecutor.Compiled compiled, Map<String, Object> bindings, @Nullable ScriptRef ref) {
            this.compiled = compiled;
            this.bindings = bindings;
            this.ref = ref;
        }

        private void report(RunState state) {
            if (sessions != null && ref != null) sessions.set(ref.file(), state);
        }

        /**
         * Runs the script with the output marker set for its whole duration, and reports every state.
         *
         * <p>Here rather than at the two call sites, for the reason {@code ScriptHost.Running.invoke}
         * gives: this is the one place a script genuinely begins and ends, and forgetting the bracket is
         * silent — the output goes to the game's log and reads as the console being broken.</p>
         */
        Object invoke() throws Throwable {
            ScriptRef previous = ref == null ? null : ScriptOutput.enter(ref);
            report(RunState.RUNNING);
            // THE LAST RUN'S VERDICT IS WITHDRAWN before this one starts. A squiggle from an exception
            // the previous run threw would otherwise sit under a line this run may sail past.
            publishRuntimeProblems(List.of());
            try {
                Object answer = executor.run(compiled, bindings, this::out, this::err,
                        JsHost::readLineFromSystemIn, name -> true);
                report(RunState.FINISHED);
                return answer;
            } catch (InterruptedException stopped) {
                // THE ENGINE'S SPELLING OF "STOPPED", translated to the shell's. Only when we asked: an
                // interrupt from anywhere else ended a script nobody meant to end, and that is a failure.
                if (stopRequested) {
                    report(RunState.STOPPED);
                    throw new ScriptStoppedException();
                }
                report(RunState.FAILED);
                throw stopped;
            } catch (Throwable failed) {
                if (stopRequested) {
                    // A stop that surfaced as something else -- a Java call that translated the interrupt
                    // into its own exception. Still asked for, still not a failure.
                    report(RunState.STOPPED);
                    throw new ScriptStoppedException();
                }
                report(RunState.FAILED);
                publishFailure(failed);
                throw failed;
            } finally {
                if (ref != null) ScriptOutput.exit(previous);
            }
        }

        private void out(String text) {
            emit(RunLevel.OUT, text);
        }

        private void err(String text) {
            emit(RunLevel.ERROR, text);
        }

        /**
         * A line the script printed, to the console — or to the process's stream when there is none.
         *
         * <p>Split on line breaks because a console row is one line and {@code console.log('a\nb')} is
         * two of them; the {@code System.out} path splits the same way in {@code ScriptOutput}.</p>
         */
        private void emit(RunLevel level, String text) {
            String[] lines = text == null || text.isEmpty() ? new String[]{""} : text.split("\\R", -1);
            boolean routed = ScriptOutput.current() != null;
            for (String line : lines) {
                if (routed) {
                    ScriptOutput.write(level, line);
                } else if (level == RunLevel.OUT) {
                    System.out.println(line);
                } else {
                    System.err.println(line);
                }
            }
        }

        /** A thrown exception, onto its line in the document — if the engine can say which. */
        private void publishFailure(Throwable failed) {
            if (ref == null) return;
            JsExecutor.Failure where = executor.describe(failed);
            if (where == null || !where.hasLine()) return;
            int row = where.line() - 1;
            TextPoint start = new TextPoint(row, Math.max(0, where.column() - 1));
            TextPoint end = new TextPoint(row, Integer.MAX_VALUE);
            publishRuntimeProblems(List.of(new Diagnostic(start, end, DiagnosticSeverity.ERROR,
                    where.message(), RUNTIME_SOURCE, null)));
        }

        /**
         * Hands the runtime's verdict to the document's services, on the UI thread.
         *
         * <p>Through the scheduler, whose {@code onDone} is documented to run on the UI thread during
         * {@code drain()} — the same hop {@code RunIndicators} makes, and for the same reason: this runs
         * on the script's thread, and the services' lanes and listeners are the document's. Inline when
         * there is no scheduler, which is a test or a host with no UI thread to protect.</p>
         */
        private void publishRuntimeProblems(List<Diagnostic> problems) {
            if (ref == null) return;
            Resource file = ref.file();
            AnalysedLanguageServices services = AnalysedLanguageServices.attachedTo(file);
            if (services == null) return;
            if (scheduler == null) {
                services.reportRuntimeProblems(problems);
                return;
            }
            scheduler.<List<Diagnostic>>job(JobKey.of(JsHost.this, "js-runtime-problems"),
                            JobLane.LATENCY, context -> problems)
                    .onDone(services::reportRuntimeProblems)
                    .submit();
        }
    }

    /** What a runtime problem names as its source. The engine's id, qualified: this came from a RUN. */
    static final String RUNTIME_SOURCE = "js-runtime";

    /**
     * One line from {@code System.in} — which is the console's input row while a script is running with a
     * source attached, because {@code ScriptInput} routes by the same marker {@code ScriptOutput} does.
     *
     * <p>Byte by byte rather than through a {@code BufferedReader}, which would read ahead and swallow the
     * rest of a line the next {@code readLine()} was owed — the same short-read contract
     * {@code ScriptInput} keeps from its side. Null at end of input, which is what a stopped wait
     * answers.</p>
     */
    @Nullable
    private static String readLineFromSystemIn() {
        InputStream in = System.in;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean any = false;
        try {
            while (true) {
                int b = in.read();
                if (b < 0) return any ? line.toString(StandardCharsets.UTF_8) : null;
                any = true;
                if (b == '\n') break;
                if (b != '\r') line.write(b);
            }
        } catch (IOException failed) {
            return null;
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    // ── The compiled handle ─────────────────────────────────────────────────────────────────────

    /** The bridge's compilation, wearing the seam's interface. */
    private static final class JsCompiled implements Compiled {

        private final JsHost host;
        private final JsExecutor.Compiled compiled;
        private ScriptRef ref;

        JsCompiled(JsHost host, JsExecutor.Compiled compiled) {
            this.host = host;
            this.compiled = compiled;
        }

        @Override
        public boolean successful() {
            return compiled.successful();
        }

        @Override
        public List<String> messages() {
            return compiled.messages();
        }

        /**
         * Names the file this was compiled from, so its output can be attributed while it runs.
         *
         * <p>The origin is Rhino's own answer, asked through the bridge — a Java script walks JVM frames
         * for its line; this asks the interpreter on the thread that is printing. @see RhinoOrigin</p>
         */
        @Override
        public Compiled withSource(Resource file) {
            this.ref = file == null ? null : new ScriptRef(file, new RhinoOrigin(host.executor));
            return this;
        }

        @Override
        public ScriptRef ref() {
            return ref;
        }

        @Override
        public ScriptRuntime runtime() {
            return host;
        }

        JsExecutor.Compiled engineCompiled() {
            return compiled;
        }
    }
}
