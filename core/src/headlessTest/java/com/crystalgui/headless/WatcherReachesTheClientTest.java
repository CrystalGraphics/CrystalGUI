package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.provider.LocalFileSystem;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * <b>The whole path, with the real watcher on real files.</b>
 *
 * <p>Every other test covers one segment: the watcher into {@code drainFileEvents}, or hand-made events
 * through the hub, or the hub into a client. Nothing joined them, so a break anywhere between the OS and
 * {@code Workspace.Watch#onChanged} was invisible to the suite and visible only by running the harness.</p>
 *
 * <p>The arrangement is the explorer's: <b>one recursive watch on the project root and no per-file watch
 * on anything</b>, which is what a file nobody has open is covered by.</p>
 */
public class WatcherReachesTheClientTest {

    private static final String PROJECT = "proj";

    private Path root;
    private WorkspaceService service;
    private WatchHub hub;
    private WorkspaceBinding<Object> binding;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private Workspace workspace;
    private final List<FsMessages.FileChange> heard = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        Protocols.resetForTesting();
        root = Files.createTempDirectory("cgui-watch-client");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(PROJECT, "Proj", root)));
        service = new WorkspaceService(projects, new LocalFileSystem(projects),
                WorkspacePermission.ALLOW_ALL);
        hub = new WatchHub(service);

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        binding = new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, "alice", PlainOps.INSTANCE);
        binding.installOn(serverSide::onRequest);

        workspace = Workspace.of(clientSide);
        // THE EXPLORER'S SUBSCRIPTION, and the only one: no document is open here.
        workspace.watch(Resource.of(CgPath.parse(PROJECT + ":")), true).onChanged
                .connect(heard::addAll);
        pump();
    }

    @After
    public void tearDown() {
        service.close();
        Protocols.resetForTesting();
    }

    /** One frame of the harness's own loop: deliver the wire, drain the watcher, fan out. */
    private void pump() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
        List<CgFileEvent> events = service.drainFileEvents();
        if (!events.isEmpty()) fanOut(hub.tick(WorkspaceActor.LOCAL, events));
        fanOut(hub.poll(WorkspaceActor.LOCAL));
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    private void fanOut(Map<Object, List<FsMessages.FileChange>> byPeer) {
        List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
        if (mine.isEmpty()) return;
        serverSide.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                        new FsMessages.ChangedNotification(mine))));
    }

    private boolean await(String endingIn, FsMessages.ChangeKind kind) {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            for (FsMessages.FileChange change : new ArrayList<>(heard)) {
                if (change.kind() == kind && change.path().endsWith(endingIn)) return true;
            }
            sleep();
            pump();
        }
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void aCreatedFileReachesTheClient() throws IOException {
        Files.write(root.resolve("Made.java"), "class Made {}\n".getBytes(StandardCharsets.UTF_8));
        assertTrue("a file appearing under a watched root", await("Made.java", FsMessages.ChangeKind.CREATED));
    }

    @Test
    public void aDeletedFileReachesTheClient() throws IOException {
        Path doomed = root.resolve("Doomed.java");
        Files.write(doomed, "class Doomed {}\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(await("Doomed.java", FsMessages.ChangeKind.CREATED));

        Files.delete(doomed);
        assertTrue("and going again", await("Doomed.java", FsMessages.ChangeKind.DELETED));
    }

    @Test
    public void aRenamedFileReachesTheClient() throws IOException {
        Path from = root.resolve("Before.java");
        Files.write(from, "class Before {}\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(await("Before.java", FsMessages.ChangeKind.CREATED));

        Files.move(from, root.resolve("After.java"));
        assertTrue("the new name arrives", await("After.java", FsMessages.ChangeKind.CREATED)
                || await("After.java", FsMessages.ChangeKind.RENAMED));
        assertTrue("and the old one must go, or the tree keeps a row for a file that is gone",
                await("Before.java", FsMessages.ChangeKind.DELETED)
                        || await("After.java", FsMessages.ChangeKind.RENAMED));
    }
}
