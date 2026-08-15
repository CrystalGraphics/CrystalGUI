package com.crystalgui.language.run;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.ui.elements.workbench.decoration.FileDecorations;

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
     * @param host the script host this workspace runs through, so its state transitions reach the rail.
     *             Null for a workbench that shows a console somebody else fills
     */
    public static RunPanel install(Workbench workbench, RunConsole console, RunSessions sessions,
                                   @Nullable ScriptHost host) {
        RunPanel panel = new RunPanel().bindTo(console);

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
                editor.requestPointerFocus();
            });
        });

        // THE INDICATOR, which is free once the provider exists: the tree already merges independent
        // contributors and bubbles them to folders.
        FileDecorations decorations = workbench.fileTree().getDecorations();
        decorations.addProvider(new RunDecorations(sessions));
        // AND THE THING THAT MAKES IT VISIBLE. A provider is PULLED during bind, so registering one is
        // only half: without this the row's colour appeared whenever the tree happened to rebind for some
        // unrelated reason, which reads as the mark being intermittent rather than as nothing asking.
        // The same call drives the stripe button's dot, because both are the same question.
        RunIndicators.install(workbench, sessions, decorations);

        if (host != null) host.reportTo(sessions);

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
