package com.crystalgui.fs.protocol;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Every filesystem payload, as a record with a codec.
 *
 * <p>One codec serves both ends, so a field written on one side is provably the field read on the
 * other. Packing and unpacking by hand at each end cannot give that: a value crosses the wire
 * correctly, is dropped on arrival, and every observable on both sides looks right.</p>
 *
 * <p>Records, so the fields are the type. One codec per record, so the encoding is stated once and both
 * halves use the same one. A required field that is missing throws naming itself, which is what
 * {@code CgCodecs.MapCodecReader.field} already does.</p>
 *
 * <h3>An unknown field is ignored, and that is a version policy</h3>
 *
 * <p>{@code MapCodecReader} reads by name, so a newer server sending a field this client has never
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

    public static <T> CgCodec<PathRequest> pathRequest() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, PathRequest value) {
                return CgCodecs.<U>map(ops)
                        .field(PATH, CgCodecs.STRING, value.path())
                        .optional(OP, CgCodecs.STRING, value.op(), "")
                        .build();
            }

            @Override
            public <U> PathRequest decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new PathRequest(in.field(PATH, CgCodecs.STRING),
                        in.optional(OP, CgCodecs.STRING, ""));
            }
        };
    }

    /** Asks about two, which is every move. */
    public record MoveRequest(String from, String to, boolean overwrite, String op) {
    }

    public static CgCodec<MoveRequest> moveRequest() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, MoveRequest value) {
                return CgCodecs.<U>map(ops)
                        .field("from", CgCodecs.STRING, value.from())
                        .field("to", CgCodecs.STRING, value.to())
                        .optional("overwrite", CgCodecs.BOOL, value.overwrite(), false)
                        .optional(OP, CgCodecs.STRING, value.op(), "")
                        .build();
            }

            @Override
            public <U> MoveRequest decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new MoveRequest(in.field("from", CgCodecs.STRING),
                        in.field("to", CgCodecs.STRING),
                        in.optional("overwrite", CgCodecs.BOOL, false),
                        in.optional(OP, CgCodecs.STRING, ""));
            }
        };
    }

    /**
     * A read, optionally conditional.
     *
     * @param ifNoneMatch an etag the caller already holds. The server answers "unchanged" and sends no
     *                    bytes when it matches — HTTP's own header, and what makes reopening a tab free
     */
    public record ReadRequest(String path, String ifNoneMatch) {
    }

    public static CgCodec<ReadRequest> readRequest() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ReadRequest value) {
                return CgCodecs.<U>map(ops)
                        .field(PATH, CgCodecs.STRING, value.path())
                        .optional("ifNoneMatch", CgCodecs.STRING, value.ifNoneMatch(), "")
                        .build();
            }

            @Override
            public <U> ReadRequest decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new ReadRequest(in.field(PATH, CgCodecs.STRING),
                        in.optional("ifNoneMatch", CgCodecs.STRING, ""));
            }
        };
    }

    /**
     * A file's bytes, or the transfer to pull them through.
     *
     * @param unchanged the conditional read matched; {@code content} is empty and means nothing
     * @param transfer  non-empty when the file is too big for one message. The bytes are then pulled
     *                  with {@link FsMethods#READ_CHUNK}
     */
    public record ReadResponse(String etag, byte[] content, boolean unchanged,
                               String transfer, long size) {
    }

    public static CgCodec<ReadResponse> readResponse() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ReadResponse value) {
                return CgCodecs.<U>map(ops)
                        .field(ETAG, CgCodecs.STRING, value.etag())
                        .optional("unchanged", CgCodecs.BOOL, value.unchanged(), false)
                        .optional("transfer", CgCodecs.STRING, value.transfer(), "")
                        .optional("size", CgCodecs.LONG, value.size(), 0L)
                        .optional(CONTENT, BYTES, value.content(), null)
                        .build();
            }

            @Override
            public <U> ReadResponse decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new ReadResponse(in.optional(ETAG, CgCodecs.STRING, ""),
                        in.optional(CONTENT, BYTES, new byte[0]),
                        in.optional("unchanged", CgCodecs.BOOL, false),
                        in.optional("transfer", CgCodecs.STRING, ""),
                        in.optional("size", CgCodecs.LONG, 0L));
            }
        };
    }

    /** One window of a transfer. */
    public record ChunkRequest(String transfer, long offset, int length) {
    }

    public static CgCodec<ChunkRequest> chunkRequest() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ChunkRequest value) {
                return CgCodecs.<U>map(ops)
                        .field("transfer", CgCodecs.STRING, value.transfer())
                        .field("offset", CgCodecs.LONG, value.offset())
                        .field("length", CgCodecs.INT, value.length())
                        .build();
            }

            @Override
            public <U> ChunkRequest decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new ChunkRequest(in.field("transfer", CgCodecs.STRING),
                        in.field("offset", CgCodecs.LONG),
                        in.field("length", CgCodecs.INT));
            }
        };
    }

    /** @param eof whether this window reached the end — how a reader knows to stop asking */
    public record ChunkResponse(byte[] content, boolean eof) {
    }

    public static CgCodec<ChunkResponse> chunkResponse() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ChunkResponse value) {
                return CgCodecs.<U>map(ops)
                        .field(CONTENT, BYTES, value.content())
                        .optional("eof", CgCodecs.BOOL, value.eof(), false)
                        .build();
            }

            @Override
            public <U> ChunkResponse decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new ChunkResponse(in.field(CONTENT, BYTES),
                        in.optional("eof", CgCodecs.BOOL, false));
            }
        };
    }

    /** A write, conditional on the etag unless it is empty. */
    public record WriteRequest(String path, byte[] content, String etag, boolean create,
                               boolean overwrite, String op) {
    }

    public static CgCodec<WriteRequest> writeRequest() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, WriteRequest value) {
                return CgCodecs.<U>map(ops)
                        .field(PATH, CgCodecs.STRING, value.path())
                        .field(CONTENT, BYTES, value.content())
                        .optional(ETAG, CgCodecs.STRING, value.etag(), "")
                        .optional("create", CgCodecs.BOOL, value.create(), false)
                        .optional("overwrite", CgCodecs.BOOL, value.overwrite(), true)
                        .optional(OP, CgCodecs.STRING, value.op(), "")
                        .build();
            }

            @Override
            public <U> WriteRequest decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new WriteRequest(in.field(PATH, CgCodecs.STRING),
                        in.field(CONTENT, BYTES),
                        in.optional(ETAG, CgCodecs.STRING, ""),
                        in.optional("create", CgCodecs.BOOL, false),
                        in.optional("overwrite", CgCodecs.BOOL, true),
                        in.optional(OP, CgCodecs.STRING, ""));
            }
        };
    }

    /** What every mutation answers: the etag the file now holds. */
    public record EtagResponse(String etag) {
    }

    public static CgCodec<EtagResponse> etagResponse() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, EtagResponse value) {
                return CgCodecs.<U>map(ops).field(ETAG, CgCodecs.STRING, value.etag()).build();
            }

            @Override
            public <U> EtagResponse decode(CgDynamicOps<U> ops, U input) {
                return new EtagResponse(CgCodecs.read(ops, input).optional(ETAG, CgCodecs.STRING, ""));
            }
        };
    }

    // ── Directory entries ───────────────────────────────────────────────────────────────────────

    public record Entry(String name, boolean directory, long size, long mtime) {
    }

    public static final CgCodec<Entry> ENTRY = new CgCodec<>() {
        @Override
        public <U> U encode(CgDynamicOps<U> ops, Entry value) {
            return CgCodecs.<U>map(ops)
                    .field(NAME, CgCodecs.STRING, value.name())
                    .optional("dir", CgCodecs.BOOL, value.directory(), false)
                    .optional("size", CgCodecs.LONG, value.size(), 0L)
                    .optional("mtime", CgCodecs.LONG, value.mtime(), 0L)
                    .build();
        }

        @Override
        public <U> Entry decode(CgDynamicOps<U> ops, U input) {
            CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
            return new Entry(in.field(NAME, CgCodecs.STRING),
                    in.optional("dir", CgCodecs.BOOL, false),
                    in.optional("size", CgCodecs.LONG, 0L),
                    in.optional("mtime", CgCodecs.LONG, 0L));
        }
    };

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

    public static CgCodec<ListResponse> listResponse() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ListResponse value) {
                return CgCodecs.<U>map(ops)
                        .field("entries", CgCodecs.listOf(ENTRY), value.entries())
                        .optional("cursor", CgCodecs.STRING, value.cursor(), "")
                        .build();
            }

            @Override
            public <U> ListResponse decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new ListResponse(in.optionalList("entries", ENTRY),
                        in.optional("cursor", CgCodecs.STRING, ""));
            }
        };
    }

    /** A directory listing, paged. */
    public record ListRequest(String path, String cursor) {
        public ListRequest(String path) {
            this(path, "");
        }
    }

    public static CgCodec<ListRequest> listRequest() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ListRequest value) {
                return CgCodecs.<U>map(ops)
                        .field(PATH, CgCodecs.STRING, value.path())
                        .optional("cursor", CgCodecs.STRING, value.cursor(), "")
                        .build();
            }

            @Override
            public <U> ListRequest decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new ListRequest(in.field(PATH, CgCodecs.STRING),
                        in.optional("cursor", CgCodecs.STRING, ""));
            }
        };
    }

    public record StatResponse(String etag, boolean directory, long size, long mtime,
                               boolean binary) {
    }

    public static CgCodec<StatResponse> statResponse() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, StatResponse value) {
                return CgCodecs.<U>map(ops)
                        .field(ETAG, CgCodecs.STRING, value.etag())
                        .optional("dir", CgCodecs.BOOL, value.directory(), false)
                        .optional("size", CgCodecs.LONG, value.size(), 0L)
                        .optional("mtime", CgCodecs.LONG, value.mtime(), 0L)
                        .optional("binary", CgCodecs.BOOL, value.binary(), false)
                        .build();
            }

            @Override
            public <U> StatResponse decode(CgDynamicOps<U> ops, U input) {
                CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
                return new StatResponse(in.optional(ETAG, CgCodecs.STRING, ""),
                        in.optional("dir", CgCodecs.BOOL, false),
                        in.optional("size", CgCodecs.LONG, 0L),
                        in.optional("mtime", CgCodecs.LONG, 0L),
                        in.optional("binary", CgCodecs.BOOL, false));
            }
        };
    }

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

    public static final CgCodec<TrashEntry> TRASH_ENTRY = new CgCodec<>() {
        @Override
        public <U> U encode(CgDynamicOps<U> ops, TrashEntry value) {
            return CgCodecs.<U>map(ops)
                    .field(ID, CgCodecs.STRING, value.id())
                    .field(PATH, CgCodecs.STRING, value.path())
                    .optional("actor", CgCodecs.STRING, value.actor(), "")
                    .optional("at", CgCodecs.LONG, value.deletedAt(), 0L)
                    .optional("dir", CgCodecs.BOOL, value.directory(), false)
                    .optional("size", CgCodecs.LONG, value.size(), 0L)
                    .build();
        }

        @Override
        public <U> TrashEntry decode(CgDynamicOps<U> ops, U input) {
            CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
            return new TrashEntry(in.field(ID, CgCodecs.STRING),
                    in.field(PATH, CgCodecs.STRING),
                    in.optional("actor", CgCodecs.STRING, ""),
                    in.optional("at", CgCodecs.LONG, 0L),
                    in.optional("dir", CgCodecs.BOOL, false),
                    in.optional("size", CgCodecs.LONG, 0L));
        }
    };

    /** What is recoverable in one project, newest first. */
    public record TrashListResponse(List<TrashEntry> entries) {
    }

    public static CgCodec<TrashListResponse> trashListResponse() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, TrashListResponse value) {
                return CgCodecs.<U>map(ops)
                        .field("entries", CgCodecs.listOf(TRASH_ENTRY), value.entries())
                        .build();
            }

            @Override
            public <U> TrashListResponse decode(CgDynamicOps<U> ops, U input) {
                return new TrashListResponse(
                        CgCodecs.read(ops, input).optionalList("entries", TRASH_ENTRY));
            }
        };
    }

    public record ProjectEntry(String id, String displayName, List<String> sourceRoots,
                               List<String> excludes) {
    }

    public static final CgCodec<ProjectEntry> PROJECT = new CgCodec<>() {
        @Override
        public <U> U encode(CgDynamicOps<U> ops, ProjectEntry value) {
            return CgCodecs.<U>map(ops)
                    .field(ID, CgCodecs.STRING, value.id())
                    .field("displayName", CgCodecs.STRING, value.displayName())
                    .optionalList("sourceRoots", CgCodecs.STRING, value.sourceRoots())
                    // THE IGNORE RULES TRAVEL, so the crawl, Go to File and the tree all skip what the
                    // PROJECT says to skip rather than each holding its own idea of it.
                    .optionalList("excludes", CgCodecs.STRING, value.excludes())
                    .build();
        }

        @Override
        public <U> ProjectEntry decode(CgDynamicOps<U> ops, U input) {
            CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
            return new ProjectEntry(in.field(ID, CgCodecs.STRING),
                    in.optional("displayName", CgCodecs.STRING, ""),
                    in.optionalList("sourceRoots", CgCodecs.STRING),
                    in.optionalList("excludes", CgCodecs.STRING));
        }
    };

    public record ProjectsResponse(List<ProjectEntry> projects) {
    }

    public static CgCodec<ProjectsResponse> projectsResponse() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ProjectsResponse value) {
                return CgCodecs.<U>map(ops)
                        .field("projects", CgCodecs.listOf(PROJECT), value.projects()).build();
            }

            @Override
            public <U> ProjectsResponse decode(CgDynamicOps<U> ops, U input) {
                return new ProjectsResponse(CgCodecs.read(ops, input).optionalList("projects", PROJECT));
            }
        };
    }

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

    public static final CgCodec<FileChange> FILE_CHANGE = new CgCodec<>() {
        @Override
        public <U> U encode(CgDynamicOps<U> ops, FileChange value) {
            return CgCodecs.<U>map(ops)
                    .field(PATH, CgCodecs.STRING, value.path())
                    .field("kind", CgCodecs.enumOf(ChangeKind.class), value.kind())
                    .optional(ETAG, CgCodecs.STRING, value.etag(), "")
                    .optional("from", CgCodecs.STRING, value.from(), "")
                    .optional("author", CgCodecs.STRING, value.author(), "")
                    .optional("directory", CgCodecs.BOOL, value.directory(), false)
                    .build();
        }

        @Override
        public <U> FileChange decode(CgDynamicOps<U> ops, U input) {
            CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
            return new FileChange(in.field(PATH, CgCodecs.STRING),
                    in.field("kind", CgCodecs.enumOf(ChangeKind.class)),
                    in.optional(ETAG, CgCodecs.STRING, ""),
                    in.optional("from", CgCodecs.STRING, ""),
                    in.optional("author", CgCodecs.STRING, ""),
                    in.optional("directory", CgCodecs.BOOL, false));
        }
    };

    /** A tick's worth of changes, coalesced. One notification, however many files moved. */
    public record ChangedNotification(List<FileChange> changes) {
    }

    public static CgCodec<ChangedNotification> changedNotification() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, ChangedNotification value) {
                return CgCodecs.<U>map(ops)
                        .field("changes", CgCodecs.listOf(FILE_CHANGE), value.changes()).build();
            }

            @Override
            public <U> ChangedNotification decode(CgDynamicOps<U> ops, U input) {
                return new ChangedNotification(
                        CgCodecs.read(ops, input).optionalList("changes", FILE_CHANGE));
            }
        };
    }

    /** @param editing whether that peer has unsaved changes, not merely the file open */
    public record PresenceEntry(String path, String who, boolean editing) {
    }

    public static final CgCodec<PresenceEntry> PRESENCE_ENTRY = new CgCodec<>() {
        @Override
        public <U> U encode(CgDynamicOps<U> ops, PresenceEntry value) {
            return CgCodecs.<U>map(ops)
                    .field(PATH, CgCodecs.STRING, value.path())
                    .field("who", CgCodecs.STRING, value.who())
                    .optional("editing", CgCodecs.BOOL, value.editing(), false)
                    .build();
        }

        @Override
        public <U> PresenceEntry decode(CgDynamicOps<U> ops, U input) {
            CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
            return new PresenceEntry(in.field(PATH, CgCodecs.STRING),
                    in.field("who", CgCodecs.STRING),
                    in.optional("editing", CgCodecs.BOOL, false));
        }
    };

    public record PresenceNotification(List<PresenceEntry> entries) {
    }

    public static CgCodec<PresenceNotification> presenceNotification() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, PresenceNotification value) {
                return CgCodecs.<U>map(ops)
                        .field("presence", CgCodecs.listOf(PRESENCE_ENTRY), value.entries()).build();
            }

            @Override
            public <U> PresenceNotification decode(CgDynamicOps<U> ops, U input) {
                return new PresenceNotification(
                        CgCodecs.read(ops, input).optionalList("presence", PRESENCE_ENTRY));
            }
        };
    }

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

    public static final CgCodec<ProjectCapability> CAPABILITY = new CgCodec<>() {
        @Override
        public <U> U encode(CgDynamicOps<U> ops, ProjectCapability value) {
            return CgCodecs.<U>map(ops)
                    .field("project", CgCodecs.STRING, value.project())
                    .optional("read", CgCodecs.BOOL, value.mayRead(), false)
                    .optional("write", CgCodecs.BOOL, value.mayWrite(), false)
                    .optional("run", CgCodecs.STRING, value.scripting().name(),
                            ScriptingMode.LIVE.name())
                    .build();
        }

        @Override
        public <U> ProjectCapability decode(CgDynamicOps<U> ops, U input) {
            CgCodecs.MapCodecReader<U> in = CgCodecs.read(ops, input);
            return new ProjectCapability(in.field("project", CgCodecs.STRING),
                    in.optional("read", CgCodecs.BOOL, false),
                    in.optional("write", CgCodecs.BOOL, false),
                    ScriptingMode.parse(in.optional("run", CgCodecs.STRING, ScriptingMode.LIVE.name())));
        }
    };

    public record CapabilitiesNotification(List<ProjectCapability> capabilities) {
    }

    public static CgCodec<CapabilitiesNotification> capabilitiesNotification() {
        return new CgCodec<>() {
            @Override
            public <U> U encode(CgDynamicOps<U> ops, CapabilitiesNotification value) {
                return CgCodecs.<U>map(ops)
                        .field("caps", CgCodecs.listOf(CAPABILITY), value.capabilities()).build();
            }

            @Override
            public <U> CapabilitiesNotification decode(CgDynamicOps<U> ops, U input) {
                return new CapabilitiesNotification(
                        CgCodecs.read(ops, input).optionalList("caps", CAPABILITY));
            }
        };
    }

    // ── Bytes ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Base64, because a {@code CgDynamicOps} has no byte-array primitive and the JSON one has no way to
     * grow one — a text encoding is what makes the same codec work over JSON and over the binary ops.
     *
     * <p>Nullable-tolerant on encode so {@code optional(..., null)} can omit it, which is what a
     * conditional read that matched sends instead of an empty array.</p>
     */
    public static final CgCodec<byte[]> BYTES = new CgCodec<>() {
        @Override
        public <U> U encode(CgDynamicOps<U> ops, byte[] value) {
            return ops.createString(value == null ? ""
                    : java.util.Base64.getEncoder().encodeToString(value));
        }

        @Override
        public <U> byte[] decode(CgDynamicOps<U> ops, U input) {
            String text = ops.getStringValue(input);
            return text == null || text.isEmpty() ? new byte[0]
                    : java.util.Base64.getDecoder().decode(text);
        }
    };
}
