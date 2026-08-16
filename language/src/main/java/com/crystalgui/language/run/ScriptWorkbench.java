package com.crystalgui.language.run;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

/**
 * Scripting, attached to a workbench — the engine, the commands, the console, and the indicator.
 *
 * <h3>This module owns the wiring, not the application</h3>
 *
 * <p>It used to live in the harness, and that was wrong in a way worth naming: the harness is one host
 * among several, and everything it was doing — compile whatever {@code .java} file is in front, run it,
 * route its output, mark it running — is true of <em>any</em> workbench, not of that one. Leaving it
 * there meant a Minecraft loader would have reimplemented all of it, from a class it could not see, and
 * the two would have drifted the first time either changed.</p>
 *
 * <p>So the host supplies the two things only it knows — which workbench, and where to cache — and this
 * supplies the rest. A mod adds what scripts can reach through {@link ScriptBindings}, which is a
 * registry precisely so no host has to know which mods are present.</p>
 *
 * <h3>Everything is optional except the workbench</h3>
 *
 * <p>{@link #install} answers null when no engine is available rather than wiring a dead Run command.
 * A menu row and an accelerator that do nothing teach people the feature is broken, which is worse than
 * their absence teaching them it is unavailable.</p>
 */
public final class ScriptWorkbench implements Closeable {

    private final ScriptHost host;
    private final RunConsole console;
    private final RunSessions sessions;
    private final RunPanel panel;
    private final CommandRegistry registry;

    /**
     * The file the last Run compiled, so a failure can be attributed to it.
     *
     * <p>A failure arrives <em>outside</em> the output marker — {@code runAsync} catches on its own
     * thread after the invocation has unwound — so {@code ScriptOutput} no longer knows whose exception
     * it is. Remembering the last compile is the smallest thing that closes that.</p>
     */
    private volatile String lastScript = "script";

    private ScriptWorkbench(ScriptHost host, RunConsole console, RunSessions sessions,
                            RunPanel panel, CommandRegistry registry) {
        this.host = host;
        this.console = console;
        this.sessions = sessions;
        this.panel = panel;
        this.registry = registry;
    }

    /**
     * Wires scripting into {@code workbench}, or answers null when no engine is present.
     *
     * @param cacheRoot where compiled scripts are cached between launches; null for memory only
     */
    @Nullable
    public static ScriptWorkbench install(CommandRegistry registry, Workbench workbench,
                                          @Nullable Path cacheRoot) {
        if (!JavaLanguage.isAvailable()) return null;

        ScriptHost host = new ScriptHost(JavaLanguage.engine(),
                cacheRoot == null ? ScriptCache.inMemory() : ScriptCache.directory(cacheRoot),
                MappingSet.IDENTITY, "identity",
                ScriptWorkbench.class.getClassLoader(), null);

        // THE FILTER CHAIN IS THE CONSOLE'S, not the panel's -- what counts as navigable is a property of
        // the output, and a headless host that keeps a transcript without showing it still wants to know.
        // Java only for now; M10's JS and M11's GLSL each add one class here and change nothing else.
        RunConsole console = new RunConsole().addFilter(new JavaStackFrameFilter());
        RunSessions sessions = new RunSessions();
        RunPanel panel = RunPanels.install(workbench, console, sessions, host);

        ScriptWorkbench installed =
                new ScriptWorkbench(host, console, sessions, panel, registry);
        ScriptCommands.register(registry, host,
                () -> installed.compileActive(workbench),
                ScriptBindings::values,
                installed::report,
                // RUNNING SOMETHING BRINGS THE CONSOLE UP, which is what both references do -- output
                // nobody can see is the same as no output, and a first-time user pressing Run and getting
                // no visible response concludes the button is broken rather than that a panel is shut.
                //
                // Only when it is CLOSED. `togglePanel` on an open one would hide it, so the second run in
                // a row would take the console away at the exact moment it filled.
                () -> {
                    if (!workbench.isPanelOpen(RunPanels.RUN_TYPE)) {
                        workbench.showPanel(RunPanels.RUN_TYPE);
                    }
                });

        panel.onClearRequested.connect(console::clear);
        panel.onStopRequested.connect(host::stop);
        // RERUN GOES THROUGH THE COMMAND, not through the host directly -- the same reason ScriptCommands
        // exists at all: a Run button wired straight to ScriptHost.run would be a second, subtly different
        // way to start a script than the keybinding and the palette, and the second kind is where "the
        // button works but the shortcut does not" comes from.
        //
        // It re-runs the ACTIVE EDITOR, which is what `script.run` means, so the rail's selection decides
        // whether the button is offered rather than what it targets. Re-running a script that is not on
        // screen would need a compile of a file nobody is looking at, and `compileActive` is explicit that
        // "the current script" is a question about the application.
        panel.onRerunRequested.connect(script -> registry.run(ScriptCommands.RUN));

        // THE RUN BOUNDARY, driven off the state the host already reports rather than off the Run
        // command -- a script can also end without anybody pressing anything.
        //
        // ⚠ THIS RUNS ON THE SCRIPT'S OWN THREAD. `RunSessions` is written by the thread whose run just
        // changed state, so anything here is off the UI thread and may touch NOTHING the engine owns.
        // `startRun` is safe because the transcript is a ConcurrentLinkedQueue drained during the frame;
        // that is the whole reason it is a queue.
        //
        // The Stop button's enablement used to be pushed from here, and it crashed the application on the
        // first press: `setEnabled` ends in `invalidateStyleMatch()`, which added to `StyleEngine`'s
        // dirty-match HashSet while the UI thread was copying it -- `ArrayIndexOutOfBoundsException` out
        // of `HashMap.keysToArray`, thrown in `advanceFrame` with nothing about the Run panel in the
        // trace. It is gone rather than hopped through `JobScheduler` because `RunPanel.refreshActions`
        // already computes exactly this every frame, on the right thread, from the same `RunSessions`.
        // Pull, not push: a per-frame reader cannot race the frame it reads in.
        sessions.onDidChange.connect(script -> {
            if (script != null && sessions.stateOf(script) == RunState.RUNNING) {
                console.startRun(script.name());
            }
        });
        panel.setStoppable(false);
        return installed;
    }

    public ScriptHost host() {
        return host;
    }

    public RunConsole console() {
        return console;
    }

    public RunSessions sessions() {
        return sessions;
    }

    public RunPanel panel() {
        return panel;
    }

    /**
     * The file in front, compiled — or null, with a reason said out loud.
     *
     * <p>Read at invoke time and never captured: "the current script" is a question about the
     * application whose answer changes with every tab switch and every keystroke. It is the
     * <b>editor's buffer</b> rather than the file on disk, so Run executes what is on screen including
     * unsaved edits — which is what an author expects and what every IDE with a scratch file does.</p>
     */
    @Nullable
    private ScriptHost.Compiled compileActive(Workbench workbench) {
        TextEditor editor = workbench.activeEditor();
        if (editor == null) {
            Notifications.warning("Run: no text file is open");
            return null;
        }
        CgPath path = workbench.activeFilePath();
        String name = path == null ? "Script" : path.name();
        if (!name.endsWith(".java")) {
            Notifications.warning("Run: " + name + " is not a Java file");
            return null;
        }

        lastScript = name;
        String className = name.substring(0, name.length() - ".java".length());
        ScriptHost.Compiled compiled = host.compileSource(
                className, editor.buffer().document().toString(), ScriptBindings.types());
        if (!compiled.successful()) {
            // THE DIAGNOSTICS ALREADY SAY WHAT IS WRONG, in the editor, on the line. This says only that
            // the run did not start, because a notification repeating a compiler message is a second
            // report of the same thing in a worse place.
            Notifications.error("Run: " + name + " has compile errors");
            return null;
        }
        // THE FILE IT CAME FROM, which is what attributes its output and marks it as running. Built from
        // the CgPath rather than from a scheme and a string: only that carries a path, and a resource
        // without one looks up correctly everywhere and is silently skipped by folder bubbling.
        return path == null ? compiled : compiled.withSource(Resource.of(path));
    }

    /**
     * A thrown exception, into the console rather than onto the real stderr.
     *
     * <p>Which is the whole point of having a console: {@code printStackTrace} here would go to the
     * game's log, because this runs after the invocation unwound and the output marker is gone.
     * Appending explicitly puts the trace where the author is already looking — one row per frame, so
     * they can be read and navigated individually rather than arriving as one block.</p>
     */
    private void report(Throwable failure) {
        Notifications.error("Script failed: " + failure);
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        for (String line : trace.toString().split("\\R")) {
            if (!line.isBlank()) console.append(RunMessage.of(lastScript, RunLevel.ERROR, line));
        }
    }

    @Override
    public void close() throws IOException {
        ScriptCommands.unregister(registry);
        host.close();
    }
}
