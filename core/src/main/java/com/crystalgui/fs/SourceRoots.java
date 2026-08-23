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
