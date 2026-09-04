package com.crystalgui.workbench;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.document.Document;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;

/**
 * Extracted from {@link Workbench}. See the plan's §4.5 for why this cluster is one thing.
 */
final class ProblemsBinding {

    private final Workbench workbench;

    ProblemsBinding(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * Puts a document's problems into the workbench's marker index.
     *
     * <p>Every kind of document, which is the change: this indexed the open TEXT documents' sets, so a
     * graph left the Problems panel empty by construction while its compiler produced a dozen attributed
     * errors with nowhere to go. A kind that reports none simply answers null.</p>
     */
    void indexProblemsOf(Document document) {
        DiagnosticSet problems = document.diagnostics();
        if (problems != null) workbench.markers.attach(document.resource(), problems);
    }

    /** The workspace's error and warning totals, as one status entry. @see Markers */
    void refreshProblemCount() {
        int errors = workbench.markers.count(DiagnosticSeverity.ERROR);
        int warnings = workbench.markers.count(DiagnosticSeverity.WARNING);
        // WITHDRAWN WHEN THERE IS NOTHING TO SAY, rather than reading "0 errors, 0 warnings". A clean
        // workspace is the normal state, and a permanent zero is a readout you learn to stop seeing.
        if (errors == 0 && warnings == 0) {
            if (workbench.problemCountEntry != null) workbench.problemCountEntry.dispose();
            workbench.problemCountEntry = null;
            return;
        }
        StatusBarEntry entry = new StatusBarEntry("Problems",
                errors + " " + (errors == 1 ? "error" : "errors")
                        + ", " + warnings + " " + (warnings == 1 ? "warning" : "warnings"),
                "Problems in the workspace", Workbench.SHOW_PROBLEMS,
                errors > 0 ? StatusBarEntry.Kind.ERROR : StatusBarEntry.Kind.WARNING);
        if (workbench.problemCountEntry == null) {
            workbench.problemCountEntry = workbench.statusBar().addEntry(entry, "workbench.problems",
                    StatusBarAlignment.LEFT, Workbench.PROBLEM_COUNT_PRIORITY);
        } else {
            workbench.problemCountEntry.update(entry);
        }
    }

    /**
     * Keeps the panel pointed at this workspace's index.
     *
     * <p>Bound <b>once</b>, not per tab. It used to re-point at the active document's set on every tab
     * change, which is what made it a second opinion about the file already on screen; the index is the
     * whole workspace, so switching tabs changes nothing about what it should show. Re-binding would also
     * rebuild the tree and throw away which files you had expanded.</p>
     */
    void rebindProblems() {
        if (workbench.problems.source() == null || workbench.problems.source().markers() != workbench.markers) {
            workbench.problems.bindTo(workbench.markers);
        }
        // WHICH FILE IS IN FRONT, told on every tab change whether or not the filter is on -- so switching
        // "Show Active File Only" on narrows to what you are looking at now rather than to whatever
        // happened to be in front when you last switched it off.
        workbench.problems.setActiveResource(workbench.activeResource());
    }

}
