package com.crystalgui.headless;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>Presence, end to end</b> — two people in one file.
 *
 * <p>It was built on both ends with no wire between them. The server tracked who had what open and who
 * was editing it ({@code WorkspacePresence}, fed by every read and every watch); the client had a
 * {@code Presence} facade with {@code whoIsEditing} and {@code whoElseHasOpen}, and a subscription to
 * {@code fs/presence}. <b>Nothing in the application ever built a {@code PresenceNotification}</b>, so
 * every one of those accessors answered "nobody", for ever — which is indistinguishable from a quiet
 * workspace, and is why the conflict dialog could never name anybody.</p>
 *
 * <p>And the half the server cannot know had no route at all: dirtiness is
 * {@code version != savedVersion} on a document only the client holds, so without {@code fs/editing}
 * "is somebody typing in this" was unanswerable by construction rather than merely unsent.</p>
 */
public class PresenceTest {

    private static final CgPath FILE = CgPath.parse("p:a.txt");
    private static final CgPath OTHER = CgPath.parse("p:b.txt");
    private static final Resource FILE_RESOURCE = Resource.of(FILE);

    private static WorkspaceActor actor(String id, String name) {
        return new WorkspaceActor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return name;
            }
        };
    }

    private static final WorkspaceActor ALICE = actor("alice", "Alice");
    private static final WorkspaceActor BOB = actor("bob", "Bob");

    private WorkspaceService service;
    private WatchHub hub;

    private Peer alice;
    private Peer bob;

    /** One client, its connection and its binding — everything a person is on this wire. */
    private final class Peer {
        final InMemoryTransport<Object>[] link;
        final ProtocolConnection<Object> server;
        final ProtocolConnection<Object> client;
        final WorkspaceBinding<Object> binding;
        final Workspace workspace;
        final WorkspaceDocuments documents;

        Peer(WorkspaceActor who) {
            link = InMemoryTransport.pair();
            server = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, who.id());
            client = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
            binding = new WorkspaceBinding<>(service, hub, who, who, PlainOps.INSTANCE);
            binding.installOn(server::onRequest);
            workspace = Workspace.over(client::call, client::onNotify, PlainOps.INSTANCE)
                    .setStorage(new InMemoryConfigStorage());
            DocumentKinds kinds = new DocumentKinds();
            kinds.register(DocumentKind.of("test:text", "Text")
                    .files(DocumentKind.FilePatterns.extension("txt"))
                    .model(TextDocumentModel::of));
            documents = new WorkspaceDocuments(workspace, kinds);
        }

        /** What the host's per-tick fan-out does, for this peer alone. */
        void deliverPresence() {
            FsMessages.PresenceNotification mine = binding.presenceFor();
            if (mine == null) return;
            server.notify(FsMethods.PRESENCE, new StateMap<>(PlainOps.INSTANCE,
                    FsMessages.presenceNotification().encode(PlainOps.INSTANCE, mine)));
        }

        Document open(CgPath path) {
            Reply<DocumentReference> reply = documents.open(Resource.of(path));
            pump();
            assertNotNull("the open failed: " + reply.error(), reply.result());
            return reply.result().document();
        }
    }

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("p:a.txt", "one")
                .seed("p:b.txt", "two");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("p", "P", Paths.get("/srv/p"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        hub = new WatchHub(service);

        alice = new Peer(ALICE);
        bob = new Peer(BOB);
        pump();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void pump() {
        for (int i = 0; i < 12; i++) {
            alice.link[0].deliver();
            alice.link[1].deliver();
            bob.link[0].deliver();
            bob.link[1].deliver();
            alice.server.tick();
            alice.client.tick();
            bob.server.tick();
            bob.client.tick();
        }
    }

    /** A tick of the whole server: everybody hears who is in their own files. */
    private void tick() {
        alice.deliverPresence();
        bob.deliverPresence();
        pump();
    }

    // ── Who has it open ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>Opening a file tells whoever else has it.</b>
     *
     * <p>Opening is what the server already records — a read watches, and watching is what having a
     * file open means. What was missing was anybody sending it on.</p>
     */
    @Test
    public void openingAFileTellsWhoeverElseHasIt() {
        alice.open(FILE);
        bob.open(FILE);
        tick();

        assertEquals(List.of("Bob"), alice.workspace.presence().whoElseHasOpen(FILE_RESOURCE));
        assertEquals("and each side is told about the OTHER, never about itself",
                List.of("Alice"), bob.workspace.presence().whoElseHasOpen(FILE_RESOURCE));
    }

    /**
     * ...and a file nobody else has open names nobody — the counter-control.
     *
     * <p>Without it a fix that reported everybody in every file would pass the test above.</p>
     */
    @Test
    public void aFileNobodyElseHasOpenNamesNobody() {
        alice.open(FILE);
        bob.open(OTHER);
        tick();

        assertEquals(List.of(), alice.workspace.presence().whoElseHasOpen(FILE_RESOURCE));
        assertEquals(List.of(), bob.workspace.presence().whoElseHasOpen(Resource.of(OTHER)));
    }

    /**
     * <b>A peer is told about the files it holds, and about no others.</b>
     *
     * <p>The same rule the change fan-out follows: telling everybody about everything would leak which
     * files exist to somebody who never asked for them.</p>
     */
    @Test
    public void aPeerHearsNothingAboutFilesItHasNotOpened() {
        bob.open(OTHER);
        tick();

        assertEquals("Alice holds nothing, so there is nothing she may be told",
                List.of(), alice.workspace.presence().whoElseHasOpen(Resource.of(OTHER)));
    }

    // ── Who is editing ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>Typing is reported, and the other side hears it.</b>
     *
     * <p>The half the server cannot observe. Without {@code fs/editing} a file with no writes coming is
     * equally one nobody has touched and one somebody has been typing in for ten minutes.</p>
     */
    @Test
    public void typingInAFileTellsTheOtherPersonWhoIsEditing() {
        alice.open(FILE);
        Document mine = bob.open(FILE);
        tick();
        assertEquals("nobody is editing yet", List.of(),
                alice.workspace.presence().whoIsEditing(FILE_RESOURCE));

        mine.as(TextDocumentModel.class).buffer().insert(0, "typed");
        pump();
        tick();

        assertEquals(List.of("Bob"), alice.workspace.presence().whoIsEditing(FILE_RESOURCE));
        assertEquals("and having it open is still true as well",
                List.of("Bob"), alice.workspace.presence().whoElseHasOpen(FILE_RESOURCE));
    }

    /** Saving puts it back: the write clears this client's editing flag on the server. */
    @Test
    public void savingStopsReportingYouAsEditing() {
        alice.open(FILE);
        Document mine = bob.open(FILE);
        mine.as(TextDocumentModel.class).buffer().insert(0, "typed");
        pump();
        tick();
        assertEquals(List.of("Bob"), alice.workspace.presence().whoIsEditing(FILE_RESOURCE));

        bob.documents.save(mine);
        pump();
        tick();

        assertEquals(List.of(), alice.workspace.presence().whoIsEditing(FILE_RESOURCE));
    }

    // ── What is sent ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Nothing is sent when nothing has moved.</b>
     *
     * <p>Presence is global, so anybody's change bumps the version for everybody — without the second
     * gate every peer would be re-sent its own unchanged picture on every one of somebody else's
     * transitions.</p>
     */
    @Test
    public void aSecondAskWithNothingMovedSaysNothing() {
        alice.open(FILE);
        bob.open(FILE);
        tick();
        assertEquals("the first ask had something to say",
                List.of("Bob"), alice.workspace.presence().whoElseHasOpen(FILE_RESOURCE));

        assertNull("and the second has nothing", alice.binding.presenceFor());
    }

    /** ...and it does speak again once somebody moves, which is the counter-control. */
    @Test
    public void anAskAfterSomebodyMovesSpeaksAgain() {
        alice.open(FILE);
        tick();
        assertNull(alice.binding.presenceFor());

        bob.open(FILE);
        pump();

        FsMessages.PresenceNotification next = alice.binding.presenceFor();
        assertNotNull("Bob arriving is news for Alice", next);
        assertTrue(next.entries().toString(),
                next.entries().stream().anyMatch(entry -> "Bob".equals(entry.who())));
    }
}
