package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgui.core.async.Reply;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.fs.protocol.ScriptingMode;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;

import java.util.List;

/**
 * <b>A server serving its workspace, with no game around it.</b>
 *
 * <p>All of this ran inside the 1.7.10 loader until W3, which meant the only way to exercise it was to
 * boot Minecraft: {@code serverSmoke} asserts that the stack came up, and nothing anywhere asserted that
 * a client on the other end of a real connection can actually list what the server is serving. This is
 * that, and it is the point of the move — the per-connection binding, the shared watcher and the fan-out
 * are the same on any host with a socket, so they belong somewhere a test can reach them.</p>
 *
 * <p>The three things a platform genuinely answers are the three this fixture supplies: a directory, a
 * permission, and who a peer is.</p>
 */
public class WorkspaceHostTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String PROJECT = "test.workspace";

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private WorkspaceHost host;

    @Before
    public void openTheWire() {
        Protocols.resetForTesting();
    }

    @After
    public void closeTheWire() {
        if (host != null) host.reset();
        Protocols.resetForTesting();
    }

    /** What a host says about running this workspace's files. @see WorkspaceHost.Host#scripting */
    private WorkspaceService.ScriptingPolicy scripting = WorkspaceService.ScriptingPolicy.LIVE;

    /** What only a platform can answer, answered by a temporary directory and a fixed name. */
    private WorkspaceHost.Host hostOver(Path root) {
        return new WorkspaceHost.Host() {
            @Override
            @Nullable
            public Path root() {
                return root;
            }

            @Override
            public WorkspacePermission permission() {
                return WorkspacePermission.ALLOW_ALL;
            }

            @Override
            public WorkspaceService.ScriptingPolicy scripting() {
                return scripting;
            }

            @Override
            public WorkspaceActor actorFor(Object peer) {
                return () -> String.valueOf(peer);
            }
        };
    }

    private void serve(Path root) {
        host = new WorkspaceHost(PROJECT, "Test", hostOver(root));
        host.contribute();

        link = InMemoryTransport.pair();
        // A PEER, because that is what makes this the server end: Protocols.server only binds where
        // there is one, which is how a single-player process avoids serving itself from its client end.
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "peer");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
    }

    private void pump() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    @Test
    public void aClientListsWhatTheServerIsServing() throws IOException {
        Path root = folder.getRoot().toPath().resolve("crystalgui/workspace");
        serve(root);

        Workspace workspace = Workspace.over(clientEnd::call, clientEnd::onNotify, PlainOps.INSTANCE);
        Reply<List<FsMessages.ProjectEntry>> projects = workspace.projects();
        pump();

        assertNotNull("the project listing failed: " + projects.error(), projects.result());
        assertEquals("one project, the one this host serves", 1, projects.result().size());
        assertEquals(PROJECT, projects.result().get(0).id());

        // THE SEEDED README, which is the whole reason there is one: an empty tree and a broken tree
        // look identical, so the first launch needs something in it that proves a listing crossed.
        assertTrue("the workspace directory was created", Files.isDirectory(root));
        Reply<FsMessages.ListResponse> listing =
                workspace.files().list(Resource.of(CgPath.of(PROJECT, "")));
        pump();
        assertNotNull("the directory listing failed: " + listing.error(), listing.result());
        assertTrue("the README is there and the client can see it",
                listing.result().entries().stream().anyMatch(e -> e.name().equals("README.md")));
    }

    /**
     * <b>No root, no binding — and no exception.</b>
     *
     * <p>A connection can arrive before there is anywhere to serve: on 1.7.10 the root is inside the
     * world directory and the mod contributes long before a world loads. Which is why the root is asked
     * per connection rather than once, and why answering null has to be an ordinary state rather than a
     * failure — the alternative is a host that must not contribute until something it does not control
     * has happened.</p>
     */
    @Test
    public void aConnectionBeforeThereIsAnywhereToServeIsSimplyNotBound() {
        host = new WorkspaceHost(PROJECT, "Test", hostOver(null));
        host.contribute();

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "peer");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        pump();

        assertEquals("nothing was bound", 0, host.boundPeerCount());
    }

    /** A peer that goes takes its binding with it, or the maps grow for the life of the server. */
    @Test
    public void aForgottenPeerIsUnbound() throws IOException {
        serve(folder.getRoot().toPath().resolve("crystalgui/workspace"));
        pump();

        assertEquals("the peer bound", 1, host.boundPeerCount());
        host.forget("peer");
        assertEquals("and is gone", 0, host.boundPeerCount());
    }

    /**
     * <b>A server says whether its files may be run in a client's JVM, and the client hears it.</b>
     *
     * <p>The Run command compiles the buffer in front and executes it in the CLIENT's process. On a
     * dedicated server that is a live scripting environment inside every player's game, reachable from
     * any project they can edit — so whether it exists is the server's to say, and it says it on the
     * same notification that carries "may read" and "may write".</p>
     *
     * <p>The default is deliberately the permissive one: an in-process workspace and a single-player
     * world are the player's own machine, and every host that predates the question meant exactly
     * that.</p>
     */
    @Test
    public void aServerSaysWhetherItsFilesMayBeRunInAClientsJvm() throws IOException {
        scripting = WorkspaceService.ScriptingPolicy.AUTHORIZED_ONLY;
        serve(folder.getRoot().toPath().resolve("crystalgui/workspace"));

        Workspace workspace = Workspace.over(clientEnd::call, clientEnd::onNotify, PlainOps.INSTANCE);
        workspace.greet();
        pump();

        Resource file = Resource.of(CgPath.of(PROJECT, "Main.java"));
        assertEquals("the server refused a local run, and said so on the capability",
                ScriptingMode.AUTHORIZED, workspace.capabilities().scriptingMode(file));
        assertFalse("...so the Run command must not compile and execute here",
                workspace.capabilities().scriptingMode(file).allowsLocalRun());
        assertTrue("...while reading is untouched: this is about what is IN a file, not the file",
                workspace.capabilities().mayRead(file));
    }

    /** ...and the default is what every host meant before there was a question to ask. */
    @Test
    public void aWorkspaceNobodyHasSpokenForRunsLocally() throws IOException {
        serve(folder.getRoot().toPath().resolve("crystalgui/workspace"));
        Workspace workspace = Workspace.over(clientEnd::call, clientEnd::onNotify, PlainOps.INSTANCE);
        workspace.greet();
        pump();

        assertTrue("an unspoken-for workspace runs locally",
                workspace.capabilities().scriptingMode(
                        Resource.of(CgPath.of(PROJECT, "Main.java"))).allowsLocalRun());
    }
}
