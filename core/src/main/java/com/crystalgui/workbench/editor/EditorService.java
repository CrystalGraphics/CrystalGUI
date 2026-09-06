package com.crystalgui.workbench.editor;

import java.util.Arrays;
import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.document.Document;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.EditorInput;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Backup;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.protocol.FsError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The open tabs — <b>one lane, whatever kind of thing is being opened</b>.
 *
 * <pre>{@code
 * editors.open(EditorInput.of(resource)).then(tab -> …).onError(failure -> …);
 * editors.saveActive();
 * editors.onDidChangeState.connect(tab -> …);
 * }</pre>
 *
 * <p>A project file, a decompiled class and a generated shader source all go through {@link #open},
 * because a document is keyed by {@link Resource} and where its bytes come from is the workspace's
 * question. A new kind of thing to open is a new {@code DocumentKind}, never a second lane.</p>
 *
 * <p>A {@link Tab} exists <b>immediately</b>, in {@link DocumentState#LOADING}, and is filled when the
 * read lands — which is what lets a session restore put twelve tabs on screen at once rather than
 * revealing them one round trip at a time.</p>
 *
 * <h3>An editor is a view; the document outlives it</h3>
 *
 * <p>Closing a tab releases that tab's {@link DocumentReference} and nothing more. The document is
 * disposed by its LAST holder, which may be the Problems panel, an index or a background compile —
 * later than the tab, and never earlier. That ordering is the "Parser is closed" defect, inverted.</p>
 */
public final class EditorService implements Disposable {

    private final WorkspaceDocuments documents;
    private final Workspace workspace;
    private final DocumentKinds kinds;

    /** Open tabs, in the order they were opened. One per {@link EditorInput}. */
    private final Map<EditorInput, Tab> tabs = new LinkedHashMap<>();

    /**
     * What each closed editor was showing, so reopening a file inside one session puts it back.
     *
     * <p>{@code DocumentEditor.writeViewState} was only ever called by {@link
     * com.crystalgui.workbench.WorkbenchSession} — at SESSION save and restore — so closing a tab and
     * reopening it lost the camera and every floating panel, while quitting and relaunching kept them.
     * That is the wrong way round: the shorter the round trip, the more certain a user is that nothing
     * should have moved.</p>
     *
     * <p>Keyed by input rather than by document, because the input is what a reopen names, and it
     * outlives the document the way the session's own record does. The same shape {@code DockGroup}
     * already keeps for a retargeted pane.</p>
     */
    private final Map<EditorInput, StateMap<?>> viewStates = new LinkedHashMap<>();

    /** A tab opened. */
    public final Signal.Value<Tab> onDidOpen = new Signal.Value<>();

    /** A tab closed. */
    public final Signal.Value<Tab> onDidClose = new Signal.Value<>();

    /** A tab's state moved — what a tab strip redraws its decoration from. */
    public final Signal.Value<Tab> onDidChangeState = new Signal.Value<>();

    /**
     * Unsaved work from a previous session was just put back into this document.
     *
     * <p><b>A restore has to be announced, because nothing else says it happened.</b> In an editor you
     * left dirty on purpose the marker needs no explanation — you know why it is there. A restore from a
     * <em>previous run</em> is the opposite: the file opens modified, the author did not modify it in
     * this session, and there is no way to tell that from a bug in the dirty state. That is precisely
     * how it was reported, twice, about a backup this application had written itself.</p>
     *
     * <p>Emitted only when a backup is genuinely adopted — one that matched the file is discarded in
     * silence, because nothing happened worth telling anyone about.</p>
     */
    public final Signal.Value<Restored> onDidRestoreUnsavedWork = new Signal.Value<>();

    /**
     * What came back, and whether the file underneath it moved while this client was away.
     *
     * <p>{@code fileAlsoChanged} is worth carrying because the author cannot see it and it changes what
     * saving means: the restored document holds the <em>backup's</em> etag, so a write is refused as a
     * conflict rather than overwriting whatever happened in the meantime. Without it that arrives as a
     * surprise at the moment of saving, which is the worst moment to learn that the file is not the one
     * this work was based on.</p>
     */
    public record Restored(Resource resource, boolean fileAlsoChanged) {
    }

    /** Which tab is in front, or null. */
    @Nullable
    private Tab active;

    public EditorService(Workspace workspace, WorkspaceDocuments documents, DocumentKinds kinds) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens an input, or brings its tab forward if it is already open.
     *
     * <p>The tab exists <b>immediately</b>, in {@link DocumentState#LOADING}, and is filled when the
     * read lands. That is what lets a session restore put twelve tabs on screen at once rather than
     * revealing them one round trip at a time — and it is why a tab's state is a real enum rather than
     * a nullable document.</p>
     */
    public Reply<Tab> open(EditorInput input) {
        Tab existing = tabs.get(input);
        if (existing != null) {
            activate(existing);
            return Reply.of(existing);
        }

        Tab tab = new Tab(input);
        tabs.put(input, tab);
        onDidOpen.emit(tab);

        PendingReply<Tab> opened = new PendingReply<>(() -> close(tab));
        documents.open(input.resource(), input.preferredKindId())
                .onError(error -> {
                    tab.fail(error);
                    opened.fail(error);
                })
                .then(reference -> {
                    tab.bind(reference);
                    activate(tab);
                    opened.resolve(tab);
                });
        return opened;
    }

    public Reply<Tab> open(Resource resource) {
        return open(EditorInput.of(resource));
    }

    /** The tab for this input, or null. */
    @Nullable
    public Tab tabFor(EditorInput input) {
        return tabs.get(input);
    }

    /** Every open tab, in the order they were opened. */
    public List<Tab> tabs() {
        return List.copyOf(tabs.values());
    }

    @Nullable
    public Tab active() {
        return active;
    }

    /** The document in front, which is what a command resolves its subject to. */
    @Nullable
    public Document activeDocument() {
        return active == null ? null : active.document();
    }

    public void activate(Tab tab) {
        if (active == tab) return;
        if (active != null) active.setActive(false);
        active = tab;
        if (tab != null) tab.setActive(true);
    }

    /**
     * Closes a tab.
     *
     * <p>Releases that tab's reference and nothing else. A document with unsaved work is <b>not</b>
     * prompted about here: it is backed up, and closing without asking is what both references do —
     * a modal between a person and closing a tab, at the moment they have already decided, is what
     * hot exit exists to remove.</p>
     */
    public void close(Tab tab) {
        if (tabs.remove(tab.input()) == null) return;
        if (active == tab) {
            active = null;
            tab.setActive(false);
        }
        tab.release();
        onDidClose.emit(tab);
    }

    /**
     * Closes the editors for something this client has just deleted.
     *
     * <p>Only for a delete the author ASKED for. A file that disappears underneath keeps its editor,
     * marked — that is {@code WorkspaceDocuments}' orphan rule, and VS Code's
     * {@code workbench.editor.closeOnFileDelete} default — so a file somebody else removes cannot take
     * an unread buffer with it. A deliberate delete is not that case: it leaves a tab open on a file its
     * author knows is gone, and a save from that tab silently recreates it.</p>
     *
     * <p><b>A tab holding unsaved work stays open</b>, whoever deleted the file. That buffer is the only
     * copy of the text left anywhere the author can see it, and a delete was never asked to take it.</p>
     *
     * @param deleted what was deleted — a directory closes every editor under it too
     */
    public void closeDeleted(Resource deleted) {
        String target = deleted.toString();
        String under = target + "/";
        for (Tab tab : new ArrayList<>(tabs.values())) {
            String key = tab.resource().toString();
            if (!key.equals(target) && !key.startsWith(under)) continue;
            Document document = tab.document();
            if (document != null && document.isDirty()) continue;
            close(tab);
        }
    }

    public void closeAll() {
        for (Tab tab : new ArrayList<>(tabs.values())) close(tab);
    }

    // ── Saving ──────────────────────────────────────────────────────────────────────────────────

    /** Saves what is in front, if anything is and it needs it. */
    public Reply<Void> saveActive() {
        Document document = activeDocument();
        if (document == null) return Reply.of(null);
        return documents.save(document);
    }

    /** Saves every dirty document — Save All, and what a close-with-unsaved-work path used to prompt. */
    public Reply<Void> saveAll() {
        List<Reply<?>> saves = new ArrayList<>();
        for (Document document : documents.dirty()) saves.add(documents.save(document));
        return Reply.all(saves);
    }

    // ── Hot exit ────────────────────────────────────────────────────────────────────────────────

    /**
     * Re-opens whatever was left unsaved when this client last stopped.
     *
     * <p>{@code files.hotExit}. Each document comes back <b>dirty against the etag it was in step
     * with</b>, so a file that moved while the client was away produces a conflict on the next save
     * rather than a silent overwrite.</p>
     *
     * <p><b>A backup that matches the file is not unsaved work</b> and is discarded rather than
     * restored — see the comparison below. Without it a stale backup marks an untouched file modified
     * on every launch.</p>
     *
     * @return how many backups were offered. A backup that turns out to match the file is counted here
     *         and restores nothing: the comparison needs the document, which arrives asynchronously
     */
    public int restoreUnsavedWork() {
        int restored = 0;
        for (Backup.Entry entry : documents.restorable()) {
            // LEFT ON DISK, not discarded. Nothing here can open it, so nothing here can compare it
            // against the file -- and a kind is absent because an extension did not load as often as
            // because it is gone, so discarding would throw away work that a later launch could give
            // back. It costs one stale file per resource; the alternative costs somebody's work.
            if (kinds.forResource(entry.resource()) == null) continue;
            // THE BYTES, once the document is there. Opening alone reads the SERVER's copy and settles
            // CLEAN, so the work this method exists to give back was read from the store, counted, and
            // thrown away -- and the count is what the covering test asserted, so it passed throughout.
            open(EditorInput.of(entry.resource())).then(tab -> {
                Document document = tab.document();
                if (document == null) return;
                // COMPARED AGAINST THE FILE, because a backup is a CLAIM that there is unsaved work and
                // not proof of it. `adoptUnsaved` marks the document DIRTY by contract -- that is what it
                // is for -- so restoring a backup whose content is what the file already holds opens a
                // file the author has not touched with a modified marker on it, on every launch, until
                // somebody edits and saves it. Reported exactly that way: "Main.java opened with the
                // asterisk and I didn't touch it, and it doesn't happen for all files" -- only the ones
                // with a backup.
                //
                // The encode is affordable here and nowhere else: once per restored document at launch,
                // against a document that was just read anyway.
                if (Arrays.equals(entry.content(), document.model().encode())) {
                    // AND THE BACKUP GOES. It says nothing the file does not, so keeping it means making
                    // the same empty offer every launch.
                    documents.discardBackup(entry.resource());
                    return;
                }
                // READ BEFORE THE ADOPT, which replaces it with the backup's. This is the etag the
                // file has right now, and comparing the two is the only way to know the file moved while
                // this client was away -- the read has just been done, so it costs nothing.
                String fileEtag = document.etag();
                document.adoptUnsaved(entry.content(), entry.etag());
                boolean moved = fileEtag != null && entry.etag() != null
                        && !fileEtag.equals(entry.etag());
                onDidRestoreUnsavedWork.emit(new Restored(entry.resource(), moved));
            });
            restored++;
        }
        return restored;
    }

    /**
     * Throws away what {@link #restoreUnsavedWork} would have offered.
     *
     * <p>The other answer, and the one that makes the offer a question. Without it a host that shows
     * "restore your unsaved work?" and is told no has nowhere to put the no, so the same work is
     * offered again on the next launch and every launch after it.</p>
     */
    public void discardUnsavedWork() {
        documents.discardRestorable();
    }

    @Override
    public void dispose() {
        closeAll();
    }

    // ── A tab ───────────────────────────────────────────────────────────────────────────────────

    /**
     * One open editor.
     *
     * <p>Holds the input it was opened with, the reference that keeps the document alive, and the view —
     * built lazily, because a tab restored into a background group has a state and a title long before
     * anybody looks at it.</p>
     */
    public final class Tab {

        private final EditorInput input;
        @Nullable
        private DocumentReference reference;
        @Nullable
        private DocumentEditor editor;
        @Nullable
        private ReplyError failure;
        private DocumentState state = DocumentState.LOADING;

        private Tab(EditorInput input) {
            this.input = input;
        }

        public EditorInput input() {
            return input;
        }

        public Resource resource() {
            // THE DOCUMENT'S, once there is one: a rename moves the document and the tab follows it,
            // rather than the tab holding an address the document has moved on from.
            Document document = document();
            return document == null ? input.resource() : document.resource();
        }

        @Nullable
        public Document document() {
            return reference == null ? null : reference.document();
        }

        /** What this tab is doing. One enum, read by the strip, the save path and the session. */
        public DocumentState state() {
            Document document = document();
            return document == null ? state : document.state();
        }

        /** Why it failed, when it did. */
        @Nullable
        public ReplyError failure() {
            return failure;
        }

        public boolean isDirty() {
            Document document = document();
            return document != null && document.isDirty();
        }

        /** What the strip shows. The file's name, since a tab is identified by what it holds. */
        public String title() {
            return resource().name();
        }

        /**
         * The view, built on first ask.
         *
         * <p>Null when the kind declares no editor, which is a real declaration: a kind that can be
         * opened, analysed and saved with nothing to look at it is what a build artefact is.</p>
         */
        @Nullable
        public DocumentEditor editor() {
            if (editor != null) return editor;
            Document document = document();
            if (document == null || !document.kind().hasEditor()) return null;
            editor = document.kind().createEditor(document);
            // THE OPENING'S, applied to the VIEW. A read-only opening and an editable one are two tabs
            // over ONE document -- which is what lets a diff's left pane sit beside the live file --
            // so the refusal cannot live on the model without taking the other tab down with it.
            if (input.isReadOnly()) editor.setReadOnly(true);
            // AND WHAT IT WAS SHOWING LAST TIME, if this file has been closed and reopened in this
            // session. @see #captureViewState
            StateMap<?> stored = viewStates.get(input);
            if (stored != null) editor.readViewState(stored);
            return editor;
        }

        /**
         * Remembers what this editor is showing, so a reopen puts it back.
         *
         * <p>Called from {@code onWillClosePanel} rather than from {@link #release}, because the dock
         * detaches the widget first and a detached element has no geometry to ask for: the rects came
         * back empty and nothing was stored.</p>
         */
        public void captureViewState() {
            DocumentEditor view = editor;
            if (view == null) return;
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            view.writeViewState(out);
            viewStates.put(input, out);
        }

        private void bind(DocumentReference held) {
            this.reference = held;
            this.state = held.document().state();
            held.document().onDidChangeState.connect(next -> {
                state = next;
                onDidChangeState.emit(this);
            });
            onDidChangeState.emit(this);
        }

        private void fail(ReplyError error) {
            this.failure = error;
            this.state = DocumentState.FAILED;
            onDidChangeState.emit(this);
        }

        private void setActive(boolean isActive) {
            DocumentEditor view = editor;
            if (view != null) view.activated(isActive);
            Document document = document();
            if (document != null && isActive) document.kind().contributeStatus(document);
        }

        private void release() {
            DocumentEditor view = editor;
            if (view != null) {
                // BEFORE disposing it, while its element is still worth naming. An inspector RETAINS
                // a detached subject on purpose, so without this a closed document kept its sections on
                // screen over whatever was opened next.
                InspectorRegistry.subjectClosed(view.view());
                view.disposeView();
            }
            editor = null;
            if (reference != null) reference.dispose();
            reference = null;
        }

        /** Retries a tab that failed to open — what a "retry" affordance on the tab calls. */
        public Reply<Tab> retry() {
            failure = null;
            state = DocumentState.LOADING;
            onDidChangeState.emit(this);
            tabs.remove(input);
            return open(input);
        }

        @Override
        public String toString() {
            return "Tab(" + resource() + ", " + state() + (isDirty() ? ", dirty)" : ")");
        }
    }
}
