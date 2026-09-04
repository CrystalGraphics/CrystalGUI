package com.crystalgui.language.run.view;

import com.crystalgui.fs.protocol.ScriptingMode;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.WorkbenchExtension;
import com.crystalgui.workbench.WorkbenchExtensions;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import java.util.List;
import java.util.Map;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.WorkbenchContext;

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
import com.crystalgui.language.run.exec.ScriptRefusedException;

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

    /** What an application's manifest names to enable scripting. @see WorkbenchExtensions */
    public static final String ID = "crystalgui:scripting";

    /**
     * Scripting as a {@link WorkbenchExtension} — one per process, activated per workbench.
     *
     * <p><b>No host installs this any more.</b> Two harness scenes and the 1.7.10 screen each called
     * {@code install(...)} with a cache root they worked out for themselves, which is the shape that
     * decides a feature by which host remembered it: the Run panel was in the harness and in the game
     * and nowhere else, and a fourth host would have had to know to ask. The language module
     * contributes this from {@code LanguageStack.registerAll}, and an application enables it by id.</p>
     */
    public static WorkbenchExtension extension() {
        return new WorkbenchExtension() {
            @Override
            public String id() {
                return ID;
            }

            /**
             * Answers a no-op handle when no language has a runtime — which is this stack's three-tier
             * degradation rather than a failure: a host with no engine band shows the file and offers
             * no Run, and a menu row that does nothing teaches people the feature is broken rather
             * than unavailable.
             */
            @Override
            public Disposable activate(WorkbenchContext workbench) {
                ScriptWorkbench installed = install(CommandRegistry.global(), workbench,
                        workbench.cacheDirectory(CACHE_DIRECTORY));
                if (installed == null) return () -> { };
                return () -> {
                    try {
                        installed.close();
                    } catch (IOException failed) {
                        // TEARDOWN IS EXACTLY WHEN A HALF-FINISHED JOB IS WORST, and a workbench
                        // closing must not be stopped by an engine band that will not shut down.
                        // Said out loud, then dropped.
                        //
                        // System.err rather than the engine's logger, which this module cannot see:
                        // log4j is the host's, and `language/` compiles without it -- the same reason
                        // LanguageStack reports a grammar that will not load this way.
                        System.err.println("[crystalgui] scripting did not close cleanly: " + failed);
                    }
                };
            }
        };
    }

    public static final String CACHE_DIRECTORY = "script-cache";

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
    public static ScriptWorkbench install(CommandRegistry registry, WorkbenchContext workbench,
                                          @Nullable Path cacheRoot) {
        // BEFORE THE RUNTIME CHECK, deliberately. Remapping a file out of the runtime namespace needs the
        // platform's MAPPING and nothing else -- no engine, no analysis, no way to run anything. A host
        // whose engine band failed to open still shows the file and still wants it readable, so gating
        // this on there being a runtime would withdraw it from the one configuration that is already
        // degraded. It is here rather than in each host for the reason LanguageStack exists: which hosts
        // get a feature is a fact about this module.
        MappingCommands.register(registry, workbench);

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
                new ScriptCommands.ScriptSource() {
                    @Override
                    public ScriptRuntime.Compiled compile(Resource script) {
                        return installed.compileFor(workbench, script);
                    }

                    @Override
                    public void compileAsync(Resource script,
                                             java.util.function.Consumer<ScriptRuntime.Compiled> onReady) {
                        installed.compileForAsync(workbench, script, onReady);
                    }
                },
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

    /** @see #compileForAsync */
    private final JobKey compileKey = JobKey.of(ScriptWorkbench.class, "run-compile");

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
    private void inputWanted(WorkbenchContext workbench) {
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
    private static void showConsole(WorkbenchContext workbench) {
        if (workbench.isPanelOpen(RunPanels.RUN_TYPE)) return;
        // ASKED BEFORE, RESTORED AFTER. Only when the editor actually held focus -- a Run started from
        // the file tree or the palette has no caret to return, and taking one there would be theft in
        // the opposite direction.
        TextEditor editor = workbench.activeEditor();
        boolean wasTyping = editor != null && editor.isFocused();
        workbench.showPanel(RunPanels.RUN_TYPE);
        // POINTER focus, never requestFocus: the latter rings, and `:focus-visible` exists to ring
        // KEYBOARD focus and not this.
        if (wasTyping) editor.document().focus().requestPointerFocus(editor);
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
    private void rerun(WorkbenchContext workbench, @Nullable Resource script) {
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
    private ScriptRuntime.Compiled compileFor(WorkbenchContext workbench, @Nullable Resource script) {
        if (script == null) return compileActive(workbench);

        CgPath path = script.asPath();
        if (path == null) {
            Notifications.warning("Run: " + script.name() + " has no file to read");
            return null;
        }
        if (!mayRunHere(workbench, path)) return null;
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
    private ScriptRuntime.Compiled compileActive(WorkbenchContext workbench) {
        TextEditor editor = workbench.activeEditor();
        if (editor == null) {
            Notifications.warning("Run: no text file is open");
            return null;
        }
        return compile(workbench.activeFilePath(), editor);
    }

    /**
     * The compile, <b>off the frame thread</b> — which is the only place it can succeed.
     *
     * <h3>Two threads, and each does the part only it may do</h3>
     *
     * <p>Everything a compile needs from the application is UI-owned: which files are open, which editor
     * holds one, and its buffer. So the SNAPSHOT is taken here, on the frame thread, exactly as before.
     * The compile itself is a pure function of that snapshot, and {@code AGENTS.md} is explicit that such
     * work belongs on {@link JobScheduler} — the same rule the {@code ui-budget} guard prints when it
     * catches one.</p>
     *
     * <p>It is not a tidiness point. A script naming a project file nobody has open needs that file READ,
     * and on a real host that is a round trip whose answer the frame loop delivers. Compiling on the frame
     * thread therefore waits for a message only it can deliver: the read times out, the type never
     * resolves, and the run fails with {@code cannot be resolved} on a file the editor reports as clean.
     * It worked in the harness throughout, because an in-memory workspace answers inside the call.</p>
     *
     * <p>{@code onDone} runs on the UI thread during {@code drain()}, which is what lets the refusal
     * notification and the console below stay where they are.</p>
     */
    private void compileForAsync(WorkbenchContext workbench, @Nullable Resource script,
                                 java.util.function.Consumer<ScriptRuntime.Compiled> onReady) {
        Snapshot snapshot = snapshotFor(workbench, script);
        if (snapshot == null) {
            onReady.accept(null);
            return;
        }
        JobScheduler.shared()
                .<ScriptRuntime.Compiled>job(compileKey, JobLane.LATENCY,
                        context -> snapshot.runtime.compileScript(
                                snapshot.name, snapshot.source, snapshot.bindings))
                .onDone(compiled -> onReady.accept(finish(snapshot, compiled)))
                .submit();
    }

    /** What a compile needs from the application, read on the thread that owns it. */
    private static final class Snapshot {

        private final ScriptRuntime runtime;
        private final String name;
        private final String source;
        private final Map<String, String> bindings;
        @Nullable private final CgPath path;

        Snapshot(ScriptRuntime runtime, String name, String source,
                 Map<String, String> bindings, @Nullable CgPath path) {
            this.runtime = runtime;
            this.name = name;
            this.source = source;
            this.bindings = bindings;
            this.path = path;
        }
    }

    /** The UI-thread half of a run: which file, which runtime, and what it says right now. */
    @Nullable
    /**
     * Whether this workbench may run {@code path} <b>here</b>, and a refusal that says why if not.
     *
     * <p>Asked at the ONE place both routes pass through — Shift+F10 and the palette compile whatever
     * is in front, the rail's Rerun names a file, and both arrive at a snapshot. Asking at the command
     * instead would leave the rail's route open, and asking in each would be two answers to one
     * question.</p>
     *
     * <p>What it enforces is a <em>server's</em> statement about its own files: on a dedicated server
     * the Run command compiles the buffer in front and executes it in the PLAYER's JVM, which is a live
     * scripting environment inside every client reachable from any project they can edit. A stock
     * client offers none of that unless the server says so. It stops nothing on a modified client, and
     * nothing can — {@code ScriptPolicy}'s javadoc says the trust model is the answer.</p>
     */
    private static boolean mayRunHere(WorkbenchContext workbench, @Nullable CgPath path) {
        if (path == null) return true;
        ScriptingMode mode = workbench.workspace().capabilities().scriptingMode(Resource.of(path));
        if (mode.allowsLocalRun()) return true;
        Notifications.show(Notification.warning("Run: " + path.name() + " may not be run here")
                .withDetail(mode == ScriptingMode.AUTHORIZED
                        ? "This server runs scripts itself and sends what it has checked. Nothing "
                                + "compiled here will execute."
                        : "No server speaks for this project while you are connected to another one."));
        return false;
    }

    private Snapshot snapshotFor(WorkbenchContext workbench, @Nullable Resource script) {
        CgPath path;
        TextEditor editor;
        if (script == null) {
            editor = workbench.activeEditor();
            if (editor == null) {
                Notifications.warning("Run: no text file is open");
                return null;
            }
            path = workbench.activeFilePath();
            if (!mayRunHere(workbench, path)) return null;
        } else {
            path = script.asPath();
            if (path == null) {
                Notifications.warning("Run: " + script.name() + " has no file to read");
                return null;
            }
            if (!mayRunHere(workbench, path)) {
                return null;
            }
            // ASKED, NOT documentFor() -- see compileFor.
            if (!workbench.openPaths().contains(path)) {
                Notifications.warning("Run: " + path.name() + " is not open");
                return null;
            }
            editor = workbench.editorFor(path);
            if (editor == null) {
                Notifications.warning("Run: " + path.name() + " is not a text file");
                return null;
            }
        }

        String name = path == null ? "Script" : path.name();
        ScriptRuntime runtime = runtimes.forFile(name);
        if (runtime == null) {
            Notifications.warning("Run: " + name + " is not a script this workbench can run ("
                    + runtimes.languageNames() + ")");
            return null;
        }
        // THE BUFFER, READ HERE. It is the editor's text rather than the file on disk, so Run executes
        // what is on screen including unsaved edits -- and a buffer may only be read on this thread.
        return new Snapshot(runtime, name, editor.buffer().document().toString(),
                ScriptBindings.types(), path);
    }

    /** The UI-thread half again: refuse with a reason, or name the file the run came from. */
    @Nullable
    private ScriptRuntime.Compiled finish(Snapshot snapshot, @Nullable ScriptRuntime.Compiled compiled) {
        if (compiled == null) return null;
        if (!compiled.successful()) {
            refuse(snapshot.name, compiled);
            return null;
        }
        return snapshot.path == null ? compiled : compiled.withSource(Resource.of(snapshot.path));
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
            refuse(name, compiled);
            return null;
        }
        // THE FILE IT CAME FROM, which is what attributes its output and marks it as running. Built from
        // the CgPath rather than from a scheme and a string: only that carries a path, and a resource
        // without one looks up correctly everywhere and is silently skipped by folder bubbling.
        return path == null ? compiled : compiled.withSource(Resource.of(path));
    }

    /**
     * Says a run was refused, and why when there is a why.
     *
     * <p>ONE of these, because both compile paths reach it and a second copy would drift.</p>
     */
    private static void refuse(String name, ScriptRuntime.Compiled compiled) {
        {
            // THE DIAGNOSTICS USUALLY SAY WHAT IS WRONG, in the editor, on the line -- so the headline
            // stays terse rather than repeating a compiler message in a worse place.
            //
            // BUT A REFUSAL WITH NOTHING TO SHOW IS A DEAD END, and that premise fails more often than it
            // reads. `messages()` is documented as "what went wrong, for a run that was refused" and was
            // being discarded here, so a compile that failed WITHOUT producing diagnostics -- a unit that
            // never reached the requestor, a project source that could not be read in time, an
            // environment that refused a name -- announced only that something had gone wrong, on a file
            // the editor was simultaneously reporting as clean. There was then nowhere left to look: the
            // Problems panel is empty because the analyser is happy, and the one component that knows the
            // reason threw it away. @see ScriptRuntime.Compiled#messages
            //
            // Shown as DETAIL rather than in the headline, so the common case reads exactly as before.
            List<String> why = compiled.messages();
            Notification refused = Notification.error("Run: " + name + " has compile errors");
            if (why != null && !why.isEmpty()) {
                StringBuilder detail = new StringBuilder();
                for (int at = 0; at < why.size() && at < 3; at++) {
                    if (detail.length() > 0) detail.append('\n');
                    detail.append(why.get(at));
                }
                if (why.size() > 3) detail.append("\n(+").append(why.size() - 3).append(" more)");
                refused = refused.withDetail(detail.toString());
            }
            Notifications.show(refused);
        }
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
        String name = script == null ? "script" : script.name();

        // A REFUSAL IS NOT A CRASH, and printing it as one told the author nothing. Every frame in its
        // trace is ours -- it is thrown out of `prepare`, before a line of the script has run -- so the
        // stack was twenty rows of internals under a notification carrying the whole refused list, which
        // is what a balloon is worst at. The list is the entire content, one class per row, in the place
        // the author is already looking; the balloon says only that it happened and how much.
        ScriptRefusedException refused = refusalIn(failure);
        if (refused != null) {
            int count = refused.refused().size();
            Notifications.error(name + ": " + count + (count == 1 ? " class" : " classes")
                    + " refused by the script policy — see the Run console");
            console.append(RunMessage.of(name, RunLevel.ERROR,
                    "Refused before running. This script reaches classes the deployment's "
                            + "ScriptPolicy does not allow:"));
            for (String each : refused.refused()) {
                console.append(RunMessage.of(name, RunLevel.ERROR, "    " + each));
            }
            return;
        }

        Notifications.error("Script failed: " + failure);
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        for (String line : trace.toString().split("\\R")) {
            if (!line.isBlank()) console.append(RunMessage.of(name, RunLevel.ERROR, line));
        }
    }

    /**
     * The refusal anywhere in a failure's cause chain, or null.
     *
     * <p>Walked rather than tested at the top, because a runtime is free to wrap what {@code prepare}
     * threw — the Java host throws it bare today and a future one need not, and a refusal reported as a
     * stack trace because somebody added a wrapper is a regression nobody would look for.</p>
     */
    @Nullable
    private static ScriptRefusedException refusalIn(@Nullable Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            if (at instanceof ScriptRefusedException) return (ScriptRefusedException) at;
            if (at.getCause() == at) break;
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        ScriptCommands.unregister(registry);
        ConsoleCommands.unregister(registry);
        MappingCommands.unregister(registry);
        runtimes.close();
    }
}
