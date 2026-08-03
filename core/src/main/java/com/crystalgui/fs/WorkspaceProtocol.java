package com.crystalgui.fs;

/**
 * The method names and field keys the workspace speaks, in one place.
 *
 * <p>Both ends read these constants rather than spelling the strings twice — a protocol whose two halves
 * each write {@code "etag"} by hand is one typo away from a silent mismatch that presents as a conflict
 * loop.</p>
 *
 * <p>Modelled on the method set of VS Code's {@code diskFileSystemProviderClient} / {@code Server}
 * ({@code src/vs/platform/files/}, MIT), reduced to what the MVP ships.</p>
 */
public final class WorkspaceProtocol {

    private WorkspaceProtocol() {
    }

    // ── Methods (client → server) ───────────────────────────────────────────────────────────────

    /** Projects this player may see. No arguments. */
    public static final String PROJECTS = "fs.projects";

    /**
     * One directory's entries, with etags.
     *
     * <p>There is deliberately no separate {@code fs.list} or {@code fs.stat}: a manifest <em>is</em> a
     * listing that carries the etags, and a second method would be the same query answered twice by two
     * code paths that could drift.</p>
     */
    public static final String MANIFEST = "fs.manifest";

    /** A whole file, with the etag it was read at. */
    public static final String READ = "fs.read";

    /** Replace a file, quoting the etag it was read at. */
    public static final String WRITE = "fs.write";

    /** Create a file that is not there. Fails rather than overwriting. */
    public static final String CREATE = "fs.create";

    /** Create one directory. */
    public static final String MKDIR = "fs.mkdir";

    /**
     * Start watching a path, so the server will report changes to it.
     *
     * <p>The server can only poll what it knows somebody is looking at. Watching everything in a project
     * would cost a stat per file per tick for files nobody has open; watching what a client actually holds
     * is bounded by the number of open editors.</p>
     */
    public static final String WATCH = "fs.watch";

    /** Stop watching. Paired with closing a document. */
    public static final String UNWATCH = "fs.unwatch";

    // ── Methods (server → client) ───────────────────────────────────────────────────────────────

    /**
     * A watched path moved.
     *
     * <p><b>Promptness only.</b> Correctness never depended on this: a stale write is refused by the
     * re-stat in {@code WorkspaceService.write} whatever the platform's watching story is. This exists so
     * a client finds out <em>before</em> it tries to save, rather than at the moment it does.</p>
     */
    public static final String CHANGED = "fs.changed";

    // ── Fields ──────────────────────────────────────────────────────────────────────────────────

    public static final String PATH = "path";
    public static final String CONTENT = "content";
    public static final String ETAG = "etag";
    public static final String ENTRIES = "entries";
    public static final String PROJECT_LIST = "projects";
    public static final String NAME = "name";
    public static final String ID = "id";
    public static final String DISPLAY_NAME = "displayName";
    public static final String DIRECTORY = "directory";
    public static final String SIZE = "size";
    public static final String MTIME = "mtime";

    /** On {@link #CHANGED}: which of {@link #KIND_MODIFIED} / {@link #KIND_DELETED} happened. */
    public static final String KIND = "kind";

    /** The file's content moved. {@link #ETAG} carries the new one. */
    public static final String KIND_MODIFIED = "modified";

    /** The file is gone. There is no new etag. */
    public static final String KIND_DELETED = "deleted";

    /** On a failure: the {@link CgFileError} name, so a client can branch without parsing prose. */
    public static final String ERROR = "error";

    /** On a conflict: the etag the file actually has, so a reload needs no second round trip. */
    public static final String ACTUAL_ETAG = "actualEtag";

    /** The error name a {@link WorkspaceConflictException} reports. Not a {@link CgFileError} — see it. */
    public static final String ERROR_CONFLICT = "CONFLICT";
}
