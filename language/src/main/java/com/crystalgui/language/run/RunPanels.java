package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

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
        panel.onEntryActivated.connect(entry -> {
            Resource file = entry.file();
            if (file == null || !file.isProject() || entry.line() <= 0) return;
            TextPoint at = new TextPoint(entry.line() - 1, 0);
            // AS THE CONTINUATION, not as the next statement. openFile is asynchronous for a file that is
            // not already on screen, so revealing straight after it acts on the editor from before the
            // click -- correct for a file you are already looking at and wrong for every other, which is
            // why that class of bug reads as intermittent rather than as broken.
            workbench.openFile(file.asPath(), () -> {
                TextEditor editor = workbench.activeEditor();
                if (editor != null) editor.revealAt(at);
            });
        });

        // THE INDICATOR, which is free once the provider exists: the tree already merges independent
        // contributors and bubbles them to folders.
        workbench.fileTree().getDecorations().addProvider(new RunDecorations(sessions));

        if (host != null) host.reportTo(sessions);

        // LAST, because it replaces System.out process-wide and everything above is inert if it throws.
        // Installing before the panel is registered would leave output arriving at a console nobody can
        // open, which is the one failure here that looks like the feature working.
        ScriptOutput.install(console);
        return panel;
    }

}
