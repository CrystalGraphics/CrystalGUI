package com.crystalgui.headless;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceConflictException;
import com.crystalgui.fs.server.WorkspaceOperation;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.WorkspaceService;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
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
}
