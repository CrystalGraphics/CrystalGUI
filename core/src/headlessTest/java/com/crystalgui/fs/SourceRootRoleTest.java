package com.crystalgui.fs;

import com.crystalgui.fs.project.SourceRoots;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * What a directory IS in a project's layout — the classification the tree draws its icons from.
 *
 * <h3>Headless on purpose</h3>
 *
 * <p>It is string arithmetic over a path and a list of roots, with no drawable, no element and no window
 * anywhere in it. That is the whole reason the decision lives in {@code SourceRoots} rather than in the
 * renderer that consumes it: an icon is not testable without a GL context, and the choice behind it is.</p>
 */
public class SourceRootRoleTest {

    private static final List<String> ROOTS = SourceRoots.CONVENTION;

    /**
     * <b>The module is the directory that CONTAINS the source roots.</b>
     *
     * <p>IntelliJ's own answer, arrived at from the layout rather than from a module file: with
     * {@code src/main/java} and {@code src/main/js} declared, {@code src/main} holds both, and that is
     * the row IntelliJ draws the module icon on for a Gradle project — where the source set IS the
     * module. Nothing is configured to make it so.</p>
     */
    @Test
    public void theDirectoryHoldingTheSourceRootsIsTheModule() {
        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf("src/main", ROOTS));
        assertEquals("separators are normalised here too", SourceRoots.Role.MODULE,
                SourceRoots.roleOf("/src/main/", ROOTS));
    }

    /**
     * <b>...and so is the project root, whatever it declares.</b>
     *
     * <p>Two module rows in one tree is the correct picture of a Gradle project rather than a mistake: a
     * module icon does not mean "here is a project", it means "here is a compilation unit with source
     * under it", and both the root and {@code src/main} are that. A project with no source roots
     * declared yet still has a root, and a plain folder glyph there reads as an ordinary directory
     * somebody happened to open.</p>
     */
    @Test
    public void theProjectRootIsAlwaysAModule() {
        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf("", ROOTS));
        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf("", List.of("java", "js")));
        assertEquals("a project with no roots still has one", SourceRoots.Role.MODULE,
                SourceRoots.roleOf("", List.of()));
    }

    /** <b>A declared root is a source root, and both of them are.</b> */
    @Test
    public void aDeclaredRootIsASourceRoot() {
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src/main/java", ROOTS));
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src/main/js", ROOTS));
    }

    /** <b>What is merely on the way to a module is an ordinary folder.</b> */
    @Test
    public void theDirectoriesOnTheWayAreOrdinaryFolders() {
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("src", ROOTS));
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("build/classes", ROOTS));
    }

    /**
     * <b>Two source sets are two modules</b> — which is what the rule generalising correctly looks like.
     *
     * <p>Not built for: it falls out of "a module contains roots". A project that later declares
     * {@code src/test/java} gets a second module row with no code change, exactly as IntelliJ shows a
     * test source set as its own module.</p>
     */
    @Test
    public void aSecondSourceSetIsASecondModule() {
        List<String> both = List.of("src/main/java", "src/test/java");

        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf("src/main", both));
        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf("src/test", both));
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("src", both));
    }

    /** <b>Anything under a source root is a package</b> — at any depth. */
    @Test
    public void anythingUnderASourceRootIsAPackage() {
        assertEquals(SourceRoots.Role.PACKAGE, SourceRoots.roleOf("src/main/java/com", ROOTS));
        assertEquals(SourceRoots.Role.PACKAGE,
                SourceRoots.roleOf("src/main/java/com/example/util", ROOTS));
        assertEquals(SourceRoots.Role.PACKAGE, SourceRoots.roleOf("src/main/js/util", ROOTS));
    }

    /**
     * <b>Roots nest, and the more specific answer wins.</b>
     *
     * <p>With {@code src} and {@code src/main/java} both declared, everything under the second is under
     * the first as well. Returning on the first containing root would call {@code src/main/java} a package
     * of {@code src} — which is the same silent failure {@link SourceRoots#locate} documents for package
     * names, where it reports {@code main.java.foo} and compiles.</p>
     */
    @Test
    public void aNestedRootIsStillARootRatherThanAPackageOfTheOuterOne() {
        List<String> nested = List.of("src", "src/main/java");

        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src/main/java", nested));
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src", nested));
        assertEquals(SourceRoots.Role.PACKAGE, SourceRoots.roleOf("src/main/java/com", nested));
    }

    /**
     * <b>A project that declares no roots has folders and a module, and nothing else.</b>
     *
     * <p>Not a degenerate case to shrug at: it is every project opened before roots existed, and a tree
     * that answered PACKAGE there would put package glyphs down a directory tree that has no packages in
     * it at all.</p>
     */
    @Test
    public void aProjectWithNoRootsHasNoPackages() {
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("src/main/java", List.of()));
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("anything", null));
    }

    /** Separators are normalised, as everywhere else a path is compared here. */
    @Test
    public void separatorsAndEdgesAreNormalised() {
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src\\main\\java", ROOTS));
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("/src/main/java/", ROOTS));
        assertEquals("a null path is the project root", SourceRoots.Role.MODULE,
                SourceRoots.roleOf(null, ROOTS));
    }
}
