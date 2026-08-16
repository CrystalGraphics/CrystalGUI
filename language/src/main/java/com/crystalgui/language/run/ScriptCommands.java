package com.crystalgui.language.run;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Run and Stop, as commands.
 *
 * <h3>Commands rather than buttons, and it is the same argument the editor's actions already made</h3>
 *
 * <p>A command is one named thing that a keybinding, a menu row, the palette and a toolbar button all
 * point at. Wiring a Run button directly to {@link ScriptHost#run} would give exactly one way to run a
 * script — no accelerator, no palette entry, nothing for a keymap to rebind — and the second affordance
 * would then be a second call site that can drift from the first.</p>
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
     * Registers both commands against a host.
     *
     * <p>The script and its bindings come from suppliers rather than being captured, because what "the
     * current script" means belongs to the application — the active editor tab, a selected file, the
     * graph being edited. Capturing one here would bind Run to whatever happened to be open at startup.</p>
     *
     * @param onFailure told about an exception thrown out of a script — a notification, a log line, a
     *                  Problems row. Not handled here: what a failure should look like is the
     *                  application's decision and it differs per host
     * @param onStarted run on the UI thread immediately before a script is launched, and only when one
     *                  actually is. The hook a shell uses to bring its console forward, for the same
     *                  reason {@code onFailure} exists: what should become visible when a script starts
     *                  is the application's decision, and a command that opened a specific tool window
     *                  itself would be a command that only works in one shell. Null for a host with no
     *                  opinion — a test, a headless runner
     */
    public static void register(CommandRegistry registry, ScriptHost host,
                                Supplier<ScriptHost.Compiled> script,
                                Supplier<Map<String, Object>> bindings,
                                Consumer<Throwable> onFailure,
                                @Nullable Runnable onStarted) {
        // IntelliJ's own accelerators, because a Run button people have to find in a palette is a Run
        // button nobody uses. Shift+F10 and Mod+F2 also avoid Mod+R, which the harness already takes for
        // a stylesheet reload — a binding that silently loses to an existing one is worse than none.
        registry.register(Command.of(RUN, "Run Script")
                .binding("Shift+F10")
                .run(context -> {
                    ScriptHost.Compiled compiled = script.get();
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
                        host.runAsync(compiled, bindings == null ? Map.of() : bindings.get(), onFailure);
                    } catch (Throwable failed) {
                        if (onFailure != null) onFailure.accept(failed);
                    }
                }));

        registry.register(Command.of(STOP, "Stop Script")
                .binding("Mod+F2")
                .enabledWhen(context -> host.isRunning())
                .run(host::stop));
    }

    /** Removes both — for a host that is torn down, and for a test that must not leak registrations. */
    public static void unregister(CommandRegistry registry) {
        registry.unregister(RUN);
        registry.unregister(STOP);
    }
}
