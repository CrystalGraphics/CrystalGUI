package com.crystalgui.fs.protocol;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

import java.util.List;

/**
 * Every filesystem payload, as a record with a codec.
 *
 * <p>One codec serves both ends, so a field written on one side is provably the field read on the
 * other. Packing and unpacking by hand at each end cannot give that: a value crosses the wire
 * correctly, is dropped on arrival, and every observable on both sides looks right.</p>
 *
 * <p>Records, so the fields are the type. One {@code CgCodecs.record} per record, so the encoding is stated once
 * and both halves use the same one. A required field that is missing throws naming itself.</p>
 *
 * <h3>An unknown field is ignored, and that is a version policy</h3>
 *
 * <p>A record codec reads by name, so a newer server sending a field this client has never
 * heard of costs nothing. That is what lets {@link FsHello}'s version be advisory rather than a gate —
 * a client refuses only a MAJOR it does not know, and tolerates everything additive.</p>
 */
public final class FsMessages {

    private FsMessages() {
    }

    // ── Shared field names ──────────────────────────────────────────────────────────────────────
    // Package-private and used by the codecs below ONLY. They are not a vocabulary a handler reads:
    // a bare constant is the shape this file exists to remove: nothing outside it
    // spells a wire key.

    private static final String PATH = "path";
    private static final String OP = "op";
    private static final String ETAG = "etag";
    private static final String CONTENT = "content";
    private static final String NAME = "name";
    private static final String ID = "id";

    private static final CgCodec<ScriptingMode> SCRIPTING =
            CgCodecs.xmap(CgCodecs.STRING, ScriptingMode::parse, ScriptingMode::name);

    // ── Requests ────────────────────────────────────────────────────────────────────────────────

    /**
     * Asks about one path.
     *
     * @param op a client-generated operation id, or empty for a read. <b>The idempotency key</b>: a
     *           mutation retried after a timeout is answered from the server's recent-operations table
     *           rather than refused as a conflict against the caller's own earlier write
     */
    public record PathRequest(String path, String op) {
        public PathRequest(String path) {
            this(path, "");
        }
    }

    public static final CgCodec<PathRequest> PATH_REQUEST = CgCodecs.record(
            CgCodecs.field(PATH, CgCodecs.STRING, PathRequest::path),
            CgCodecs.optionalField(OP, CgCodecs.STRING, PathRequest::op, ""),
            PathRequest::new);

    /** Asks about two, which is every move. */
    public record MoveRequest(String from, String to, boolean overwrite, String op) {
    }

    public static final CgCodec<MoveRequest> MOVE_REQUEST = CgCodecs.record(
            CgCodecs.field("from", CgCodecs.STRING, MoveRequest::from),
            CgCodecs.field("to", CgCodecs.STRING, MoveRequest::to),
            CgCodecs.optionalField("overwrite", CgCodecs.BOOL, MoveRequest::overwrite, false),
            CgCodecs.optionalField(OP, CgCodecs.STRING, MoveRequest::op, ""),
            MoveRequest::new);

    /**
     * A read, optionally conditional.
     *
     * @param ifNoneMatch an etag the caller already holds. The server answers "unchanged" and sends no
     *                    bytes when it matches — HTTP's own header, and what makes reopening a tab free
     */
    public record ReadRequest(String path, String ifNoneMatch) {
    }

    public static final CgCodec<ReadRequest> READ_REQUEST = CgCodecs.record(
            CgCodecs.field(PATH, CgCodecs.STRING, ReadRequest::path),
            CgCodecs.optionalField("ifNoneMatch", CgCodecs.STRING, ReadRequest::ifNoneMatch, ""),
            ReadRequest::new);

    /**
     * A file's bytes, or the transfer to pull them through.
     *
     * @param unchanged the conditional read matched; {@code content} is empty and means nothing
     * @param transfer  non-empty when the file is too big for one message. The bytes are then pulled
     *                  with {@link FsMethods#READ_CHUNK}
     */
    public record ReadResponse(String etag, byte[] content, boolean unchanged,
                               String transfer, long size) {

        /** What a matched conditional read carries: not sent, and read back as no bytes. */
        static final byte[] NO_CONTENT = new byte[0];

        public ReadResponse {
            if (content == null) content = NO_CONTENT;
        }
    }

    public static final CgCodec<ReadResponse> READ_RESPONSE = CgCodecs.record(
            CgCodecs.optionalField(ETAG, CgCodecs.STRING, ReadResponse::etag, ""),
            CgCodecs.optionalField(CONTENT, CgCodecs.BYTES, ReadResponse::content, ReadResponse.NO_CONTENT),
            CgCodecs.optionalField("unchanged", CgCodecs.BOOL, ReadResponse::unchanged, false),
            CgCodecs.optionalField("transfer", CgCodecs.STRING, ReadResponse::transfer, ""),
            CgCodecs.optionalField("size", CgCodecs.LONG, ReadResponse::size, 0L),
            ReadResponse::new);

    /** One window of a transfer. */
    public record ChunkRequest(String transfer, long offset, int length) {
    }

    public static final CgCodec<ChunkRequest> CHUNK_REQUEST = CgCodecs.record(
            CgCodecs.field("transfer", CgCodecs.STRING, ChunkRequest::transfer),
            CgCodecs.field("offset", CgCodecs.LONG, ChunkRequest::offset),
            CgCodecs.field("length", CgCodecs.INT, ChunkRequest::length),
            ChunkRequest::new);

    /** @param eof whether this window reached the end — how a reader knows to stop asking */
    public record ChunkResponse(byte[] content, boolean eof) {
    }

    public static final CgCodec<ChunkResponse> CHUNK_RESPONSE = CgCodecs.record(
            CgCodecs.field(CONTENT, CgCodecs.BYTES, ChunkResponse::content),
            CgCodecs.optionalField("eof", CgCodecs.BOOL, ChunkResponse::eof, false),
            ChunkResponse::new);

    /** A write, conditional on the etag unless it is empty. */
    public record WriteRequest(String path, byte[] content, String etag, boolean create,
                               boolean overwrite, String op) {
    }

    public static final CgCodec<WriteRequest> WRITE_REQUEST = CgCodecs.record(
            CgCodecs.field(PATH, CgCodecs.STRING, WriteRequest::path),
            CgCodecs.field(CONTENT, CgCodecs.BYTES, WriteRequest::content),
            CgCodecs.optionalField(ETAG, CgCodecs.STRING, WriteRequest::etag, ""),
            CgCodecs.optionalField("create", CgCodecs.BOOL, WriteRequest::create, false),
            CgCodecs.optionalField("overwrite", CgCodecs.BOOL, WriteRequest::overwrite, true),
            CgCodecs.optionalField(OP, CgCodecs.STRING, WriteRequest::op, ""),
            WriteRequest::new);

    /** What every mutation answers: the etag the file now holds. */
    public record EtagResponse(String etag) {
    }

    public static final CgCodec<EtagResponse> ETAG_RESPONSE = CgCodecs.record(
            CgCodecs.optionalField(ETAG, CgCodecs.STRING, EtagResponse::etag, ""),
            EtagResponse::new);

    // ── Directory entries ───────────────────────────────────────────────────────────────────────

    public record Entry(String name, boolean directory, long size, long mtime) {
    }

    public static final CgCodec<Entry> ENTRY = CgCodecs.record(
            CgCodecs.field(NAME, CgCodecs.STRING, Entry::name),
            CgCodecs.optionalField("dir", CgCodecs.BOOL, Entry::directory, false),
            CgCodecs.optionalField("size", CgCodecs.LONG, Entry::size, 0L),
            CgCodecs.optionalField("mtime", CgCodecs.LONG, Entry::mtime, 0L),
            Entry::new);

    /**
     * @param cursor a continuation for the next page, or empty when this is the last.
     *               A listing of a large directory arrives in pages rather than as one message that
     *               may not fit — which is what the transport's reassembly cap is there to refuse
     */
    public record ListResponse(List<Entry> entries, String cursor) {
        public ListResponse(List<Entry> entries) {
            this(entries, "");
        }
    }

    public static final CgCodec<ListResponse> LIST_RESPONSE = CgCodecs.record(
            CgCodecs.optionalField("entries", CgCodecs.listOf(ENTRY), ListResponse::entries, List.of()),
            CgCodecs.optionalField("cursor", CgCodecs.STRING, ListResponse::cursor, ""),
            ListResponse::new);

    /** A directory listing, paged. */
    public record ListRequest(String path, String cursor) {
        public ListRequest(String path) {
            this(path, "");
        }
    }

    public static final CgCodec<ListRequest> LIST_REQUEST = CgCodecs.record(
            CgCodecs.field(PATH, CgCodecs.STRING, ListRequest::path),
            CgCodecs.optionalField("cursor", CgCodecs.STRING, ListRequest::cursor, ""),
            ListRequest::new);

    public record StatResponse(String etag, boolean directory, long size, long mtime,
                               boolean binary) {
    }

    public static final CgCodec<StatResponse> STAT_RESPONSE = CgCodecs.record(
            CgCodecs.optionalField(ETAG, CgCodecs.STRING, StatResponse::etag, ""),
            CgCodecs.optionalField("dir", CgCodecs.BOOL, StatResponse::directory, false),
            CgCodecs.optionalField("size", CgCodecs.LONG, StatResponse::size, 0L),
            CgCodecs.optionalField("mtime", CgCodecs.LONG, StatResponse::mtime, 0L),
            CgCodecs.optionalField("binary", CgCodecs.BOOL, StatResponse::binary, false),
            StatResponse::new);

    // ── Projects ────────────────────────────────────────────────────────────────────────────────

    // ── The trash ───────────────────────────────────────────────────────────────────────────────

    /**
     * One recoverable deletion.
     *
     * @param path      where it came from, and where a restore puts it back
     * @param actor     who deleted it — a shared workspace's trash holds everybody's
     * @param deletedAt milliseconds since the epoch
     * @param size      total bytes held, which for a directory is the whole subtree
     */
    public record TrashEntry(String id, String path, String actor, long deletedAt,
                             boolean directory, long size) {
    }

    public static final CgCodec<TrashEntry> TRASH_ENTRY = CgCodecs.record(
            CgCodecs.field(ID, CgCodecs.STRING, TrashEntry::id),
            CgCodecs.field(PATH, CgCodecs.STRING, TrashEntry::path),
            CgCodecs.optionalField("actor", CgCodecs.STRING, TrashEntry::actor, ""),
            CgCodecs.optionalField("at", CgCodecs.LONG, TrashEntry::deletedAt, 0L),
            CgCodecs.optionalField("dir", CgCodecs.BOOL, TrashEntry::directory, false),
            CgCodecs.optionalField("size", CgCodecs.LONG, TrashEntry::size, 0L),
            TrashEntry::new);

    /** What is recoverable in one project, newest first. */
    public record TrashListResponse(List<TrashEntry> entries) {
    }

    public static final CgCodec<TrashListResponse> TRASH_LIST_RESPONSE = CgCodecs.record(
            CgCodecs.optionalField("entries", CgCodecs.listOf(TRASH_ENTRY), TrashListResponse::entries, List.of()),
            TrashListResponse::new);

    public record ProjectEntry(String id, String displayName, List<String> sourceRoots,
                               List<String> excludes) {
    }

    public static final CgCodec<ProjectEntry> PROJECT = CgCodecs.record(
            CgCodecs.field(ID, CgCodecs.STRING, ProjectEntry::id),
            CgCodecs.optionalField("displayName", CgCodecs.STRING, ProjectEntry::displayName, ""),
            CgCodecs.optionalField("sourceRoots", CgCodecs.listOf(CgCodecs.STRING), ProjectEntry::sourceRoots, List.of()),
            CgCodecs.optionalField("excludes", CgCodecs.listOf(CgCodecs.STRING), ProjectEntry::excludes, List.of()),
            ProjectEntry::new);

    public record ProjectsResponse(List<ProjectEntry> projects) {
    }

    public static final CgCodec<ProjectsResponse> PROJECTS_RESPONSE = CgCodecs.record(
            CgCodecs.optionalField("projects", CgCodecs.listOf(PROJECT), ProjectsResponse::projects, List.of()),
            ProjectsResponse::new);

    // ── What the server says without being asked ────────────────────────────────────────────────

    /**
     * One thing that happened to one path.
     *
     * <p>{@code created} and {@code renamed} are new at F3. The wire carried {@code modified} and
     * {@code deleted} only, so an external rename arrived as a deletion and a creation in a folder you
     * had expanded arrived as nothing at all.
     */
    public enum ChangeKind {
        CREATED, MODIFIED, DELETED, RENAMED
    }

    /**
     * @param from   set only for a {@link ChangeKind#RENAMED}, which is ONE event and never a pair
     * @param author who asked for this, or empty when nothing here did — a filesystem event carries no
     *               name, because the OS does not know who was asking. Empty therefore means
     *               <b>outside the workspace</b>, not unknown: a change the server performed always
     *               names its actor
     */
    public record FileChange(String path, ChangeKind kind, String etag, String from, String author,
                             boolean directory) {
        public FileChange(String path, ChangeKind kind, String etag, String from, String author) {
            this(path, kind, etag, from, author, false);
        }

        public FileChange(String path, ChangeKind kind, String etag, String from) {
            this(path, kind, etag, from, "", false);
        }

        public FileChange(String path, ChangeKind kind, String etag) {
            this(path, kind, etag, "", "", false);
        }

        /** Whether somebody on this workspace did it, as opposed to something outside it. */
        public boolean byPeer() {
            return !author.isEmpty();
        }

        public FileChange by(String who) {
            return new FileChange(path, kind, etag, from, who == null ? "" : who, directory);
        }
    }

    public static final CgCodec<FileChange> FILE_CHANGE = CgCodecs.record(
            CgCodecs.field(PATH, CgCodecs.STRING, FileChange::path),
            CgCodecs.field("kind", CgCodecs.enumOf(ChangeKind.class), FileChange::kind),
            CgCodecs.optionalField(ETAG, CgCodecs.STRING, FileChange::etag, ""),
            CgCodecs.optionalField("from", CgCodecs.STRING, FileChange::from, ""),
            CgCodecs.optionalField("author", CgCodecs.STRING, FileChange::author, ""),
            CgCodecs.optionalField("directory", CgCodecs.BOOL, FileChange::directory, false),
            FileChange::new);

    /** A tick's worth of changes, coalesced. One notification, however many files moved. */
    public record ChangedNotification(List<FileChange> changes) {
    }

    public static final CgCodec<ChangedNotification> CHANGED_NOTIFICATION = CgCodecs.record(
            CgCodecs.optionalField("changes", CgCodecs.listOf(FILE_CHANGE), ChangedNotification::changes, List.of()),
            ChangedNotification::new);

    /** @param editing whether that peer has unsaved changes, not merely the file open */
    public record PresenceEntry(String path, String who, boolean editing) {
    }

    public static final CgCodec<PresenceEntry> PRESENCE_ENTRY = CgCodecs.record(
            CgCodecs.field(PATH, CgCodecs.STRING, PresenceEntry::path),
            CgCodecs.field("who", CgCodecs.STRING, PresenceEntry::who),
            CgCodecs.optionalField("editing", CgCodecs.BOOL, PresenceEntry::editing, false),
            PresenceEntry::new);

    public record PresenceNotification(List<PresenceEntry> entries) {
    }

    public static final CgCodec<PresenceNotification> PRESENCE_NOTIFICATION = CgCodecs.record(
            CgCodecs.optionalField("presence", CgCodecs.listOf(PRESENCE_ENTRY), PresenceNotification::entries, List.of()),
            PresenceNotification::new);

    /**
     * What an actor may do with one project.
     *
     * <p>{@code scripting} is additive and defaults to {@link ScriptingMode#LIVE} on both ends, so a
     * client talking to a server that has never heard of it behaves exactly as it did.</p>
     */
    public record ProjectCapability(String project, boolean mayRead, boolean mayWrite,
                                    ScriptingMode scripting) {

        public ProjectCapability(String project, boolean mayRead, boolean mayWrite) {
            this(project, mayRead, mayWrite, ScriptingMode.LIVE);
        }
    }

    public static final CgCodec<ProjectCapability> CAPABILITY = CgCodecs.record(
            CgCodecs.field("project", CgCodecs.STRING, ProjectCapability::project),
            CgCodecs.optionalField("read", CgCodecs.BOOL, ProjectCapability::mayRead, false),
            CgCodecs.optionalField("write", CgCodecs.BOOL, ProjectCapability::mayWrite, false),
            CgCodecs.optionalField("run", SCRIPTING, ProjectCapability::scripting, ScriptingMode.LIVE),
            ProjectCapability::new);

    public record CapabilitiesNotification(List<ProjectCapability> capabilities) {
    }

    public static final CgCodec<CapabilitiesNotification> CAPABILITIES_NOTIFICATION = CgCodecs.record(
            CgCodecs.optionalField("caps", CgCodecs.listOf(CAPABILITY), CapabilitiesNotification::capabilities, List.of()),
            CapabilitiesNotification::new);
}
