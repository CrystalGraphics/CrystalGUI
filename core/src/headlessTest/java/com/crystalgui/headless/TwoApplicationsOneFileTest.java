package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.net.CgInMemoryTransport;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.storage.config.CgInMemoryConfigStorage;
import com.crystalgui.core.async.Owner;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * <b>Two applications on one connection agree through the file.</b>
 *
 * <p>One {@link Workspace} per connection, a {@link WorkspaceClient} and a {@link WorkspaceDocuments} per
 * application. A save in one reaches the other's watch without waiting for the server's own announcement (this
 * fixture never sends one), a clean copy reloads and a dirty one conflicts; the second application is named in the
 * first's presence.</p>
 */
public class TwoApplicationsOneFileTest {

    private static final CgPath MAIN_PATH = CgPath.parse("proj:src/Main.java");
    private static final Resource MAIN = Resource.of(MAIN_PATH);

    private CgInMemoryTransport<Object>[] link;
    private CgProtocolConnection<Object> serverSide;
    private CgProtocolConnection<Object> clientSide;
    private WorkspaceService service;
    private Workspace workspace;
    private WorkspaceClient editor;
    private WorkspaceClient lab;
    private WorkspaceDocuments editorDocuments;
    private WorkspaceDocuments labDocuments;

    @Before
    public void setUp() {
        CgProtocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem().seed("proj:src/Main.java", "class Main {}\n");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", Paths.get("/srv/proj"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        WatchHub hub = new WatchHub(service);

        link = CgInMemoryTransport.pair();
        serverSide = CgProtocols.open(link[0], CgPlainOps.INSTANCE, () -> { }, "alice");
        clientSide = CgProtocols.open(link[1], CgPlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, "alice", CgPlainOps.INSTANCE)
                .installOn(serverSide::onRequest);

        workspace = Workspace.of(clientSide);
        workspace.setStorage(new CgInMemoryConfigStorage());
        editor = workspace.client("Crystal Editor");
        lab = workspace.client("VFX Lab");
        DocumentKinds kinds = new DocumentKinds();
        kinds.contribute(Owner.PROCESS, DocumentKind.of("test:java", "Java")
                .files(DocumentKind.FilePatterns.extension("java"))
                .model(TextDocumentModel::of));
        editorDocuments = new WorkspaceDocuments(editor, kinds);
        labDocuments = new WorkspaceDocuments(lab, kinds);
        pump();
    }

    @After
    public void tearDown() {
        CgProtocols.resetForTesting();
    }

    private void pump() {
        for (int i = 0; i < 12; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    private Document open(WorkspaceDocuments in) {
        CgReply<DocumentReference> reply = in.open(MAIN);
        pump();
        assertNotNull("the open failed: " + reply.error(), reply.result());
        return reply.result().document();
    }

    private static String text(Document document) {
        return document.as(TextDocumentModel.class).buffer().toString();
    }

    @Test
    public void aSaveInOneApplicationReloadsACleanCopyInTheOther() {
        Document mine = open(editorDocuments);
        Document theirs = open(labDocuments);
        mine.as(TextDocumentModel.class).buffer().insert(0, "// saved\n");

        CgReply<Void> saved = editorDocuments.save(mine);
        pump();

        assertNull("the save failed: " + saved.error(), saved.error());
        assertEquals("the other application's clean copy reloaded", "// saved\nclass Main {}\n", text(theirs));
        assertEquals(DocumentState.CLEAN, theirs.state());
    }

    @Test
    public void aSaveInOneApplicationConflictsADirtyCopyInTheOther() {
        Document mine = open(editorDocuments);
        Document theirs = open(labDocuments);
        theirs.as(TextDocumentModel.class).buffer().insert(0, "// lab's own\n");
        mine.as(TextDocumentModel.class).buffer().insert(0, "// editor's\n");

        editorDocuments.save(mine);
        pump();

        assertEquals(DocumentState.CONFLICTING, theirs.state());
        assertTrue("and nothing it typed was lost", text(theirs).startsWith("// lab's own"));
    }

    @Test
    public void anApplicationIsNamedInTheOthersPresenceWhileItHasTheFileOpen() {
        assertTrue(lab.presence().whoElseHasOpen(MAIN).isEmpty());

        Document mine = open(editorDocuments);
        assertEquals(List.of("Crystal Editor"), lab.presence().whoElseHasOpen(MAIN));
        assertFalse("it names the other, never itself", editor.presence().whoElseHasOpen(MAIN)
                .contains("Crystal Editor"));

        mine.as(TextDocumentModel.class).buffer().insert(0, "x");
        pump();
        assertEquals("unsaved work is named too", List.of("Crystal Editor"), lab.presence().whoIsEditing(MAIN));

        editor.dispose();
        assertTrue("a client that is gone leaves presence", lab.presence().whoElseHasOpen(MAIN).isEmpty());
    }

    @Test
    public void oneSubscriptionOnTheServerServesBothApplications() {
        open(editorDocuments);
        open(labDocuments);

        assertEquals("two applications hold clients of one workspace", 2, workspace.clientCount());
        assertEquals("a connection has one workspace", workspace, Workspace.of(clientSide));
    }
}
