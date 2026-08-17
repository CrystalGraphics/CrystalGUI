package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.syntax.Language;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * What the Run panel needs from a language in order to run its scripts — and nothing more.
 *
 * <h3>The seam, and why the shell stops here</h3>
 *
 * <p>Everything above this line — the commands, the console, the rail, the sessions, the running
 * indicator, the input row — is about <em>a script running</em> and never about how. Compile-then-load
 * over a child classloader with safepoints injected is how a Java script runs; a Rhino context with an
 * instruction-count observer is how a JavaScript one will. Both start on an explicit command, both replace
 * their previous run, both can be stopped, both print into the same console. So the shell asks these five
 * questions of a runtime and knows no answer's shape:</p>
 *
 * <ol>
 *   <li>{@link #language() Which files are yours?}</li>
 *   <li>{@link #compileScript Can this source run, and if not, why?}</li>
 *   <li>{@link #runAsync Run it, off the UI thread, and tell me if it throws.}</li>
 *   <li>{@link #stop Stop whatever is running.} / {@link #isRunning()}</li>
 *   <li>{@link #consoleFilters() What in your output is a place a click can go?}</li>
 * </ol>
 *
 * <p>This used to be the Java runtime's concrete class, and the shell was written against it: the Run
 * command took it, the panel asked it whether it was running, the workbench asked {@code JavaLanguage}
 * whether it existed and refused any file not ending in {@code .java}. A second language would have
 * meant a second Run command, a second panel wiring and a second workbench — or a rewrite of all three.
 * The interface is the rewrite done once, before the second language rather than after it.</p>
 *
 * <h3>Compile always, run explicitly, re-run replaces</h3>
 *
 * <p>The lifecycle every runtime keeps, because it is the lifecycle the shell assumes. A script is
 * compiled whenever it is asked to run — the editor's diagnostics come from the engine's own analysis, not
 * from here — and run only when asked, because running is a side effect on a world. And a re-run
 * <b>replaces</b> the previous instance rather than hot-swapping it: hot swap is a non-goal (§22), and
 * replacement is honest about what happened — the old objects are gone, not migrated.</p>
 *
 * <h3>Bindings are offered, and a script uses the part it needs</h3>
 *
 * <p>{@link ScriptBindings} is the registry a mod contributes to, and both halves of it reach the runtime:
 * the declared <em>types</em> at compile time, for a language that needs them (Java's prelude declares a
 * typed field per binding), and the <em>values</em> at run time. A language with no static types ignores
 * the first map; a binding the script never names is skipped rather than refused, so adding a binding to
 * the host cannot break a script written before it existed.</p>
 */
public interface ScriptRuntime extends Closeable {

    /** The language this runs — the same {@link Language} the file's registry entry names. */
    Language language();

    /**
     * Where run states are reported, or null for a runtime nobody is watching.
     *
     * <p>Set on the runtime rather than passed per run, because the states this records are the runtime's
     * own transitions and it is the only thing that sees all of them: a caller sees the run it started,
     * and never sees the {@code STOPPED} that another caller's Stop produced. Optional for the same reason
     * the console is: a dedicated server runs scripts and has no rail to show them in.</p>
     */
    ScriptRuntime reportTo(@Nullable RunSessions sessions);

    /**
     * Compiles a script the way its file names it.
     *
     * @param scriptName   the file's simple name — {@code Main.java}, {@code tick.js}. What the runtime
     *                     derives from it (a class name, a source name for stack traces) is its own
     *                     business, and so is whether the extension is present
     * @param source       the text as it is on screen, unsaved edits included
     * @param bindingTypes {@link ScriptBindings#types()} — ignored by a runtime with nothing to declare
     * @return the compilation, successful or not; never null
     */
    Compiled compileScript(String scriptName, String source, Map<String, String> bindingTypes);

    /**
     * Restricts which Java classes a script may reach. Ignored by a runtime with nothing to restrict.
     *
     * <p>On the runtime rather than passed per run, because the policy is a property of the <b>deployment</b>
     * and every run in it obeys the same one — and because it is also consulted where there is no run at all:
     * a completion list and a hover ask the same question before anything has executed. A default of
     * {@link ScriptPolicy#allowAll} is the harness's posture and a test's; M12's platform sets the real one.</p>
     */
    default ScriptRuntime restrictTo(ScriptPolicy policy) {
        return this;
    }

    /**
     * Runs a compiled script on a fresh daemon thread and returns it, replacing whatever was running.
     *
     * <p>Daemon, because a script that will not die must never be the reason the game cannot exit. That
     * is the backstop under cooperative stopping rather than a substitute for it.</p>
     *
     * @param bindings  {@link ScriptBindings#values()}
     * @param onFailure told which run threw as well as what it threw — the ref is carried because a
     *                  failure arrives after the invocation has unwound, and a host reconstructing it from
     *                  "the last thing I compiled" attributes the trace to the wrong file the moment
     *                  somebody presses Run on one that does not build while an older script is alive
     * @throws Throwable when the script cannot be prepared at all — the shell reports it as a failure of
     *                   the run that was asked for
     */
    Thread runAsync(Compiled compiled, Map<String, Object> bindings,
                    @Nullable BiConsumer<ScriptRef, Throwable> onFailure) throws Throwable;

    /**
     * Asks the running script to stop, and forgets it. Returns immediately — <b>never joins</b>, because
     * a script that refuses to stop must not be able to block the thread that asked it to.
     *
     * @return whether there was something to stop
     */
    boolean stop();

    boolean isRunning();

    /**
     * What in this runtime's output a click can navigate — a stack frame, an error location.
     *
     * <p>Answered by the runtime because the shape is the runtime's: {@code at Foo.method(Foo.java:12)} is
     * a JVM frame, and a Rhino error names {@code script.js#12} in its own way. The console installs every
     * runtime's filters into one chain, so a line is matched by whichever recognises it.</p>
     */
    default List<ConsoleFilter> consoleFilters() {
        return List.of();
    }

    @Override
    void close();

    /**
     * A compilation, and whether it may run.
     *
     * <p>Kept opaque above this line on purpose: what a runtime holds between compile and run — class
     * bytes, a Rhino {@code Script}, a cache key — is exactly the part that differs, and the shell has no
     * question to ask of it except these three.</p>
     */
    interface Compiled {

        /** The <b>compiler's</b> verdict — not "did anything come back". A failed compile never runs. */
        boolean successful();

        /** What went wrong, for a run that was refused. Empty when {@link #successful()}. */
        List<String> messages();

        /**
         * Names the file this was compiled from, so its output can be attributed while it runs.
         *
         * <p><b>On the compiled script rather than on the run call, deliberately.</b> Whoever compiles
         * knows which file the source came from; whoever runs may be a keybinding several layers away
         * that has no idea. Putting it here means the runtime brackets every invocation itself and
         * <em>no caller can forget to</em> — which matters because forgetting is silent: the output simply
         * goes to the game's log instead of the panel, and looks like the console not working.</p>
         *
         * <p>Optional. A host with no console — a test, a dedicated server — never calls it, and nothing
         * routes anywhere. @see ScriptOutput</p>
         */
        Compiled withSource(Resource file);

        /** The file and origin this runs as, or null when {@link #withSource} was never called. */
        @Nullable
        ScriptRef ref();

        /** The runtime that made this — and therefore the one that can run it. */
        ScriptRuntime runtime();
    }
}
