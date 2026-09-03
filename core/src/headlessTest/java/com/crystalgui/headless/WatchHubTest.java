package com.crystalgui.headless;

import com.crystalgui.fs.CgFileCapability;
import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileEvent;
import com.crystalgui.fs.CgFileSystem;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.server.WatchHub;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code plan_fs_rewrite.md} F3.2, D19 — the watch hub, with <b>two peers</b>.
 *
 * <p>Every claim here is a multi-client one, which is the plan's testing rule: one service, two peers,
 * and the interesting behaviour is what one peer's action does to the other's view. A one-peer fixture
 * cannot see the cost this replaces (a stat per peer per event) nor the leak it closes (a peer told
 * about a file it never asked for).</p>
 */
public class WatchHubTest {

    /** Counts what reaches the filesystem, so a cost can be asserted rather than described. */
    private static final class Counting implements CgFileSystem {
        private final CgFileSystem delegate;
        int stats;

        Counting(CgFileSystem delegate) {
            this.delegate = delegate;
        }

        @Override public CgFileEntry stat(CgPath path) {
            stats++;
            return delegate.stat(path);
        }
        @Override public byte[] read(CgPath path) { return delegate.read(path); }
        @Override public List<CgFileEntry> list(CgPath directory) { return delegate.list(directory); }
        @Override public void write(CgPath path, byte[] c, boolean create, boolean overwrite) {
            delegate.write(path, c, create, overwrite);
        }
        @Override public void mkdir(CgPath path) { delegate.mkdir(path); }
        @Override public void delete(CgPath path, boolean recursive) { delegate.delete(path, recursive); }
        @Override public void rename(CgPath from, CgPath to, boolean overwrite) {
            delegate.rename(from, to, overwrite);
        }
        @Override public Set<CgFileCapability> capabilities() { return delegate.capabilities(); }
    }

    private static final Object ALICE = "alice";
    private static final Object BOB = "bob";

    private static CgPath p(String path) {
        return CgPath.parse("proj:" + path);
    }

    private Counting files;
    private WorkspaceService service;
    private WatchHub hub;

    @Before
    public void setUp() {
        files = new Counting(new InMemoryFileSystem()
                .seed("proj:src/Main.java", "class Main {}")
                .seed("proj:src/Other.java", "class Other {}")
                .seed("proj:src/deep/Nested.java", "class Nested {}")
                .seed("proj:README.md", "# hi"));
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", Paths.get("/srv/proj"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        hub = new WatchHub(service);
    }

    private List<CgFileEvent> modified(String... paths) {
        return java.util.Arrays.stream(paths)
                .map(path -> CgFileEvent.of(CgFileEvent.Kind.MODIFIED, p(path)))
                .toList();
    }

    /** Writes, creating the file when it is not there — which is what a save and a new file both are. */
    private void write(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            service.stat(WorkspaceActor.LOCAL, p(path));
            service.write(WorkspaceActor.LOCAL, p(path), bytes, null);
        } catch (CgFileSystemException absent) {
            service.create(WorkspaceActor.LOCAL, p(path), bytes);
        }
    }

    // ── Directories ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>N28, the headline.</b> A client could only watch what it had read, so another client's create
     * inside a folder it had expanded never reached it. The tree went stale with nothing to say so.
     */
    @Test
    public void anotherClientsCreateReachesAnExpandedFolder() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src"), false);

        write("src/Fresh.java", "class Fresh {}");
        Map<Object, List<FsMessages.FileChange>> out = hub.tick(WorkspaceActor.LOCAL,
                List.of(CgFileEvent.of(CgFileEvent.Kind.CREATED, p("src/Fresh.java"))));

        assertTrue("Alice expanded src and must hear about a new file in it", out.containsKey(ALICE));
        assertEquals(1, out.get(ALICE).size());
        assertEquals(FsMessages.ChangeKind.CREATED, out.get(ALICE).get(0).kind());
        assertEquals("proj:src/Fresh.java", out.get(ALICE).get(0).path());
    }

    /** A non-recursive folder watch is what an expanded folder is, and it stops at its own children. */
    @Test
    public void aFolderWatchDoesNotReachIntoSubfolders() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src"), false);

        write("src/deep/Nested.java", "changed");
        Map<Object, List<FsMessages.FileChange>> out =
                hub.tick(WorkspaceActor.LOCAL, modified("src/deep/Nested.java"));

        assertFalse("a folder nobody expanded is not on screen", out.containsKey(ALICE));
    }

    @Test
    public void aRecursiveWatchDoesReachIntoSubfolders() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src"), true);

        write("src/deep/Nested.java", "changed");
        Map<Object, List<FsMessages.FileChange>> out =
                hub.tick(WorkspaceActor.LOCAL, modified("src/deep/Nested.java"));

        assertEquals(1, out.get(ALICE).size());
    }

    /** A peer hears about nothing it did not ask for — which is a leak, not merely noise. */
    @Test
    public void aPeerIsToldOnlyAboutWhatItWatches() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        hub.watch(BOB, WorkspaceActor.LOCAL, p("README.md"), false);

        write("src/Main.java", "changed");
        Map<Object, List<FsMessages.FileChange>> out =
                hub.tick(WorkspaceActor.LOCAL, modified("src/Main.java"));

        assertTrue(out.containsKey(ALICE));
        assertFalse("telling Bob would leak that a file he never asked for exists",
                out.containsKey(BOB));
    }

    // ── One stat, however many peers ────────────────────────────────────────────────────────────

    /**
     * <b>N25.</b> One drained batch was handed to every peer and each re-stat-ed the same file, so K
     * peers meant K stats per event — on top of the poll's K x M, twice a second.
     */
    @Test
    public void twoPeersWatchingOneFileCostOneStat() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        hub.watch(BOB, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        write("src/Main.java", "changed");

        int before = files.stats;
        Map<Object, List<FsMessages.FileChange>> out =
                hub.tick(WorkspaceActor.LOCAL, modified("src/Main.java"));

        assertEquals("one stat, not one per peer", 1, files.stats - before);
        assertEquals("and both are told", 2, out.size());
    }

    /** The poll is the reconciliation and pays the same rule. */
    @Test
    public void aPollStatsEachFileOnceHoweverManyPeersWatchIt() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        hub.watch(BOB, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        hub.watch(BOB, WorkspaceActor.LOCAL, p("README.md"), false);

        int before = files.stats;
        hub.poll(WorkspaceActor.LOCAL);

        assertEquals("two distinct files, two stats", 2, files.stats - before);
    }

    // ── Coalescing ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>N30.</b> One save is often several events — truncate, write, rename into place — and each was
     * reported, so one save was three reloads on the far side.
     */
    @Test
    public void oneSaveIsOneChangeHoweverManyEventsItRaised() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        write("src/Main.java", "changed");

        Map<Object, List<FsMessages.FileChange>> out = hub.tick(WorkspaceActor.LOCAL,
                modified("src/Main.java", "src/Main.java", "src/Main.java"));

        assertEquals(1, out.get(ALICE).size());
    }

    /**
     * <b>Fifty files under one directory is ONE notification.</b> It was fifty, each invalidating a
     * listing and refreshing the tree.
     */
    @Test
    public void fiftyFilesChangingIsOneNotification() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src"), true);
        String[] paths = new String[50];
        for (int i = 0; i < 50; i++) {
            paths[i] = "src/File" + i + ".java";
            write(paths[i], "class File" + i + " {}");
        }

        Map<Object, List<FsMessages.FileChange>> out =
                hub.tick(WorkspaceActor.LOCAL, modified(paths));

        assertEquals("one peer, one answer", 1, out.size());
        assertEquals("carrying all fifty in one message", 50, out.get(ALICE).size());
    }

    /** A modify that changed no bytes is not a change. The etag is the arbiter, not the event. */
    @Test
    public void aTouchThatChangedNothingIsNotReported() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);

        Map<Object, List<FsMessages.FileChange>> out =
                hub.tick(WorkspaceActor.LOCAL, modified("src/Main.java"));

        assertTrue("a client that reloaded on this would lose an unsaved buffer to identical bytes",
                out.isEmpty());
    }

    // ── Renames and deletions ───────────────────────────────────────────────────────────────────

    /**
     * <b>N29.</b> A rename arrived as a deletion, so the client closed the tab.
     *
     * <p>Stated by the server rather than inferred, because a rename the server performed is a fact.
     */
    @Test
    public void aServerRenameIsOneEventCarryingBothEnds() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);

        FsMessages.FileChange change =
                hub.noteRenamed(p("src/Main.java"), p("src/Renamed.java"), "9:12");

        assertEquals(FsMessages.ChangeKind.RENAMED, change.kind());
        assertEquals("proj:src/Renamed.java", change.path());
        assertEquals("proj:src/Main.java", change.from());
    }

    /**
     * And an EXTERNAL one is paired from its two halves, which is all a filesystem watcher reports.
     *
     * <p>Both references pair them this way; there is no other way to get a rename out of NIO,
     * inotify or ReadDirectoryChangesW.
     */
    @Test
    public void anExternalRenameIsPairedFromItsDeleteAndItsCreate() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src"), true);
        // WATCHED AS A FILE TOO, which is what an open tab is -- and the reason the pairing can work
        // at all: the etag the deletion took away is the only thing tying the two halves together, and
        // a file nobody was watching has none to take.
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);

        // What the OS reports, in either order: the old path gone, the new one there with the same
        // bytes. The move itself happens outside the hub, exactly as an external one does.
        service.rename(WorkspaceActor.LOCAL, p("src/Main.java"), p("src/Moved.java"), false);
        Map<Object, List<FsMessages.FileChange>> out = hub.tick(WorkspaceActor.LOCAL, List.of(
                CgFileEvent.of(CgFileEvent.Kind.DELETED, p("src/Main.java")),
                CgFileEvent.of(CgFileEvent.Kind.CREATED, p("src/Moved.java"))));

        List<FsMessages.FileChange> mine = out.get(ALICE);
        assertEquals("a pair becomes one event, not two", 1, mine.size());
        assertEquals(FsMessages.ChangeKind.RENAMED, mine.get(0).kind());
        assertEquals("proj:src/Moved.java", mine.get(0).path());
        assertEquals("proj:src/Main.java", mine.get(0).from());
    }

    @Test
    public void aDeletionIsStillADeletion() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        service.delete(WorkspaceActor.LOCAL, p("src/Main.java"), false);

        Map<Object, List<FsMessages.FileChange>> out = hub.tick(WorkspaceActor.LOCAL,
                List.of(CgFileEvent.of(CgFileEvent.Kind.DELETED, p("src/Main.java"))));

        assertEquals(FsMessages.ChangeKind.DELETED, out.get(ALICE).get(0).kind());
    }

    // ── Reconciliation ──────────────────────────────────────────────────────────────────────────

    /** An OVERFLOW means events were lost, so nothing in the batch can be trusted. Re-scan. */
    @Test
    public void anOverflowFallsThroughToAFullRescan() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        write("src/Main.java", "changed underneath, with the event dropped");

        Map<Object, List<FsMessages.FileChange>> out = hub.tick(WorkspaceActor.LOCAL,
                List.of(CgFileEvent.overflow()));

        assertEquals("the change must be found anyway", 1, out.get(ALICE).size());
    }

    /** The server's own write is not news to anybody. */
    @Test
    public void aWriteTheServerRecordedIsNotReported() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        write("src/Main.java", "changed");
        hub.noteWritten(p("src/Main.java"),
                service.stat(WorkspaceActor.LOCAL, p("src/Main.java")).etag());

        assertTrue(hub.poll(WorkspaceActor.LOCAL).isEmpty());
    }

    // ── Bookkeeping ─────────────────────────────────────────────────────────────────────────────

    /** A subscription costs the server work, so an unbounded one is a peer commanding it. */
    @Test
    public void aPeerCannotWatchPastTheCap() {
        for (int i = 0; i < WatchHub.MAX_SUBSCRIPTIONS_PER_PEER; i++) {
            hub.watch(ALICE, WorkspaceActor.LOCAL, p("f" + i + ".txt"), false);
        }
        try {
            hub.watch(ALICE, WorkspaceActor.LOCAL, p("one-too-many.txt"), false);
            fail("an unbounded subscription set is a peer making the server do arbitrary work");
        } catch (CgFileSystemException refused) {
            assertTrue(refused.getMessage().contains("limit"));
        }
        assertEquals("and Bob is unaffected", 0, hub.subscriptionCount(BOB));
    }

    @Test
    public void aDisconnectedPeerTakesItsSubscriptionsWithIt() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);
        hub.watch(BOB, WorkspaceActor.LOCAL, p("src/Main.java"), false);

        hub.forget(ALICE);

        assertEquals(0, hub.subscriptionCount(ALICE));
        assertEquals("and Bob still hears about the file they shared", 1, hub.subscriptionCount(BOB));
        write("src/Main.java", "changed");
        assertTrue(hub.tick(WorkspaceActor.LOCAL, modified("src/Main.java")).containsKey(BOB));
    }

    @Test
    public void subscribingSeedsSoTheFirstPollReportsNothing() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Main.java"), false);

        assertTrue("a fresh subscription must not report the file as changed",
                hub.poll(WorkspaceActor.LOCAL).isEmpty());
    }

    /** Watching something that is not there yet is legitimate — a file about to be created. */
    @Test
    public void watchingAFileThatDoesNotExistYetReportsItsCreation() {
        hub.watch(ALICE, WorkspaceActor.LOCAL, p("src/Later.java"), false);
        assertTrue(hub.poll(WorkspaceActor.LOCAL).isEmpty());

        write("src/Later.java", "class Later {}");
        Map<Object, List<FsMessages.FileChange>> out = hub.poll(WorkspaceActor.LOCAL);

        assertEquals(FsMessages.ChangeKind.CREATED, out.get(ALICE).get(0).kind());
    }
}
