package com.crystalgui.fs;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link SourceRoots} — turning a path into a qualified name.
 *
 * <p>Headless because it is arithmetic on strings, and putting it here asserts that: nothing about
 * deciding what a file is called should need a window, a workspace connection or an engine.</p>
 */
public class SourceRootsTest {

    private static final List<String> MAVEN = List.of("src/main/java", "src/main/js");

    private static SourceRoots.Located locate(String path, List<String> roots) {
        return SourceRoots.locate(CgPath.parse(path), roots);
    }

    private static String qualified(String path, List<String> roots) {
        SourceRoots.Located located = locate(path, roots);
        return located == null ? null : located.qualifiedName();
    }

    // ── The ordinary case ───────────────────────────────────────────────────────────────────────

    /** <b>A file under a root is named by where it sits.</b> The whole point of declaring one. */
    @Test
    public void aFileUnderARootIsNamedByItsDirectory() {
        assertEquals("com.example.Main", qualified("p:src/main/java/com/example/Main.java", MAVEN));
        assertEquals("Main", qualified("p:src/main/java/Main.java", MAVEN));
        assertEquals("util.Helper", qualified("p:src/main/js/util/Helper.js", MAVEN));
    }

    /** <b>The parts are separated, not just the joined name.</b> A rename needs the pieces. */
    @Test
    public void theRootPackageAndNameAreAllReported() {
        SourceRoots.Located located = locate("p:src/main/java/com/example/Main.java", MAVEN);
        assertEquals("src/main/java", located.root());
        assertEquals("com.example", located.packageName());
        assertEquals("Main", located.simpleName());
    }

    /** <b>A file directly in the root is in the default package</b>, not in one called after the root. */
    @Test
    public void aFileAtTheRootIsInTheDefaultPackage() {
        assertEquals("", locate("p:src/main/java/Main.java", MAVEN).packageName());
    }

    // ── What must NOT be named ──────────────────────────────────────────────────────────────────

    /**
     * <b>A file outside every root has no derived name.</b>
     *
     * <p>Null rather than a guess, because the caller has a correct fallback — the file's own
     * {@code package} declaration — and inventing one here would override it with a fact nobody stated.
     * A README, a config file and a scratch script in a rootless project all land here.</p>
     */
    @Test
    public void aFileOutsideEveryRootIsNotLocated() {
        assertNull(locate("p:README.md", MAVEN));
        assertNull(locate("p:src/Main.java", MAVEN));
        assertNull(locate("p:src/test/java/com/example/MainTest.java", MAVEN));
    }

    /** <b>A project that declares no roots locates nothing</b> — and is not an error. */
    @Test
    public void noRootsLocatesNothing() {
        assertNull(locate("p:src/main/java/Main.java", List.of()));
        assertNull(locate("p:src/main/java/Main.java", null));
        assertNull(SourceRoots.locate(null, MAVEN));
    }

    /**
     * <b>A root matches only at a directory boundary.</b>
     *
     * <p>A plain {@code startsWith} makes {@code src/mainland/Foo.java} a member of root
     * {@code src/main} and reports its package as {@code land} — a name derived from half a directory,
     * which exists nowhere and resolves to nothing.</p>
     */
    @Test
    public void aRootMatchesOnlyWholeDirectories() {
        assertNull(locate("p:src/mainland/Foo.java", List.of("src/main")));
        assertEquals("Foo", qualified("p:src/main/Foo.java", List.of("src/main")));
    }

    // ── Nesting ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The longest matching root wins.</b>
     *
     * <p>Roots nest in real layouts, and taking the first match fails <em>quietly</em>: with both
     * {@code src} and {@code src/main/java} declared, {@code src/main/java/foo/Bar.java} would report
     * package {@code main.java.foo}. That is a name ECJ will happily resolve against and that is wrong
     * everywhere it is displayed.</p>
     *
     * <p>Asserted in both declaration orders, because "first match" and "longest match" agree in one of
     * them — a single-order test passes against the bug half the time.</p>
     */
    @Test
    public void theLongestRootWinsWhateverOrderTheyAreDeclaredIn() {
        assertEquals("foo.Bar",
                qualified("p:src/main/java/foo/Bar.java", List.of("src", "src/main/java")));
        assertEquals("foo.Bar",
                qualified("p:src/main/java/foo/Bar.java", List.of("src/main/java", "src")));
    }

    // ── Shapes a declaration can arrive in ──────────────────────────────────────────────────────

    /** <b>Slashes around a declared root are trimmed</b>, so hand-written config cannot miss by one. */
    @Test
    public void aRootIsNormalisedBeforeItIsMatched() {
        assertEquals("Main", qualified("p:src/main/java/Main.java", List.of("/src/main/java/")));
        assertEquals("Main", qualified("p:src/main/java/Main.java", List.of("src\\main\\java")));
    }

    /** <b>A root naming nothing is ignored</b> rather than matching everything. */
    @Test
    public void anEmptyRootDeclarationIsIgnored() {
        assertNull(locate("p:src/main/java/Main.java", List.of("", "   ", "/")));
    }

    /** <b>The name is the stem</b>, whatever the extension — and a dotfile keeps its whole name. */
    @Test
    public void theSimpleNameDropsTheExtension() {
        assertEquals("Main", locate("p:src/main/java/Main.java", MAVEN).simpleName());
        assertEquals("Main", locate("p:src/main/js/Main.js", MAVEN).simpleName());
        assertEquals(".gitkeep", locate("p:src/main/java/.gitkeep", MAVEN).simpleName());
    }

    // ── The default ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A project that says nothing gets the ordinary layout.</b>
     *
     * <p>And empty means the same as absent, deliberately: the wire encodes the two identically, so an
     * older server describing an ordinary project would otherwise read as rootless and silently switch
     * every file back to a declaration-derived package.</p>
     */
    @Test
    public void aProjectThatDeclaresNothingGetsTheConvention() {
        assertEquals(SourceRoots.CONVENTION, new ProjectInfo("p", "P").sourceRoots());
        assertEquals(SourceRoots.CONVENTION, new ProjectInfo("p", "P", null).sourceRoots());
        assertEquals(SourceRoots.CONVENTION, new ProjectInfo("p", "P", List.of()).sourceRoots());
    }

    /**
     * <b>The harness fixture's own paths, pinned.</b>
     *
     * <p>These are the exact files {@code HarnessWorkspace} seeds for the cross-file demonstration, and
     * the names asserted here are the ones their {@code package} declarations state. If the layout and
     * this derivation ever disagree, the fixture stops resolving and reads as the FEATURE being broken
     * rather than the fixture being misplaced — which is the worst way for a demonstration to fail.</p>
     */
    @Test
    public void theHarnessCrossFileFixtureIsNamedAsItsPackagesDeclare() {
        assertEquals("com.example.Main",
                qualified("harness.scratch:src/main/java/com/example/Main.java", MAVEN));
        assertEquals("com.example.util.Greeter",
                qualified("harness.scratch:src/main/java/com/example/util/Greeter.java", MAVEN));
        assertEquals("com.example.util.Formatter",
                qualified("harness.scratch:src/main/java/com/example/util/Formatter.java", MAVEN));
    }

    /**
     * <b>...and the JavaScript half of it, which uses the OTHER declared root.</b>
     *
     * <p>Pinned separately because it is a different root doing the work: {@code src/main/js} is what
     * turns {@code util/Greeter.js} into the name {@code util.Greeter} that an {@code import} statement
     * writes. A fixture placed under {@code src/} instead would have no derived name at all and could not
     * be imported — and the demonstration would fail in a way that reads as the FEATURE being broken.</p>
     */
    @Test
    public void theHarnessJavaScriptFixtureIsNamedByItsRoot() {
        assertEquals("App", qualified("harness.scratch:src/main/js/App.js", MAVEN));
        assertEquals("util.Greeter", qualified("harness.scratch:src/main/js/util/Greeter.js", MAVEN));
        assertEquals("util.Formatter", qualified("harness.scratch:src/main/js/util/Formatter.js", MAVEN));
    }

    /** <b>...and one that does say something keeps it.</b> */
    @Test
    public void aDeclaredLayoutIsKept() {
        assertEquals(List.of("scripts"), new ProjectInfo("p", "P", List.of("scripts")).sourceRoots());
    }
}
