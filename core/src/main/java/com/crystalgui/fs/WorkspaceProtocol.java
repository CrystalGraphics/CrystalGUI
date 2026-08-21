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

    /**
     * Pulls one slice of a transfer {@link #READ} opened. <b>Client-driven</b>, deliberately.
     *
     * <p>A push would work — the transport has credit flow control, so the server could stream and the
     * receiver would exert backpressure. Pull was chosen for two reasons that outlast this method: the
     * client decides the pace, so a UI can pause or abandon a download without a cancel protocol, and a
     * resume is the same request with a different {@link #OFFSET}, which is what D11's *"resume"* row
     * becomes rather than a new mechanism. HTTP range requests are the same shape for the same reason.</p>
     */
    public static final String READ_CHUNK = "fs.readChunk";

    /**
     * Writes a text file as a {@code ChangeSet} against a known base revision — P6.1.10 <b>D10</b>.
     *
     * <p>D10's rule is that <i>writing branches on what the CLIENT is holding, not on what the file
     * is</i>: a text document with a matching base revision sends a change set, anything else sends the
     * whole file. That is knowable locally and correct for the awkward cases by construction, since a
     * binary file cannot produce a change set and takes the whole-file path without anyone remembering a
     * rule.</p>
     *
     * <p>The win is the ordinary case rather than an extreme one: typing a character into a 2 MB file
     * sends the character instead of the file. The etag is still quoted and still re-stat'd, so the
     * conflict story is exactly {@link #WRITE}'s — a delta against a file that moved is refused, not
     * merged.</p>
     */
    public static final String WRITE_DELTA = "fs.writeDelta";

    /** Replace a file, quoting the etag it was read at. */
    public static final String WRITE = "fs.write";

    /** Create a file that is not there. Fails rather than overwriting. */
    public static final String CREATE = "fs.create";

    /** Create one directory. */
    public static final String MKDIR = "fs.mkdir";

    /**
     * Remove a file or directory, quoting the etag it was last seen at.
     *
     * <p><b>The etag is not optional ceremony.</b> Deleting a file that changed underneath you is the same
     * class of mistake as overwriting one, and it is the worse of the two — an overwrite loses the other
     * edit, a delete loses the file. The guard is the same re-stat {@link #WRITE} uses and reports the
     * same {@link #ERROR_CONFLICT}.</p>
     *
     * <p>Whether the bytes are actually destroyed or kept somewhere recoverable is the <em>server's</em>
     * business and deliberately absent from this method. A trash store is a policy the service applies;
     * the client asks for a deletion either way, and does not get to choose.</p>
     */
    public static final String DELETE = "fs.delete";

    /**
     * Move a file or directory, quoting the etag the source was last seen at.
     *
     * <p>Rename and move are one method because within a workspace they are one operation — the same
     * reason POSIX has {@code rename(2)} and not a separate {@code move}. Cross-project moves are refused
     * by the service.</p>
     */
    public static final String RENAME = "fs.rename";

    /**
     * Put a deleted file back.
     *
     * <p>Takes an opaque {@link #TRASH_ID}, never a path into the trash — where deleted bytes live is the
     * server's business, and a client that knew the location could write into it, list it as an ordinary
     * directory, and race the sweep.</p>
     */
    public static final String RESTORE = "fs.restore";

    /** Destroy a trashed entry for good — what Shift+Delete does, behind a harder confirmation. */
    public static final String PURGE = "fs.purge";

    /** What is recoverable in a project, newest first. */
    public static final String TRASH_LIST = "fs.trashList";

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

    /**
     * What this actor may do, per project — <b>both directions</b>.
     *
     * <p>A REQUEST the client makes once, and a NOTIFICATION the server pushes when the answer changes
     * (an operator promoted, a permission node revoked). {@code MessageRouter} keys request handlers and
     * notification handlers separately, so one name serves both without ambiguity, and it should: they
     * are the same question, asked and volunteered.</p>
     *
     * <p><b>Per project, not per path</b>, and that is a real coarsening — {@link WorkspacePermission}
     * takes a path, so a host may legitimately allow writes under {@code src/} and refuse them under
     * {@code config/}, which no broadcast can express. This is therefore a <em>hint for enablement</em>
     * and never the authority: every operation is still authorised server-side on its own path. The
     * direction of the error is what makes the coarsening safe — see {@code WorkspaceClient.mayWrite}.</p>
     */
    public static final String CAPABILITIES = "fs.capabilities";

    /** The list {@link #CAPABILITIES} carries, of {@code {project, read, write}}. */
    public static final String PROJECT_CAPABILITIES = "caps";

    public static final String PROJECT = "project";
    public static final String MAY_READ = "read";
    public static final String MAY_WRITE = "write";

    public static final String PATH = "path";
    public static final String CONTENT = "content";
    public static final String ETAG = "etag";

    /**
     * On a {@link #READ} reply: true when the content did not come with it and must be pulled.
     *
     * <p>Absent means inline, so an old client reading a small file sees exactly what it always did.
     * The threshold is the server's to choose and the client must not assume one — it reads the flag.</p>
     */
    public static final String CHUNKED = "chunked";

    /** Identifies an open transfer, for {@link #READ_CHUNK}. */
    public static final String TRANSFER = "transfer";

    // Total bytes in the file, so a client can size a progress bar before pulling anything -- the same
    // SIZE a manifest entry already uses, deliberately not a second name for one number.

    /** Where a chunk starts. */
    public static final String OFFSET = "offset";

    /** How many bytes a chunk asks for; the server may answer with fewer. */
    public static final String LENGTH = "length";

    /** True on the chunk that completes a transfer, after which the id is no longer valid. */
    public static final String EOF = "eof";

    /**
     * A {@link #WRITE_DELTA} payload: the changes, each carrying {@link #FROM}, {@link #TO} and
     * {@link #INSERT}.
     *
     * <p>{@code FROM} and {@code TO} are the pair {@code fs.rename} already uses — reused rather than
     * duplicated under a second spelling, because they mean the same thing here (a span) and two names
     * for one concept is how a protocol starts disagreeing with itself.</p>
     */
    public static final String CHANGES = "changes";

    public static final String INSERT = "insert";

    /**
     * On a {@link #READ}: the etag the client already holds, and on the reply, that nothing changed.
     *
     * <p>HTTP's conditional GET, for the same reason — the common re-read is of a file that has not
     * moved, and answering "still the one you have" costs a few bytes instead of the file. Absent means
     * "send it regardless", so a client with no cache behaves exactly as before.</p>
     */
    public static final String IF_NONE_MATCH = "ifNoneMatch";

    public static final String UNCHANGED = "unchanged";
    public static final String ENTRIES = "entries";
    public static final String PROJECT_LIST = "projects";
    public static final String NAME = "name";
    public static final String ID = "id";
    public static final String DISPLAY_NAME = "displayName";
    public static final String DIRECTORY = "directory";
    public static final String SIZE = "size";
    public static final String MTIME = "mtime";

    /** On {@link #RENAME}: where it is moving from and to. {@link #PATH} is unused there. */
    public static final String FROM = "from";
    public static final String TO = "to";

    /** Identifies one trashed deletion. Opaque by design — see {@link #RESTORE}. */
    public static final String TRASH_ID = "trashId";

    /** On {@link #DELETE}'s response: where the copy went, so an undo can put it back. Absent when the
     * server keeps nothing. */
    public static final String DELETED_AT = "deletedAt";
    public static final String ACTOR = "actor";

    /** On {@link #DELETE}: whether a non-empty directory may go. Absent means false. */
    public static final String RECURSIVE = "recursive";

    /** On {@link #RENAME}: whether an existing destination may be replaced. Absent means false. */
    public static final String OVERWRITE = "overwrite";

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
