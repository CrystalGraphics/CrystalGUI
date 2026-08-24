package com.crystalgui.fs;

import javax.annotation.Nullable;

import java.util.List;

/**
 * Where a project's source starts, and what a file's fully-qualified name is because of it.
 *
 * <h3>Why a project needs to say this at all</h3>
 *
 * <p>Until now a Java file's package came from its own {@code package} declaration — {@code SourcePackages}
 * reads the line and believes it. That is the only thing a <em>rootless</em> file can do, and it is what a
 * scratch snippet will always need. It is not what a project should do: the declaration and the directory
 * can disagree, and when they do, the file is in the wrong place and every IDE says so on line 1. A source
 * root is what makes that judgement possible, because it is what makes the path mean something.</p>
 *
 * <p>It is also what makes an index affordable. Mapping every file in a workspace to a qualified name by
 * <em>reading</em> each one is I/O per file; deriving it from the path is free, and the crawl has the paths
 * already.</p>
 *
 * <h3>Declared, with a convention as the default</h3>
 *
 * <p>{@link #CONVENTION} is Maven's and Gradle's layout, which is what almost everything uses and what the
 * milestone asks for. But it is a <b>default rather than a rule</b>: a mod may keep its scripts anywhere,
 * and a layout nobody can opt out of is one that excludes them. The roots live on {@link ProjectInfo}
 * rather than on {@link WorkspaceProject} because the index that consumes them is client-side and a
 * workspace may be remote — {@code excludes} sits on the project precisely because it is the opposite, a
 * server-side rule about what is never listed.</p>
 */
public final class SourceRoots {

    /**
     * The default layout: {@code src/main/java} and {@code src/main/js}.
     *
     * <p>Both, always, rather than one per language — a project is not asked which languages it intends to
     * use, and a root that happens to contain nothing costs nothing.</p>
     */
    public static final List<String> CONVENTION = List.of("src/main/java", "src/main/js");

    private SourceRoots() {
    }

    /**
     * What a directory IS in a project's layout — what IntelliJ draws a different icon for.
     *
     * <p>Four roles and no more, because a single-module project has no others to distinguish. A tree
     * that draws one folder glyph for all of them makes {@code src/main/java} look like an ordinary
     * directory that happens to be nested deeply, which is exactly the thing a reader is scanning for.</p>
     */
    public enum Role {

        /** A directory that directly CONTAINS source roots — {@code src/main}. @see #roleOf */
        MODULE,

        /** A declared source root: where package names start counting from. */
        SOURCE_ROOT,

        /** Inside a source root, so its name is a package segment rather than a directory name. */
        PACKAGE,

        /** Everything else — {@code src}, {@code build}, a resources tree, a project with no roots. */
        FOLDER
    }

    /**
     * Which of the four {@code directory} is, given the project's declared roots.
     *
     * <h3>A module is what CONTAINS source roots</h3>
     *
     * <p>Which is IntelliJ's own answer, arrived at from the layout rather than from a module file. With
     * {@code src/main/java} and {@code src/main/js} declared, the directory holding both is
     * {@code src/main} — and that is exactly the row IntelliJ draws the module icon on for a Gradle
     * project, where the source set IS the module. It falls out of the roots with nothing to configure,
     * and it generalises the way the real thing does: roots under {@code src/test} would make a second
     * module, which is what a test source set is.</p>
     *
     * <p><b>The project root is a module too</b>, whatever it declares. It was briefly not — on the
     * argument that its row already says it is a project, so a second module glyph would be noise — and
     * that argument is wrong about which thing a module icon names. It does not mean "here is a project";
     * it means "here is a compilation unit with source under it", and the project root is one of those
     * even when the source sits three directories down. A plain folder glyph there says the opposite.
     * Two module rows in one tree is the correct picture of a Gradle project, which is what IntelliJ
     * draws for one.</p>
     *
     * <p>{@code relativePath} is project-relative, as every {@link CgPath} in a workspace is: empty is the
     * project root itself. Roles are ranked rather than returned first-match, because roots nest — see
     * the note in the loop.</p>
     */
    public static Role roleOf(@Nullable String relativePath, @Nullable List<String> roots) {
        String normalisedPath = relativePath == null ? null : normalise(relativePath);
        String within = normalisedPath == null ? "" : normalisedPath;
        // THE PROJECT ROOT, BEFORE THE ROOTS ARE CONSULTED. It is a module because of what it is, not
        // because of what it declares -- a project that has not been given source roots yet still has a
        // root, and drawing a plain folder there says it is an ordinary directory somebody opened.
        if (within.isEmpty()) return Role.MODULE;
        if (roots == null || roots.isEmpty()) return Role.FOLDER;

        Role role = Role.FOLDER;
        for (String root : roots) {
            String normalised = normalise(root);
            if (normalised == null) continue;
            // EXACT, so it answers immediately -- nothing outranks being a root.
            if (within.equals(normalised)) return Role.SOURCE_ROOT;
            // RANKED, NOT FIRST-MATCH, because roots nest: with `src` and `src/main/java` both declared,
            // a path under the second is under the first as well, and being INSIDE a root is the more
            // specific fact than merely containing one.
            if (contains(normalised, within)) role = Role.PACKAGE;
            else if (role != Role.PACKAGE && within.equals(parentOf(normalised))) role = Role.MODULE;
        }
        return role;
    }

    /** {@code src/main/java} → {@code src/main}; a root with no parent → {@code ""}, the project root. */
    private static String parentOf(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? "" : path.substring(0, lastSlash);
    }

    /**
     * Where {@code path} sits relative to the first root that contains it, or null when no root does.
     *
     * <h3>Null is an answer, not a failure</h3>
     *
     * <p>A file outside every source root is an ordinary file — a README, a config, a scratch script in a
     * project that declares no roots at all. Guessing a package for it would invent a fact; answering null
     * lets the caller fall back to the declaration, which is exactly right for the rootless case.</p>
     *
     * <h3>The longest root wins</h3>
     *
     * <p>Roots nest in real layouts, and the failure is silent rather than loud: with {@code src} and
     * {@code src/main/java} both declared, taking the first match makes
     * {@code src/main/java/foo/Bar.java} report package {@code main.java.foo}. That resolves, compiles as
     * far as ECJ is concerned, and is wrong everywhere it is shown.</p>
     */
    @Nullable
    public static Located locate(@Nullable CgPath path, @Nullable List<String> roots) {
        if (path == null || roots == null || roots.isEmpty()) return null;
        String within = path.path();
        if (within == null || within.isEmpty()) return null;

        String best = null;
        for (String root : roots) {
            String normalised = normalise(root);
            if (normalised == null || !contains(normalised, within)) continue;
            if (best == null || normalised.length() > best.length()) best = normalised;
        }
        if (best == null) return null;

        // The remainder after the root, which is the package path plus the file name.
        String relative = best.isEmpty() ? within : within.substring(best.length() + 1);
        int lastSlash = relative.lastIndexOf('/');
        String fileName = lastSlash < 0 ? relative : relative.substring(lastSlash + 1);
        String packagePath = lastSlash < 0 ? "" : relative.substring(0, lastSlash);
        return new Located(best, packagePath.replace('/', '.'), stem(fileName));
    }

    /**
     * Whether {@code root} contains {@code path}, at a <b>segment boundary</b>.
     *
     * <p>A plain {@code startsWith} makes {@code src/mainland/Foo.java} a member of root {@code src/main},
     * which then reports package {@code land} — a name that exists nowhere, derived from half a directory.
     */
    private static boolean contains(String root, String path) {
        if (root.isEmpty()) return true;
        return path.length() > root.length()
                && path.startsWith(root)
                && path.charAt(root.length()) == '/';
    }

    /** Trims slashes and rejects nothing-shaped roots. Null for a root that names no directory. */
    @Nullable
    private static String normalise(@Nullable String root) {
        if (root == null) return null;
        String trimmed = root.replace('\\', '/').trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** {@code Bar.java} → {@code Bar}. A name with no dot is its own stem. */
    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * A file placed within a source root.
     *
     * @param root        the root that contains it, normalised — {@code src/main/java}
     * @param packageName dotted, empty for a file directly in the root (the default package)
     * @param simpleName  the file's stem, which for a well-formed Java file is its public type's name
     */
    public record Located(String root, String packageName, String simpleName) {

        public Located {
            if (root == null) throw new IllegalArgumentException("root");
            packageName = packageName == null ? "" : packageName;
            if (simpleName == null || simpleName.isEmpty()) {
                throw new IllegalArgumentException("simpleName");
            }
        }

        /**
         * The binary name this file's top-level type must have.
         *
         * <p>Derived rather than stored, for the reason every other pair of spellings in this codebase is:
         * two fields holding one fact are two fields that can disagree.</p>
         */
        public String qualifiedName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }
    }
}
