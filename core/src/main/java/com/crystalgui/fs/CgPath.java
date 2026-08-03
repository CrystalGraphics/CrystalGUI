package com.crystalgui.fs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A location inside a project: {@code project:path/within/it}.
 *
 * <p>Modelled on VS Code's {@code URI} ({@code src/vs/base/common/uri.ts}, MIT) — a scheme naming
 * <em>which</em> filesystem, and a path inside it — reduced to the two parts this engine needs. The
 * project id plays the scheme's role, which is why it is spelled with a colon and reads like every other
 * namespaced identifier here ({@code CgIO} domains, {@code StyleSheetRegistry}, {@code ElementRegistry}).
 *
 * <h3>A path is structurally confined to its project</h3>
 * <p><b>{@code ..} is resolved at construction, and a path that would escape its project is refused
 * outright.</b> That is the single most valuable property this type has: every {@code CgPath} that exists
 * is already inside its root, so no caller can forget to check, and the resolver above it is left with
 * exactly one job — making sure a <em>symlink</em> does not escape, which is the part lexical analysis
 * genuinely cannot answer.</p>
 *
 * <p>Doing it the other way round — carrying arbitrary text and validating at the point of use — means the
 * check lives at every call site and is one {@code if} away from a directory traversal. This is a network
 * protocol reachable by any client; the type is the place to put that.</p>
 *
 * <h3>Serializable, and therefore frozen</h3>
 * <p>These are written into saved documents, so {@link #toString()} and {@link #parse(String)} must round
 * trip exactly and forever. That is why the project id is explicit rather than derived from a directory:
 * moving a project's folder must not invalidate every reference to it.</p>
 *
 * <h3>Case</h3>
 * <p><b>Comparison is case-SENSITIVE here.</b> Whether a filesystem folds case is a property of that
 * filesystem — VS Code models it as {@code PathCaseSensitive}, a per-provider capability — so a value type
 * cannot know. A provider that folds case does so when it resolves; two {@code CgPath}s differing only in
 * case are two distinct values, and it is the provider that may map them to one file.</p>
 */
public final class CgPath {

    /** Separates the project id from the path within it. */
    public static final char PROJECT_SEPARATOR = ':';

    private final String project;
    private final List<String> segments;

    private CgPath(String project, List<String> segments) {
        this.project = project;
        this.segments = Collections.unmodifiableList(segments);
    }

    /** The root of a project — {@code project:} with no path inside it. */
    public static CgPath ofProject(String project) {
        return new CgPath(requireProject(project), new ArrayList<>());
    }

    /**
     * A path inside {@code project}.
     *
     * @throws CgFileSystemException with {@link CgFileError#INVALID_PATH} when the path is malformed or
     *                               would escape the project root
     */
    public static CgPath of(String project, String path) {
        return new CgPath(requireProject(project), normalise(project, path));
    }

    /**
     * Parses {@code project:path/within/it}.
     *
     * <p>The inverse of {@link #toString()}, exactly — a round trip is what saved documents depend on.</p>
     */
    public static CgPath parse(String text) {
        if (text == null || text.isEmpty()) {
            throw invalid("<empty>", "a path must name a project");
        }
        int colon = text.indexOf(PROJECT_SEPARATOR);
        if (colon < 0) {
            throw invalid(text, "missing '" + PROJECT_SEPARATOR + "' — a path must name a project");
        }
        return of(text.substring(0, colon), text.substring(colon + 1));
    }

    public String project() {
        return project;
    }

    /** The path within the project, {@code /}-separated and never leading or trailing. Empty at the root. */
    public String path() {
        return String.join("/", segments);
    }

    /** The individual path components, already normalised. Empty at the project root. */
    public List<String> segments() {
        return segments;
    }

    /** The last component, or {@code ""} at the project root. */
    public String name() {
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    /** The file extension without its dot, lowercased, or {@code ""} when there is none. */
    public String extension() {
        String name = name();
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isProjectRoot() {
        return segments.isEmpty();
    }

    /** The containing directory, or {@code null} at the project root. */
    public CgPath parent() {
        if (segments.isEmpty()) return null;
        return new CgPath(project, new ArrayList<>(segments.subList(0, segments.size() - 1)));
    }

    /**
     * A child of this path.
     *
     * <p>Goes through the same normalisation as everything else, so {@code resolve("../x")} is refused at
     * the root rather than quietly stepping outside it.</p>
     */
    public CgPath resolve(String relative) {
        if (relative == null || relative.isEmpty()) return this;
        // Concatenated and normalised ONCE, rather than normalising the relative part on its own and
        // appending. The first version did the latter and `resolve("..")` silently did nothing: the `..`
        // popped from a list that was still empty, because the segments it needed to pop were in the
        // other one. Building the text and running it through the single normaliser cannot drift.
        String combined = segments.isEmpty() ? relative : path() + "/" + relative;
        return new CgPath(project, normalise(project, combined));
    }

    /** Whether {@code other} is this path or lies beneath it. Used to fan a directory event out to files. */
    public boolean contains(CgPath other) {
        if (other == null || !project.equals(other.project)) return false;
        if (other.segments.size() < segments.size()) return false;
        return other.segments.subList(0, segments.size()).equals(segments);
    }

    @Override
    public String toString() {
        return project + PROJECT_SEPARATOR + path();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgPath other)) return false;
        return project.equals(other.project) && segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project, segments);
    }

    // ── Normalisation ───────────────────────────────────────────────────────────────────────────

    private static String requireProject(String project) {
        if (project == null || project.isEmpty()) {
            throw invalid(String.valueOf(project), "a project id may not be empty");
        }
        if (project.indexOf(PROJECT_SEPARATOR) >= 0 || project.indexOf('/') >= 0
                || project.indexOf('\\') >= 0) {
            throw invalid(project, "a project id may not contain '" + PROJECT_SEPARATOR + "', '/' or '\\'");
        }
        return project;
    }

    /**
     * Splits, cleans and lexically resolves a path.
     *
     * <p>The rules are LDLib2's {@code FilePath.normalizePath} — backslashes to {@code /}, collapse
     * repeats, strip trailing — plus the two it does not do: {@code .} is dropped and {@code ..} pops.
     *
     */
    private static List<String> normalise(String project, String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isEmpty()) return out;

        for (String raw : path.replace('\\', '/').split("/")) {
            if (raw.isEmpty() || raw.equals(".")) continue;      // collapses repeats and leading/trailing
            if (raw.equals("..")) {
                if (out.isEmpty()) {
                    // The whole reason this type exists. A path that climbs out of its project is not a
                    // path we clamp to the root -- silently clamping turns an attack into a wrong answer
                    // that looks fine.
                    throw invalid(project + PROJECT_SEPARATOR + path,
                            "path escapes its project root");
                }
                out.remove(out.size() - 1);
                continue;
            }
            if (raw.indexOf('\0') >= 0) {
                throw invalid(project + PROJECT_SEPARATOR + path, "path contains a NUL character");
            }
            out.add(raw);
        }
        return out;
    }

    private static CgFileSystemException invalid(String path, String why) {
        return new CgFileSystemException(CgFileError.INVALID_PATH, "invalid path '" + path + "': " + why);
    }
}
