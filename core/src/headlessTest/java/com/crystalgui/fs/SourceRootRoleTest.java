package com.crystalgui.fs;

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

    /** <b>The project root is the module.</b> One module per project, so its root is the one that is it. */
    @Test
    public void theProjectRootIsTheModule() {
        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf("", ROOTS));
        assertEquals("a path of separators is still the root", SourceRoots.Role.MODULE,
                SourceRoots.roleOf("/", ROOTS));
    }

    /** <b>A declared root is a source root, and both of them are.</b> */
    @Test
    public void aDeclaredRootIsASourceRoot() {
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src/main/java", ROOTS));
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src/main/js", ROOTS));
    }

    /**
     * <b>What is on the WAY to a source root is an ordinary folder.</b>
     *
     * <p>The deliberate divergence from IntelliJ, which shows a Gradle source set as a module and so puts
     * the module icon on {@code src/main}. That modelling earns its keep when a project has several
     * modules; ours has one, so {@code src} and {@code src/main} are the folders they are and the
     * distinction the icons exist to draw — root, source root, package — survives intact.</p>
     */
    @Test
    public void theDirectoriesOnTheWayAreOrdinaryFolders() {
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("src", ROOTS));
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("src/main", ROOTS));
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("build/classes", ROOTS));
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
        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf("", List.of()));
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("src/main/java", List.of()));
        assertEquals(SourceRoots.Role.FOLDER, SourceRoots.roleOf("anything", null));
    }

    /** Separators are normalised, as everywhere else a path is compared here. */
    @Test
    public void separatorsAndEdgesAreNormalised() {
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("src\\main\\java", ROOTS));
        assertEquals(SourceRoots.Role.SOURCE_ROOT, SourceRoots.roleOf("/src/main/java/", ROOTS));
        assertEquals(SourceRoots.Role.MODULE, SourceRoots.roleOf(null, ROOTS));
    }
}
