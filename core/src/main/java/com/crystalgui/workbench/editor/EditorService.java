package com.crystalgui.workbench.editor;

import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentEditor;
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

    /** A tab opened. */
    public final Signal.Value<Tab> onDidOpen = new Signal.Value<>();

    /** A tab closed. */
    public final Signal.Value<Tab> onDidClose = new Signal.Value<>();

    /** A tab's state moved — what a tab strip redraws its decoration from. */
    public final Signal.Value<Tab> onDidChangeState = new Signal.Value<>();

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
        documents.open(input.resource())
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
     * @return how many were restored
     */
    public int restoreUnsavedWork() {
        int restored = 0;
        for (Backup.Entry entry : documents.restorable()) {
            if (kinds.forResource(entry.resource()) == null) continue;
            open(EditorInput.of(entry.resource()));
            restored++;
        }
        return restored;
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
            return editor;
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
            if (view != null) view.disposeView();
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
