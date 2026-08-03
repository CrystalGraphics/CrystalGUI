package com.crystalgui.headless;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CgPath} — the workspace's one-way door.
 *
 * <p>Two properties are worth more than the rest combined, and both are tested first: a path cannot
 * escape its project, and a path survives a round trip through text. The first is the security boundary;
 * the second is what saved documents depend on.</p>
 *
 * <p>Headless on purpose — this is a value type with no window, no GL and no platform, and it must stay
 * that way so a dedicated server can hold one.</p>
 */
public class CgPathTest {

    // ── Traversal — written before any resolver exists ──────────────────────────────────────────

    /**
     * <b>A path may not climb out of its project.</b>
     *
     * <p>The whole reason the type resolves {@code ..} at construction. Every one of these is the same
     * attack written a different way, which is exactly why a call-site check is the wrong place for it —
     * they do not all look alike, and missing one is a directory traversal on a network protocol.</p>
     */
    @Test
    public void aPathCannotEscapeItsProject() {
        String[] attacks = {
                "..",
                "../",
                "../secret",
                "../../server.properties",
                "src/../..",
                "src/../../etc/passwd",
                "a/b/../../..",
                "./../x",
                "..\\..\\windows",           // backslashes normalise to '/', so this is the same attack
                "src\\..\\..\\ops.json",
                "a/./../../b",
        };
        for (String attack : attacks) {
            try {
                CgPath escaped = CgPath.of("proj", attack);
                fail("'" + attack + "' escaped the project and produced " + escaped);
            } catch (CgFileSystemException e) {
                assertEquals("'" + attack + "' must be refused as an invalid path",
                        CgFileError.INVALID_PATH, e.getError());
            }
        }
    }

    /**
     * <b>Refused, never clamped.</b>
     *
     * <p>Silently pinning an escaping path to the root turns an attack into a wrong answer that looks
     * fine — the client asked for one thing and got another with no error, which is the hardest kind of
     * bug to notice and the easiest to build on top of.</p>
     */
    @Test
    public void escapingIsRefusedRatherThanClampedToTheRoot() {
        try {
            CgPath.of("proj", "../../elsewhere");
            fail("expected a refusal");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.INVALID_PATH, e.getError());
        }
    }

    /** {@code ..} is fine while it stays inside — it is escaping that is refused, not the syntax. */
    @Test
    public void dotDotResolvesNormallyWhileItStaysInside() {
        assertEquals("proj:src/Main.java", CgPath.of("proj", "src/util/../Main.java").toString());
        assertEquals("proj:a", CgPath.of("proj", "a/b/..").toString());
        assertEquals("proj:", CgPath.of("proj", "a/..").toString());
    }

    /** And {@code resolve} is held to the same rule, since it is the other way in. */
    @Test
    public void resolveCannotEscapeEither() {
        CgPath root = CgPath.ofProject("proj");
        try {
            root.resolve("../outside");
            fail("resolve escaped from the project root");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.INVALID_PATH, e.getError());
        }
        // ...but from a subdirectory it may step back up, because it has somewhere to go.
        assertEquals("proj:src", CgPath.of("proj", "src/util").resolve("..").toString());
    }

    @Test
    public void aNulCharacterIsRefused() {
        try {
            CgPath.of("proj", "a/b\u0000c");
            fail("expected a refusal");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.INVALID_PATH, e.getError());
        }
    }

    // ── Round trip — what saved documents depend on ─────────────────────────────────────────────

    /** <b>Every path survives {@code toString} → {@code parse} unchanged.</b> */
    @Test
    public void everyPathRoundTrips() {
        String[] paths = {
                "proj:",
                "proj:a",
                "proj:src/Main.java",
                "proj:a/b/c/d/e.txt",
                "my-project:deeply/nested/path/file.with.dots.png",
                "p:file name with spaces.md",
                "p:unicode/\u00e9\u00e8\u00ea/\u65e5\u672c\u8a9e.txt",
        };
        for (String text : paths) {
            CgPath parsed = CgPath.parse(text);
            assertEquals("round trip", text, parsed.toString());
            assertEquals("re-parsing is stable", parsed, CgPath.parse(parsed.toString()));
        }
    }

    /** Normalisation is idempotent, so a normalised path parses back to itself rather than shifting. */
    @Test
    public void normalisationIsIdempotent() {
        CgPath once = CgPath.of("proj", "\\a//b/./c/");
        assertEquals("proj:a/b/c", once.toString());
        assertEquals(once, CgPath.parse(once.toString()));
    }

    @Test
    public void separatorsAndRepeatsAreNormalised() {
        assertEquals("proj:a/b", CgPath.of("proj", "a\\b").toString());
        assertEquals("proj:a/b", CgPath.of("proj", "a//b").toString());
        assertEquals("proj:a/b", CgPath.of("proj", "/a/b/").toString());
        assertEquals("proj:a/b", CgPath.of("proj", "./a/./b").toString());
        assertEquals("proj:", CgPath.of("proj", "").toString());
        assertEquals("proj:", CgPath.of("proj", "/").toString());
    }

    // ── Shape ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aProjectIdIsRequiredAndMayNotContainSeparators() {
        for (String bad : new String[] { "", "a:b", "a/b", "a\\b" }) {
            try {
                CgPath.of(bad, "x");
                fail("'" + bad + "' should not be a legal project id");
            } catch (CgFileSystemException e) {
                assertEquals(CgFileError.INVALID_PATH, e.getError());
            }
        }
        try {
            CgPath.parse("no-colon-here");
            fail("a path without a project must be refused");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.INVALID_PATH, e.getError());
        }
    }

    @Test
    public void nameParentAndSegments() {
        CgPath file = CgPath.of("proj", "src/util/Strings.java");
        assertEquals("Strings.java", file.name());
        assertEquals(List.of("src", "util", "Strings.java"), file.segments());
        assertEquals("proj:src/util", file.parent().toString());
        assertEquals("proj:src", file.parent().parent().toString());
        assertEquals("proj:", file.parent().parent().parent().toString());
        assertNull("the project root has no parent", CgPath.ofProject("proj").parent());

        assertTrue(CgPath.ofProject("proj").isProjectRoot());
        assertEquals("", CgPath.ofProject("proj").name());
    }

    @Test
    public void extensionIsLowercasedAndDotless() {
        assertEquals("java", CgPath.of("p", "A.java").extension());
        assertEquals("png", CgPath.of("p", "img.PNG").extension());
        assertEquals("gz", CgPath.of("p", "a.tar.gz").extension());
        assertEquals("", CgPath.of("p", "Makefile").extension());
        assertEquals("", CgPath.of("p", "trailing.").extension());
        assertEquals("", CgPath.of("p", ".gitignore").extension());   // a dotfile is not an extension
        assertEquals("", CgPath.ofProject("p").extension());
    }

    /** {@code contains} is what fans a directory event out to the files under it. */
    @Test
    public void containsCoversTheSubtreeAndNothingElse() {
        CgPath dir = CgPath.of("proj", "src/util");
        assertTrue("itself", dir.contains(dir));
        assertTrue("a child", dir.contains(CgPath.of("proj", "src/util/Strings.java")));
        assertTrue("a grandchild", dir.contains(CgPath.of("proj", "src/util/a/b.txt")));
        assertFalse("the parent", dir.contains(CgPath.of("proj", "src")));
        assertFalse("a sibling", dir.contains(CgPath.of("proj", "src/other/x")));
        assertFalse("another project", dir.contains(CgPath.of("other", "src/util/x")));

        // A PREFIX IS NOT A PARENT. Comparing strings would make "src/utilities" a child of "src/util",
        // which is why this compares SEGMENTS.
        assertFalse("a name that merely starts the same",
                dir.contains(CgPath.of("proj", "src/utilities/x")));
    }

    /**
     * <b>Comparison is case-sensitive here</b>, and that is not an oversight.
     *
     * <p>Whether a filesystem folds case is a property of that filesystem — VS Code models it as the
     * {@code PathCaseSensitive} capability — so a value type cannot answer it. Two paths differing only
     * in case are two values; a provider that folds may resolve both to one file.</p>
     */
    @Test
    public void equalityIsCaseSensitiveBecauseCaseIsTheProvidersBusiness() {
        assertNotEquals(CgPath.of("p", "Foo.java"), CgPath.of("p", "foo.java"));
        assertNotEquals(CgPath.of("A", "x"), CgPath.of("a", "x"));
        assertEquals(CgPath.of("p", "Foo.java"), CgPath.of("p", "Foo.java"));
        assertEquals(CgPath.of("p", "Foo.java").hashCode(), CgPath.of("p", "Foo.java").hashCode());
    }

    @Test
    public void segmentsAreImmutable() {
        try {
            CgPath.of("p", "a/b").segments().add("c");
            fail("segments must not be mutable — a path is a value");
        } catch (UnsupportedOperationException expected) {
            // the point
        }
    }
}
