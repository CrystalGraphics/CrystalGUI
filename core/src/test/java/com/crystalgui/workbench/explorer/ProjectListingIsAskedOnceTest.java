package com.crystalgui.workbench.explorer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
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

/**
 * <b>The project list is asked for once, however many callers ask and however many frames pass.</b>
 *
 * <p>Three things call {@code loadProjects} - the application restoring its session, the file tree
 * filling itself, and the workbench's per-frame ticker - and the ticker had no latch of its own on the
 * stated grounds that the source had one. It did not. So a running workbench sent the server a
 * {@code fs/projects} request on <em>every frame</em>, for the life of the screen, with the answer
 * already in hand; it was reported as the log being spammed, which is the cheapest symptom of it.</p>
 *
 * <p>Asserted on <b>traffic</b>, not on the roots: the tree is correct either way, which is exactly why
 * this went unnoticed until somebody read the console.</p>
 */
public class ProjectListingIsAskedOnceTest {

    private static final String PROJECT = "scratch";

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private Workspace workspace;
    private WorkspaceTreeSource source;

    @Before
    public void openWorkspace() {
        Protocols.resetForTesting();

        InMemoryFileSystem files = new InMemoryFileSystem().seed(PROJECT + ":Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        workspace = Workspace.of(clientEnd);
        source = new WorkspaceTreeSource(workspace);
    }

    @After
    public void close() {
        if (clientEnd != null) clientEnd.close("test over");
        if (serverEnd != null) serverEnd.close("test over");
        Protocols.resetForTesting();
    }

    /**
     * What a frame does: ask, then let both ends dispatch - exactly as {@code Workbench.tick} does.
     *
     * @return how many messages this client put on the wire, which is the whole assertion
     */
    private int askTimes(int times, AtomicInteger loaded) {
        int before = link[1].sent().size();
        for (int i = 0; i < times; i++) {
            source.loadProjects(loaded::incrementAndGet, () -> { });
            // DELIVER, THEN TICK, BOTH ENDS. A frame's worth of wire: the transport moves the bytes and
            // each connection dispatches its own mailbox, so a request and its reply need a round of
            // each. Without it every caller here waits for ever and the test measures nothing.
            for (int round = 0; round < 3; round++) {
                link[0].deliver();
                link[1].deliver();
                serverEnd.tick();
                clientEnd.tick();
            }
        }
        return link[1].sent().size() - before;
    }

    @Test
    public void twentyFramesOfAskingSendOneRequest() {
        AtomicInteger loaded = new AtomicInteger();

        int firstAsk = askTimes(1, loaded);
        assertTrue("the first ask sent nothing at all", firstAsk > 0);
        assertEquals("the answer never arrived, so this test is measuring nothing", 1, loaded.get());

        assertEquals("the workbench's ticker went back to the server with the answer already in hand",
                0, askTimes(20, loaded));
    }

    /**
     * ...and every caller is still told.
     *
     * <p>The counter-control that stops this being fixed by swallowing later callers. Whoever loses the
     * race is the application, whose continuation restores the session - so a latch that simply returned
     * would leave a workbench that never restores, which is a far worse bug than a noisy log.</p>
     */
    @Test
    public void everyCallerHearsAboutTheProjects() {
        AtomicInteger loaded = new AtomicInteger();
        askTimes(3, loaded);

        assertEquals("a caller that asked after the answer landed was not served", 3, loaded.get());
        assertEquals("one of the roots is missing", 1, source.roots().size());
    }

    /**
     * A reconnect is the one thing that makes the answer wrong rather than merely old.
     *
     * <p>Without this the latch would be a boolean for the life of the screen, and a client that moved to
     * another server would show the previous one's projects with nothing able to correct it - no change
     * notification can arrive about roots nobody is watching.
     */
    @Test
    public void aNewWireIsAskedAgain() {
        AtomicInteger loaded = new AtomicInteger();
        askTimes(1, loaded);
        assertEquals(0, askTimes(1, loaded));

        source.markProjectsStale();

        assertTrue("a reconnected workspace kept the previous server's project list",
                askTimes(1, loaded) > 0);
    }
}
