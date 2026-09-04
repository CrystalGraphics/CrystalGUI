package com.crystalgui.workbench;

import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.document.Document;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.InputDialog;
import com.crystalgui.workbench.chrome.status.Breadcrumbs;
import com.crystalgui.workbench.diff.ConflictDialog;
import com.crystalgui.workbench.diff.MergeView;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Extracted from {@link Workbench}. See the plan's §4.5 for why this cluster is one thing.
 */
final class SaveActions {

    private final Workbench workbench;

    SaveActions(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * The project, then the path within it — which is what IntelliJ shows and what a bare path cannot say.
     *
     * <p>{@code segments()} is project-<em>relative</em>, so a file at the project root produced a
     * one-segment trail reading just {@code manifest.mf}: true, and useless, because the one thing a
     * breadcrumb is for is saying where among several places you are.</p>
     */
    static List<Breadcrumbs.Crumb> trailFor(@Nullable CgPath path) {
        if (path == null) return List.of();
        List<Breadcrumbs.Crumb> trail = new ArrayList<>();
        trail.add(Breadcrumbs.Crumb.of(path.project()));
        List<String> segments = path.segments();
        for (int i = 0; i < segments.size(); i++) {
            String name = segments.get(i);
            // THE FILE GETS AN ICON; THE FOLDERS ABOVE IT DO NOT. IntelliJ draws a folder glyph on every
            // directory crumb, and in a 22px bar that is four near-identical marks competing with the one
            // that carries information -- the file's type is the thing you cannot read off the text.
            if (i < segments.size() - 1) {
                trail.add(Breadcrumbs.Crumb.of(name));
                continue;
            }
            FileIconTheme theme = FileIconTheme.getDefault();
            trail.add(new Breadcrumbs.Crumb(name, theme.drawableFor(name, false, false),
                    theme.classFor(name, false)));
        }
        return trail;
    }

    /** Writes the active tab back. A stale write is reported distinctly — it has a recovery path. */
    public boolean saveActiveFile() {
        CgPath target = workbench.activeFilePath();
        if (target == null) {
            Notifications.show(Notification.warning("No file tab active"));
            return false;
        }
        Document document = workbench.documents.get(Resource.of(target));
        if (document == null) return false;
        if (!document.state().isSaveable()) {
            Notifications.show(Notification.error("Refusing to save")
                    .withDetail(target.name() + " — " + document.state()));
            return false;
        }
        byte[] written = document.model().encode();
        workbench.documents.save(document)
                .then(ok -> saved(target))
                .onError(failure -> {
                    if (failure instanceof FsError problem && problem.is(FsError.CONFLICT)) {
                        askWhichVersionSurvives(target, written);
                        return;
                    }
                    Notifications.show(saveFailed(target, failure)
                            .withAction("Retry", this::saveActiveFile));
                });
        return true;
    }

    /**
     * The document is already marked saved by {@link WorkspaceDocuments#save} — <b>at the version the
     * bytes were taken</b>, not at the version it holds now, so an edit made while the write crossed the
     * wire leaves it dirty afterwards. This is only what the workbench has to do afterwards.
     */
    private void saved(CgPath target) {
        // THE DISAGREEMENT IS OVER, whichever way it was resolved -- a successful write means this
        // buffer is now what the server holds. Leaving the mark would show a conflict badge on a file
        // that has none, which teaches people to ignore the badge.
        workbench.externallyChanged.remove(target);
        workbench.externallyDeleted.remove(target);
        workbench.documentTabs.refreshTabTitles();
    }

    /**
     * Phase 5.6 — who else has this file open, phrased for a human.
     *
     * <p>{@code null} rather than an empty string when nobody is known, because the dialog omits the
     * whole line rather than drawing "nobody has it open" — which would be a claim, and the client cannot
     * make it: an empty presence list means <em>nothing has been said</em>, not that the file is
     * unoccupied.</p>
     *
     * <p><b>Who is EDITING, not who has it open.</b> That is the question that matters and the one
     * presence could not answer: two people found out they were both editing a file when the second one
     * saved and was refused. @see Workspace.Presence#whoIsEditing</p>
     */
    @Nullable
    String othersEditing(@Nullable CgPath target) {
        // NOT A FILE, so nobody is editing it. A dock panel need not be about a path at all -- a
        // networked panel a server opened as a tab is the first one that is not, and this threw out of
        // the active-panel signal, which runs inside the click that activated the tab.
        if (target == null) return null;
        return workbench.presenceBinding.phrase(workbench.workspace.presence().whoIsEditing(Resource.of(target)));
    }

    private void askWhichVersionSurvives(CgPath target, byte[] written) {
        // CAPTURED BEFORE ANYTHING READS: finishRead overwrites cachedContent with whatever the server
        // now holds, so a base fetched after the read is the SERVER version wearing the name of the
        // ancestor -- and a three-way merge against that reports every one of my own edits as a conflict.
        // THE LAST VERSION THIS CLIENT WROTE, which is what a common ancestor IS. Read from the local
        // history rather than from a content cache: a cache is overwritten by whatever the server now
        // holds, so a base fetched after the conflicting read is THEIRS wearing the ancestor's name --
        // and a three-way merge against that reports every one of my own edits as a conflict.
        byte[] base = workbench.workspace.history() == null
                ? null : workbench.workspace.history().mergeBase(Resource.of(target));
        ConflictDialog.ask(workbench, target, othersEditing(target),
                () -> overwrite(target, written),
                () -> workbench.opener.openFile(target),
                // No base, no merge. A file that was never read has no common ancestor, and offering the
                // option and then failing is worse than not offering it.
                base == null ? null : () -> openMerge(target, written, base));
    }

    /**
     * Fetches the server's current copy and opens a three-way merge over it.
     *
     * <p>The read is what makes this asynchronous: {@code written} and {@code base} are both already in
     * hand, and only <em>theirs</em> has to come off the wire.</p>
     */
    private void openMerge(CgPath target, byte[] written, byte[] base) {
        workbench.files.readWhole(Resource.of(target))
                .then(theirs -> showMerge(target, base, written, theirs.bytes()))
                .onError(failure -> Notifications.show(saveFailed(target, failure)));
    }

    private void showMerge(CgPath target, byte[] base, byte[] mine, byte[] theirs) {
        UIDocument window = workbench.document();
        if (window == null) return;

        ThreeWayMerge merge = ThreeWayMerge.of(workbench.text(base), workbench.text(mine), workbench.text(theirs));
        MergeView view = new MergeView(merge);

        Dialog dialog = new Dialog("Merge " + target.name());
        dialog.getContent().append(view);

        UIElement actions = new UIElement();
        actions.addClass(MergeView.DIALOG_ACTIONS_CLASS);
        dialog.getContent().append(actions);

        Button apply = new Button("Save merged");
        Button cancel = new Button("Cancel");
        actions.append(apply);
        actions.append(cancel);

        // GATED ON EVERY CONFLICT HAVING BEEN DECIDED, not on there being none. An undecided conflict
        // still produces text -- it defaults to mine -- so an ungated button would write a merge nobody
        // read and it would look like it worked.
        Runnable syncEnabled = () -> {
            boolean ready = view.isResolved();
            apply.setEnabled(ready);
            apply.setHitTest(ready);
        };
        view.onChanged.connect(syncEnabled);
        syncEnabled.run();

        apply.onPressed.connect(() -> {
            String merged = view.mergedText();
            dialog.close();
            overwrite(target, merged.getBytes(StandardCharsets.UTF_8));
        });
        cancel.onPressed.connect(dialog::close);

        window.addOverlay(dialog, workbench);
        dialog.onClosed.connect(dialog::removeSelf);
        dialog.showModal();
        window.focus().requestFocus(cancel);
    }

    /**
     * <b>Keep mine</b> — writes the active file over whatever the server holds.
     *
     * <p>Named rather than left inline inside the conflict handler, because it is one of the three
     * answers {@code ConflictDialog} offers and the only one with no other way to reach it. A branch that
     * exists only inside a modal's callback cannot be tested without driving the modal, which is how a
     * resolution path ends up being the one nobody checks.</p>
     */
    public boolean overwriteActiveFile() {
        CgPath target = workbench.activeFilePath();
        if (target == null) return false;
        Document document = workbench.documents.get(Resource.of(target));
        if (document == null || !document.state().isSaveable()) return false;
        overwrite(target, document.model().encode());
        return true;
    }

    private void overwrite(CgPath target, byte[] written) {
        Document document = workbench.documents.get(Resource.of(target));
        if (document == null) return;
        workbench.documents.save(document, WorkspaceDocuments.SaveReason.EXPLICIT, true)
                .then(ok -> saved(target))
                .onError(
                // A SECOND failure is not another conflict -- overwrite carries no etag, so it cannot be
                // refused as stale. Anything arriving here is a real error and belongs on the ordinary
                // path rather than reopening the question.
                again -> Notifications.show(saveFailed(target, again)));
    }

    /**
     * The two failures a read or a write raises, phrased once.
     *
     * <p>Returned rather than shown, so a caller can add the action it knows about — retrying an open
     * means something different from retrying a save.</p>
     */
    static Notification openFailed(Resource resource, ReplyError failure) {
        return Notification.error("Open failed")
                .withDetail(resource.name() + " — " + failure.detail());
    }

    /** @see #openFailed */
    private static Notification saveFailed(CgPath path, ReplyError failure) {
        return Notification.error("Save failed").withDetail(path.name() + " — " + failure.detail());
    }

    /**
     * Whether this open file has changes that are not on disk.
     *
     * <p>Compared against the bytes last read or written rather than counted from edit events: a counter
     * says "modified" after a change <em>and its undo</em>, which is exactly the state somebody is in when
     * they close a tab and get asked to save a file identical to the one already there.</p>
     *
     * <p>False for a file that is not open, and false for one whose document refused to load it. Encoding
     * <p><b>A version comparison, never an encode-and-compare.</b> {@code version() != savedVersion()} is
     * the whole test: monotonic, so it cannot be fooled by a change and its undo landing back on the same
     * bytes, and free, where the comparison it replaces serialised every open document once a frame.</p>
     */
    public boolean isDirty(CgPath path) {
        Document document = workbench.documents.get(Resource.of(path));
        return document != null && document.isDirty();
    }

    /** Every open file with unsaved changes, in no particular order. */
    public List<CgPath> unsavedFiles() {
        List<CgPath> dirty = new ArrayList<>();
        for (Document document : workbench.documents.dirty()) {
            CgPath path = document.resource().asPath();
            if (path != null) dirty.add(path);
        }
        return dirty;
    }

    /**
     * Writes every modified file.
     *
     * <p>Issued per file rather than as one call, because they succeed and fail separately — the same
     * reasoning the drop and the paste follow. No undo grouping, though: saving is not an edit, and it is
     * not on the undo stack at all.</p>
     *
     * @return how many writes were issued
     */
    public int saveAll() {
        int issued = 0;
        for (Document document : workbench.documents.dirty()) {
            CgPath path = document.resource().asPath();
            if (path == null) continue;
            issued++;
            workbench.documents.save(document)
                    .then(ok -> workbench.documentTabs.refreshTabTitles())
                    .onError(failure -> Notifications.show(saveFailed(path, failure)));
        }
        if (issued == 0) Notifications.show(Notification.info("Nothing to save"));
        return issued;
    }

    /**
     * Whether {@code panel} may close now, asking the user first when it would discard unsaved work.
     *
     * <p>Returns <b>false</b> for a modified file and puts up a prompt, rather than trying to answer
     * "yes, eventually": the prompt is asynchronous, so there is no answer to give at the moment the dock
     * asks. Confirming closes through {@link DockArea#closePanelDiscarding}, which skips this guard —
     * without that it would ask again, forever.</p>
     *
     * <p>Only files are guarded. A tool panel — the tree, Problems — holds nothing that is not on disk, and
     * asking about it would train the answer out of the user by the time it matters.</p>
     */
    boolean confirmClose(DockPanelRef panel) {
        String state = panel.state(Workbench.PATH_STATE, "");
        if (state.isEmpty()) return true;
        CgPath path = CgPath.parse(state);
        if (!isDirty(path)) return true;

        InputDialog.confirm(workbench, "Unsaved changes",
                path.name() + " has unsaved changes — Enter to discard, Escape to keep editing",
                () -> workbench.dock.closePanelDiscarding(panel));
        return false;
    }

}
