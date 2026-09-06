package com.crystalgui.headless;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.provider.LocalFileSystem;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceConflictException;
import com.crystalgui.fs.server.WorkspaceOperation;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.WorkspaceService;
import java.util.Map;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.server.WatchHub;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;

import java.nio.file.Files;

import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link WorkspaceService} — authorisation and the etag rules, above a filesystem that knows neither.
 *
 * <p>Runs entirely in memory: no disk, no Minecraft, no GL. That is the property the whole layering
 * exists to buy.</p>
 */
public class WorkspaceServiceTest {

    private static final WorkspaceActor ALICE = () -> "alice";
    private static final WorkspaceActor BOB = () -> "bob";

    private InMemoryFileSystem files;
    private ProjectRegistry registry;

    @Before
    public void setUp() {
        files = new InMemoryFileSystem()
                .seed("mymod.scripts:src/Main.java", "class Main {}")
                .seed("mymod.scripts:README.md", "# hello")
                .seed("mymod.scripts:node_modules/left-pad/index.js", "module.exports = 1;")
                .seed("other.proj:secret.txt", "not yours");

        registry = new ProjectRegistry()
                .register(() -> List.of(
                        new WorkspaceProject(new ProjectInfo("mymod.scripts", "Scripts"),
                                Paths.get("/srv/scripts"), List.of("node_modules", "*.tmp")),
                        new WorkspaceProject("other.proj", "Other", Paths.get("/srv/other"))));
    }

    private WorkspaceService service(WorkspacePermission permission) {
        return new WorkspaceService(registry, files, permission);
    }

    private static CgPath p(String path) {
        return CgPath.parse(path);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── Authorisation ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>A host that forgets the callback gets a workspace nobody can open.</b>
     *
     * <p>The safe direction. Defaulting to allow-all would mean a mod that registered projects and had
     * not yet written its permission check shipped an open filesystem, and nothing would look wrong.</p>
     */
    @Test
    public void theDefaultIsToRefuse() {
        WorkspaceService service = new WorkspaceService(registry, files, null);
        assertTrue(service.projects(ALICE).isEmpty());
        try {
            service.read(ALICE, p("mymod.scripts:README.md"));
            fail("expected a refusal");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.NO_PERMISSIONS, e.getError());
        }
    }

    @Test
    public void readOnlyPermitsReadingAndRefusesWriting() {
        WorkspaceService service = service(WorkspacePermission.READ_ONLY);
        assertEquals("# hello", text(service.read(ALICE, p("mymod.scripts:README.md")).content()));
        try {
            service.write(ALICE, p("mymod.scripts:README.md"), "no".getBytes(StandardCharsets.UTF_8), null);
            fail("expected a refusal");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.NO_PERMISSIONS, e.getError());
        }
    }

    /** The callback sees who, where and what — all four, or it cannot make a real decision. */
    @Test
    public void theCallbackSeesActorProjectPathAndOperation() {
        WorkspaceService service = service((actor, project, path, operation) ->
                actor == ALICE
                        && project.id().equals("mymod.scripts")
                        && !path.name().equals("README.md")
                        && operation == WorkspaceOperation.READ);

        assertEquals("class Main {}", text(service.read(ALICE, p("mymod.scripts:src/Main.java")).content()));

        for (Runnable refused : List.<Runnable>of(
                () -> service.read(BOB, p("mymod.scripts:src/Main.java")),        // wrong actor
                () -> service.read(ALICE, p("other.proj:secret.txt")),            // wrong project
                () -> service.read(ALICE, p("mymod.scripts:README.md")),          // wrong path
                () -> service.create(ALICE, p("mymod.scripts:x.java"), new byte[0]))) {  // wrong operation
            try {
                refused.run();
                fail("expected a refusal");
            } catch (CgFileSystemException e) {
                assertEquals(CgFileError.NO_PERMISSIONS, e.getError());
            }
        }
    }

    /**
     * <b>Refusal must not reveal whether the path exists.</b>
     *
     * <p>A distinct "no such file" for an unauthorised read would let a client map a server's disk by
     * comparing error codes, which is a slower version of being allowed to list it.</p>
     */
    @Test
    public void refusalLooksTheSameWhetherOrNotTheFileIsThere() {
        WorkspaceService service = service(WorkspacePermission.DENY_ALL);

        CgFileError present = errorFrom(() -> service.read(ALICE, p("mymod.scripts:README.md")));
        CgFileError absent = errorFrom(() -> service.read(ALICE, p("mymod.scripts:ghost.md")));
        assertEquals(present, absent);
        assertEquals(CgFileError.NO_PERMISSIONS, present);
    }

    @Test
    public void projectsAreFilteredByPermission() {
        WorkspaceService service = service((actor, project, path, operation) ->
                project.id().equals("mymod.scripts"));

        List<String> visible = service.projects(ALICE).stream().map(ProjectInfo::id).toList();
        assertEquals(List.of("mymod.scripts"), visible);
    }

    /** A rename is a write at BOTH ends, or it is a way to write somewhere you may not. */
    @Test
    public void renameIsAuthorisedAtBothEnds() {
        WorkspaceService service = service((actor, project, path, operation) ->
                !path.name().equals("locked.txt"));

        files.write(p("mymod.scripts:locked.txt"), new byte[0], true, true);
        try {
            service.rename(ALICE, p("mymod.scripts:README.md"), p("mymod.scripts:locked.txt"), true);
            fail("writing to a path the actor may not touch must be refused");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.NO_PERMISSIONS, e.getError());
        }
    }

    @Test
    public void renamingAcrossProjectsIsRefused() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        try {
            service.rename(ALICE, p("mymod.scripts:README.md"), p("other.proj:README.md"), false);
            fail("a project is a boundary, not a directory");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.INVALID_PATH, e.getError());
        }
    }

    // ── etag and conflict ───────────────────────────────────────────────────────────────────────

    /** The happy path: read, write back quoting the etag, get a new one. */
    @Test
    public void aWriteQuotingTheCurrentEtagSucceeds() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        var read = service.read(ALICE, p("mymod.scripts:README.md"));

        String after = service.write(ALICE, p("mymod.scripts:README.md"),
                "# changed".getBytes(StandardCharsets.UTF_8), read.etag());

        assertNotEquals("the etag must move", read.etag(), after);
        assertEquals("# changed", text(service.read(ALICE, p("mymod.scripts:README.md")).content()));
    }

    /**
     * <b>A stale write is refused, and the refusal carries the etag the file actually has.</b>
     *
     * <p>The whole conflict story. Alice reads, Bob writes, Alice writes — and Alice's write must not
     * silently win. The current etag comes back with the refusal so the client can offer a reload without
     * a second round trip.</p>
     */
    @Test
    public void aStaleWriteIsRefusedWithTheCurrentEtag() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);

        var alice = service.read(ALICE, p("mymod.scripts:README.md"));
        service.write(BOB, p("mymod.scripts:README.md"), "# bob".getBytes(StandardCharsets.UTF_8), alice.etag());

        try {
            service.write(ALICE, p("mymod.scripts:README.md"),
                    "# alice".getBytes(StandardCharsets.UTF_8), alice.etag());
            fail("Alice's write was based on a version that no longer exists");
        } catch (WorkspaceConflictException e) {
            assertEquals(alice.etag(), e.getExpectedEtag());
            assertNotEquals(alice.etag(), e.getActualEtag());
            assertEquals("Bob's content survives", "# bob",
                    text(service.read(ALICE, p("mymod.scripts:README.md")).content()));
        }
    }

    /**
     * <b>The re-stat is what makes it safe, not a watcher.</b>
     *
     * <p>Here the file is changed <em>behind the service's back</em> — straight through the filesystem,
     * with no notification of any kind, which is exactly what an edit on the host machine looks like. The
     * stale write must still be refused, because the check happens on the operation rather than depending
     * on anyone having been told.</p>
     */
    @Test
    public void anOutOfBandChangeIsCaughtWithoutAnyNotification() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        var read = service.read(ALICE, p("mymod.scripts:README.md"));

        files.write(p("mymod.scripts:README.md"), "# edited on the host".getBytes(StandardCharsets.UTF_8),
                false, true);

        try {
            service.write(ALICE, p("mymod.scripts:README.md"), "# mine".getBytes(StandardCharsets.UTF_8),
                    read.etag());
            fail("a write onto a file changed underneath must be refused");
        } catch (WorkspaceConflictException expected) {
            assertEquals("# edited on the host",
                    text(service.read(ALICE, p("mymod.scripts:README.md")).content()));
        }
    }

    /** A null etag means "I do not care" — used by a first write, not by a save. */
    @Test
    public void aNullEtagWritesUnconditionally() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        files.write(p("mymod.scripts:README.md"), "# moved".getBytes(StandardCharsets.UTF_8), false, true);

        service.write(ALICE, p("mymod.scripts:README.md"), "# forced".getBytes(StandardCharsets.UTF_8), null);
        assertEquals("# forced", text(service.read(ALICE, p("mymod.scripts:README.md")).content()));
    }

    /** Quoting an etag for a file that has since been deleted reports the deletion, not a conflict. */
    @Test
    public void writingToADeletedFileReportsItMissing() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        var read = service.read(ALICE, p("mymod.scripts:README.md"));
        files.delete(p("mymod.scripts:README.md"), false);

        try {
            service.write(ALICE, p("mymod.scripts:README.md"), new byte[0], read.etag());
            fail("expected a refusal");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.FILE_NOT_FOUND, e.getError());
        }
    }

    /** Create refuses an existing file rather than clobbering it. */
    @Test
    public void createRefusesAnExistingFile() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        try {
            service.create(ALICE, p("mymod.scripts:README.md"), new byte[0]);
            fail("New File must not overwrite");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.FILE_EXISTS, e.getError());
        }
        assertEquals("# hello", text(service.read(ALICE, p("mymod.scripts:README.md")).content()));
    }

    // ── Manifests and exclusions ────────────────────────────────────────────────────────────────

    @Test
    public void aManifestCarriesEtagsForCaching() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        var entries = service.manifest(ALICE, p("mymod.scripts:src"));

        assertEquals(1, entries.size());
        assertEquals("Main.java", entries.get(0).name());
        assertEquals("the manifest's etag must match a direct read's",
                service.read(ALICE, p("mymod.scripts:src/Main.java")).etag(), entries.get(0).etag());
    }

    /** Exclusions are applied server-side, so an excluded path never reaches a client at all. */
    @Test
    public void exclusionsAreAppliedToListings() {
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);
        List<String> names = service.manifest(ALICE, p("mymod.scripts:")).stream()
                .map(e -> e.name()).sorted().toList();

        assertEquals(List.of("README.md", "src"), names);
        assertFalse("node_modules is excluded", names.contains("node_modules"));
    }

    @Test
    public void exclusionGlobsMatchWithinOneName() {
        files.write(p("mymod.scripts:build.tmp"), new byte[0], true, true);
        files.write(p("mymod.scripts:keep.txt"), new byte[0], true, true);
        WorkspaceService service = service(WorkspacePermission.ALLOW_ALL);

        List<String> names = service.manifest(ALICE, p("mymod.scripts:")).stream()
                .map(e -> e.name()).toList();
        assertFalse("*.tmp is excluded", names.contains("build.tmp"));
        assertTrue(names.contains("keep.txt"));
    }

    private static CgFileError errorFrom(Runnable action) {
        try {
            action.run();
            fail("expected a refusal");
            return null;
        } catch (CgFileSystemException e) {
            return e.getError();
        }
    }

    // ── The watcher comes with the filesystem ─────────────────────────────────────

    /**
     * <b>A service built by hand watches its files, because the provider brings the watcher.</b>
     *
     * <p>It used to be attached by {@code WorkspaceHost}, so anything assembling a service without that
     * class got none — silently, since the field defaults to {@code NONE} and the only symptom is a
     * workspace that runs a poll interval behind for ever.</p>
     */
    @Test
    public void aServiceOverRealFilesWatchesThemWithoutBeingToldTo() {
        Path root = tempRoot();
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", root)));
        WorkspaceService service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);
        try {
            service.drainFileEvents();   // the first drain is what opens a watcher on each root
            write(root.resolve("Made.java"), "class Made {}" + "\n");

            assertTrue("nobody attached a source; the LocalFileSystem supplied its own",
                    awaitEvent(service, "Made.java"));
        } finally {
            service.close();
        }
    }

    /** And closing releases the handles — which nothing owned, so nothing freed. */
    @Test
    public void closingReleasesTheWatchHandles() {
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry(), new InMemoryFileSystem(), WorkspacePermission.ALLOW_ALL);

        service.close();
        // Twice, because a server stopped in place runs reset() again on the next stop.
        service.close();

        assertTrue(service.drainFileEvents().isEmpty());
    }

    private static boolean awaitEvent(WorkspaceService service, String endingIn) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (CgFileEvent event : service.drainFileEvents()) {
                if (event.path() != null && event.path().toString().endsWith(endingIn)) return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static Path tempRoot() {
        try {
            return Files.createTempDirectory("cgui-provider-watch");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * <b>A deletion the watcher saw is reported, even for a file nobody opened.</b>
     *
     * <p>The explorer's arrangement: one recursive watch on the project root and no per-file watch on
     * anything. Only opened files are ever stat-ed into the hub, so the rescan's rule — "not there now,
     * not there before is not news" — silently swallowed every deletion in the project except one for
     * a file that happened to be open. Creates survived, so a move half-arrived: the new row appeared
     * and the old one never left.</p>
     */
    @Test
    public void aDeletionUnderARecursiveWatchIsReported() {
        Path root = tempRoot();
        Path doomed = root.resolve("Doomed.java");
        write(doomed, "class Doomed {}" + "\n");

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", root)));
        WorkspaceService service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);
        WatchHub hub = new WatchHub(service);
        Object peer = new Object();
        // The root, recursively, and NOTHING per file -- exactly what the explorer subscribes.
        hub.watch(peer, WorkspaceActor.LOCAL, CgPath.parse("proj:"), true);

        service.drainFileEvents();
        delete(doomed);

        assertTrue("the watcher saw it go; the hub had never stat-ed it",
                awaitChange(service, hub, peer, "Doomed.java"));
        service.close();
    }

    private static boolean awaitChange(WorkspaceService service, WatchHub hub, Object peer,
                                       String endingIn) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<CgFileEvent> events = service.drainFileEvents();
            if (!events.isEmpty()) {
                for (List<FsMessages.FileChange> mine
                        : hub.tick(WorkspaceActor.LOCAL, events).values()) {
                    for (FsMessages.FileChange change : mine) {
                        if (change.path().endsWith(endingIn)
                                && change.kind() == FsMessages.ChangeKind.DELETED) return true;
                    }
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ── Who did it ────────────────────────────────────────────────────────

    /**
     * <b>An operation reaches the other peers with a name on it, and never goes home again.</b> A
     * filesystem event cannot carry one — the OS was never told who asked — so only an operation the
     * server performed can say, and the peer that asked is the one peer that already knows.
     */
    @Test
    public void anOperationIsToldToOtherPeersByName() {
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", tempRoot())));
        WorkspaceService service = new WorkspaceService(
                registry, new InMemoryFileSystem().seed("proj:Main.java", "class Main {}"),
                WorkspacePermission.ALLOW_ALL);
        WatchHub hub = new WatchHub(service);
        Object alice = new Object();
        Object bob = new Object();
        hub.watch(alice, () -> "alice", CgPath.parse("proj:"), true);
        hub.watch(bob, () -> "bob", CgPath.parse("proj:"), true);

        hub.noteChanged(CgPath.parse("proj:Main.java"), FsMessages.ChangeKind.MODIFIED,
                "etag-2", "alice", alice);
        Map<Object, List<FsMessages.FileChange>> out = hub.tick(WorkspaceActor.LOCAL, List.of());

        assertNull("whoever asked already knows, and would reload what they just wrote", out.get(alice));
        List<FsMessages.FileChange> heard = out.get(bob);
        assertNotNull("everybody else hears about it", heard);
        assertEquals(1, heard.size());
        assertEquals("alice", heard.get(0).author());
        assertTrue(heard.get(0).byPeer());
    }

    /** And a change from outside the workspace carries no name, because nothing knows one. */
    @Test
    public void aChangeFromOutsideCarriesNoName() {
        Path root = tempRoot();
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", root)));
        WorkspaceService service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);
        WatchHub hub = new WatchHub(service);
        Object peer = new Object();
        hub.watch(peer, WorkspaceActor.LOCAL, CgPath.parse("proj:"), true);

        service.drainFileEvents();
        write(root.resolve("Appeared.java"), "class Appeared {}" + "\n");

        long deadline = System.currentTimeMillis() + 5000;
        FsMessages.FileChange seen = null;
        while (seen == null && System.currentTimeMillis() < deadline) {
            for (List<FsMessages.FileChange> mine
                    : hub.tick(WorkspaceActor.LOCAL, service.drainFileEvents()).values()) {
                for (FsMessages.FileChange change : mine) {
                    if (change.path().endsWith("Appeared.java")) seen = change;
                }
            }
            if (seen == null) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertNotNull("the watcher saw it", seen);
        assertFalse("nothing here asked for it", seen.byPeer());
        assertEquals("", seen.author());
        service.close();
    }

    /**
     * <b>A rename the server performed is one event, not a rename and a deletion.</b>
     *
     * <p>The watcher sees the same move as two halves. The destination is suppressed because its etag
     * already matches what the write recorded, but the source looks exactly like a deletion — and a
     * deletion the watcher SAW is trusted on its own, since that is the only way a file nobody had open
     * can be reported gone. So an author renaming their own file was told it had been deleted.</p>
     */
    @Test
    public void aStatedRenameIsNotAlsoReportedAsADeletion() {
        Path root = tempRoot();
        Path from = root.resolve("Before.java");
        write(from, "class Before {}\n");

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", root)));
        WorkspaceService service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);
        WatchHub hub = new WatchHub(service);
        Object watcher = new Object();
        Object mover = new Object();
        hub.watch(watcher, WorkspaceActor.LOCAL, CgPath.parse("proj:"), true);
        hub.watch(mover, () -> "mover", CgPath.parse("proj:"), true);
        service.drainFileEvents();

        CgPath source = CgPath.parse("proj:Before.java");
        CgPath target = CgPath.parse("proj:After.java");
        service.rename(WorkspaceActor.LOCAL, source, target, false);
        hub.noteRenamed(source, target, service.stat(WorkspaceActor.LOCAL, target).etag(),
                "mover", mover);

        long deadline = System.currentTimeMillis() + 3000;
        List<FsMessages.FileChange> heard = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            List<FsMessages.FileChange> mine =
                    hub.tick(WorkspaceActor.LOCAL, service.drainFileEvents()).get(watcher);
            if (mine != null) heard.addAll(mine);
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        service.close();

        for (FsMessages.FileChange change : heard) {
            assertNotEquals("the move came back as a deletion of where it came from",
                    FsMessages.ChangeKind.DELETED, change.kind());
        }
        assertTrue("and the rename itself is told, by name", heard.stream().anyMatch(
                change -> change.kind() == FsMessages.ChangeKind.RENAMED
                        && "mover".equals(change.author())));
    }
}
