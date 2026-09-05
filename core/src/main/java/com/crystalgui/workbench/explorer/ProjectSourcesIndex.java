package com.crystalgui.workbench.explorer;

import com.crystalgui.document.Document;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.project.SourceRoots;
import com.crystalgui.text.syntax.DocComments;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.Workbench;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>What the language engines are allowed to see of this workspace</b> - the project's own sources, as
 * a snapshot an analysis thread can read.
 *
 * <p>The workbench's own collaborator. It keeps a {@code ProjectIndex} pointed at the crawled file list,
 * each project's source roots and the text of every open buffer, contributes it to the process-wide
 * {@code ProjectSourcesRegistry} while this workbench is alive, and withdraws it when it is disposed -
 * so two workbenches are two projects rather than a fight over one slot.</p>
 *
 * <h3>Snapshots, because the reader is not the frame thread</h3>
 *
 * <p>Everything it hands the index is a snapshot taken on the UI thread and read from inside a compile.
 * That is the whole reason this is a class rather than a few fields: the listing maps and the
 * open-document map are being mutated while the analysis reads, so what crosses is a copy taken at a
 * known moment and re-taken when it moves.</p>
 *
 * <p>It also announces when an unsaved buffer changes what a name resolves to, which is what makes a
 * cross-file reference in an unsaved file resolve at all.</p>
 */
public final class ProjectSourcesIndex {

    private final Workbench workbench;

    public ProjectSourcesIndex(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * A background read landed, so anything that resolved without it is now out of date.
     *
     * <h3>A flag, because this is not the UI thread</h3>
     *
     * <p>It runs on whatever thread the workspace client answers on. Walking the open editors from here
     * would reach {@code setEnabled}-shaped state and end in {@code invalidateStyleMatch()}, adding to
     * {@code StyleEngine}'s dirty-match set while the frame is copying it — the {@code RunSessions} crash
     * exactly, which arrives as an {@code ArrayIndexOutOfBoundsException} out of {@code advanceFrame} with
     * nothing about this class in the trace.</p>
     *
     * <p>So: set, and let {@link #tick} drain it. Coalescing is free and wanted — a workspace crawl fills
     * many files in one frame and they all mean the same single "ask again".</p>
     */
    public void onProjectIndexFilled() {
        workbench.projectSourcesMoved = true;
    }

    /**
     * Re-takes the three snapshots the index reads. UI thread, once a frame.
     *
     * <h3>Nothing here is re-derived unless something says it changed</h3>
     *
     * <p>This runs every frame, so every unguarded line in it is a per-frame cost. {@code knownFiles()}
     * builds a list of <b>every file in the workspace</b>, and the roots walk is over that same list — so
     * taking them unconditionally meant allocating and walking the whole workspace sixty times a second to
     * discover that nothing had happened. {@link WorkspaceTreeSource#indexRevision()} answers that in a
     * field read.</p>
     *
     * <p>Buffers are the same argument one level down: encoding an open document is not free, and
     * {@code refreshDirtyMarkers} doing it for every open file every frame is precisely what
     * {@code Document.onDidChange} was added to stop.</p>
     */
    public void refreshProjectIndexInputs() {
        int revision = workbench.projects().indexRevision();
        boolean workspaceMoved = revision != workbench.lastIndexRevision;
        if (workspaceMoved) {
            workbench.lastIndexRevision = revision;
            List<CgPath> files = workbench.projects().knownFiles();
            workbench.crawledFiles = files;

            Map<String, List<String>> roots = new HashMap<>();
            for (CgPath file : files) {
                if (file == null) continue;
                roots.computeIfAbsent(file.project(),
                        id -> workbench.projects().sourceRootsOf(id));
            }
            workbench.projectRoots = roots;
        }

        // A BIGGER WORKSPACE RESOLVES MORE NAMES, so a crawl that grew is a reason to ask again.
        //
        // This is the case that shipped broken and the hardest to see, because every part of it behaves
        // correctly. A file is opened immediately; the crawl is still walking; the package it imports has
        // not been reached yet, so the index truthfully reports that nothing is declared there and the
        // import is marked unresolvable. The crawl then finds it, the index becomes right, and NOTHING
        // re-runs the analysis that was wrong -- so the error stands for the life of the session while
        // every file opened afterwards resolves perfectly. It reads as a problem with the broken file
        // rather than as a race with a background walk.
        //
        // ONCE IT SETTLES, not on every change. The crawl lands listings continuously at startup, and a
        // debounced job whose trigger fires every frame is a job that never runs -- the first analysis
        // would be pushed back until the whole walk finished. Announcing on the frame AFTER the last
        // change fires at each point the crawl pauses, which is when its answer is worth re-asking.
        if (!workspaceMoved && workbench.workspaceMovedLastFrame) workbench.projectSourcesMoved = true;
        workbench.workspaceMovedLastFrame = workspaceMoved;

        refreshBufferSnapshot();
    }

    /**
     * Re-takes the open documents' text, when it has moved.
     *
     * <p>Its own method because it ends in a guard clause, and a guard clause halfway down a three-part
     * method is a trap for whatever gets added below it — the same reason a paint method may skip the
     * draw but never the method.</p>
     */
    private void refreshBufferSnapshot() {
        List<CgPath> open = workbench.openPaths();
        // THE SET, NOT THE COUNT. Two independent things move this snapshot -- a buffer's content, and
        // which documents are open -- and comparing counts gets the second wrong in BOTH directions. One
        // file closing while another opens leaves the count identical with every entry different, so the
        // snapshot silently keeps a closed document's text, which outranks the file on disk. And a
        // document whose text cannot be encoded never enters the snapshot at all, so the counts differ
        // for as long as it is open and every frame re-encodes every buffer -- the exact per-frame cost
        // this whole shape exists to avoid.
        if (workbench.dirtyBuffers.isEmpty() && workbench.snapshotOver.size() == open.size()
                && workbench.snapshotOver.containsAll(open)) return;

        // REBUILT WHOLE, so a closed document's text leaves with it. A stale entry here outranks the file
        // on disk, so a document that was closed and edited elsewhere would resolve to what it used to say.
        Map<CgPath, String> buffers = new HashMap<>();
        for (CgPath path : open) {
            // ALREADY HELD AND STILL TRUE. `encode()` serialises the whole document, so re-taking it for
            // a file nobody touched is the per-frame cost this shape exists to avoid, paid per keystroke
            // instead -- and paid for every open document rather than the one being typed into. The map
            // is still REBUILT rather than patched, so a closed document's text still leaves with it.
            if (!workbench.dirtyBuffers.contains(path)) {
                String held = workbench.bufferSnapshot.get(path);
                if (held != null) {
                    buffers.put(path, held);
                    continue;
                }
            }
            String text;
            try {
                text = workbench.openBufferText(path);
            } catch (RuntimeException unreadable) {
                // ONE DOCUMENT'S PROBLEM IS NOT THE FRAME'S. Encoding reaches into a live widget, and a
                // document that is closing has already disposed its tokenizer -- asking it for text throws
                // `IllegalStateException: Parser is closed`, which is the same fault
                // `reopeningAClosedFileShowsTheLiveEditor` was written for. Thrown from here it escapes
                // `tick()`, so every later line of the frame is skipped: the dock never attaches the panel
                // it was mid-way through opening, and a REOPENED FILE COMES UP BLANK. Nothing in that
                // symptom points at a cache of source text.
                //
                // Skipped rather than fatal because this cache is best-effort by construction -- `sourceOf`
                // answering null is a supported state that costs one re-analysis, and it is what a file
                // nobody has open already returns.
                continue;
            }
            if (text != null) buffers.put(path, text);
        }
        workbench.bufferSnapshot = buffers;
        workbench.snapshotOver.clear();
        workbench.snapshotOver.addAll(open);
        workbench.dirtyBuffers.clear();
        // The buffer tier moved, so anything that resolved against the old one is stale -- the same
        // announcement a landed read makes, for the same reason.
        workbench.projectSourcesMoved = true;
    }

    /**
     * Tells every open editor that the world outside its document moved.
     *
     * <p>Needed because {@code ProjectSources.sourceOf} answers null for a file nobody has open and
     * schedules a read — so the first analysis after opening a file that names a sibling resolves nothing.
     * Without this the error stands until the author types, which reads as the feature being flaky rather
     * than as one missing signal.</p>
     *
     * <p>Broadcast to every editor rather than to the ones that asked. A services object cannot say which
     * names it failed to resolve, and an analysis is debounced and keyed — so the cost of telling a
     * document that did not care is one coalesced job that finds nothing changed.</p>
     */
    public void announceProjectSourcesMoved() {
        if (!workbench.projectSourcesMoved) return;
        workbench.projectSourcesMoved = false;
        // AND THE TREE, whose rows resolve against the same thing an editor does. A `.java` row shows
        // what the file DECLARES, read through `ProjectSources` -- which answers null for a file nobody
        // has read yet and schedules the read. Without this the row keeps the file-type icon until
        // something else happens to rebind it, so a package's icons appear one at a time as you click
        // around, or never. @see ProjectFileTree#requestRefresh
        // THE LISTING ANNOUNCES; whoever is drawing it redraws. This used to call requestRefresh()
        // on the tree, which is engine code reaching for a panel that may not be enabled.
        //
        // ANNOUNCE, NEVER `invalidateAll()`. What is stale here is what a row DRAWS -- a `.java` row
        // shows what its file declares, and that answer arrives after the row does. The listing itself
        // has not changed, so dropping every directory and re-requesting it from the server is both the
        // wrong instrument and a loop: a re-listing feeds the crawl, the crawl fills the index, filling
        // the index sets the very flag this method is draining, and round it goes -- once per frame,
        // for the life of the screen. It did not even redraw anything, because `invalidateAll` re-fetches
        // without announcing.
        workbench.projects().announceRowsChanged();
        // AND EVERY TAB, for the same reason and one more: a tab's icon is pulled when the tab is BUILT
        // and never re-read, which was correct while it was a function of the file NAME. It is now a
        // function of what the file declares, and that answer arrives later than the tab does.
        workbench.documentTabs.syncTabDecorations();
        for (CgPath path : workbench.openPaths()) {
            TextEditor editor = workbench.editorFor(path);
            if (editor == null || editor.languageServices() == null) continue;
            editor.languageServices().environmentChanged();
        }
    }

    /**
     * <b>Fills in documents that were opened before their language could answer.</b>
     *
     * <p>Services are attached once, when a document is created, and that is right — they hold a compile
     * result about <em>this</em> text and re-creating them would throw one away. It is also why an editor
     * already on screen when an engine band finished downloading stayed dark until it was closed and
     * reopened: {@code JavaLanguage} retries its resolve per document, so a document opened <em>after</em>
     * the band arrived was fine and one opened before it was not, which reads as the feature working for
     * some files and not others.</p>
     *
     * <p><b>Only the nulls.</b> Anything already attached is left alone — replacing a live services object
     * would discard a compile result about text that has not changed, and re-subscribe every listener that
     * hangs off it. Filling a gap is not the same operation as refreshing.</p>
     *
     * <p>On the UI thread, because {@code LanguageRegistry.onCapabilityChanged} is emitted there — see
     * that signal's own note for why an emit from a job would be a different and much worse thing.</p>
     */
    public void attachLateServices() {
        for (Document document : workbench.documents.all()) {
            if (!(document.model() instanceof TextDocumentModel model)) continue;
            if (model.services() != null) continue;
            Resource resource = document.resource();
            LanguageRegistry.Entry entry = LanguageRegistry.forFileName(workbench.opener.languageFileNameOf(resource));
            // THE MODEL'S, so every view of the document gets them at once -- which is the whole reason
            // they moved off the editor. Setting them on one pane of a split left the other analysing
            // against nothing.
            model.setLanguage(entry.language(), DocComments.refining(entry.newTokenizer()),
                    entry.newServices(model.buffer(), resource));
            TextEditor editor = workbench.editorFor(resource);
            if (editor == null) continue;
            editor.setLanguage(model.language());
            editor.setTokenizer(model.tokenizer());
            editor.setLanguageServices(model.services());
        }
    }

}
