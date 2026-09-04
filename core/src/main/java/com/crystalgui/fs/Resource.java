package com.crystalgui.fs;

import com.crystalgui.fs.CgPath;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Anything a tab can be opened on — a file, or something that merely behaves like one.
 *
 * <h3>Why this exists beside {@link CgPath}</h3>
 *
 * <p>A workbench can open things that are not on disk: a generated shader, a diff, a decompiled class, an
 * untitled buffer. Both references model this the same way and neither of them models it as "a path with
 * a flag" — VS Code has {@code URI} with a {@code scheme} and an {@code IFileSystemProvider} per scheme;
 * IntelliJ has {@code VirtualFile} over a {@code VirtualFileSystem}, with {@code LightVirtualFile} for
 * content that lives only in memory.</p>
 *
 * <p>Without it, a derived document has to be faked by the <em>application</em>: {@code CrystalEditor}
 * kept a map of graph paths so it could answer "which graph is this generated source for", which is a
 * per-application reimplementation of what a scheme says in one word.</p>
 *
 * <h3>The project scheme keeps {@code CgPath}'s exact text form</h3>
 *
 * <p>Not negotiable. {@code CgPath}'s own javadoc states that it is <b>written into saved documents, so
 * {@code toString()}/{@code parse()} must round trip exactly and forever</b>. So:</p>
 *
 * <ul>
 *   <li>a project resource is spelled {@code mymod.proj:src/Main.java} — byte for byte what
 *       {@code CgPath} already writes;</li>
 *   <li>every other scheme is spelled {@code scheme://path}.</li>
 * </ul>
 *
 * <p>Those two cannot collide. A project id may not contain {@code /} (it is the path separator), so
 * {@code ://} can never appear in the existing form — which is why {@link #parse} tests for {@code ://}
 * first and treats everything else as a {@code CgPath}. Every saved document and session keeps parsing.</p>
 *
 * <h3>Derived resources carry their origin</h3>
 *
 * <p>A generated shader is <em>of</em> a graph, and that relationship is the thing an application would
 * otherwise keep a map for. It is spelled inside the path — {@code shader-generated://mymod.proj:fire.shadergraph}
 * — so it survives {@link #parse} and therefore a saved session, with nothing to keep in step.</p>
 *
 * <p>Immutable, value-equal, and usable as a map key: that is what lets the document store be keyed by
 * resource rather than by path without anything else changing.</p>
 */
public final class Resource {

    /** Files in a workspace project. The only scheme whose text form is not {@code scheme://path}. */
    public static final String SCHEME_PROJECT = "project";

    /**
     * A type the workspace does not contain — a JDK class, a library class, a class from another mod.
     *
     * <p>The path is the <b>top-level binary name</b> and nothing else: {@code library://java.util.Map}
     * is where {@code Map.Entry} is declared too, because a source archive is keyed by compilation unit
     * and a nested type has no file of its own. Deliberately carrying no classpath, no jar and no
     * provenance — what serves the text is a question for whoever registered the scheme, asked afresh
     * each time, so a resource stays a name rather than a snapshot of where the name resolved once.</p>
     */
    public static final String SCHEME_LIBRARY = "library";

    private static final String MARKER = "://";

    private final String scheme;
    private final String path;

    /** Non-null only for the project scheme, so the {@code CgPath} is parsed once rather than per ask. */
    @Nullable
    private final CgPath projectPath;

    /** Non-null when this was derived from another resource — see the class note. */
    @Nullable
    private final Resource origin;

    private Resource(String scheme, String path, @Nullable CgPath projectPath, @Nullable Resource origin) {
        this.scheme = scheme;
        this.path = path;
        this.projectPath = projectPath;
        this.origin = origin;
    }

    /** A file in a project. */
    public static Resource of(CgPath path) {
        Objects.requireNonNull(path, "path");
        return new Resource(SCHEME_PROJECT, path.toString(), path, null);
    }

    /** A resource in some other scheme — {@code untitled}, {@code output}, a provider's own. */
    public static Resource of(String scheme, String path) {
        return new Resource(requireScheme(scheme), Objects.requireNonNull(path, "path"), null, null);
    }

    /**
     * Something generated <b>from</b> {@code origin} — a compiled shader, a diff, a decompiled view.
     *
     * <p>One derived resource per origin per scheme, which is what makes five open graphs produce five
     * distinct generated-source tabs rather than one shared panel showing whichever is in front.</p>
     */
    public static Resource derived(String scheme, Resource origin) {
        Objects.requireNonNull(origin, "origin");
        return new Resource(requireScheme(scheme), origin.toString(), null, origin);
    }

    /**
     * The inverse of {@link #toString()}, for every scheme.
     *
     * <p>{@code ://} decides: with it, a scheme and a path; without it, a {@code CgPath} and therefore
     * the project scheme. A colon inside the <em>path</em> segment of a project path is unaffected,
     * because only the marker is looked for.</p>
     */
    public static Resource parse(String text) {
        Objects.requireNonNull(text, "text");
        int marker = text.indexOf(MARKER);
        if (marker < 0) return of(CgPath.parse(text));

        String scheme = text.substring(0, marker);
        String rest = text.substring(marker + MARKER.length());
        // A nested resource is an ORIGIN, and it is recognised by being parseable rather than by a second
        // marker: a project origin has no marker at all, which is the common case and the one that would
        // be missed by looking for one.
        return new Resource(requireScheme(scheme), rest, null, parseOriginOrNull(rest));
    }

    @Nullable
    private static Resource parseOriginOrNull(String rest) {
        if (rest.isEmpty()) return null;
        try {
            return parse(rest);
        } catch (RuntimeException notAResource) {
            // An ordinary path in some scheme of its own -- "output://build.log" is not derived from
            // anything. Absence is the answer, not a failure.
            return null;
        }
    }

    private static String requireScheme(String scheme) {
        Objects.requireNonNull(scheme, "scheme");
        if (scheme.isEmpty()) throw new IllegalArgumentException("a scheme may not be empty");
        if (scheme.contains("/") || scheme.contains(":")) {
            throw new IllegalArgumentException("a scheme may not contain '/' or ':': " + scheme);
        }
        return scheme;
    }

    public String scheme() {
        return scheme;
    }

    public String path() {
        return path;
    }

    public boolean isProject() {
        return projectPath != null;
    }

    /** The underlying path, or null when this is not a project resource. */
    @Nullable
    public CgPath asPath() {
        return projectPath;
    }

    /** What this was generated from, or null when it was not generated from anything. */
    @Nullable
    public Resource origin() {
        return origin;
    }

    /**
     * The last segment — what a tab is labelled with.
     *
     * <p>A derived resource answers with its <b>origin's</b> name, because that is what the thing is
     * about: the generated source for {@code fire.shadergraph} is named after the graph, not after the
     * scheme. Decorating it further ("Compiled: fire") is a label concern and belongs to whoever builds
     * the tab, not here.</p>
     */
    public String name() {
        if (projectPath != null) return projectPath.name();
        if (origin != null) return origin.name();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The extension of {@link #name()}, without the dot, or empty. */
    public String extension() {
        if (projectPath != null) return projectPath.extension();
        String name = name();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** Round-trips through {@link #parse}, and is what a session stores. */
    @Override
    public String toString() {
        return projectPath != null ? projectPath.toString() : scheme + MARKER + path;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Resource that)) return false;
        return scheme.equals(that.scheme) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return 31 * scheme.hashCode() + path.hashCode();
    }
}
