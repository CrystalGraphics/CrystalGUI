package com.crystalgui.language.run;

import com.crystalgui.core.command.CommandContext;
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

    private static final String JAVA = ".java";

    private final ScriptHost host;
    private final RunConsole console;
    private final RunSessions sessions;
    private final RunPanel panel;
    private final CommandRegistry registry;

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
                script -> installed.compileFor(workbench, script),
                ScriptBindings::values,
                installed::report,
                // RUNNING SOMETHING BRINGS THE CONSOLE UP, which is what both references do -- output
                // nobody can see is the same as no output, and a first-time user pressing Run and getting
                // no visible response concludes the button is broken rather than that a panel is shut.
                () -> showConsole(workbench));

        panel.onClearRequested.connect(console::clear);
        panel.onStopRequested.connect(host::stop);
        // RERUN NAMES ITS SUBJECT, and still goes through the command -- the same reason ScriptCommands
        // exists at all: a Run button wired straight to ScriptHost.run would be a second, subtly
        // different way to start a script than the keybinding and the palette.
        panel.onRerunRequested.connect(script -> installed.rerun(workbench, script));

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
        // already computes exactly this every frame, on the right thread, from the same source the Stop
        // COMMAND uses. Pull, not push: a per-frame reader cannot race the frame it reads in.
        sessions.onDidChange.connect(script -> {
            if (script != null && sessions.stateOf(script) == RunState.RUNNING) {
                console.startRun(script.name());
            }
        });
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
     * Brings the console forward without taking the caret out of the editor.
     *
     * <p><b>The restore is the point.</b> {@code showPanel} focuses what it shows, deliberately and
     * correctly — Alt+6 should put you <em>in</em> Problems. But Shift+F10 is not a request to go and
     * look at the console; it is a request to run, issued from the editor, usually mid-edit. IntelliJ
     * draws exactly this line: its Run tool window activates and does not take focus unless "Focus tool
     * window" is switched on. Without the restore, running a script silently ends your typing session.</p>
     */
    private static void showConsole(Workbench workbench) {
        if (workbench.isPanelOpen(RunPanels.RUN_TYPE)) return;
        // ASKED BEFORE, RESTORED AFTER. Only when the editor actually held focus -- a Run started from
        // the file tree or the palette has no caret to return, and taking one there would be theft in
        // the opposite direction.
        TextEditor editor = workbench.activeEditor();
        boolean wasTyping = editor != null && editor.isFocused();
        workbench.showPanel(RunPanels.RUN_TYPE);
        // POINTER focus, never requestFocus: the latter rings, and `:focus-visible` exists to ring
        // KEYBOARD focus and not this.
        if (wasTyping) editor.requestPointerFocus();
    }

    /**
     * Runs one named script again — opening it first if it has been closed since it last ran.
     *
     * <p>The rail lists scripts that ran <em>this session</em>, which is not the same as scripts that are
     * still open. Compiling needs source, and the source that matters is the buffer rather than the file
     * on disk (Run executes what is on screen, unsaved edits included) — so a closed script has to be
     * opened before it can be re-run, and {@code openFile} is asynchronous for a file that is not already
     * there. Hence the continuation: running straight after the call would compile whatever was in front
     * a moment ago, which is the exact bug this method exists to close.</p>
     */
    private void rerun(Workbench workbench, @Nullable Resource script) {
        if (script == null) {
            registry.run(ScriptCommands.RUN);
            return;
        }
        CgPath path = script.asPath();
        if (path == null || workbench.openPaths().contains(path)) {
            registry.run(ScriptCommands.RUN, new CommandContext(null, script));
            return;
        }
        workbench.openFile(path,
                () -> registry.run(ScriptCommands.RUN, new CommandContext(null, script)));
    }

    /**
     * The script a Run is about, compiled — or null, with a reason said out loud.
     *
     * <p>Null names the file in front. Read at invoke time and never captured: "the current script" is a
     * question about the application whose answer changes with every tab switch and every keystroke.</p>
     */
    @Nullable
    private ScriptHost.Compiled compileFor(Workbench workbench, @Nullable Resource script) {
        if (script == null) return compileActive(workbench);

        CgPath path = script.asPath();
        if (path == null) {
            Notifications.warning("Run: " + script.name() + " has no file to read");
            return null;
        }
        // ASKED, NOT documentFor() -- which CREATES a document for any path handed to it. Compiling
        // would then open the file as a side effect, so a toolbar button would grow a tab, and a
        // script deleted since it ran would come back as an empty editor rather than as a refusal.
        if (!workbench.openPaths().contains(path)) {
            Notifications.warning("Run: " + path.name() + " is not open");
            return null;
        }
        TextEditor editor = workbench.editorFor(path);
        if (editor == null) {
            Notifications.warning("Run: " + path.name() + " is not a text file");
            return null;
        }
        return compile(path, editor);
    }

    @Nullable
    private ScriptHost.Compiled compileActive(Workbench workbench) {
        TextEditor editor = workbench.activeEditor();
        if (editor == null) {
            Notifications.warning("Run: no text file is open");
            return null;
        }
        return compile(workbench.activeFilePath(), editor);
    }

    /**
     * One compile, whichever way the script was named.
     *
     * <p>It is the <b>editor's buffer</b> rather than the file on disk, so Run executes what is on screen
     * including unsaved edits — which is what an author expects and what every IDE with a scratch file
     * does.</p>
     */
    @Nullable
    private ScriptHost.Compiled compile(@Nullable CgPath path, TextEditor editor) {
        String name = path == null ? "Script" : path.name();
        if (!name.endsWith(JAVA)) {
            Notifications.warning("Run: " + name + " is not a Java file");
            return null;
        }
        String className = name.substring(0, name.length() - JAVA.length());
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
     *
     * <p><b>Attributed from the run that threw</b>, which {@link ScriptHost} hands over, rather than from
     * a "last compiled" field this used to keep. That field was written when a compile <em>began</em>,
     * so pressing Run on a file with errors re-labelled the script still running, and its next exception
     * arrived in the transcript under a filename that had never executed a line — filtered to the wrong
     * rail row, and navigating to the wrong file.</p>
     */
    private void report(@Nullable ScriptRef script, Throwable failure) {
        Notifications.error("Script failed: " + failure);
        String name = script == null ? "script" : script.name();
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        for (String line : trace.toString().split("\\R")) {
            if (!line.isBlank()) console.append(RunMessage.of(name, RunLevel.ERROR, line));
        }
    }

    @Override
    public void close() throws IOException {
        ScriptCommands.unregister(registry);
        host.close();
    }
}
