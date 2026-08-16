package com.crystalgui.language.engine.bridge;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Running a script, on the engine's side of the bridge.
 *
 * <h3>Why this is not {@code ScriptRuntime}</h3>
 *
 * <p>{@code ScriptRuntime} is what the Run panel holds, and it lives in {@code language.run} — which is
 * <b>not</b> in {@code EngineClassLoader.PARENT_FIRST} and must not be added to it. Widening the shared
 * surface to include the shell would hand the child a view of the panel, the console and the sessions,
 * which is precisely the coupling {@code RunShellIsEngineNeutralTest} refuses from the other side. So
 * the split is: {@code JsHost} is host-side and implements {@code ScriptRuntime}; this is child-side and
 * names Rhino; and everything crossing between them is a JDK type or a bridge interface.</p>
 *
 * <p>That is why the console arrives as two {@link Consumer}s rather than as a {@code RunConsole}, why
 * input is a {@link Supplier} rather than {@code ScriptInput}, and why the sandbox is a
 * {@link Predicate} rather than the host's policy object — each is the JDK-typed shadow of something the
 * child may not see. {@code JsHost} does the wiring on its side, inside the {@code ScriptOutput}
 * bracket it already owns.</p>
 *
 * <h3>The stop is an {@code Error}, and that is not this interface's choice</h3>
 *
 * <p>{@link #stop()} arms Rhino's instruction observer to throw. It throws a {@code java.lang.Error}
 * because Rhino's interpreter treats one as uncatchable by a script's own {@code catch} while still
 * running {@code finally} — which is the same reason the Java side's {@code ScriptStoppedException} is
 * an {@code Error}, arrived at from the other direction. The child never names that class (it is the
 * shell's); {@code JsHost} translates.</p>
 */
public interface JsExecutor {

    /**
     * Parses and compiles, without running anything.
     *
     * <p>Never throws for a syntax error — the messages come back on the result, because a refused
     * compile is an ordinary outcome the shell reports rather than an exception it has to catch. The
     * analyser has usually already reported the same problems on the same offsets; this is the gate that
     * stops a broken script starting.</p>
     */
    Compiled compile(String sourceName, String source);

    /**
     * Runs a compiled script <b>on the calling thread</b>, in a fresh scope.
     *
     * <p>Fresh, because that is what "a re-run replaces the previous instance" means in a language with
     * no classloader to drop: nothing the last run defined is reachable from this one. The thread is the
     * caller's for the same reason the Java side's is — whoever calls this has already bracketed the
     * output marker and reported the state, and a runtime that started its own thread would leave both
     * describing a different one.</p>
     *
     * @param bindings what the host puts in scope, by name
     * @param out      a line the script printed at ordinary level — {@code console.log}, {@code print}
     * @param err      a line at error level — {@code console.error}
     * @param readLine blocks until the host has a line, for {@code readLine()}; may be null
     * @param allowsClass the sandbox, as the only question it is ever asked: may a script reach this
     *                    binary name? Consulted at call time by Rhino's own shutter, which is what makes
     *                    the JS refusal real rather than advisory
     */
    Object run(Compiled script, Map<String, Object> bindings,
               Consumer<String> out, Consumer<String> err,
               Supplier<String> readLine, Predicate<String> allowsClass) throws Throwable;

    /**
     * Asks the running script to stop, and returns immediately.
     *
     * <p>Never joins: a script that refuses to stop must not be able to block the thread that asked it
     * to. Reaches a spinning script through the instruction observer and a blocked one through the
     * thread's interrupt status, which is the pair the Java side already relies on.</p>
     */
    void stop();

    /**
     * The 1-based line of the script currently executing on the <b>calling</b> thread, or {@code -1}.
     *
     * <p>Asked at every emitted console line, so it must be cheap. What answers it is a per-band
     * question ({@code plan_m10.md} §9.4): the direct accessor is package-private on all three bands, so
     * the routes are an {@code EvaluatorException} constructed on the script thread, or a same-package
     * accessor shaded beside Rhino. Answering {@code -1} is legitimate and costs only the column that
     * names a row's origin.</p>
     */
    int currentLine();

    /**
     * A compilation, and whether it may run.
     *
     * <p>Deliberately not the compiled {@code Script} itself: that is a Rhino type, and the whole point
     * of the bridge is that the host holds a handle it cannot look inside.</p>
     */
    interface Compiled {

        /** Whether this compiled. A refused compile never runs. */
        boolean successful();

        /** What the engine said, when it did not. Empty when {@link #successful()}. */
        List<String> messages();
    }
}
