package com.crystalgui.fs.client;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.Documents;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsMessages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;

/**
 * The open documents, and the wire underneath them.
 *
 * <pre>{@code
 * documents.open(resource).then(reference -> …);
 * documents.save(document).onError(conflict -> …);
 * documents.onDidChangeState.connect((document, state) -> …);
 * }</pre>
 *
 * <p>Built over a {@link Workspace} and a {@link DocumentKinds}: opening reads the resource through the
 * workspace, asks the kinds which model to build, and hands back a {@link DocumentReference}. A second
 * caller for one resource joins the document already open, which is what makes two split panes one
 * document.</p>
 *
 * <h3>The save is the synchronisation point</h3>
 *
 * <p>A document lives on one client between saves. A save quotes the etag it last saw and is refused
 * if the file moved; the refusal is a conflict a person resolves. So every disagreement is an etag
 * mismatch and surfaces where somebody can act on it.</p>
 *
 * <h3>What a change on the server means depends on whether the document is dirty</h3>
 *
 * <p>Clean, it reloads through {@code adopt} — no prompt, no decision, and the undo history is fenced
 * so Ctrl+Z cannot resurrect the replaced text. Dirty, it is marked {@link DocumentState#CONFLICTING}
 * and left alone: there is unsaved work and only a person can say what happens to it.</p>
 */
public final class WorkspaceDocuments implements Disposable {

    private final Workspace workspace;
    private final DocumentKinds kinds;
    private final Documents documents = new Documents();

    /** Per open document, its watch — released with the document. */
    private final Map<Resource, Workspace.Watch> watches = new LinkedHashMap<>();

    /** How often unsaved work is written to the backup store, in milliseconds of edit activity. */
    public static final long BACKUP_DEBOUNCE_MILLIS = 1000L;

    /** A document opened for the first time. */
    public final Signal.Value<Document> onDidOpen = new Signal.Value<>();

    /** Its last reference went. */
    public final Signal.Value<Document> onDidClose = new Signal.Value<>();

    /** Any document's state moved. */
    public final Signal.Pair<Document, DocumentState> onDidChangeState = new Signal.Pair<>();

    /** A document was written. */
    public final Signal.Value<Document> onDidSave = new Signal.Value<>();

    /**
     * Runs before a save takes the bytes. May edit the document; may not refuse it.
     *
     * <p>Trimming trailing whitespace, sorting imports, running a formatter. VS Code's
     * {@code onWillSaveTextDocument}, minus the veto — a participant that could refuse a save is a
     * participant that can lose somebody's work to a bug in a formatter.</p>
     */
    public final List<BiConsumer<Document, SaveReason>> onWillSave =
            new ArrayList<>();

    /** Why a save is happening, which a participant legitimately branches on. */
    public enum SaveReason {
        EXPLICIT, AUTO_AFTER_DELAY, AUTO_ON_FOCUS_CHANGE, CLOSING
    }

    public WorkspaceDocuments(Workspace workspace, DocumentKinds kinds) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        documents.onDidOpen.connect(onDidOpen::emit);
        documents.onDidClose.connect(document -> {
            Workspace.Watch watch = watches.remove(document.resource());
            if (watch != null) watch.dispose();
            // THE UNWATCH TAKES THIS CLIENT OUT OF THE PATH'S PRESENCE, so there is nothing to
            // withdraw -- only the memo of what the server was told, which describes a document that
            // no longer exists.
            reportedEditing.remove(document.resource());
            onDidClose.emit(document);
        });
        // THE SERVER'S CASE RULE, asked once it has answered. Until then the conservative assumption
        // holds -- see FsHello.unknown, and why the failure it produces is the one an etag catches.
        //
        // HELD, because this is the one subscription that points the WRONG WAY down the lifetimes: a
        // Workspace is per connection and a store is per workbench, so a dropped connection here keeps
        // the store, its DocumentKinds, and -- through the kind whose model factory captures it -- the
        // whole workbench that built them. It is the exact path a heap walk found from
        // ContentProviders.onDidChange to a workbench that had been disposed.
        lifetime.add(workspace.onDidGreet.connect(hello ->
                documents.setKeyStrategy(workspace.documentKeyStrategy())));
    }

    /** What this store subscribed to on something that outlives it. @see #dispose() */
    private final ConnectionGroup lifetime = new ConnectionGroup();

    /**
     * Lets go of the workspace, and of every document this store is still holding open.
     *
     * <p>Called by whatever built it — a workbench — and never by the workspace, which does not know
     * how many stores are reading it.</p>
     */
    @Override
    public void dispose() {
        lifetime.disconnectAll();
        for (Workspace.Watch watch : watches.values()) watch.dispose();
        watches.clear();
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens a document, reading it if nothing has it open yet.
     *
     * <p>A second caller for one resource joins the document already open rather than reading it
     * again — which is what makes two split panes one document, and the Problems panel's hold and the
     * tab's hold the same object.</p>
     */
    public Reply<DocumentReference> open(Resource resource) {
        return open(resource, null);
    }

    /**
     * The same, opened <b>as</b> a named kind rather than as the file's name suggests.
     *
     * <p>"Open With…", and a caller that knows something the name does not — a generated shader source
     * has no extension of its own and is GLSL.</p>
     *
     * <p><b>It can only apply on the first open, and says so rather than pretending.</b> A
     * {@link DocumentKind} is a model as well as an editor, and a document is one per resource, so
     * opening a file that is already open under another kind would mean two models writing one file.
     * That is refused with a conflict naming the kind it IS open as. The alternative — quietly joining
     * the open document — hands the caller a tab that looks like the one they asked for and is not, and
     * an "Open With" that silently does nothing is worse than one that explains itself.</p>
     */
    public Reply<DocumentReference> open(Resource resource, @Nullable String preferredKindId) {
        Document existing = documents.get(resource);
        if (existing != null) {
            if (preferredKindId != null && !preferredKindId.equals(existing.kind().id())) {
                return Reply.failed(new FsError(FsError.CONFLICT, resource.name()
                        + " is already open as " + existing.kind().displayName()
                        + "; close it before opening it as something else"));
            }
            DocumentReference reference = documents.reference(resource);
            return Reply.of(reference);
        }

        DocumentKind kind = preferredKindId == null
                ? kinds.forResource(resource) : kinds.byId(preferredKindId);
        if (kind == null) {
            return Reply.failed(new FsError(FsError.INVALID_PATH, preferredKindId == null
                    ? "nothing knows how to open " + resource
                    : "no document kind '" + preferredKindId + "' is registered"));
        }

        PendingReply<DocumentReference> opened = new PendingReply<>(null);
        boolean fromServer = resource.isProject();
        // ONE DOOR. A project resource goes over the wire; a decompiled class or a generated shader goes
        // to whatever registered its scheme. Routing here is what makes this the ONE open lane -- the
        // second lane existed because there was nowhere else to say it.
        workspace.read(resource)
                .onError(opened::fail)
                .then(content -> {
                    Document document;
                    try {
                        document = new Document(resource, kind,
                                kind.createModel(resource, content.bytes()));
                    } catch (RuntimeException undecodable) {
                        // A model that cannot take the bytes must FAIL rather than open empty: an empty
                        // document reports itself modified against the file it could not read, and the
                        // first save writes that emptiness over somebody's work.
                        opened.fail(new FsError(FsError.FAILED,
                                resource + " could not be opened: " + undecodable));
                        return;
                    }
                    // THE ETAG THE BYTES CAME WITH, not a second round trip for it. The read
                    // already knows -- it is in the response the content arrived in -- and asking again
                    // paid a `stat` per open for an answer that could also have MOVED in between, which
                    // is an etag quoting a file nobody read.
                    document.setEtag(content.etag());
                    document.setState(DocumentState.CLEAN);
                    DocumentReference reference = documents.open(resource, ignored -> document);
                    if (fromServer) {
                        // AND ONLY THE SERVER'S FILES ARE WATCHED. Nothing on the far side knows about a
                        // decompiled class, so a watch on one is a subscription to an event that can
                        // never arrive -- and it would cost a real subscription slot to say nothing.
                        attach(document);
                    }
                    opened.resolve(reference);
                });
        return opened;
    }

    /** Another reference on a document already open, or null. */
    @Nullable
    public DocumentReference reference(Resource resource) {
        return documents.reference(resource);
    }

    @Nullable
    public Document get(Resource resource) {
        return documents.get(resource);
    }

    public List<Document> all() {
        return documents.all();
    }

    public List<Document> dirty() {
        return documents.dirty();
    }

    /** Watches the file and reports this client's dirtiness, so the other side can say who is editing. */
    private void attach(Document document) {
        Workspace.Watch watch = workspace.watch(document.resource(), false);
        watches.put(document.resource(), watch);
        watch.onChanged.connect(changes -> {
            for (FsMessages.FileChange change : changes) {
                // MATCHED ON EITHER END, for the reason Workspace.deliver records: a rename's `path` is
                // where the file went, and this document is still sitting at where it came FROM.
                if (Resource.parse(change.path()).equals(document.resource())
                        || (!change.from().isEmpty()
                        && Resource.parse(change.from()).equals(document.resource()))) {
                    applyChange(document, change);
                }
            }
        });
        document.onDidChangeState.connect(state -> {
            onDidChangeState.emit(document, state);
            reportEditing(document);
        });
        document.onDidChange.connect(() -> backup(document));
    }

    /**
     * Tells the server when this client's dirtiness <b>changes</b>, and only then.
     *
     * <p>Sent on the transition rather than on every state change, because a state change is not the
     * same event: a document goes STALE and back to CLEAN without anybody having typed, and a reload
     * moves it twice. What the far side needs is the edge.</p>
     */
    private void reportEditing(Document document) {
        boolean dirty = document.isDirty();
        Boolean told = reportedEditing.get(document.resource());
        if (told != null && told == dirty) return;
        reportedEditing.put(document.resource(), dirty);
        workspace.setEditing(document.resource(), dirty);
    }

    /** Per open document, the last dirtiness the server was told. @see #reportEditing */
    private final Map<Resource, Boolean> reportedEditing = new LinkedHashMap<>();

    // ── What a change on the server means ───────────────────────────────────────────────────────

    private void applyChange(Document document, FsMessages.FileChange change) {
        switch (change.kind()) {
            case DELETED -> document.setState(DocumentState.ORPHANED);
            case RENAMED -> {
                // ONE EVENT, carrying both ends. It arrived as a deletion before, so the client closed
                // the tab -- and the document is the identity, so a rename moves it rather than
                // replacing it. @see Document#retarget
                documents.retarget(document.resource(), Resource.parse(change.path()));
                document.setEtag(change.etag());
            }
            case CREATED, MODIFIED -> {
                if (document.isDirty()) {
                    // UNSAVED WORK. Only a person can say what happens to it, and until they do the
                    // document is left exactly as it is.
                    document.setState(DocumentState.CONFLICTING);
                } else {
                    document.setState(DocumentState.STALE);
                    reload(document);
                }
            }
        }
    }

    /** Takes the server's copy. Refused while dirty unless forced, for the reason above. */
    public Reply<Void> reload(Document document) {
        return reload(document, false);
    }

    public Reply<Void> reload(Document document, boolean force) {
        if (document.isDirty() && !force) {
            return Reply.failed(new FsError(FsError.CONFLICT,
                    "there is unsaved work; reloading would discard it", document.etag()));
        }
        PendingReply<Void> done = new PendingReply<>(null);
        // THE ONE DOOR, exactly as the open path takes. Read straight through `files().read` this took
        // the response's `content` and stopped, so a file over the server's inline limit came back
        // EMPTY: an external change to a big file replaced the document with nothing and marked it
        // clean, and the next save wrote that over the file. It also could not reload a resource a
        // PROVIDER serves, having nowhere to ask.
        workspace.read(document.resource())
                .onError(error -> {
                    document.setState(DocumentState.FAILED);
                    done.fail(error);
                })
                .then(response -> {
                    document.adopt(response.bytes(), response.etag());
                    discardBackup(document);
                    done.resolve(null);
                });
        return done;
    }

    // ── Saving ──────────────────────────────────────────────────────────────────────────────────

    public Reply<Void> save(Document document) {
        return save(document, SaveReason.EXPLICIT, false);
    }

    /**
     * Writes the document, quoting the etag it last saw.
     *
     * @param force write regardless of the etag — the deliberate "mine wins" a person chooses in a
     *              conflict dialog, and never a default
     */
    public Reply<Void> save(Document document, SaveReason reason, boolean force) {
        for (BiConsumer<Document, SaveReason> participant : onWillSave) {
            participant.accept(document, reason);
        }
        byte[] content = document.model().encode();
        // THE CONTENT'S IDENTITY, not the change count: what was written is what this document must
        // compare itself against afterwards, and undoing back to it must read as clean.
        int savedAt = document.contentVersion();

        PendingReply<Void> done = new PendingReply<>(null);
        workspace.files().write(document.resource(), content, force ? null : document.etag())
                .onError(error -> {
                    if (error instanceof FsError failure && failure.is(FsError.CONFLICT)) {
                        // THE ETAG THE FILE ACTUALLY HOLDS, as a field. A conflict without it is a
                        // conflict nothing can resolve but an unconditional overwrite.
                        document.setEtag(failure.actualEtag());
                        document.setState(DocumentState.CONFLICTING);
                    }
                    done.fail(error);
                })
                .then(etag -> {
                    // THE VERSION AT THE MOMENT THE BYTES WERE TAKEN, not now: an edit made while the
                    // write was crossing the wire must leave the document dirty afterwards, and a byte
                    // comparison cannot express that.
                    document.markSavedAt(savedAt, etag);
                    recordHistory(document, content);
                    discardBackup(document);
                    onDidSave.emit(document);
                    done.resolve(null);
                });
        return done;
    }

    /** Back to what is on disk, discarding this client's edits. */
    public Reply<Void> revert(Document document) {
        return reload(document, true);
    }

    // ── Backup and history ──────────────────────────────────────────────────────────────────────

    /**
     * Keeps the backup in step with whether there is anything to back up.
     *
     * <p>Clean means DISCARD and not merely "nothing to write": a save is not the only way a document
     * stops holding unsaved work, since {@code contentVersion} is the content's identity and comes back
     * when an undo returns to it. Returning early there left the pre-undo bytes on disk, to be offered
     * on the next launch as work the author had taken back — and offered as a CONFLICT rather than
     * discarded as identical if the file had moved meanwhile.</p>
     */
    private void backup(Document document) {
        Backup store = workspace.backup();
        if (store == null) return;
        if (!document.isDirty()) {
            store.discard(document.resource());
            return;
        }
        store.save(document.resource(), document.model().encode(), document.etag());
    }

    private void discardBackup(Document document) {
        discardBackup(document.resource());
    }

    /**
     * Throws away the backup held for one resource.
     *
     * <p>Public for the restore, which is the one caller that can discover a backup is worth nothing:
     * a save discards its own as it writes, but a backup whose content turns out to match the file has
     * to be dropped by whoever compared them, or it is offered again on every launch for ever.</p>
     */
    public void discardBackup(Resource resource) {
        Backup store = workspace.backup();
        if (store != null) store.discard(resource);
    }

    private void recordHistory(Document document, byte[] content) {
        LocalHistory history = workspace.history();
        if (history != null) history.record(document.resource(), content);
    }

    /**
     * Throws away every offer of unsaved work — the answer when somebody declines to restore it.
     *
     * <p>The half {@link #restorable} needs to be a question rather than a standing obligation: without
     * it a declined offer is made again on every launch, for ever, because nothing has said no.</p>
     */
    public void discardRestorable() {
        Backup store = workspace.backup();
        if (store != null) store.discardAll();
    }

    /**
     * Unsaved work from a previous session, offered as documents.
     *
     * <p>Each comes back <b>dirty against the etag it was in step with</b>, so if the file moved while
     * this client was away the next save is a conflict rather than a silent overwrite.</p>
     */
    public List<Backup.Entry> restorable() {
        Backup store = workspace.backup();
        return store == null ? List.of() : store.restorable();
    }
}
