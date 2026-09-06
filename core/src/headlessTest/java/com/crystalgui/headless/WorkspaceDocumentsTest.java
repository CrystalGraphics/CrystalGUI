package com.crystalgui.headless;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan/fs-rewrite.md} F4.2, D10 — <b>the save is the synchronisation point</b>.
 *
 * <p>Documents over the wire, end to end: open, edit, save, and what a change on the server means under
 * a clean buffer and under a dirty one. None of this could be written before — no test constructed a
 * workbench or called {@code openFile}, {@code saveActiveFile} or {@code isDirty}, and the document
 * layer had no direct test at all (N36).</p>
 */
public class WorkspaceDocumentsTest {

    private static Resource file(String path) {
        return Resource.of(CgPath.parse("proj:" + path));
    }

    private static final Resource MAIN = file("src/Main.java");
    private static final CgPath MAIN_PATH = CgPath.parse("proj:src/Main.java");

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private WorkspaceService service;
    private WatchHub hub;
    private WorkspaceBinding<Object> binding;
    private Workspace workspace;
    private WorkspaceDocuments documents;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("proj:src/Main.java", "class Main {}\n")
                .seed("proj:README.md", "# hi\n");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", Paths.get("/srv/proj"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        hub = new WatchHub(service);

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        binding = new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, "alice", PlainOps.INSTANCE);
        binding.installOn(serverSide::onRequest);

        workspace = Workspace.of(clientSide).setStorage(new InMemoryConfigStorage());
        DocumentKinds kinds = new DocumentKinds();
        kinds.register(DocumentKind.of("test:java", "Java")
                .files(DocumentKind.FilePatterns.extension("java"),
                        DocumentKind.FilePatterns.extension("md"))
                .model(TextDocumentModel::of));
        documents = new WorkspaceDocuments(workspace, kinds);
        pump();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void pump() {
        for (int i = 0; i < 12; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    /** One server tick's worth of watch notifications, delivered. */
    private void notifyChange(CgPath path, CgFileEvent.Kind kind) {
        deliverTick(List.of(CgFileEvent.of(kind, path)));
    }

    /** A whole tick, through the same coalescing and per-peer filter a real one takes. */
    private void deliverTick(List<CgFileEvent> events) {
        Map<Object, List<FsMessages.FileChange>> byPeer = hub.tick(WorkspaceActor.LOCAL, events);
        List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
        if (!mine.isEmpty()) {
            serverSide.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                    FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                            new FsMessages.ChangedNotification(mine))));
        }
        pump();
    }

    /** A rename the server states as a fact, delivered to the client. */
    private void notifyRename(CgPath from, CgPath to, String etag) {
        FsMessages.FileChange change = hub.noteRenamed(from, to, etag);
        serverSide.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                        new FsMessages.ChangedNotification(List.of(change)))));
        pump();
    }

    private Document open(Resource resource) {
        Reply<DocumentReference> reply = documents.open(resource);
        pump();
        assertNotNull("the open failed: " + reply.error(), reply.result());
        return reply.result().document();
    }

    private void type(Document document, String text) {
        document.as(TextDocumentModel.class).buffer().insert(0, text);
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void openingReadsTheFileAndStartsClean() {
        Document document = open(MAIN);

        assertEquals("class Main {}\n", document.as(TextDocumentModel.class).buffer().toString());
        assertEquals(DocumentState.CLEAN, document.state());
        assertFalse(document.isDirty());
        assertNotNull(document.etag());
    }

    /** Two callers for one resource are one document — two split panes, a tab and the Problems panel. */
    @Test
    public void openingTwiceIsOneDocument() {
        Document first = open(MAIN);
        Document second = open(MAIN);

        assertSame(first, second);
        assertEquals(1, documents.all().size());
    }

    @Test
    public void aFileNoKindClaimsIsRefusedRatherThanOpenedEmpty() {
        Reply<DocumentReference> reply = documents.open(file("image.png"));
        pump();

        assertNull(reply.result());
        assertEquals(FsError.INVALID_PATH, reply.error().code());
    }

    @Test
    public void aMissingFileFailsWithItsCode() {
        Reply<DocumentReference> reply = documents.open(file("nope.java"));
        pump();

        assertEquals(FsError.NOT_FOUND, reply.error().code());
    }

    // ── Editing and saving ──────────────────────────────────────────────────────────────────────

    @Test
    public void editingMakesItDirtyAndSavingMakesItCleanAgain() {
        Document document = open(MAIN);

        type(document, "// typed\n");
        assertTrue(document.isDirty());
        assertEquals(DocumentState.DIRTY, document.state());

        Reply<Void> saved = documents.save(document);
        pump();

        assertNull("the save failed: " + saved.error(), saved.error());
        assertFalse(document.isDirty());
        assertEquals(DocumentState.CLEAN, document.state());
        assertEquals("// typed\nclass Main {}\n",
                new String(service.read(WorkspaceActor.LOCAL, MAIN_PATH).content(),
                        StandardCharsets.UTF_8));
    }

    /**
     * <b>An edit made while a save is in flight leaves the document dirty.</b>
     *
     * <p>The bytes written were taken before it, so recording "the version now" would call the document
     * clean while holding edits the file does not have — and the next reload would discard them without
     * asking. A byte comparison cannot express this at all.</p>
     */
    @Test
    public void anEditDuringASaveLeavesTheDocumentDirty() {
        Document document = open(MAIN);
        type(document, "// first\n");

        Reply<Void> saving = documents.save(document);
        // The write is on the wire. The person keeps typing.
        type(document, "// second\n");
        pump();

        assertNull(saving.error());
        assertTrue("the second edit is not on disk and the document must say so", document.isDirty());
    }

    /**
     * <b>A save that does not catch up leaves the backup standing.</b> The bytes written were taken
     * before the second edit, so the document is still dirty afterwards — and a discard keyed on the
     * save rather than on what is left unsaved takes away the only copy of that edit.
     */
    @Test
    public void aSaveThatLeavesWorkUnsavedKeepsItsBackup() {
        Document document = open(MAIN);
        type(document, "// first\n");

        Reply<Void> saving = documents.save(document);
        type(document, "// second\n");
        pump();

        assertNull(saving.error());
        assertTrue(document.isDirty());
        assertEquals("the second edit is on disk nowhere else", 1, documents.restorable().size());
    }

    /** A save participant may edit the document before its bytes are taken. */
    @Test
    public void aSaveParticipantRunsBeforeTheBytesAreTaken() {
        Document document = open(MAIN);
        documents.onWillSave.add((doc, reason) ->
                doc.as(TextDocumentModel.class).buffer().insert(0, "// added by a participant\n"));

        documents.save(document);
        pump();

        assertTrue(new String(service.read(WorkspaceActor.LOCAL, MAIN_PATH).content(),
                StandardCharsets.UTF_8).startsWith("// added by a participant"));
    }

    // ── A change on the server ──────────────────────────────────────────────────────────────────

    /** <b>Clean: it reloads.</b> No prompt, no decision — there is nothing at risk. */
    @Test
    public void aChangeUnderACleanDocumentReloadsIt() {
        Document document = open(MAIN);

        service.write(WorkspaceActor.LOCAL, MAIN_PATH,
                "somebody else wrote this\n".getBytes(StandardCharsets.UTF_8), null);
        notifyChange(MAIN_PATH, CgFileEvent.Kind.MODIFIED);

        assertEquals("somebody else wrote this\n",
                document.as(TextDocumentModel.class).buffer().toString());
        assertEquals(DocumentState.CLEAN, document.state());
        assertFalse("and undo cannot resurrect the replaced text", document.history().canUndo());
    }

    /**
     * <b>...and a file over the inline limit reloads WHOLE.</b>
     *
     * <p>{@code fs/read} answers inline or with a TRANSFER, so a caller that takes {@code content} and
     * stops is right for every small file and silently wrong for a large one. {@code Workspace.read}
     * was fixed for the OPEN path; the reload path still read straight through {@code files().read},
     * so an external change to a big file adopted an EMPTY array and marked the document clean --
     * and the next save wrote that emptiness over somebody's work.</p>
     *
     * <p>The size is the whole test: identical to the one above in every other respect, which is what
     * makes the pair a statement about the limit rather than about reloading.</p>
     */
    @Test
    public void aChangeUnderACleanDocumentOverTheInlineLimitReloadsAllOfIt() {
        CgPath big = CgPath.parse("proj:src/Big.java");
        service.create(WorkspaceActor.LOCAL, big, body('a'));
        Document document = open(Resource.of(big));
        assertEquals("the OPEN path already joins the chunks",
                BIG_BYTES, document.as(TextDocumentModel.class).buffer().toString().length());

        service.write(WorkspaceActor.LOCAL, big, body('b'), null);
        notifyChange(big, CgFileEvent.Kind.MODIFIED);
        // A CHUNK IS A ROUND TRIP AND THE PULL IS SERIAL, so a reload of this file is six of them --
        // where every other test in this class settles in one.
        pump();

        String reloaded = document.as(TextDocumentModel.class).buffer().toString();
        assertEquals("every byte of it, not the empty content a transfer carries",
                BIG_BYTES, reloaded.length());
        assertEquals('b', reloaded.charAt(0));
        assertEquals(DocumentState.CLEAN, document.state());
    }

    /** Comfortably over {@code WorkspaceBinding.INLINE_LIMIT}, so the server answers a transfer. */
    private static final int BIG_BYTES = 300_000;

    private static byte[] body(char fill) {
        byte[] bytes = new byte[BIG_BYTES];
        java.util.Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    /** <b>Dirty: it is marked and left alone.</b> Only a person can say what happens to unsaved work. */
    @Test
    public void aChangeUnderADirtyDocumentIsAConflictAndChangesNothing() {
        Document document = open(MAIN);
        type(document, "// mine\n");

        service.write(WorkspaceActor.LOCAL, MAIN_PATH,
                "theirs\n".getBytes(StandardCharsets.UTF_8), null);
        notifyChange(MAIN_PATH, CgFileEvent.Kind.MODIFIED);

        assertEquals(DocumentState.CONFLICTING, document.state());
        assertTrue("the buffer is untouched", document.as(TextDocumentModel.class)
                .buffer().toString().startsWith("// mine"));
    }

    /** And reloading one is refused, rather than quietly discarding the work. */
    @Test
    public void reloadingADirtyDocumentIsRefusedUnlessForced() {
        Document document = open(MAIN);
        type(document, "// mine\n");

        Reply<Void> refused = documents.reload(document);
        pump();
        assertEquals(FsError.CONFLICT, refused.error().code());

        Reply<Void> forced = documents.reload(document, true);
        pump();
        assertNull(forced.error());
        assertFalse(document.isDirty());
    }

    /** Saving over a file that moved is a conflict carrying the etag it now holds. */
    @Test
    public void savingOverAChangedFileIsAConflictWithTheActualEtag() {
        Document document = open(MAIN);
        type(document, "// mine\n");
        service.write(WorkspaceActor.LOCAL, MAIN_PATH,
                "theirs\n".getBytes(StandardCharsets.UTF_8), null);

        List<ReplyError> errors = new ArrayList<>();
        documents.save(document).onError(errors::add);
        pump();

        assertEquals(FsError.CONFLICT, errors.get(0).code());
        assertEquals(DocumentState.CONFLICTING, document.state());
        assertNotNull(((FsError) errors.get(0)).actualEtag());
        assertEquals("and the etag is taken up, so the forced save can succeed",
                ((FsError) errors.get(0)).actualEtag(), document.etag());
    }

    /** "Mine wins" is a deliberate forced save, never a default. */
    @Test
    public void aForcedSaveOverwrites() {
        Document document = open(MAIN);
        type(document, "// mine\n");
        service.write(WorkspaceActor.LOCAL, MAIN_PATH,
                "theirs\n".getBytes(StandardCharsets.UTF_8), null);
        documents.save(document);
        pump();

        Reply<Void> forced = documents.save(document, WorkspaceDocuments.SaveReason.EXPLICIT, true);
        pump();

        assertNull(forced.error());
        assertTrue(new String(service.read(WorkspaceActor.LOCAL, MAIN_PATH).content(),
                StandardCharsets.UTF_8).startsWith("// mine"));
    }

    @Test
    public void aDeletionOrphansTheDocumentRatherThanClosingIt() {
        Document document = open(MAIN);

        service.delete(WorkspaceActor.LOCAL, MAIN_PATH, false);
        notifyChange(MAIN_PATH, CgFileEvent.Kind.DELETED);

        assertEquals(DocumentState.ORPHANED, document.state());
        assertEquals("the content is still here, and saving would recreate the file",
                "class Main {}\n", document.as(TextDocumentModel.class).buffer().toString());
    }

    // ── Backup ──────────────────────────────────────────────────────────────────────────────────

    /** Unsaved work is written where the server cannot lose it, and a save discards it. */
    @Test
    public void unsavedWorkIsBackedUpAndASaveDiscardsIt() {
        Document document = open(MAIN);

        type(document, "// half-typed\n");
        assertEquals(1, documents.restorable().size());
        assertEquals(MAIN, documents.restorable().get(0).resource());

        documents.save(document);
        pump();

        assertTrue("what is on disk needs no backup", documents.restorable().isEmpty());
    }

    /**
     * <b>Undoing back to the saved state discards the backup too.</b> A save is not the only way a
     * document stops holding unsaved work — {@code contentVersion} is the content's identity and comes
     * back when an undo returns to it — so a backup written on the way out has to be dropped on the way
     * back, or the next launch offers work the author took back as if it were still pending.
     */
    @Test
    public void undoingBackToCleanDiscardsTheBackup() {
        Document document = open(MAIN);

        type(document, "// half-typed\n");
        assertEquals(1, documents.restorable().size());

        assertTrue("the edit is undoable", document.model().history().undo());
        assertFalse("an undo back to the saved state leaves nothing unsaved", document.isDirty());

        assertTrue("a document holding no unsaved work holds no backup",
                documents.restorable().isEmpty());
    }

    /** And the history records what was saved, which is where "keep mine" survives. */
    @Test
    public void aSaveIsRecordedInLocalHistory() {
        Document document = open(MAIN);
        type(document, "// first\n");
        documents.save(document);
        pump();

        assertEquals(1, workspace.history().entriesOf(MAIN).size());
        assertNotNull(workspace.history().mergeBase(MAIN));
    }

    /**
     * <b>A rename takes the backup with it.</b> A backup is filed under its resource, so the one
     * written before the move is discarded by nothing afterwards: a save discards under the new name
     * and the orphan is offered on every launch as unsaved work for a path that no longer exists —
     * which cannot be opened, so the restore cannot compare it away either.
     */
    @Test
    public void aRenameTakesTheBackupWithIt() {
        Document document = open(MAIN);
        type(document, "// half-typed\n");
        assertEquals(1, documents.restorable().size());

        notifyRename(MAIN_PATH, CgPath.parse("proj:src/Renamed.java"), "etag-2");

        assertEquals("still exactly one backup", 1, documents.restorable().size());
        assertEquals("and it is filed under where the document went",
                file("src/Renamed.java"), documents.restorable().get(0).resource());
    }

    /** And the history follows the file, so a renamed document is not one with no past. */
    @Test
    public void aRenameTakesTheLocalHistoryWithIt() {
        Document document = open(MAIN);
        type(document, "// first\n");
        documents.save(document);
        pump();
        assertEquals(1, workspace.history().entriesOf(MAIN).size());

        notifyRename(MAIN_PATH, CgPath.parse("proj:src/Renamed.java"), "etag-2");

        Resource renamed = file("src/Renamed.java");
        assertEquals("the history moved with the file", 1,
                workspace.history().entriesOf(renamed).size());
        assertTrue("and nothing is stranded under the old name",
                workspace.history().entriesOf(MAIN).isEmpty());
        assertNotNull("so a three-way merge still has its base",
                workspace.history().mergeBase(renamed));
    }

    /**
     * <b>And for a file nothing has open</b> — which is how a rename usually happens. There is no
     * document and no watch on it, so a hand-off hung off the open document cannot see this at all.
     */
    @Test
    public void aRenameMovesTheStoresOfAFileNobodyHasOpen() {
        Resource closed = file("src/Closed.java");
        workspace.history().record(closed, "was here\n".getBytes(StandardCharsets.UTF_8));
        workspace.backup().save(closed, "unsaved\n".getBytes(StandardCharsets.UTF_8), "etag-1");

        notifyRename(CgPath.parse("proj:src/Closed.java"),
                CgPath.parse("proj:src/Moved.java"), "etag-2");

        Resource moved = file("src/Moved.java");
        assertEquals("the history came with it", 1, workspace.history().entriesOf(moved).size());
        assertNotNull("and so did the backup", workspace.backup().get(moved));
        assertNull("with nothing stranded behind", workspace.backup().get(closed));
        assertTrue(workspace.history().entriesOf(closed).isEmpty());
    }

    /**
     * <b>And for a rename nobody performed in the app.</b> The explorer holds a recursive watch on each
     * project root, so a change to a file nothing has open still reaches this client; {@code WatchHub}
     * pairs the delete and the create a filesystem watcher reports into the one RENAMED that carries
     * the stores. Nothing else puts that pairing in front of a client.
     */
    @Test
    public void anExternalRenameOfAClosedFileMovesItsStores() {
        workspace.watch(Resource.of(CgPath.parse("proj:")), true);
        pump();

        Resource closed = file("README.md");
        CgPath from = CgPath.parse("proj:README.md");
        CgPath to = CgPath.parse("proj:NOTES.md");
        workspace.history().record(closed, "was here\n".getBytes(StandardCharsets.UTF_8));
        workspace.backup().save(closed, "unsaved\n".getBytes(StandardCharsets.UTF_8), "etag-1");

        // The hub has to have stat-ed it once, or its deletion carries no etag to pair against.
        deliverTick(List.of(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, from)));

        // Moved from outside: the file really moves -- mtime and so etag intact -- and the watcher
        // reports the two halves separately, which is all any of NIO, inotify or Win32 ever gives.
        service.rename(WorkspaceActor.LOCAL, from, to, false);
        deliverTick(List.of(CgFileEvent.of(CgFileEvent.Kind.DELETED, from),
                CgFileEvent.of(CgFileEvent.Kind.CREATED, to)));

        Resource moved = file("NOTES.md");
        assertEquals("the history came with it", 1, workspace.history().entriesOf(moved).size());
        assertNotNull("and so did the backup", workspace.backup().get(moved));
        assertNull("with nothing stranded behind", workspace.backup().get(closed));
    }

    // ── Lifetime ────────────────────────────────────────────────────────────────────────────────

    /** The last reference releases the document AND its watch. */
    @Test
    public void closingTheLastReferenceUnwatchesTheFile() {
        Reply<DocumentReference> first = documents.open(MAIN);
        pump();
        DocumentReference held = documents.reference(MAIN);
        assertEquals(1, hub.subscriptionCount("alice"));

        first.result().dispose();
        pump();
        assertEquals("a second holder keeps it open", 1, hub.subscriptionCount("alice"));

        held.dispose();
        pump();
        assertNull(documents.get(MAIN));
        assertEquals(0, hub.subscriptionCount("alice"));
    }
}
