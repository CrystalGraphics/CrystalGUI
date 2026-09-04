package com.crystalgui.workbench.extension;

import javax.annotation.Nullable;

import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.text.TextPoint;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.document.Document;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;

/**
 * The Problems panel and its status count, as a feature a manifest can enable.
 *
 * <p>The panel, the marker indexing that fills it and the status-bar entry that summarises it are one
 * feature and were three places: a registration in {@code Workbench}, a {@code ProblemsBinding} beside
 * it, and a {@code problemCountEntry} field on the engine. A product that has no use for a Problems
 * panel got all three.</p>
 *
 * <h3>Every kind of document, not every open TEXT document</h3>
 *
 * <p>{@link #index} attaches whatever a document reports. It used to index the open text documents'
 * sets, so a graph left the panel empty by construction while its compiler produced a dozen attributed
 * errors with nowhere to go; a kind that reports none simply answers null.</p>
 *
 * <h3>Subscribing from {@code activate} is now correct, and was not</h3>
 *
 * <p>The engine did this on ATTACH, with a comment explaining that a workbench subscribing from its
 * constructor "stayed subscribed and kept writing its own entry into the one static bar — one per test
 * in the suite". Both halves of that have since gone: the status bar is per workbench (D4), and an
 * extension's handle is disposed with the workbench, so there is nothing left to accumulate. What
 * remains is one subscription per live feature, released when the feature is.</p>
 */
public final class ProblemsExtension implements WorkbenchExtension {

    public static final String ID = "crystalgui:problems";

    /** The panel type id — a session record and a stripe button both name it. */
    public static final String TYPE = "problems";

    /** Reveals the panel. What a failing status readout points at. */
    public static final String SHOW = "workbench.showProblems";

    /** Left of the caret readout and right of the branch: the workspace, then the file. */
    public static final int COUNT_PRIORITY = 200;

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public ProblemsExtension() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        ProblemsPanel panel = new ProblemsPanel();
        Live live = new Live(workbench, panel);

        Disposable registration = workbench.registerToolWindow(
                ToolWindowKind.of(TYPE, "Problems")
                        .icon("crystalgui:toolwindows/problems")
                        .anchor(DockDropZone.SPLIT_DOWN)
                        .view(ctx -> panel)
                        .toggle(SHOW)
                        .openByDefault());

        live.bind();
        return () -> {
            live.close();
            registration.dispose();
        };
    }

    /** What the feature holds while it is on: the index wiring and the one status entry. */
    private static final class Live {

        private final WorkbenchContext workbench;
        private final ProblemsPanel panel;
        private final ConnectionGroup lifetime = new ConnectionGroup();

        @Nullable
        private StatusBarEntryAccessor countEntry;

        Live(WorkbenchContext workbench, ProblemsPanel panel) {
            this.workbench = workbench;
            this.panel = panel;
        }

        void bind() {
            lifetime.add(workbench.documents().onDidOpen.connect(this::index));
            for (Document already : workbench.documents().all()) index(already);
            lifetime.add(workbench.markers().onDidChange.connect(resource -> refreshCount()));
            // WHICH FILE IS IN FRONT, told on every tab change whether or not the filter is on -- so
            // switching "Show Active File Only" on narrows to what you are looking at NOW rather than to
            // whatever happened to be in front when you last switched it off.
            lifetime.add(workbench.onDidOpenDocument().connect(path -> follow()));
            follow();
            refreshCount();

            // BOTH HANDLERS ARE INLINE, and deliberately not folded into one openAndReveal(CgPath, TextPoint).
            //
            // That helper reads as the obvious de-duplication and gives this class a navigation API in terms
            // of a text POSITION -- which is knowledge a workbench has no business holding. It arranges panels
            // and owns documents; where a caret goes inside one is the editor's affair, and a method here
            // taking a TextPoint invites every future caller to route text navigation through the shell.
            lifetime.add(panel.onProblemChosen.connect(node -> {
                if (node.diagnostic() == null || node.resource() == null || !node.resource().isProject()) return;
                TextPoint at = node.diagnostic().start();
                // AS THE CONTINUATION OF THE OPEN, not as the statement after it. openFile is asynchronous for
                // a file that is not already on screen -- it returns before the read has come back -- so
                // positioning on the next line acted on the editor from BEFORE the click. That is correct for
                // a problem in the file you are already looking at and wrong for every other, which is why it
                // read as intermittent rather than as broken.
                workbench.openFile(node.resource().asPath(), () -> {
                    TextEditor editor = workbench.activeEditor();
                    if (editor == null) return;
                    editor.revealAt(at);
                    UIDocument window = workbench.document();
                    if (window != null) window.focus().requestFocus(editor);
                });
            }));

            // SHOW QUICK-FIXES IS NAVIGATE PLUS ONE STEP, and it is spelled out here for the same reason the
            // handler above is: which editor and what to do with it is the caller's business. The panel has
            // no editor and must not reach for one -- it asks, and this answers.
            //
            // The list is opened INSIDE the continuation, after the caret has been placed: the actions are
            // resolved from an offset, so asking before the file is open and positioned would ask about
            // wherever the previous editor's caret happened to be.
            lifetime.add(panel.onQuickFixesRequested.connect(node -> {
                if (node.diagnostic() == null || node.resource() == null || !node.resource().isProject()) return;
                TextPoint at = node.diagnostic().start();
                workbench.openFile(node.resource().asPath(), () -> {
                    TextEditor editor = workbench.activeEditor();
                    if (editor == null) return;
                    editor.revealAt(at);
                    UIDocument window = workbench.document();
                    if (window != null) window.focus().requestFocus(editor);
                    editor.showCodeActionsAt(editor.getCaret());
                });
            }));
        }

        private void index(Document document) {
            DiagnosticSet problems = document.diagnostics();
            if (problems != null) workbench.markers().attach(document.resource(), problems);
        }

        /**
         * Points the panel at this workspace's index.
         *
         * <p>Bound <b>once</b>, not per tab. It used to re-point at the active document's set on every
         * tab change, which is what made it a second opinion about the file already on screen; the index
         * is the whole workspace, so switching tabs changes nothing about what it should show, and
         * re-binding would rebuild the tree and throw away which files you had expanded.</p>
         */
        private void follow() {
            if (panel.source() == null || panel.source().markers() != workbench.markers()) {
                panel.bindTo(workbench.markers());
            }
            panel.setActiveResource(workbench.activeResource());
        }

        /** The workspace's error and warning totals, as one status entry. */
        private void refreshCount() {
            int errors = workbench.markers().count(DiagnosticSeverity.ERROR);
            int warnings = workbench.markers().count(DiagnosticSeverity.WARNING);
            // WITHDRAWN WHEN THERE IS NOTHING TO SAY, rather than reading "0 errors, 0 warnings". A clean
            // workspace is the normal state, and a permanent zero is a readout you learn to stop seeing.
            if (errors == 0 && warnings == 0) {
                if (countEntry != null) countEntry.dispose();
                countEntry = null;
                return;
            }
            StatusBarEntry entry = new StatusBarEntry("Problems",
                    errors + " " + (errors == 1 ? "error" : "errors")
                            + ", " + warnings + " " + (warnings == 1 ? "warning" : "warnings"),
                    "Problems in the workspace", SHOW,
                    errors > 0 ? StatusBarEntry.Kind.ERROR : StatusBarEntry.Kind.WARNING);
            if (countEntry == null) {
                countEntry = workbench.statusBar().addEntry(entry, "workbench.problems",
                        StatusBarAlignment.LEFT, COUNT_PRIORITY);
            } else {
                countEntry.update(entry);
            }
        }

        void close() {
            lifetime.disconnectAll();
            if (countEntry != null) countEntry.dispose();
            countEntry = null;
        }
    }
}
