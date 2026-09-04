package com.crystalgui.headless;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.EditorInput;
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
import com.crystalgui.workbench.editor.EditorService;

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
 * {@code plan_fs_rewrite.md} F5.1, N1 — <b>one open lane, whatever kind of thing it is.</b>
 *
 * <p>These are the workbench-level behaviours the plan says could not be written before: open, edit,
 * save, external change under a clean buffer and under a dirty one, delete under an open tab, rename
 * under an open tab. No test constructed a {@code Workbench} or called {@code openFile}, and a project
 * file and a library class went down two different lanes — the second of which cost roughly four
 * hundred lines of re-derived open, adopt and presentation.</p>
 */
public class EditorServiceTest {

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
    private DocumentKinds kinds;
    private EditorService editors;

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
        kinds = new DocumentKinds();
        // A kind with a MODEL and no EDITOR, which is a real declaration and what lets this whole
        // suite be headless: everything asserted here is about the lane, not about a widget.
        kinds.register(DocumentKind.of("test:text", "Text")
                .files(DocumentKind.FilePatterns.extension("java"),
                        DocumentKind.FilePatterns.extension("md"))
                .model(TextDocumentModel::of));
        documents = new WorkspaceDocuments(workspace, kinds);
        editors = new EditorService(workspace, documents, kinds);
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

    private void notifyChange(CgPath path, CgFileEvent.Kind kind) {
        Map<Object, List<FsMessages.FileChange>> byPeer =
                hub.tick(WorkspaceActor.LOCAL, List.of(CgFileEvent.of(kind, path)));
        List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
        if (!mine.isEmpty()) {
            serverSide.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                    FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                            new FsMessages.ChangedNotification(mine))));
        }
        pump();
    }

    private EditorService.Tab open(Resource resource) {
        Reply<EditorService.Tab> reply = editors.open(resource);
        pump();
        return reply.result() != null ? reply.result() : editors.tabFor(EditorInput.of(resource));
    }

    // ── The one lane ────────────────────────────────────────────────────────────────────────────

    @Test
    public void openingAFileGivesATabWithItsDocument() {
        EditorService.Tab tab = open(MAIN);

        assertNotNull(tab);
        assertEquals(DocumentState.CLEAN, tab.state());
        assertEquals("Main.java", tab.title());
        assertNotNull(tab.document());
        assertSame(tab, editors.active());
    }

    /**
     * <b>The tab exists immediately, in LOADING.</b> Which is what lets a session restore put twelve
     * tabs on screen at once rather than revealing them one round trip at a time.
     */
    @Test
    public void aTabExistsBeforeItsReadLands() {
        editors.open(MAIN);

        EditorService.Tab tab = editors.tabFor(EditorInput.of(MAIN));
        assertNotNull("the tab is there before anything has been delivered", tab);
        assertEquals(DocumentState.LOADING, tab.state());
        assertNull(tab.document());
    }

    /** Opening what is already open brings it forward rather than opening it twice. */
    @Test
    public void openingAnOpenFileAgainActivatesIt() {
        EditorService.Tab first = open(MAIN);
        open(file("README.md"));
        assertFalse(first == editors.active());

        EditorService.Tab again = open(MAIN);

        assertSame(first, again);
        assertSame(first, editors.active());
        assertEquals(2, editors.tabs().size());
    }

    /** Two inputs for one resource are two tabs when they differ — a read-only view beside the live one. */
    @Test
    public void aReadOnlyViewIsItsOwnTab() {
        editors.open(EditorInput.of(MAIN));
        editors.open(EditorInput.of(MAIN).readOnly());
        pump();

        assertEquals(2, editors.tabs().size());
        assertEquals("but there is one document behind them", 1, documents.all().size());
    }

    @Test
    public void aFileThatCannotBeOpenedFailsItsTabRatherThanVanishing() {
        Reply<EditorService.Tab> reply = editors.open(file("nope.java"));
        pump();

        EditorService.Tab tab = editors.tabFor(EditorInput.of(file("nope.java")));
        assertNotNull("a failure must leave something on screen to retry", tab);
        assertEquals(DocumentState.FAILED, tab.state());
        assertEquals(FsError.NOT_FOUND, tab.failure().code());
        assertEquals(FsError.NOT_FOUND, reply.error().code());
    }

    /** And it can be retried, which is what a "retry" affordance on the tab calls. */
    @Test
    public void aFailedTabCanBeRetried() {
        Reply<EditorService.Tab> first = editors.open(file("later.java"));
        pump();
        assertEquals(DocumentState.FAILED, editors.tabFor(EditorInput.of(file("later.java"))).state());

        service.create(WorkspaceActor.LOCAL, CgPath.parse("proj:later.java"),
                "class Later {}\n".getBytes(StandardCharsets.UTF_8));
        EditorService.Tab tab = editors.tabFor(EditorInput.of(file("later.java")));
        tab.retry();
        pump();

        assertEquals(DocumentState.CLEAN,
                editors.tabFor(EditorInput.of(file("later.java"))).state());
    }

    // ── Editing and saving ──────────────────────────────────────────────────────────────────────

    @Test
    public void editingMarksTheTabAndSavingClearsIt() {
        EditorService.Tab tab = open(MAIN);
        List<EditorService.Tab> announced = new ArrayList<>();
        editors.onDidChangeState.connect(announced::add);

        tab.document().as(TextDocumentModel.class).buffer().insert(0, "// typed\n");
        assertTrue(tab.isDirty());
        assertEquals(DocumentState.DIRTY, tab.state());
        assertFalse("and the strip is told", announced.isEmpty());

        editors.saveActive();
        pump();

        assertFalse(tab.isDirty());
        assertEquals(DocumentState.CLEAN, tab.state());
    }

    @Test
    public void saveAllWritesEveryDirtyDocument() {
        EditorService.Tab one = open(MAIN);
        EditorService.Tab two = open(file("README.md"));
        one.document().as(TextDocumentModel.class).buffer().insert(0, "// a\n");
        two.document().as(TextDocumentModel.class).buffer().insert(0, "// b\n");

        editors.saveAll();
        pump();

        assertFalse(one.isDirty());
        assertFalse(two.isDirty());
    }

    // ── What happens to an open tab ─────────────────────────────────────────────────────────────

    @Test
    public void anExternalChangeUnderACleanTabReloadsIt() {
        EditorService.Tab tab = open(MAIN);

        service.write(WorkspaceActor.LOCAL, MAIN_PATH,
                "theirs\n".getBytes(StandardCharsets.UTF_8), null);
        notifyChange(MAIN_PATH, CgFileEvent.Kind.MODIFIED);

        assertEquals(DocumentState.CLEAN, tab.state());
        assertEquals("theirs\n", tab.document().as(TextDocumentModel.class).buffer().toString());
    }

    @Test
    public void anExternalChangeUnderADirtyTabConflicts() {
        EditorService.Tab tab = open(MAIN);
        tab.document().as(TextDocumentModel.class).buffer().insert(0, "// mine\n");

        service.write(WorkspaceActor.LOCAL, MAIN_PATH,
                "theirs\n".getBytes(StandardCharsets.UTF_8), null);
        notifyChange(MAIN_PATH, CgFileEvent.Kind.MODIFIED);

        assertEquals(DocumentState.CONFLICTING, tab.state());
    }

    /** A deleted file leaves the tab open and orphaned — the content is still worth saving. */
    @Test
    public void aDeletionUnderAnOpenTabOrphansIt() {
        EditorService.Tab tab = open(MAIN);

        service.delete(WorkspaceActor.LOCAL, MAIN_PATH, false);
        notifyChange(MAIN_PATH, CgFileEvent.Kind.DELETED);

        assertEquals(DocumentState.ORPHANED, tab.state());
        assertEquals("and the tab is still there", 1, editors.tabs().size());
    }

    /**
     * <b>A rename under an open tab moves it.</b> The document is the identity, so the tab follows —
     * it arrived as a deletion before, and the client closed the tab.
     */
    @Test
    public void aRenameUnderAnOpenTabRetargetsIt() {
        EditorService.Tab tab = open(MAIN);
        CgPath renamed = CgPath.parse("proj:src/Renamed.java");

        service.rename(WorkspaceActor.LOCAL, MAIN_PATH, renamed, false);
        Map<Object, List<FsMessages.FileChange>> byPeer = Map.of("alice",
                List.of(hub.noteRenamed(MAIN_PATH, renamed,
                        service.stat(WorkspaceActor.LOCAL, renamed).etag())));
        serverSide.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                        new FsMessages.ChangedNotification(byPeer.get("alice")))));
        pump();

        assertEquals("the tab reads the DOCUMENT's address, not the one it was opened with",
                Resource.of(renamed), tab.resource());
        assertEquals("Renamed.java", tab.title());
    }

    // ── Closing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Closing a tab releases that tab's reference and nothing more.
     *
     * <p>The document is disposed by its LAST holder, which may be the Problems panel or an index —
     * later than the tab and never earlier. That ordering inverted is the "Parser is closed" defect.
     */
    @Test
    public void closingATabDoesNotDisposeADocumentSomethingElseHolds() {
        EditorService.Tab tab = open(MAIN);
        Document document = tab.document();
        var heldElsewhere = documents.reference(MAIN);

        editors.close(tab);

        assertEquals(0, editors.tabs().size());
        assertFalse("something else still holds it", document.isDisposed());
        assertSame(document, documents.get(MAIN));

        heldElsewhere.dispose();
        assertTrue(document.isDisposed());
    }

    @Test
    public void closingTheLastTabWithNothingElseHoldingItReleasesTheDocument() {
        EditorService.Tab tab = open(MAIN);
        Document document = tab.document();

        editors.close(tab);

        assertTrue(document.isDisposed());
        assertNull(documents.get(MAIN));
        assertNull(editors.active());
    }

    // ── Hot exit ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Closing with unsaved work asks nothing and gives it back.</b> VS Code's {@code files.hotExit}:
     * a modal between a person and closing, at the moment they have already decided, is what this
     * replaces.
     */
    @Test
    public void closingTheScreenWithADirtyDocumentAsksNothingAndRestoresIt() {
        EditorService.Tab tab = open(MAIN);
        tab.document().as(TextDocumentModel.class).buffer().insert(0, "// half-typed\n");
        assertEquals(1, workspace.backup().restorable().size());

        // The screen closes. Nothing is prompted and nothing is saved.
        editors.closeAll();
        assertEquals(0, editors.tabs().size());
        assertEquals("the work is still on offer", 1, workspace.backup().restorable().size());

        int restored = editors.restoreUnsavedWork();
        pump();

        assertEquals(1, restored);
        assertNotNull(editors.tabFor(EditorInput.of(MAIN)));
    }

    @Test
    public void savedWorkIsNotOfferedBack() {
        EditorService.Tab tab = open(MAIN);
        tab.document().as(TextDocumentModel.class).buffer().insert(0, "// typed\n");
        editors.saveActive();
        pump();

        editors.closeAll();

        assertEquals(0, editors.restoreUnsavedWork());
    }
}
