package com.crystalgui.workbench.editor;

import java.util.Arrays;
import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.ConnectionGroup;
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
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Backup;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.protocol.FsError;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
 * <h3>One document, a view per pane</h3>
 *
 * <p>A file shown in two groups is ONE {@link Tab} — one document, one history, one dirty state — with a {@link View}
 * for each group, and each view keeps its own caret, selection, scroll and folds: VS Code's model beside its editor
 * panes, IntelliJ's {@code Document} beside its {@code FileEditor}s. A new view starts where the front one is, which
 * is what a split looks like: the same place, then its own way.</p>
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

    /**
     * The content is in — this tab's document exists and its model holds the file.
     *
     * <p>Not {@link #onDidOpen}, which is the tab APPEARING: that fires while the read is still in
     * flight, so at it there is no document, {@link Tab#editor()} answers null, and anything derived
     * from the content has nothing to act on. Everything that has to wait for the bytes — a restored
     * caret or camera, a diagnostic pass, a panel re-seeding itself — waits for this one.</p>
     */
    public final Signal.Value<Tab> onDidLoad = new Signal.Value<>();

    /** A tab closed. */
    public final Signal.Value<Tab> onDidClose = new Signal.Value<>();

    /**
     * A different tab is in front — the one with focus — and null when the last editor closed. <b>The one to follow
     * for a panel that describes whatever is being edited</b>, whatever kind it is; a panel that describes only one
     * kind follows {@link #follow} instead.
     *
     * <p>Not the dock's {@code onDidChangeActivePanel}, which announces a PANEL and fires while the read
     * behind it is still in flight; not {@link #onDidOpen}, which says nothing when you click between two
     * files that are already open. This fires from {@link #activate}, after {@link #active()} has moved,
     * so a listener reading it gets the new tab.</p>
     *
     * <pre>{@code
     * whileConnected(() -> workbench.editors().onDidChangeActive.connect(tab -> follow()));
     * }</pre>
     *
     * <p>The tab's {@link Tab#editor()} may still be null at this moment — activation and the content
     * landing are different events. A panel that needs the document waits for {@link #onDidLoad} as
     * well, or re-reads on both.</p>
     */
    public final Signal.Value<Tab> onDidChangeActive = new Signal.Value<>();

    /**
     * Which tabs are on screen changed: a group's front tab moved, or a group came or went. @see #visible()
     */
    public final Signal.Action onDidChangeVisible = new Signal.Action();

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

    /** A tab that has just been given a view and still has to be told it is in front. */
    @Nullable
    private Tab pendingActivation;

    /** The tab last asked to the front, loaded or not: what a read landing later may still bring forward. */
    @Nullable
    private Tab wanted;

    /** What the host shows: the front tab of every group, by input, so a tab opened after it was shown counts. */
    private Set<EditorInput> visibleInputs = Set.of();

    /** Every tab that has been in front, most recent first. @see #follow */
    private final List<Tab> recency = new ArrayList<>();

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
        return open(input, true);
    }

    /**
     * Opens an input, bringing it to the front only when {@code activate} is set.
     *
     * <pre>{@code
     * editors.open(input, false).then(tab -> …);   // behind whatever is in front: restoring a backup
     * }</pre>
     *
     * <p>An inactive open still loads, still announces {@link #onDidOpen} and {@link #onDidLoad}, and never
     * moves {@link #active()} — neither now nor when its read lands.</p>
     */
    public Reply<Tab> open(EditorInput input, boolean activate) {
        Tab existing = tabs.get(input);
        if (existing != null) {
            if (activate) activate(existing);
            return Reply.of(existing);
        }

        Tab tab = new Tab(input);
        tabs.put(input, tab);
        if (activate) wanted = tab;
        onDidOpen.emit(tab);

        PendingReply<Tab> opened = new PendingReply<>(() -> close(tab));
        documents.open(input.resource(), input.preferredKindId())
                .onError(error -> {
                    tab.fail(error);
                    opened.fail(error);
                })
                .then(reference -> {
                    tab.bind(reference);
                    // AFTER THE BIND, which is the whole point of the signal: before it there is no
                    // document on this tab and `editor()` answers null.
                    onDidLoad.emit(tab);
                    // ONLY IF NOTHING WAS BROUGHT FORWARD SINCE. A read lands whenever it lands, so a tab asked
                    // for first but loaded last took the front from the one chosen after it: a restored window's
                    // tab over the session's own, a file opened and then clicked away from.
                    if (activate && wanted == tab) activate(tab);
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
        wanted = tab;
        if (active == tab) return;
        if (active != null) active.setActive(false);
        active = tab;
        if (tab != null) {
            tab.setActive(true);
            recency.remove(tab);
            recency.add(0, tab);
        }
        // AFTER the field moves, so a listener that reads active() gets the new one.
        onDidChangeActive.emit(tab);
    }

    // ── What is on screen ───────────────────────────────────────────────────

    /**
     * The tabs on screen: the front tab of every group, the {@linkplain #active() active} one among them — VS Code's
     * {@code visibleTextEditors} beside its {@code activeTextEditor}.
     */
    public List<Tab> visible() {
        List<Tab> out = new ArrayList<>();
        for (Tab tab : tabs.values()) {
            if (visibleInputs.contains(tab.input())) out.add(tab);
        }
        return out;
    }

    /** Says which inputs are on screen. <b>The host's</b>, called as its layout changes; nothing else should. */
    public void setVisible(Collection<EditorInput> inputs) {
        Set<EditorInput> next = new LinkedHashSet<>(inputs);
        if (next.equals(visibleInputs)) return;
        visibleInputs = next;
        onDidChangeVisible.emit();
    }

    /**
     * The editor of the most recently active visible tab whose editor is a {@code kind}, or null — what a panel
     * describing one kind of document shows. A visible tab that was never in front counts after every one that was.
     */
    @Nullable
    public <E extends DocumentEditor> E lastVisible(Class<E> kind) {
        for (Tab tab : recency) {
            if (visibleInputs.contains(tab.input()) && kind.isInstance(tab.editor())) return kind.cast(tab.editor());
        }
        for (Tab tab : visible()) {
            if (!recency.contains(tab) && kind.isInstance(tab.editor())) return kind.cast(tab.editor());
        }
        return null;
    }

    /**
     * Follows the editor a panel describing ONE kind of document shows: {@link #lastVisible}, handed to
     * {@code follower} now and whenever it changes — null when no visible tab holds that kind.
     *
     * <pre>{@code
     * whileConnected(() -> workbench.editors().follow(UIBuilderView.class, this::show));
     *
     * private void show(@Nullable UIBuilderView editor) { ... }
     * }</pre>
     *
     * <p>Not the active tab: a panel that can describe only a {@code .cgui} has nothing to say about a CSS file
     * focused beside one, and emptying for it throws away a canvas still on screen and editable. So it keeps the
     * last one it could show while that is visible, as Unity's Hierarchy keeps its scene while a script is edited,
     * and moves when another becomes the most recent or it leaves the screen.</p>
     *
     * <ul>
     *   <li>Called again when the tab's editor is replaced — a load, a reload — and never for the same editor twice.</li>
     *   <li>Only visible tabs are asked for their editor, so following builds nothing that was not on screen.</li>
     * </ul>
     *
     * @return ends the following
     */
    public <E extends DocumentEditor> Connection follow(Class<E> kind, Consumer<? super E> follower) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(follower, "follower");
        Follower<E> following = new Follower<>(kind, follower);
        following.check();
        ConnectionGroup triggers = new ConnectionGroup();
        triggers.add(onDidChangeActive.connect(tab -> following.check()));
        triggers.add(onDidChangeVisible.connect(following::check));
        triggers.add(onDidOpen.connect(tab -> following.check()));
        triggers.add(onDidLoad.connect(tab -> following.check()));
        triggers.add(onDidClose.connect(tab -> following.check()));
        return triggers::dispose;
    }

    /** One {@link #follow}: what it last handed out, so only a change is handed out again. */
    private final class Follower<E extends DocumentEditor> {
        private final Class<E> kind;
        private final Consumer<? super E> follower;
        private boolean told;
        @Nullable
        private E editor;

        Follower(Class<E> kind, Consumer<? super E> follower) {
            this.kind = kind;
            this.follower = follower;
        }

        void check() {
            E now = lastVisible(kind);
            if (told && now == editor) return;
            told = true;
            editor = now;
            follower.accept(now);
        }
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
        recency.remove(tab);
        boolean wasInFront = active == tab;
        if (wanted == tab) wanted = null;
        if (wasInFront) {
            active = null;
            tab.setActive(false);
        }
        tab.release();
        // CLOSING THE FRONT TAB LEAVES NOTHING IN FRONT, and a panel following the editor has to hear
        // that as readily as a switch -- otherwise it keeps describing a document that is gone.
        if (wasInFront) onDidChangeActive.emit(null);
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

    /**
     * Makes the view mounted in {@code content} its tab's front one — the pane with focus, when a file is shown in
     * several. When that tab is the active one the old front view is told it went back and the new one that it came
     * forward; otherwise this only records which will be told.
     */
    public void focusView(@Nullable UIElement content) {
        if (content == null) return;
        for (Tab tab : tabs.values()) {
            View view = tab.viewIn(content);
            if (view == null) continue;
            if (tab.front == view) return;
            boolean announce = active == tab;
            if (announce) tab.setActive(false);
            tab.front = view;
            if (announce) tab.setActive(true);
            return;
        }
    }

    /**
     * Tells a freshly-built view that it is in front, <b>once it is on a surface</b>.
     *
     * <p>Called every frame. A view announces what it has to say — the caret, the indentation, the
     * encoding, the line separator — by resolving the status bar from its own position in the tree, so
     * being told before the dock has attached it is the same as not being told at all, except that
     * nothing reports it. Waiting on {@code view().document()} asks the only question that matters and
     * needs no frame counting: the dock attaches content during the animation phase whether it built it
     * outright or deferred a rebuild, so this lands on the first frame it can and stops.</p>
     */
    public void flushPendingActivation() {
        Tab pending = pendingActivation;
        if (pending == null) return;
        // The tab moved on while its view was being built: there is nothing left to announce.
        if (active != pending) {
            pendingActivation = null;
            return;
        }
        View view = pending.front;
        if (view == null || view.element.document() == null) return;
        pendingActivation = null;
        pending.setActive(true);
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
            //
            // INACTIVE: a backup landing put its file in front of the tab the session restored, with no dock
            // tab of its own, so everything following the active editor described a file nobody was shown.
            open(EditorInput.of(entry.resource()), false).then(tab -> {
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
     * One pane's look at a tab's document: an editor built for one dock group, with its own caret, selection and
     * scroll.
     */
    public final class View {

        private final DocumentEditor editor;
        /** Asked of the editor once: everything done with a view has to be done to the same element. */
        private final UIElement element;
        private boolean disposed;

        private View(DocumentEditor editor) {
            this.editor = editor;
            this.element = editor.view();
        }

        public DocumentEditor editor() {
            return editor;
        }

        public UIElement element() {
            return element;
        }

        /** In no pane: built and not shown yet, or left by a pane that moved away. Mounting reuses it. */
        private boolean isFree() {
            return !disposed && element.parent() == null;
        }

        private void dispose() {
            if (disposed) return;
            disposed = true;
            // BEFORE disposing it, while its element is still worth naming. An inspector RETAINS a detached subject
            // on purpose, so without this a closed document kept its sections on screen over whatever was opened next.
            InspectorRegistry.subjectClosed(element);
            editor.disposeView();
        }
    }

    /**
     * One open document, and the views showing it.
     *
     * <p>Holds the input it was opened with, the reference that keeps the document alive, and a {@link View} per
     * pane — built lazily, because a tab restored into a background group has a state and a title long before
     * anybody looks at it.</p>
     */
    public final class Tab {

        private final EditorInput input;
        @Nullable
        private DocumentReference reference;
        /** One per pane showing this document. */
        private final List<View> views = new ArrayList<>();
        /** The view in the pane that last had focus: what {@link #editor()} answers and what is told it is in front. */
        @Nullable
        private View front;
        @Nullable
        private ReplyError failure;
        private DocumentState state = DocumentState.LOADING;

        private Tab(EditorInput input) {
            this.input = input;
        }

        public EditorInput input() {
            return input;
        }

        /** The front view's element, or null. @see #editor() */
        @Nullable
        public UIElement viewElement() {
            View view = front();
            return view == null ? null : view.element;
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
         * The front view's editor, building the first view on first ask.
         *
         * <p>Null when the kind declares no editor, which is a real declaration: a kind that can be
         * opened, analysed and saved with nothing to look at it is what a build artefact is.</p>
         */
        @Nullable
        public DocumentEditor editor() {
            View view = front();
            return view == null ? null : view.editor;
        }

        /** Every view of this document, one per pane showing it. */
        public List<View> views() {
            return List.copyOf(views);
        }

        /** The view in the pane that last had focus, building the first one if there is none. */
        @Nullable
        public View front() {
            if (front == null) front = views.isEmpty() ? build() : views.get(0);
            return front;
        }

        /**
         * A view for a pane about to show this document: one no pane holds — built ahead, or left by a pane that
         * moved away — else a new one. A new one is what gives a split its own editor rather than the first's.
         */
        @Nullable
        public View mount() {
            for (View view : views) {
                if (view.isFree()) return view;
            }
            return build();
        }

        @Nullable
        private View build() {
            Document document = document();
            if (document == null || !document.kind().hasEditor()) return null;
            View view = new View(document.kind().createEditor(document));
            // WHAT A VIEW IN THE EDITOR REGION IS, applied here because it is true of EVERY kind's view
            // and not of the text one that happened to declare it. A tab's content sits flush against the
            // dock group, so the bottom two corners of the document ARE the island's -- and a square view
            // squares the island off under it. Only `texteditor` said so, so a .cgui and a shadergraph
            // came out pointed against a rounded panel.
            view.element.addClass(Workbench.FILE_EDITOR_CLASS);
            // THE OPENING'S, applied to the VIEW. A read-only opening and an editable one are two tabs
            // over ONE document -- which is what lets a diff's left pane sit beside the live file --
            // so the refusal cannot live on the model without taking the other tab down with it.
            if (input.isReadOnly()) view.editor.setReadOnly(true);
            // WHERE THE FRONT VIEW IS, for a second pane: a split opens on the same place and then goes its own way,
            // as VS Code's fillActiveEditorViewState and IntelliJ's currentStateAsFileEntry do. Else what the file
            // showed when last closed in this session. @see #captureViewState
            StateMap<?> seed = front != null ? stateOf(front) : viewStates.get(input);
            if (seed != null) view.editor.readViewState(seed);
            views.add(view);
            if (front == null) front = view;
            // AND TOLD IT IS IN FRONT, if it already is -- but NOT HERE. `setActive` runs when a tab
            // BECOMES active and does nothing when the view is not built yet, which is every restored
            // tab: the arrangement is applied while the documents are still crossing the wire. So the
            // tab in front came back with no caret position, no indent, no encoding and no line ending,
            // and switching away and back was what finally announced it -- a second activation, by
            // which time there was a view to hear it.
            //
            // Announcing on this line is a frame too early, and silently so. A view says where it is in
            // front BY WALKING UP FROM ITSELF -- `TextEditorView` resolves the status bar through its
            // own data context -- and at this moment the view has just been constructed and the dock has
            // not put it in the tree yet. Every readout was computed and dropped, which looks exactly
            // like never having been told. @see #flushPendingActivation
            if (active == this && front == view) pendingActivation = this;
            return view;
        }

        private StateMap<?> stateOf(View view) {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            view.editor.writeViewState(out);
            return out;
        }

        /**
         * Remembers what the view in {@code closing} is showing, so a reopen puts it back.
         *
         * <p>Called from {@code onWillClosePanel} rather than from {@link #release}, because the dock
         * detaches the widget first and a detached element has no geometry to ask for: the rects came
         * back empty and nothing was stored.</p>
         *
         * @param closing the pane's content, as the dock built it
         */
        public void captureViewState(@Nullable UIElement closing) {
            View view = viewIn(closing);
            if (view != null) viewStates.put(input, stateOf(view));
        }

        /**
         * Disposes the view one pane showed, for a pane that closed while another still shows this document. The
         * last goes with {@link EditorService#close}.
         */
        public void closeView(@Nullable UIElement closing) {
            View view = viewIn(closing);
            if (view == null) return;
            views.remove(view);
            if (front == view) {
                boolean announce = active == this;
                if (announce) view.editor.activated(false);
                front = views.isEmpty() ? null : views.get(0);
                if (announce) setActive(true);
            }
            view.dispose();
        }

        /** The view mounted in {@code content}: the element itself, or a banner column around it. */
        @Nullable
        private View viewIn(@Nullable UIElement content) {
            if (content == null) return null;
            for (View view : views) {
                if (view.element == content || content.contains(view.element)) return view;
            }
            return null;
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
            View view = front;
            // NOT INTO A VIEW THAT IS NOT ON A SURFACE YET. A view says what it has to say by walking up
            // from itself -- the status bar is resolved through its own data context -- so telling it
            // while the dock still has it detached publishes nothing and reports nothing. Parked, and
            // said again on the first frame it can be heard. @see #flushPendingActivation
            if (isActive && view != null && view.element.document() == null) {
                pendingActivation = this;
                return;
            }
            if (view != null) view.editor.activated(isActive);
            Document document = document();
            if (document != null && isActive) document.kind().contributeStatus(document);
        }

        private void release() {
            for (View view : views) view.dispose();
            views.clear();
            front = null;
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
