package com.crystalgui.headless;

import com.crystalgui.fs.CgFileCapability;
import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link InMemoryFileSystem} — the implementation the protocol is developed against.
 *
 * <p>Every refusal is asserted by <b>code</b>, not by "it threw". The codes are what a UI branches on,
 * and a test that only checks for an exception passes just as happily when every failure collapses to
 * {@code UNKNOWN}.</p>
 */
public class InMemoryFileSystemTest {

    private InMemoryFileSystem fs;

    @Before
    public void setUp() {
        fs = new InMemoryFileSystem()
                .seed("proj:src/Main.java", "class Main {}")
                .seed("proj:src/util/Strings.java", "class Strings {}")
                .seed("proj:README.md", "# hello");
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

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void readsWhatWasSeeded() {
        assertEquals("class Main {}", new String(fs.read(p("proj:src/Main.java")), StandardCharsets.UTF_8));
        assertEquals("# hello", new String(fs.read(p("proj:README.md")), StandardCharsets.UTF_8));
    }

    @Test
    public void listsADirectory() {
        List<String> names = fs.list(p("proj:src")).stream().map(CgFileEntry::name).sorted().toList();
        assertEquals(List.of("Main.java", "util"), names);

        List<String> root = fs.list(p("proj:")).stream().map(CgFileEntry::name).sorted().toList();
        assertEquals(List.of("README.md", "src"), root);
    }

    @Test
    public void statDistinguishesFilesFromDirectories() {
        assertTrue(fs.stat(p("proj:src")).isDirectory());
        assertTrue(fs.stat(p("proj:src/Main.java")).isFile());
        assertEquals("class Main {}".length(), fs.stat(p("proj:src/Main.java")).size());
        assertEquals("a directory has no size of its own", 0L, fs.stat(p("proj:src")).size());
    }

    @Test
    public void missingThingsReportNotFound() {
        expect(CgFileError.FILE_NOT_FOUND, () -> fs.read(p("proj:nope.txt")));
        expect(CgFileError.FILE_NOT_FOUND, () -> fs.stat(p("proj:nope.txt")));
        expect(CgFileError.FILE_NOT_FOUND, () -> fs.list(p("proj:nope")));
        expect(CgFileError.FILE_NOT_FOUND, () -> fs.read(p("other:Main.java")));
    }

    @Test
    public void theWrongKindOfThingSaysWhichKind() {
        expect(CgFileError.FILE_IS_A_DIRECTORY, () -> fs.read(p("proj:src")));
        expect(CgFileError.FILE_NOT_A_DIRECTORY, () -> fs.list(p("proj:README.md")));
    }

    /** Reads are copies — a caller cannot reach into the stored bytes. */
    @Test
    public void readReturnsACopy() {
        byte[] first = fs.read(p("proj:README.md"));
        first[0] = '!';
        assertEquals("# hello", new String(fs.read(p("proj:README.md")), StandardCharsets.UTF_8));
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>{@code create} and {@code overwrite} are separate, and both are needed.</b>
     *
     * <p>Save is {@code create=false, overwrite=true}. New File is {@code create=true, overwrite=false},
     * and it must fail rather than clobber something that appeared between the user typing a name and
     * pressing OK.</p>
     */
    @Test
    public void createAndOverwriteAreIndependent() {
        // Save over an existing file.
        fs.write(p("proj:README.md"), "# changed".getBytes(StandardCharsets.UTF_8), false, true);
        assertEquals("# changed", new String(fs.read(p("proj:README.md")), StandardCharsets.UTF_8));

        // Save to something absent: refused, because save does not invent files.
        expect(CgFileError.FILE_NOT_FOUND,
                () -> fs.write(p("proj:ghost.md"), new byte[0], false, true));

        // New File where nothing is: allowed.
        fs.write(p("proj:new.md"), "new".getBytes(StandardCharsets.UTF_8), true, false);
        assertEquals("new", new String(fs.read(p("proj:new.md")), StandardCharsets.UTF_8));

        // New File onto something that exists: refused.
        expect(CgFileError.FILE_EXISTS,
                () -> fs.write(p("proj:new.md"), new byte[0], true, false));
    }

    @Test
    public void writingOverADirectoryIsRefused() {
        expect(CgFileError.FILE_IS_A_DIRECTORY,
                () -> fs.write(p("proj:src"), new byte[0], true, true));
    }

    @Test
    public void writingIntoAMissingDirectoryIsRefused() {
        expect(CgFileError.FILE_NOT_FOUND,
                () -> fs.write(p("proj:nowhere/file.txt"), new byte[0], true, true));
    }

    /** Writes are copies too, in the other direction. */
    @Test
    public void writeStoresACopy() {
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        fs.write(p("proj:copy.txt"), content, true, true);
        content[0] = 'z';
        assertEquals("abc", new String(fs.read(p("proj:copy.txt")), StandardCharsets.UTF_8));
    }

    // ── etag, which everything above this depends on ────────────────────────────────────────────

    /**
     * <b>Every write moves the etag.</b>
     *
     * <p>The conflict machinery is built entirely on this. The in-memory clock is a counter rather than
     * a wall clock precisely so that two writes in the same millisecond are still distinguishable — with
     * {@code currentTimeMillis} this test would pass or fail depending on how fast the machine is.</p>
     */
    @Test
    public void everyWriteMovesTheEtag() {
        String before = fs.stat(p("proj:README.md")).etag();

        fs.write(p("proj:README.md"), "# one".getBytes(StandardCharsets.UTF_8), false, true);
        String after = fs.stat(p("proj:README.md")).etag();
        assertNotEquals("a changed file must not keep its etag", before, after);

        // ...even when the new content is the SAME LENGTH, which is the case a size-only check misses.
        fs.write(p("proj:README.md"), "# two".getBytes(StandardCharsets.UTF_8), false, true);
        assertNotEquals("a same-length rewrite must still move it",
                after, fs.stat(p("proj:README.md")).etag());
    }

    @Test
    public void anUnchangedFileKeepsItsEtag() {
        String first = fs.stat(p("proj:README.md")).etag();
        fs.list(p("proj:"));
        fs.read(p("proj:README.md"));
        assertEquals("reading must not disturb it", first, fs.stat(p("proj:README.md")).etag());
    }

    // ── Directories ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void mkdirCreatesOneLevel() {
        fs.mkdir(p("proj:build"));
        assertTrue(fs.stat(p("proj:build")).isDirectory());
        assertTrue(fs.list(p("proj:build")).isEmpty());

        expect(CgFileError.FILE_EXISTS, () -> fs.mkdir(p("proj:build")));
        expect(CgFileError.FILE_NOT_FOUND, () -> fs.mkdir(p("proj:a/b/c")));
    }

    @Test
    public void deleteRefusesANonEmptyDirectoryUnlessRecursive() {
        expect(CgFileError.FILE_IS_A_DIRECTORY, () -> fs.delete(p("proj:src"), false));
        fs.delete(p("proj:src"), true);
        assertFalse(fs.exists(p("proj:src")));
        assertFalse("children go with it", fs.exists(p("proj:src/Main.java")));
        assertTrue("siblings do not", fs.exists(p("proj:README.md")));
    }

    @Test
    public void deleteReportsAMissingTarget() {
        expect(CgFileError.FILE_NOT_FOUND, () -> fs.delete(p("proj:ghost"), false));
    }

    // ── Rename ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void renameMovesAnEntry() {
        fs.rename(p("proj:README.md"), p("proj:src/README.md"), false);
        assertFalse(fs.exists(p("proj:README.md")));
        assertEquals("# hello", new String(fs.read(p("proj:src/README.md")), StandardCharsets.UTF_8));
    }

    @Test
    public void renameCarriesAWholeSubtree() {
        fs.rename(p("proj:src"), p("proj:source"), false);
        assertEquals("class Strings {}",
                new String(fs.read(p("proj:source/util/Strings.java")), StandardCharsets.UTF_8));
    }

    /**
     * <b>A refused rename leaves the source where it was.</b>
     *
     * <p>The obvious implementation removes the source first and then discovers the target is occupied,
     * which loses the file. Ordering it the other way round is one line and the difference between a
     * refusal and data loss.</p>
     */
    @Test
    public void aRefusedRenameLosesNothing() {
        expect(CgFileError.FILE_EXISTS,
                () -> fs.rename(p("proj:README.md"), p("proj:src/Main.java"), false));

        assertTrue("the source survives", fs.exists(p("proj:README.md")));
        assertEquals("# hello", new String(fs.read(p("proj:README.md")), StandardCharsets.UTF_8));
        assertEquals("and the target is untouched", "class Main {}",
                new String(fs.read(p("proj:src/Main.java")), StandardCharsets.UTF_8));
    }

    @Test
    public void renameCanOverwriteWhenAsked() {
        fs.rename(p("proj:README.md"), p("proj:src/Main.java"), true);
        assertEquals("# hello", new String(fs.read(p("proj:src/Main.java")), StandardCharsets.UTF_8));
        assertFalse(fs.exists(p("proj:README.md")));
    }

    // ── Capabilities ────────────────────────────────────────────────────────────────────────────

    @Test
    public void itAdvertisesWhatItCanDo() {
        assertTrue(fs.has(CgFileCapability.FILE_READ_WRITE));
        assertTrue("a map keyed by string is case-sensitive whether or not anyone decided it",
                fs.has(CgFileCapability.PATH_CASE_SENSITIVE));
        assertFalse("no descriptor API yet", fs.has(CgFileCapability.FILE_OPEN_READ_WRITE_CLOSE));
        assertFalse(fs.has(CgFileCapability.READONLY));
    }

    /** Being case-sensitive is a claim, so it is checked rather than assumed. */
    @Test
    public void caseSensitivityIsRealAndNotJustAdvertised() {
        fs.write(p("proj:Case.txt"), "upper".getBytes(StandardCharsets.UTF_8), true, false);
        fs.write(p("proj:case.txt"), "lower".getBytes(StandardCharsets.UTF_8), true, false);

        assertEquals("upper", new String(fs.read(p("proj:Case.txt")), StandardCharsets.UTF_8));
        assertEquals("lower", new String(fs.read(p("proj:case.txt")), StandardCharsets.UTF_8));
    }

    // ── Bytes ───────────────────────────────────────────────────────────────────────────────────

    /** Binary survives unchanged — the workspace carries assets, not only text. */
    @Test
    public void arbitraryBytesSurvive() {
        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) binary[i] = (byte) i;

        fs.write(p("proj:image.png"), binary, true, true);
        assertArrayEquals(binary, fs.read(p("proj:image.png")));
    }
}
