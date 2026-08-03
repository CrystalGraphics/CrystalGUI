package com.crystalgui.headless;

import com.crystalgui.fs.CgFileCapability;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.LocalFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceProject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link LocalFileSystem} — the only implementation that touches a disk, and so the only place the
 * symlink boundary can be tested at all.
 *
 * <p>Real directories in a temp folder, real symlinks where the OS permits them. The escape tests are the
 * reason this class exists; everything else it does is already covered against the in-memory
 * implementation.</p>
 */
public class LocalFileSystemTest {

    private Path sandbox;
    private Path projectRoot;
    private Path outside;
    private LocalFileSystem fs;

    @Before
    public void setUp() throws IOException {
        sandbox = Files.createTempDirectory("cgui-fs-test");
        projectRoot = Files.createDirectory(sandbox.resolve("project"));
        outside = Files.createDirectory(sandbox.resolve("outside"));

        Files.createDirectory(projectRoot.resolve("src"));
        Files.write(projectRoot.resolve("src/Main.java"), "class Main {}".getBytes(StandardCharsets.UTF_8));
        Files.write(projectRoot.resolve("README.md"), "# hello".getBytes(StandardCharsets.UTF_8));
        Files.write(outside.resolve("secret.txt"), "not yours".getBytes(StandardCharsets.UTF_8));

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "Project", projectRoot)));
        fs = new LocalFileSystem(registry);
    }

    @After
    public void tearDown() throws IOException {
        if (sandbox == null || !Files.exists(sandbox)) return;
        try (Stream<Path> walk = Files.walk(sandbox)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(each);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static CgPath p(String path) {
        return CgPath.parse(path);
    }

    private static void expect(CgFileError code, Runnable action) {
        try {
            action.run();
            fail("expected " + code);
        } catch (CgFileSystemException e) {
            assertEquals(code, e.getError());
        }
    }

    /** Creates a symlink, or skips the test where the OS will not allow one (Windows without privileges). */
    private Path symlinkOrSkip(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assume.assumeNoException("this OS/user cannot create symlinks", e);
            return null;
        }
    }

    // ── The symlink boundary — the reason this class is tested separately ───────────────────────

    /**
     * <b>A symlink pointing out of the project does not let a client read through it.</b>
     *
     * <p>The check {@link CgPath} structurally cannot make: {@code proj:escape/secret.txt} contains no
     * {@code ..} at all and is a perfectly well-formed path. Only resolving it against the real
     * filesystem reveals that it lands outside the root.</p>
     */
    @Test
    public void aSymlinkOutOfTheProjectIsRefused() {
        symlinkOrSkip(projectRoot.resolve("escape"), outside);

        expect(CgFileError.INVALID_PATH, () -> fs.read(p("mymod.proj:escape/secret.txt")));
        expect(CgFileError.INVALID_PATH, () -> fs.list(p("mymod.proj:escape")));
        expect(CgFileError.INVALID_PATH, () -> fs.stat(p("mymod.proj:escape/secret.txt")));
    }

    /** Writing through one is refused too — the check is on resolution, not on the operation. */
    @Test
    public void writingThroughAnEscapingSymlinkIsRefused() {
        symlinkOrSkip(projectRoot.resolve("escape"), outside);

        expect(CgFileError.INVALID_PATH,
                () -> fs.write(p("mymod.proj:escape/planted.txt"), new byte[] { 1 }, true, true));
        assertFalse("nothing may be created outside the project",
                Files.exists(outside.resolve("planted.txt")));
    }

    /** A symlink to a single file outside is caught as surely as one to a directory. */
    @Test
    public void aSymlinkToAFileOutsideIsRefused() {
        symlinkOrSkip(projectRoot.resolve("leak.txt"), outside.resolve("secret.txt"));
        expect(CgFileError.INVALID_PATH, () -> fs.read(p("mymod.proj:leak.txt")));
    }

    /** A link that stays INSIDE the project is fine — this confines, it does not ban links. */
    @Test
    public void aSymlinkWithinTheProjectStillWorks() {
        symlinkOrSkip(projectRoot.resolve("alias"), projectRoot.resolve("src"));

        assertEquals("class Main {}",
                new String(fs.read(p("mymod.proj:alias/Main.java")), StandardCharsets.UTF_8));
    }

    /**
     * <b>A recursive delete does not follow a link out of the project.</b>
     *
     * <p>The worst possible version of this bug: not a leak, but destruction of files the server never
     * meant to expose. The walk does not follow links, so the link itself is removed and its target is
     * left alone.</p>
     */
    @Test
    public void aRecursiveDeleteDoesNotFollowLinksOutOfTheProject() {
        Path linkDir = projectRoot.resolve("src/link");
        symlinkOrSkip(linkDir, outside);

        fs.delete(p("mymod.proj:src"), true);

        assertFalse("the project's own directory is gone", Files.exists(projectRoot.resolve("src")));
        assertTrue("but the link's TARGET survives", Files.exists(outside));
        assertTrue("and so does everything in it", Files.exists(outside.resolve("secret.txt")));
    }

    /** A path that never existed still cannot resolve outside — the ancestor check covers creation. */
    @Test
    public void creatingUnderAnEscapingLinkIsRefusedBeforeAnythingIsWritten() {
        symlinkOrSkip(projectRoot.resolve("escape"), outside);
        expect(CgFileError.INVALID_PATH, () -> fs.mkdir(p("mymod.proj:escape/newdir")));
        assertFalse(Files.exists(outside.resolve("newdir")));
    }

    // ── Atomic write ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The temp file lives in the target's own directory.</b>
     *
     * <p>Not cosmetic: a temp elsewhere is on another filesystem as often as not, where
     * {@code ATOMIC_MOVE} is unsupported and the fallback is copy-then-delete — the very thing being
     * avoided. Observed rather than asserted about, by catching the file mid-write.</p>
     */
    @Test
    public void theTempFileIsWrittenBesideTheTarget() throws IOException {
        fs.write(p("mymod.proj:src/Big.java"), new byte[64 * 1024], true, true);

        try (Stream<Path> leftovers = Files.list(projectRoot.resolve("src"))) {
            List<String> names = leftovers.map(x -> x.getFileName().toString()).toList();
            assertFalse("no temp file may be left behind: " + names,
                    names.stream().anyMatch(n -> n.startsWith(".cgui-")));
            assertTrue(names.contains("Big.java"));
        }
    }

    @Test
    public void aWriteReplacesContentExactly() {
        byte[] binary = new byte[512];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) (i * 13 + 5);

        fs.write(p("mymod.proj:asset.bin"), binary, true, true);
        assertArrayEquals(binary, fs.read(p("mymod.proj:asset.bin")));
    }

    @Test
    public void writeRespectsCreateAndOverwrite() {
        expect(CgFileError.FILE_NOT_FOUND,
                () -> fs.write(p("mymod.proj:ghost.txt"), new byte[0], false, true));
        expect(CgFileError.FILE_EXISTS,
                () -> fs.write(p("mymod.proj:README.md"), new byte[0], true, false));
        expect(CgFileError.FILE_IS_A_DIRECTORY,
                () -> fs.write(p("mymod.proj:src"), new byte[0], true, true));
    }

    // ── The size ceiling ────────────────────────────────────────────────────────────────────────

    /** Over the limit is refused with a code, not attempted and not truncated. */
    @Test
    public void aFileOverTheCeilingIsRefused() throws IOException {
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "Project", projectRoot)));
        LocalFileSystem tiny = new LocalFileSystem(registry, 16, true);

        Files.write(projectRoot.resolve("big.bin"), new byte[64]);
        expect(CgFileError.FILE_TOO_LARGE, () -> tiny.read(p("mymod.proj:big.bin")));

        // ...and one under it is unaffected.
        Files.write(projectRoot.resolve("small.bin"), new byte[8]);
        assertEquals(8, tiny.read(p("mymod.proj:small.bin")).length);
    }

    // ── Ordinary behaviour, to prove it matches the in-memory one ───────────────────────────────

    @Test
    public void readListStatAndMkdirBehaveAsTheInMemoryOneDoes() {
        assertEquals("# hello", new String(fs.read(p("mymod.proj:README.md")), StandardCharsets.UTF_8));

        List<String> names = fs.list(p("mymod.proj:")).stream()
                .map(e -> e.name()).sorted().toList();
        assertEquals(List.of("README.md", "src"), names);

        assertTrue(fs.stat(p("mymod.proj:src")).isDirectory());
        assertTrue(fs.stat(p("mymod.proj:README.md")).isFile());

        fs.mkdir(p("mymod.proj:build"));
        assertTrue(fs.stat(p("mymod.proj:build")).isDirectory());
        expect(CgFileError.FILE_EXISTS, () -> fs.mkdir(p("mymod.proj:build")));

        expect(CgFileError.FILE_NOT_FOUND, () -> fs.read(p("mymod.proj:ghost.md")));
        expect(CgFileError.FILE_IS_A_DIRECTORY, () -> fs.read(p("mymod.proj:src")));
        expect(CgFileError.FILE_NOT_A_DIRECTORY, () -> fs.list(p("mymod.proj:README.md")));
    }

    /** An etag moves when the file does — on a real filesystem, where mtime is the OS's to give. */
    @Test
    public void theEtagTracksARealWrite() throws InterruptedException {
        String before = fs.stat(p("mymod.proj:README.md")).etag();
        // Content of a different LENGTH, so the etag moves even where mtime granularity is coarse --
        // some filesystems round to a second, which would make a same-length rewrite look unchanged.
        fs.write(p("mymod.proj:README.md"), "# a longer greeting".getBytes(StandardCharsets.UTF_8),
                false, true);
        assertEquals("differing sizes must differ",
                false, before.equals(fs.stat(p("mymod.proj:README.md")).etag()));
    }

    @Test
    public void deleteAndRenameWork() {
        fs.rename(p("mymod.proj:README.md"), p("mymod.proj:src/README.md"), false);
        assertFalse(fs.exists(p("mymod.proj:README.md")));
        assertTrue(fs.exists(p("mymod.proj:src/README.md")));

        expect(CgFileError.FILE_IS_A_DIRECTORY, () -> fs.delete(p("mymod.proj:src"), false));
        fs.delete(p("mymod.proj:src"), true);
        assertFalse(fs.exists(p("mymod.proj:src")));
    }

    @Test
    public void anUnknownProjectIsNotFound() {
        expect(CgFileError.FILE_NOT_FOUND, () -> fs.read(p("ghost.proj:a.txt")));
    }

    // ── Capabilities ────────────────────────────────────────────────────────────────────────────

    @Test
    public void itAdvertisesAtomicWritesAndItsOwnCaseRule() {
        assertTrue(fs.has(CgFileCapability.FILE_READ_WRITE));
        assertTrue(fs.has(CgFileCapability.FILE_ATOMIC_WRITE));

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "Project", projectRoot)));
        assertTrue(new LocalFileSystem(registry, 1024, true).has(CgFileCapability.PATH_CASE_SENSITIVE));
        assertFalse("a host that knows better can say so",
                new LocalFileSystem(registry, 1024, false).has(CgFileCapability.PATH_CASE_SENSITIVE));
    }
}
