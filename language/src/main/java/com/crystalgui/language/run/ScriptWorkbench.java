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

        RunConsole console = new RunConsole();
        RunSessions sessions = new RunSessions();
        RunPanel panel = RunPanels.install(workbench, console, sessions, host);

        ScriptWorkbench installed =
                new ScriptWorkbench(host, console, sessions, panel, registry);
        ScriptCommands.register(registry, host,
                () -> installed.compileActive(workbench),
                ScriptBindings::values,
                installed::report);

        panel.onClearRequested.connect(console::clear);
        panel.onStopRequested.connect(host::stop);

        // THE BOUNDARY AND THE STOP BUTTON'S ENABLEMENT, both driven off the state the host already
        // reports rather than off the Run command. That matters for the second one: a script can also
        // end without anybody pressing anything, and a Stop enabled by the command and disabled by
        // nothing would stay live over a finished run -- a dead control that looks alive, which is what
        // `ScriptCommands` gave `enabledWhen` to Stop to avoid in the menu.
        sessions.onDidChange.connect(script -> {
            if (script != null && sessions.stateOf(script) == RunState.RUNNING) {
                console.startRun(script.name());
            }
            panel.setStoppable(!sessions.active().isEmpty());
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
