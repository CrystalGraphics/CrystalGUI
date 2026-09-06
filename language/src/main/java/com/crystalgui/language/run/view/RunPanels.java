package com.crystalgui.language.run.view;

import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.ClipboardCommands;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.document.DocumentState;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.language.run.exec.ScriptInput;
import com.crystalgui.language.run.exec.ScriptOutput;
import com.crystalgui.language.run.ScriptRuntimes;
import com.crystalgui.language.run.console.ConsoleCommands;
import com.crystalgui.language.run.console.ConsoleFilter;
import com.crystalgui.language.run.console.ConsoleSettings;
import com.crystalgui.language.run.console.RunConsole;
import com.crystalgui.text.TextPoint;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.widget.texteditor.EditorCommands;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.decoration.FileDecorations;

import javax.annotation.Nullable;

/**
 * Puts the Run console into a workbench — the whole wiring, in one call.
 *
 * <h3>Registered from outside, because the dependency runs the other way</h3>
 *
 * <p>{@code Workbench} constructs and registers its own panels — Project, Problems, Notifications — and
 * cannot do the same for this one: it lives in {@code core/}, this lives in {@code language/}, and
 * {@code core/} must never depend on {@code language/}. So the registration happens <em>into</em> the
 * workbench rather than inside it, through the public {@code panels()} registry.</p>
 *
 * <p>That is the shape {@code JavaLanguage.register()} already uses for language services, and it has
 * the same property: an application with this module absent gets a workbench with no Run panel and no
 * gap where one should be, rather than a hole {@code core/} has to know about.</p>
 *
 * <h3>Four things, and forgetting any one of them fails quietly</h3>
 *
 * <p>Which is the argument for this class existing at all rather than four lines in a host: a panel with
 * no output routed to it looks like a console that does not work, and a host that registers the panel
 * but not the decorations gets an indicator that never appears.</p>
 */
public final class RunPanels {

    /** The dock type id, matching {@code Workbench}'s own naming for its panels. */
    public static final String RUN_TYPE = "run";

    private RunPanels() {
    }

    /**
     * Registers the panel, routes output into it, and marks running files.
     *
     * @param runtimes the runtimes this workspace runs through, so "is anything running" is answered by
     *                 the things that would be stopped. Null for a workbench that shows a console somebody
     *                 else fills
     */
    public static RunPanel install(WorkbenchContext workbench, RunConsole console, RunSessions sessions,
                                   @Nullable ScriptRuntimes runtimes) {
        RunPanel panel = new RunPanel().bindTo(console).bindSessions(sessions);
        // ONE SOURCE FOR "IS ANYTHING RUNNING", and it is the runtimes — the same objects `script.stop`'s
        // own `enabledWhen` asks. The panel used to answer this from RunSessions instead, which is a
        // different question: a stop that has been asked for and not yet obeyed leaves the runtime with
        // nothing to stop while the session stays RUNNING until the thread dies, so the menu row greyed
        // and the button stayed red over a run that was already gone. A console filled by somebody else
        // has no runtime and therefore nothing this panel could stop.
        panel.setStoppableWhen(runtimes == null ? () -> false : runtimes::isAnyRunning);
        // THE EMPTY STATE NAMES WHAT WOULD WORK. "Open a .java file" was true for exactly as long as Java
        // was the only language that could run; the languages are asked, so a second runtime changes the
        // caption without anyone remembering to.
        panel.setRunnableLanguages(runtimes == null ? "" : runtimes.languageNames());
        // NAMED AND OPTED IN, which is the whole of remembering soft wrap between launches. The id ties
        // a stored payload to the widget; UIDocument hands it its state as it joins the tree, so a
        // restored setting is applied before the first frame rather than after it.
        panel.setId(RunPanel.PANEL_ID);
        panel.set(Attribute.SESSION_PERSISTENT, true);

        // BESIDE PROBLEMS, and for the reason Workbench gives for its own anchors: closing a panel and
        // reopening it from the activity bar should land it back where it was rather than somewhere
        // merely legal. A console belongs under the editor, which is where both references put it.
        workbench.panels().register(
                DockPanelDescriptor.singleton(RUN_TYPE, "Run")
                        .icon("crystalgui:toolwindows/run")
                        .anchor(DockDropZone.SPLIT_DOWN),
                ref -> panel);

        // A STACK FRAME IS A PLACE, so a row that names one opens it -- spelled out here rather than
        // through a workbench helper, which `Workbench` deliberately does not have. Its own note says
        // why: a method there taking a TextPoint would give the shell a navigation API in terms of a
        // text position, "which is knowledge a workbench has no business holding". The shared primitive
        // is openFile(path, continuation) and the rest is the caller's business.
        panel.onLinkActivated.connect((entry, link) -> {
            Resource file = resolve(entry, link, sessions);
            if (file == null || !file.isProject()) return;
            TextPoint at = new TextPoint(link.line() - 1, 0);
            // AS THE CONTINUATION, not as the next statement. openFile is asynchronous for a file that is
            // not already on screen, so revealing straight after it acts on the editor from before the
            // click -- correct for a file you are already looking at and wrong for every other, which is
            // why that class of bug reads as intermittent rather than as broken.
            workbench.openFile(file.asPath(), () -> {
                TextEditor editor = workbench.activeEditor();
                if (editor == null) return;
                editor.revealAt(at);
                // AND FOCUS GOES WITH IT. Navigating puts the caret on the line and leaves it somewhere
                // you cannot type: the console is itself a TextEditor and took focus on the press that
                // started this, so without the hand-off the file scrolls into view under a caret owned by
                // the panel below it. Jumping to source is a request to work there.
                //
                // POINTER focus, not programmatic. requestFocus rings -- `:focus-visible` exists to ring
                // KEYBOARD focus and not clicks -- so the programmatic one would outline the whole editor
                // viewport every time a link was followed. It also scrolls, which would fight the
                // revealAt above rather than agree with it.
                editor.document().focus().requestPointerFocus(editor);
            });
        });

        // A SCRIPT THAT NO LONGER EXISTS IS NOT A SCRIPT THIS WORKSPACE HAS RUN. `RunSessions.forget`
        // has existed since the map did and nothing ever called it, so a deleted file kept its rail row,
        // its elapsed time and its Rerun -- a button offering to run a file that is gone, which fails at
        // the compile with a message about the file not being open rather than about it not being there.
        //
        // A RENAME COUNTS TOO, and it is the case that would have been missed: the run under the old name
        // is over, and `Operation.source()` is the only place the old path still exists by the time this
        // fires. Both come off `onDidRun` rather than `onWillRun` -- a delete the server refuses must not
        // take the row with it.
        //
        // THROUGH THE DOCUMENT STORE, not through this client's own operations. A file can be deleted or
        // renamed by anybody -- another player, a git checkout, an editor outside the game -- and the
        // server reports all of them the same way. Listening to what THIS client did covered one case
        // of three, and it read as the panel being right because the case it covered is the one you
        // test by hand.
        workbench.documents().onDidChangeState.connect((document, state) -> {
            if (state == DocumentState.ORPHANED) sessions.forget(document.resource());
        });
        workbench.documents().onDidOpen.connect(document ->
                // A RENAME MOVES THE RUN, it does not end it. The session is about the SCRIPT, and a
                // script that was renamed while it was running is still running -- ending it there
                // dropped the transcript, the elapsed time and the Stop button for a run that was still
                // going, with the process left with nothing pointing at it.
                document.onDidChangeResource.connect(sessions::retarget));

        // REMOVING A SCRIPT TAKES ITS OUTPUT WITH IT, and that is the whole point of the verb: the
        // complaint it answers is a console that fills up with runs you have finished reading. Dropping
        // the row alone would leave those lines under All output with nothing to filter them to -- worse
        // than before, because the transcript would then hold text the rail could not account for.
        //
        // Here rather than in the panel because this is where both models are in scope; the panel is a
        // view over them and does not get to decide what a "remove" touches. @see RunPanel#onRemoveRequested
        panel.onRemoveRequested.connect(script -> {
            if (script == null) return;
            sessions.forget(script);
            console.forget(script.name());
        });

        // THE CONSOLE'S OWN PREFERENCES. Declared here rather than from ScriptWorkbench because a host
        // that shows a console somebody else fills still wants to size its buffer.
        ConsoleSettings.declare();

        // THE INDICATOR, which is free once the provider exists: the tree already merges independent
        // contributors and bubbles them to folders.
        FileDecorations decorations = workbench.decorations();
        decorations.addProvider(new RunDecorations(sessions));
        // AND THE THING THAT MAKES IT VISIBLE. A provider is PULLED during bind, so registering one is
        // only half: without this the row's colour appeared whenever the tree happened to rebind for some
        // unrelated reason, which reads as the mark being intermittent rather than as nothing asking.
        // The same call drives the stripe button's dot, because both are the same question.
        RunIndicators.install(workbench, sessions, decorations);

        if (runtimes != null) runtimes.reportTo(sessions);

        // LAST, because it replaces System.out process-wide and everything above is inert if it throws.
        // Installing before the panel is registered would leave output arriving at a console nobody can
        // open, which is the one failure here that looks like the feature working.
        ScriptOutput.install(console);
        // AND THE READING HALF. Same marker, same reasoning, opposite direction: a script's System.in
        // waits for the panel's input row, and everybody else's -- the game's, another mod's -- goes to
        // the real stream untouched. Redirecting it wholesale would park the game on a text field.
        ScriptInput.install(console);
        return panel;
    }

    /**
     * Gives the transcript a right-click menu — the editor's own verbs, then the console's.
     *
     * <h3>Copy is spliced in, never re-declared</h3>
     *
     * <p>The transcript <em>is</em> a {@code TextEditor}, so {@code editor.copy} and {@code
     * editor.selectAll} already work on it and already resolve their target the way every other editor
     * command does. Registering console-flavoured twins would be the mistake this codebase has a named
     * invariant about — one command asking the position, never one per widget — and it fails visibly:
     * two Copy rows, one of which is greyed. {@code ContextMenu} composes fixed items with contributed
     * ones, which is exactly the seam for this.</p>
     *
     * <p>The console's own three are <em>contributed</em> rather than listed, so a later addition to
     * {@link ConsoleCommands} appears here without this method changing — which is the property the whole
     * {@code MenuId} design exists to buy.</p>
     */
    static void attachContextMenu(CommandRegistry registry, RunPanel panel) {
        ContextMenu.attach(panel.view().element(), registry, element -> ContextMenu.builder()
                .item(EditorCommands.PREFIX + "copy")
                .item(EditorCommands.PREFIX + "selectAll")
                .contributions(ConsoleCommands.CONTEXT));

        // AND THE RAIL'S ROWS, which are about a script rather than about the transcript.
        //
        // The list's own default menu is declined first: `attach` keeps one live menu per attachment
        // site, but two attachments on one element are two listeners and BOTH would open -- so Copy is
        // spliced in here instead, from the same command the default installer would have used.
        ListView<Resource> rows = panel.rail().list();
        rows.suppressDefaultContextMenu();
        ContextMenu.attach(rows, registry, element -> {
            int index = rows.indexOfRowElement(element);
            if (index < 0) return null;
            // THE ROW THE POINTER NAMED, told to both: the list needs it so Copy acts on that row rather
            // than on the selection, and the rail needs it so Remove does. Neither moves the selection --
            // a right-click says what it is about and leaves what you were reading alone.
            rows.setContextRow(index);
            panel.rail().setContextIndex(index);
            return ContextMenu.builder()
                    .item(ClipboardCommands.COPY)
                    .contributions(ConsoleCommands.RAIL_CONTEXT);
        });
    }

    /**
     * Turns a frame's bare {@code RunTest.java} into a file, or null when it names nothing we have.
     *
     * <h3>Two candidates, cheapest first, and no workspace scan</h3>
     *
     * <p>The line's own <b>origin</b> is tried first because it is nearly always the answer: a trace is
     * usually printed by the script it happened in. Then the scripts this session knows about, which
     * covers a trace crossing from one script into another. Neither touches the file system.</p>
     *
     * <h3>Refusing is the correct answer, not a gap</h3>
     *
     * <p>Most frames in a real trace are JDK or engine code, and those genuinely have nowhere to go. A
     * resolver that guessed — searching the workspace for any file with a matching name — would open the
     * wrong file confidently, which is worse than a frame that does not navigate. IntelliJ links only what
     * is in the project for the same reason.</p>
     */
    @Nullable
    static Resource resolve(RunConsole.Line line, ConsoleFilter.Link link, RunSessions sessions) {
        if (line != null && matches(line.file(), link.fileName())) return line.file();
        for (Resource script : sessions.scripts()) {
            if (matches(script, link.fileName())) return script;
        }
        return null;
    }

    private static boolean matches(@Nullable Resource resource, String fileName) {
        if (resource == null) return false;
        CgPath path = resource.asPath();
        return path != null && fileName.equals(path.name());
    }
}
