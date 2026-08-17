package com.crystalgui.language.run.view;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.language.run.RunState;
import com.crystalgui.language.run.ScriptBindings;
import com.crystalgui.language.run.ScriptCommands;
import com.crystalgui.language.run.ScriptRef;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.language.run.ScriptRuntimes;
import com.crystalgui.language.run.console.ConsoleCommands;
import com.crystalgui.language.run.console.ConsoleFilter;
import com.crystalgui.language.run.console.RunConsole;
import com.crystalgui.language.run.console.RunLevel;
import com.crystalgui.language.run.console.RunMessage;
import com.crystalgui.language.run.console.RunSummary;

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
 * registry precisely so no host has to know which mods are present; a language adds that it can run
 * through {@link ScriptRuntimes}, which is a registry for the same reason. <b>Nothing here names a
 * language.</b> Which file is a script, which runtime compiles it, what a stack frame looks like in its
 * output — every one of those is asked of the runtimes, so the day a second one registers, this class
 * is not edited.</p>
 *
 * <h3>Everything is optional except the workbench</h3>
 *
 * <p>{@link #install} answers null when no runtime is available rather than wiring a dead Run command.
 * A menu row and an accelerator that do nothing teach people the feature is broken, which is worse than
 * their absence teaching them it is unavailable.</p>
 */
public final class ScriptWorkbench implements Closeable {

    private final ScriptRuntimes runtimes;
    private final RunConsole console;
    private final RunSessions sessions;
    private final RunPanel panel;
    private final CommandRegistry registry;

    private ScriptWorkbench(ScriptRuntimes runtimes, RunConsole console, RunSessions sessions,
                            RunPanel panel, CommandRegistry registry) {
        this.runtimes = runtimes;
        this.console = console;
        this.sessions = sessions;
        this.panel = panel;
        this.registry = registry;
    }

    /**
     * Wires scripting into {@code workbench}, or answers null when no language has a runtime.
     *
     * @param cacheRoot where compiled scripts are cached between launches; null for memory only
     */
    @Nullable
    public static ScriptWorkbench install(CommandRegistry registry, Workbench workbench,
                                          @Nullable Path cacheRoot) {
        ScriptRuntimes runtimes = ScriptRuntimes.open(cacheRoot);
        if (runtimes.isEmpty()) return null;

        // THE FILTER CHAIN IS THE CONSOLE'S, not the panel's -- what counts as navigable is a property of
        // the output, and a headless host that keeps a transcript without showing it still wants to know.
        // Each runtime says what in ITS output is a place: a JVM frame for Java, and whatever a Rhino
        // error names for JavaScript, without this class learning either shape.
        RunConsole console = new RunConsole();
        for (ConsoleFilter filter : runtimes.consoleFilters()) console.addFilter(filter);
        RunSessions sessions = new RunSessions();
        runtimes.reportTo(sessions);
        RunPanel panel = RunPanels.install(workbench, console, sessions, runtimes);

        ScriptWorkbench installed =
                new ScriptWorkbench(runtimes, console, sessions, panel, registry);
        ScriptCommands.register(registry, runtimes,
                script -> installed.compileFor(workbench, script),
                ScriptBindings::values,
                installed::report,
                // RUNNING SOMETHING BRINGS THE CONSOLE UP, which is what both references do -- output
                // nobody can see is the same as no output, and a first-time user pressing Run and getting
                // no visible response concludes the button is broken rather than that a panel is shut.
                () -> showConsole(workbench));

        panel.onClearRequested.connect(console::clear);
        // THE CONSOLE'S OWN VERBS, AND ITS RIGHT-CLICK MENU. Registered here rather than in RunPanels
        // because this is where the CommandRegistry is -- and the menu is the reason they are commands at
        // all: a row is built from one, by design, and MenuBuilder being the single path is an invariant
        // this codebase has already paid for.
        ConsoleCommands.register(registry, panel);
        RunPanels.attachContextMenu(registry, panel);
        // THE SUBJECT IS ACCEPTED AND NOT YET USED, which is honest rather than lazy: each runtime holds
        // exactly one live run, so "stop that one" and "stop whatever is running" are the same request
        // and pretending otherwise would be a second code path nothing exercises. The signal carries it
        // so the day a second run can be live, this line is the only one that has to change.
        panel.onStopRequested.connect(script -> runtimes.stopAll());
        // A SCRIPT THAT STOPS TO ASK A QUESTION MUST BE ABLE TO ASK IT. The input row IS the prompt --
        // §9.5.9's own argument for having no label and no empty state -- and a prompt behind a closed
        // panel is not one: the script simply stops, with nothing anywhere saying why.
        console.onDidChange.connect(() -> installed.inputWanted(workbench));
        // RERUN NAMES ITS SUBJECT, and still goes through the command -- the same reason ScriptCommands
        // exists at all: a Run button wired straight to ScriptRuntime.runAsync would be a second, subtly
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
            if (script == null) return;
            RunSessions.Session session = sessions.sessionOf(script);
            if (session == null) return;
            if (session.state() == RunState.RUNNING) {
                console.startRun(script.name());
                return;
            }
            // AND THE CLOSING ONE. Null for a state that is not an ending -- a script that registered
            // handlers is LIVE, not finished, and saying otherwise is the falsehood RunState exists to
            // avoid. @see RunSummary
            console.endRun(script.name(), RunSummary.of(script.name(), session.state(),
                    session.elapsedNanos(System.nanoTime())));
        });
        return installed;
    }

    public ScriptRuntimes runtimes() {
        return runtimes;
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
     * Whether a prompt has already brought the panel up for the wait currently in progress.
     *
     * <p>{@code onDidChange} fires on every line a script prints, so this listener is on the hot path of
     * every burst; the flag is what keeps a waiting script from asking for the panel sixty times a
     * second. Cleared when the wait ends, so the <em>next</em> read asks again — a reader who closed the
     * console between two questions has closed it once, not forever.</p>
     */
    private final AtomicBoolean promptShown = new AtomicBoolean();

    private final JobKey promptKey = JobKey.of(ScriptWorkbench.class, "run-input-prompt");

    /**
     * Brings the console up when something blocks reading {@code System.in}.
     *
     * <p><b>⚠ Called on the script's own thread</b>, like everything else {@code RunConsole} announces —
     * so it may touch nothing the engine owns, and opening a tool window builds widgets. The hop is
     * {@link JobScheduler}, whose {@code onDone} is documented to run on the UI thread during
     * {@code drain()}; {@code RunIndicators} is the reference for the same problem. Pulling per frame is
     * the other safe shape and is unavailable here for the reason the bug exists: a closed panel is a
     * detached one, so nothing inside it is running to do the pulling.</p>
     */
    private void inputWanted(Workbench workbench) {
        if (!console.isAwaitingInput()) {
            promptShown.set(false);
            return;
        }
        if (!promptShown.compareAndSet(false, true)) return;
        JobScheduler.shared()
                .<Boolean>job(promptKey, JobLane.LATENCY, context -> Boolean.TRUE)
                .onDone(ignored -> showConsole(workbench))
                .submit();
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
    private ScriptRuntime.Compiled compileFor(Workbench workbench, @Nullable Resource script) {
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
    private ScriptRuntime.Compiled compileActive(Workbench workbench) {
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
    private ScriptRuntime.Compiled compile(@Nullable CgPath path, TextEditor editor) {
        String name = path == null ? "Script" : path.name();
        // WHICH RUNTIME is the file's language's, and the file's language is the registry's answer -- the
        // same one that chose the editor's tokenizer. A file no runtime claims is refused with the list
        // of what would have worked, which is a better message than "not a Java file" the moment there
        // are two.
        ScriptRuntime runtime = runtimes.forFile(name);
        if (runtime == null) {
            Notifications.warning("Run: " + name + " is not a script this workbench can run ("
                    + runtimes.languageNames() + ")");
            return null;
        }
        ScriptRuntime.Compiled compiled = runtime.compileScript(
                name, editor.buffer().document().toString(), ScriptBindings.types());
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
     * <p><b>Attributed from the run that threw</b>, which the runtime hands over, rather than from
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
        ConsoleCommands.unregister(registry);
        runtimes.close();
    }
}
