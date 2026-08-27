package com.crystalgui.language.run;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.Resource;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Run and Stop, as commands.
 *
 * <h3>Commands rather than buttons, and it is the same argument the editor's actions already made</h3>
 *
 * <p>A command is one named thing that a keybinding, a menu row, the palette and a toolbar button all
 * point at. Wiring a Run button directly to {@link ScriptRuntime#runAsync} would give exactly one way to
 * run a script — no accelerator, no palette entry, nothing for a keymap to rebind — and the second
 * affordance would then be a second call site that can drift from the first.</p>
 *
 * <h3>One Run for every language, and the compilation says which runtime it belongs to</h3>
 *
 * <p>There is no {@code java.run} and {@code js.run}: Shift+F10 means "run what is in front of me" in
 * any language, and a menu row per language would be a menu that grows a row every time a runtime is
 * added. So the source hands back a {@link ScriptRuntime.Compiled}, which knows the runtime that made
 * it, and Run asks that runtime. Stop asks all of them, because "stop whatever is running" is the only
 * meaning Stop has ever had.</p>
 *
 * <h3>Stop is enabled only while something is running, and that is not decoration</h3>
 *
 * <p>{@code enabledWhen} is what makes the menu row dim and the accelerator inert. A Stop that is always
 * live is a Stop that does nothing most of the time, which trains people not to trust it — and the
 * registry <b>dims rather than hides</b> (an invariant this codebase already paid for), so the row stays
 * where it was and simply reads as unavailable.</p>
 */
public final class ScriptCommands {

    public static final String RUN = "script.run";
    public static final String STOP = "script.stop";

    private ScriptCommands() {
    }

    /**
     * Compiles whatever a Run is about — a named script, or whatever the application calls current.
     *
     * <h3>One seam, two questions, because Run is asked both ways</h3>
     *
     * <p>Shift+F10 and the palette mean <em>the thing in front of me</em> and pass null. The rail's Rerun
     * means <em>that file</em> and passes it. Those used to be one supplier that could only answer the
     * first, so Rerun named a script in its tooltip, went dead without a rail selection, and then ran the
     * active editor anyway: select {@code A.java} with {@code B.java} on screen and the button said A and
     * ran B. Nothing failed, which is why it survived a release — the wrong script running looks exactly
     * like the right one running when both print.</p>
     *
     * <p>Answering null is an ordinary outcome, not an error: no file open, not a script, does not
     * compile. Whoever implements this says why, because only it knows.</p>
     */
    @FunctionalInterface
    public interface ScriptSource {

        /** @param script the file to compile, or null for "whatever is current" */
        @Nullable
        ScriptRuntime.Compiled compile(@Nullable Resource script);

        /**
         * The same question, answered when it can be — which for a real host is not immediately.
         *
         * <h3>Why a compile cannot be synchronous</h3>
         *
         * <p>A script may name a project file nobody has open, and reading one is a round trip to the
         * workspace. On an in-memory workspace — the harness, every test — that read completes inside the
         * call and a synchronous answer is correct, which is why this defaults to {@link #compile}. On a
         * real host it is a message to a server, delivered by the frame loop; a compile running ON the
         * frame thread is therefore waiting for an answer only it can deliver, and can only time out.
         * That is the whole of the difference between the two hosts: the harness resolved a cold file and
         * mc1710 reported {@code cannot be resolved} for it, forever.</p>
         *
         * <p>{@code onReady} is called on the UI thread — a caller opens tool windows from it — and is
         * called exactly once, with null for every ordinary refusal ({@link #compile}'s own contract).</p>
         */
        default void compileAsync(@Nullable Resource script,
                                  java.util.function.Consumer<ScriptRuntime.Compiled> onReady) {
            onReady.accept(compile(script));
        }
    }

    /**
     * Registers both commands against a host.
     *
     * <p>The script and its bindings come from a source rather than being captured, because what "the
     * current script" means belongs to the application — the active editor tab, a selected file, the
     * graph being edited. Capturing one here would bind Run to whatever happened to be open at startup.</p>
     *
     * @param onFailure told about an exception thrown out of a script, <b>with the run it came from</b> —
     *                  a notification, a log line, a Problems row. Not handled here: what a failure
     *                  should look like is the application's decision and it differs per host.
     *                  <p>The {@link ScriptRef} is carried rather than left to the host to remember,
     *                  because a host remembering it gets it wrong: the obvious field is written when a
     *                  compile <em>starts</em>, so pressing Run on a file that does not build re-labels
     *                  the script that is still running, and its next exception is reported against a
     *                  file that never ran. Null when the run had no source attached</p>
     * @param onStarted run on the UI thread immediately before a script is launched, and only when one
     *                  actually is. The hook a shell uses to bring its console forward, for the same
     *                  reason {@code onFailure} exists: what should become visible when a script starts
     *                  is the application's decision, and a command that opened a specific tool window
     *                  itself would be a command that only works in one shell. Null for a host with no
     *                  opinion — a test, a headless runner
     */
    public static void register(CommandRegistry registry, ScriptRuntimes runtimes,
                                ScriptSource source,
                                Supplier<Map<String, Object>> bindings,
                                @Nullable BiConsumer<ScriptRef, Throwable> onFailure,
                                @Nullable Runnable onStarted) {
        // IntelliJ's own accelerators, because a Run button people have to find in a palette is a Run
        // button nobody uses. Shift+F10 and Mod+F2 also avoid Mod+R, which the harness already takes for
        // a stylesheet reload — a binding that silently loses to an existing one is worse than none.
        registry.register(Command.of(RUN, "Run Script")
                .binding("Shift+F10")
                .run(context -> source.compileAsync(subjectOf(context), compiled -> {
                    if (compiled == null || !compiled.successful()) return;
                    // AFTER the compile check, so a file that did not build does not summon an empty
                    // console -- its errors belong to Problems, which is where both references send them.
                    // Before `runAsync` rather than off the session signal, and that is the whole point:
                    // a session change is emitted on the SCRIPT's thread, and opening a tool window from
                    // there would build widgets off the UI thread. Here we are still inside the command,
                    // on the thread that invoked it.
                    if (onStarted != null) onStarted.run();
                    try {
                        // ASYNC, ALWAYS. Running on the UI thread would mean a script with a slow loop
                        // freezes the frame that is meant to offer the Stop button -- so the one
                        // affordance that could rescue the situation is the one that cannot be reached.
                        // THE RUNTIME THAT COMPILED IT, not "the" runtime -- there is one per language.
                        compiled.runtime().runAsync(compiled,
                                bindings == null ? Map.of() : bindings.get(), onFailure);
                    } catch (Throwable failed) {
                        if (onFailure != null) onFailure.accept(compiled.ref(), failed);
                    }
                })));

        registry.register(Command.of(STOP, "Stop Script")
                .binding("Mod+F2")
                .enabledWhen(context -> runtimes.isAnyRunning())
                .run(runtimes::stopAll));
    }

    /** Both commands against a single runtime — a test, or a host with exactly one language. */
    public static void register(CommandRegistry registry, ScriptRuntime runtime,
                                ScriptSource source,
                                Supplier<Map<String, Object>> bindings,
                                @Nullable BiConsumer<ScriptRef, Throwable> onFailure,
                                @Nullable Runnable onStarted) {
        register(registry, ScriptRuntimes.of(runtime), source, bindings, onFailure, onStarted);
    }

    /**
     * The file a Run was asked about, or null for "the current one".
     *
     * <p>{@code args} is the binding payload {@link CommandContext} already carries — VS Code's own
     * {@code "args"} field — so naming a subject needs no second command and no new plumbing. Anything
     * that is not a {@link Resource} is treated as absent rather than refused: a keymap can attach
     * whatever it likes to a binding, and a Run that threw on an unexpected payload would be a command
     * a keymap could break.</p>
     */
    @Nullable
    private static Resource subjectOf(@Nullable CommandContext context) {
        return context != null && context.args() instanceof Resource script ? script : null;
    }

    /** Removes both — for a host that is torn down, and for a test that must not leak registrations. */
    public static void unregister(CommandRegistry registry) {
        registry.unregister(RUN);
        registry.unregister(STOP);
    }
}
