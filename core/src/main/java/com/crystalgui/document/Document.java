package com.crystalgui.document;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * <b>One open document</b> — an object with an identity that survives being renamed.
 *
 * <h3>Identity is the object, and the resource is a property of it</h3>
 *
 * <p>{@code plan_fs_rewrite.md} N4, D2. A text document was a {@code record(editor, resource)}, so a
 * rename moved the entry in the store's map and the document went on answering the old name. Four
 * stores rekeyed independently — the Problems panel's scope, {@code Markers}, the language attachment
 * and the run session — three of them by hand and one, {@code RunPanels}, by <b>forgetting</b> the
 * session rather than retargeting it. Nothing was ever told.</p>
 *
 * <p>So: the document is the key, {@link #resource()} is a property, and {@link #onDidChangeResource}
 * is the one event every store subscribes to. IntelliJ's {@code VirtualFile} is the same object after a
 * rename, and its {@code VFilePropertyChangeEvent} is this signal.</p>
 *
 * <h3>Held through a reference, released by the last holder</h3>
 *
 * <p>A tab, a diff, a merge view, the Problems panel and a background compile may each hold one. The
 * model is disposed when the last is released — later than a tab closing, and never earlier, which is
 * the failure this replaces: a document released while the index still held it, reported as
 * <em>"Parser is closed"</em>.</p>
 *
 * <h3>Headless</h3>
 *
 * <p>Nothing here names a widget. A document with no tab is an ordinary state — it analyses, it holds
 * diagnostics, it can be saved — and that is what lets the Problems panel and Go to Definition work on
 * a file nobody has opened a view onto.</p>
 */
public final class Document {

    private final DocumentModel model;
    private final DocumentKind kind;

    private Resource resource;
    private DocumentState state = DocumentState.LOADING;
    private int savedVersion;
    @Nullable
    private String etag;
    private int references;
    private boolean disposed;

    /** {@code (from, to)} — the one rename event. Every store that keys by document subscribes. */
    public final Signal.Pair<Resource, Resource> onDidChangeResource = new Signal.Pair<>();

    /** The state moved. Carries the state it moved TO. */
    public final Signal.Value<DocumentState> onDidChangeState = new Signal.Value<>();

    /** The content moved. Read {@link #version()} for the stamp. */
    public final Signal.Action onDidChange = new Signal.Action();

    /** Written, and the file now matches. */
    public final Signal.Action onDidSave = new Signal.Action();

    public Document(Resource resource, DocumentKind kind, DocumentModel model) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.model = Objects.requireNonNull(model, "model");
        this.savedVersion = model.version();
        model.onChanged().connect(this::modelChanged);
    }

    // ── Identity ────────────────────────────────────────────────────────────────────────────────

    public Resource resource() {
        return resource;
    }

    /**
     * Moves this document to a new address and announces it.
     *
     * <p>Called by whoever performed the rename. Everything keyed by document hears once and rekeys
     * itself; nothing has to be walked, and nothing can be forgotten by a store that was not thought
     * of.</p>
     */
    public void retarget(Resource target) {
        Objects.requireNonNull(target, "target");
        if (resource.equals(target)) return;
        Resource from = resource;
        resource = target;
        onDidChangeResource.emit(from, target);
    }

    public DocumentKind kind() {
        return kind;
    }

    public DocumentModel model() {
        return model;
    }

    /**
     * The model as its own type.
     *
     * @throws IllegalStateException if this document is a different kind — a document is one thing, and
     *                               a caller that guessed wrong should hear about it here rather than
     *                               through a class cast three frames later
     */
    public <M extends DocumentModel> M as(Class<M> type) {
        if (!type.isInstance(model)) {
            throw new IllegalStateException(resource + " is a " + model.getClass().getSimpleName()
                    + ", not a " + type.getSimpleName());
        }
        return type.cast(model);
    }

    // ── State ───────────────────────────────────────────────────────────────────────────────────

    public DocumentState state() {
        return state;
    }

    /**
     * Whether there is unsaved work — <b>a comparison, not a serialisation</b>.
     *
     * <p>It used to be {@code encode()} against the bytes last read, for every open document on every
     * change, which for a shader graph means writing the whole graph to JSON to decide whether a tab
     * needs an asterisk.</p>
     */
    public boolean isDirty() {
        return model.version() != savedVersion;
    }

    public int version() {
        return model.version();
    }

    /** The version the file on disk holds. */
    public int savedVersion() {
        return savedVersion;
    }

    /** The file's etag as last seen — a fact about the FILE, never about this document's content. */
    @Nullable
    public String etag() {
        return etag;
    }

    public UndoStack history() {
        return model.history();
    }

    /**
     * Records that the content and the file now agree.
     *
     * <p>Called after a successful save and after a reload. The version recorded is the model's
     * <em>at this moment</em>, so an edit made while a save was in flight leaves the document dirty
     * afterwards — which is correct, and is what a version records that a byte comparison cannot.</p>
     */
    public void markSaved(@Nullable String newEtag) {
        markSavedAt(model.version(), newEtag);
    }

    /**
     * The same, for a save whose bytes were taken at an EARLIER version.
     *
     * <p>Which is every save that crosses a wire: the content is encoded, the write travels, and the
     * person goes on typing. Recording "the version now" would call the document clean while holding
     * edits the file does not have — and the next reload would discard them without asking. This is the
     * property a byte comparison cannot express at all.</p>
     */
    public void markSavedAt(int version, @Nullable String newEtag) {
        this.savedVersion = version;
        this.etag = newEtag;
        setState(isDirty() ? DocumentState.DIRTY : DocumentState.CLEAN);
        onDidSave.emit();
    }

    /** Records the etag without claiming the content matches — what a stat answers. */
    public void setEtag(@Nullable String newEtag) {
        this.etag = newEtag;
    }

    public void setState(DocumentState next) {
        if (next == null || state == next) return;
        state = next;
        onDidChangeState.emit(next);
    }

    /**
     * The content arrived from the file. Not an edit: the history is cleared by the model.
     *
     * @throws RuntimeException whatever the model throws when the bytes cannot be applied. The caller
     *                          marks the document {@link DocumentState#FAILED} and refuses to save it,
     *                          rather than letting an empty document be written over somebody's work.
     */
    public void adopt(byte[] bytes, @Nullable String newEtag) {
        model.adopt(bytes);
        markSaved(newEtag);
    }

    // ── Lifetime ────────────────────────────────────────────────────────────────────────────────

    /**
     * One more holder. The document lives until every reference is released.
     *
     * <p>Package-private: a reference comes from {@code Documents.open}, so the count and the store's
     * map cannot disagree about whether a document is still alive.</p>
     */
    DocumentReference acquire(Runnable onLastRelease) {
        references++;
        return new Handle(onLastRelease);
    }

    /** How many holders there are. In the health readout, because a count that only grows is a leak. */
    public int referenceCount() {
        return references;
    }

    public boolean isDisposed() {
        return disposed;
    }

    private void release(Runnable onLastRelease) {
        if (references == 0) return;
        references--;
        if (references > 0 || disposed) return;
        disposed = true;
        model.dispose();
        onLastRelease.run();
    }

    private void modelChanged() {
        if (state == DocumentState.CLEAN && isDirty()) setState(DocumentState.DIRTY);
        else if (state == DocumentState.DIRTY && !isDirty()) setState(DocumentState.CLEAN);
        onDidChange.emit();
    }

    @Override
    public String toString() {
        return "Document(" + resource + ", " + state + (isDirty() ? ", dirty)" : ")");
    }

    /** One holder's claim. Idempotent, so a double dispose costs nothing rather than double-releasing. */
    private final class Handle implements DocumentReference {
        private final Runnable onLastRelease;
        private boolean released;

        Handle(Runnable onLastRelease) {
            this.onLastRelease = onLastRelease;
        }

        @Override
        public Document document() {
            return Document.this;
        }

        @Override
        public void dispose() {
            if (released) return;
            released = true;
            release(onLastRelease);
        }
    }
}
