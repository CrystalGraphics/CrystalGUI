package com.crystalgui.fs.protocol;

/**
 * <b>The method names, and nothing else.</b>
 *
 * <p>Method names only: what each one carries is a record with a codec in {@link FsMessages}, so a
 * field written on one side is provably the field read on the other.</p>
 *
 * <p>Two shapes. A <b>request</b> is a question with an answer, and a client is refused if it may not
 * ask. A <b>notification</b> is the server telling a client something: one-way, costing no pending
 * entry and no timeout slot, with nothing to answer. {@code fs/changed}, {@code fs/presence} and
 * {@code fs/capabilities} are notifications — sent as requests they would each occupy a slot and a
 * timeout per watched file per change per peer.</p>
 */
public final class FsMethods {

    private FsMethods() {
    }

    // ── The greeting ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Request. First, before anything else.</b> Carries the protocol version and the server's own
     * facts: whether its filesystem folds case, what it will refuse as a file name, and the size
     * thresholds above which a document loses its services or becomes read-only.
     *
     * <p>Those were unknowable, so the client guessed or did without: case sensitivity was advertised
     * as a capability and read by nobody, and New File found out a name was reserved by making the
     * round trip and being refused.</p>
     */
    public static final String HELLO = "fs/hello";

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    /** Request. Every project this actor may see. */
    public static final String PROJECTS = "fs/projects";

    /** Request. One directory's entries, excludes applied. */
    public static final String LIST = "fs/list";

    /** Request. A file's metadata, with the etag a write must quote back. */
    public static final String STAT = "fs/stat";

    /**
     * Request. A file's bytes, or a transfer to pull them through.
     *
     * <p>Conditional: a client that quotes an etag it already holds is told the file is unchanged and
     * sent nothing, which is HTTP's {@code If-None-Match} and is what makes reopening a tab free.</p>
     */
    public static final String READ = "fs/read";

    /** Request. One window of a transfer. @see #READ */
    public static final String READ_CHUNK = "fs/readChunk";

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    /** Request. Replaces a file, refusing if it moved since the quoted etag. */
    public static final String WRITE = "fs/write";

    /*
     * There is no `fs/writeDelta`. It was declared here -- "the same, expressed as a change set" --
     * and served by nobody, sent by nobody, and named in no plan step that had been done. A verb in
     * this file is a promise to whoever reads it, and one with neither a producer nor a consumer is
     * the same silence this vocabulary exists to remove. It belongs with plan_phase6.md's histogram
     * differ, which is what would produce the splices; until something can, the honest surface is the
     * one that does not mention it.
     */

    /** Request. Creates a file that must not already exist. */
    public static final String CREATE = "fs/create";

    /** Request. Creates a directory. */
    public static final String MKDIR = "fs/mkdir";

    /** Request. Moves a file to the trash, answering the id that can restore it. */
    public static final String DELETE = "fs/delete";

    /** Request. Moves or renames. */
    public static final String RENAME = "fs/rename";

    /** Request. Copies. */
    public static final String COPY = "fs/copy";

    // ── The trash ───────────────────────────────────────────────────────────────────────────────

    /** Request. What is in the trash. */
    public static final String TRASH_LIST = "fs/trashList";

    /** Request. Puts one back. */
    public static final String RESTORE = "fs/restore";

    /** Request. Destroys one for good. */
    public static final String PURGE = "fs/purge";

    // ── Watching ────────────────────────────────────────────────────────────────────────────────

    /**
     * Request. Subscribes to a path — a file, or a directory with or without its descendants.
     *
     * <p>Directories are new at F3. A client could only watch files it had read, so another client's
     * create, rename or delete in a folder you had expanded never reached you.</p>
     */
    public static final String WATCH = "fs/watch";

    /** Request. Drops a subscription. */
    public static final String UNWATCH = "fs/unwatch";

    /**
     * Request. Says this client has, or no longer has, unsaved changes to a path.
     *
     * <p><b>Dirtiness is the client's to report and nobody else's</b> — it is
     * {@code version != savedVersion} on a document only this client holds, so the server cannot
     * observe it and cannot infer it: a file with no writes coming is equally one nobody has touched
     * and one somebody has been typing in for ten minutes. Which is the whole question presence exists
     * to answer, and why "who has it open" alone is the weaker signal.</p>
     *
     * <p>{@code op} is {@code "dirty"} or empty, the same shape {@link #WATCH} uses for
     * {@code "recursive"}. A request rather than a notification because it is rare — once per
     * clean-to-dirty transition, never per keystroke — and a refusal is worth hearing.</p>
     */
    public static final String EDITING = "fs/editing";

    // ── What the server says without being asked ────────────────────────────────────────────────

    /** <b>Notification.</b> Files changed. Coalesced per tick, and carries {@code renamed} as one event. */
    public static final String CHANGED = "fs/changed";

    /** <b>Notification.</b> Who else has this open, and who is editing it. */
    public static final String PRESENCE = "fs/presence";

    /** <b>Notification.</b> What this actor may now do, when that has changed. */
    public static final String CAPABILITIES = "fs/capabilities";
}
