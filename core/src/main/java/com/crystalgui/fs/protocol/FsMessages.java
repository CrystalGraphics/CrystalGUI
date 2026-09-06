package com.crystalgui.fs.protocol;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.DynamicOps;

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
 * {@code Codecs.MapCodecReader.field} already does.</p>
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

    public static <T> Codec<PathRequest> pathRequest() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, PathRequest value) {
                return Codecs.<U>map(ops)
                        .field(PATH, Codecs.STRING, value.path())
                        .optional(OP, Codecs.STRING, value.op(), "")
                        .build();
            }

            @Override
            public <U> PathRequest decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new PathRequest(in.field(PATH, Codecs.STRING),
                        in.optional(OP, Codecs.STRING, ""));
            }
        };
    }

    /** Asks about two, which is every move. */
    public record MoveRequest(String from, String to, boolean overwrite, String op) {
    }

    public static Codec<MoveRequest> moveRequest() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, MoveRequest value) {
                return Codecs.<U>map(ops)
                        .field("from", Codecs.STRING, value.from())
                        .field("to", Codecs.STRING, value.to())
                        .optional("overwrite", Codecs.BOOL, value.overwrite(), false)
                        .optional(OP, Codecs.STRING, value.op(), "")
                        .build();
            }

            @Override
            public <U> MoveRequest decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new MoveRequest(in.field("from", Codecs.STRING),
                        in.field("to", Codecs.STRING),
                        in.optional("overwrite", Codecs.BOOL, false),
                        in.optional(OP, Codecs.STRING, ""));
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

    public static Codec<ReadRequest> readRequest() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ReadRequest value) {
                return Codecs.<U>map(ops)
                        .field(PATH, Codecs.STRING, value.path())
                        .optional("ifNoneMatch", Codecs.STRING, value.ifNoneMatch(), "")
                        .build();
            }

            @Override
            public <U> ReadRequest decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new ReadRequest(in.field(PATH, Codecs.STRING),
                        in.optional("ifNoneMatch", Codecs.STRING, ""));
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

    public static Codec<ReadResponse> readResponse() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ReadResponse value) {
                return Codecs.<U>map(ops)
                        .field(ETAG, Codecs.STRING, value.etag())
                        .optional("unchanged", Codecs.BOOL, value.unchanged(), false)
                        .optional("transfer", Codecs.STRING, value.transfer(), "")
                        .optional("size", Codecs.LONG, value.size(), 0L)
                        .optional(CONTENT, BYTES, value.content(), null)
                        .build();
            }

            @Override
            public <U> ReadResponse decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new ReadResponse(in.optional(ETAG, Codecs.STRING, ""),
                        in.optional(CONTENT, BYTES, new byte[0]),
                        in.optional("unchanged", Codecs.BOOL, false),
                        in.optional("transfer", Codecs.STRING, ""),
                        in.optional("size", Codecs.LONG, 0L));
            }
        };
    }

    /** One window of a transfer. */
    public record ChunkRequest(String transfer, long offset, int length) {
    }

    public static Codec<ChunkRequest> chunkRequest() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ChunkRequest value) {
                return Codecs.<U>map(ops)
                        .field("transfer", Codecs.STRING, value.transfer())
                        .field("offset", Codecs.LONG, value.offset())
                        .field("length", Codecs.INT, value.length())
                        .build();
            }

            @Override
            public <U> ChunkRequest decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new ChunkRequest(in.field("transfer", Codecs.STRING),
                        in.field("offset", Codecs.LONG),
                        in.field("length", Codecs.INT));
            }
        };
    }

    /** @param eof whether this window reached the end — how a reader knows to stop asking */
    public record ChunkResponse(byte[] content, boolean eof) {
    }

    public static Codec<ChunkResponse> chunkResponse() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ChunkResponse value) {
                return Codecs.<U>map(ops)
                        .field(CONTENT, BYTES, value.content())
                        .optional("eof", Codecs.BOOL, value.eof(), false)
                        .build();
            }

            @Override
            public <U> ChunkResponse decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new ChunkResponse(in.field(CONTENT, BYTES),
                        in.optional("eof", Codecs.BOOL, false));
            }
        };
    }

    /** A write, conditional on the etag unless it is empty. */
    public record WriteRequest(String path, byte[] content, String etag, boolean create,
                               boolean overwrite, String op) {
    }

    public static Codec<WriteRequest> writeRequest() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, WriteRequest value) {
                return Codecs.<U>map(ops)
                        .field(PATH, Codecs.STRING, value.path())
                        .field(CONTENT, BYTES, value.content())
                        .optional(ETAG, Codecs.STRING, value.etag(), "")
                        .optional("create", Codecs.BOOL, value.create(), false)
                        .optional("overwrite", Codecs.BOOL, value.overwrite(), true)
                        .optional(OP, Codecs.STRING, value.op(), "")
                        .build();
            }

            @Override
            public <U> WriteRequest decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new WriteRequest(in.field(PATH, Codecs.STRING),
                        in.field(CONTENT, BYTES),
                        in.optional(ETAG, Codecs.STRING, ""),
                        in.optional("create", Codecs.BOOL, false),
                        in.optional("overwrite", Codecs.BOOL, true),
                        in.optional(OP, Codecs.STRING, ""));
            }
        };
    }

    /** What every mutation answers: the etag the file now holds. */
    public record EtagResponse(String etag) {
    }

    public static Codec<EtagResponse> etagResponse() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, EtagResponse value) {
                return Codecs.<U>map(ops).field(ETAG, Codecs.STRING, value.etag()).build();
            }

            @Override
            public <U> EtagResponse decode(DynamicOps<U> ops, U input) {
                return new EtagResponse(Codecs.read(ops, input).optional(ETAG, Codecs.STRING, ""));
            }
        };
    }

    // ── Directory entries ───────────────────────────────────────────────────────────────────────

    public record Entry(String name, boolean directory, long size, long mtime) {
    }

    public static final Codec<Entry> ENTRY = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, Entry value) {
            return Codecs.<U>map(ops)
                    .field(NAME, Codecs.STRING, value.name())
                    .optional("dir", Codecs.BOOL, value.directory(), false)
                    .optional("size", Codecs.LONG, value.size(), 0L)
                    .optional("mtime", Codecs.LONG, value.mtime(), 0L)
                    .build();
        }

        @Override
        public <U> Entry decode(DynamicOps<U> ops, U input) {
            Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
            return new Entry(in.field(NAME, Codecs.STRING),
                    in.optional("dir", Codecs.BOOL, false),
                    in.optional("size", Codecs.LONG, 0L),
                    in.optional("mtime", Codecs.LONG, 0L));
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

    public static Codec<ListResponse> listResponse() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ListResponse value) {
                return Codecs.<U>map(ops)
                        .field("entries", Codecs.listOf(ENTRY), value.entries())
                        .optional("cursor", Codecs.STRING, value.cursor(), "")
                        .build();
            }

            @Override
            public <U> ListResponse decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new ListResponse(in.optionalList("entries", ENTRY),
                        in.optional("cursor", Codecs.STRING, ""));
            }
        };
    }

    /** A directory listing, paged. */
    public record ListRequest(String path, String cursor) {
        public ListRequest(String path) {
            this(path, "");
        }
    }

    public static Codec<ListRequest> listRequest() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ListRequest value) {
                return Codecs.<U>map(ops)
                        .field(PATH, Codecs.STRING, value.path())
                        .optional("cursor", Codecs.STRING, value.cursor(), "")
                        .build();
            }

            @Override
            public <U> ListRequest decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new ListRequest(in.field(PATH, Codecs.STRING),
                        in.optional("cursor", Codecs.STRING, ""));
            }
        };
    }

    public record StatResponse(String etag, boolean directory, long size, long mtime,
                               boolean binary) {
    }

    public static Codec<StatResponse> statResponse() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, StatResponse value) {
                return Codecs.<U>map(ops)
                        .field(ETAG, Codecs.STRING, value.etag())
                        .optional("dir", Codecs.BOOL, value.directory(), false)
                        .optional("size", Codecs.LONG, value.size(), 0L)
                        .optional("mtime", Codecs.LONG, value.mtime(), 0L)
                        .optional("binary", Codecs.BOOL, value.binary(), false)
                        .build();
            }

            @Override
            public <U> StatResponse decode(DynamicOps<U> ops, U input) {
                Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
                return new StatResponse(in.optional(ETAG, Codecs.STRING, ""),
                        in.optional("dir", Codecs.BOOL, false),
                        in.optional("size", Codecs.LONG, 0L),
                        in.optional("mtime", Codecs.LONG, 0L),
                        in.optional("binary", Codecs.BOOL, false));
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

    public static final Codec<TrashEntry> TRASH_ENTRY = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, TrashEntry value) {
            return Codecs.<U>map(ops)
                    .field(ID, Codecs.STRING, value.id())
                    .field(PATH, Codecs.STRING, value.path())
                    .optional("actor", Codecs.STRING, value.actor(), "")
                    .optional("at", Codecs.LONG, value.deletedAt(), 0L)
                    .optional("dir", Codecs.BOOL, value.directory(), false)
                    .optional("size", Codecs.LONG, value.size(), 0L)
                    .build();
        }

        @Override
        public <U> TrashEntry decode(DynamicOps<U> ops, U input) {
            Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
            return new TrashEntry(in.field(ID, Codecs.STRING),
                    in.field(PATH, Codecs.STRING),
                    in.optional("actor", Codecs.STRING, ""),
                    in.optional("at", Codecs.LONG, 0L),
                    in.optional("dir", Codecs.BOOL, false),
                    in.optional("size", Codecs.LONG, 0L));
        }
    };

    /** What is recoverable in one project, newest first. */
    public record TrashListResponse(List<TrashEntry> entries) {
    }

    public static Codec<TrashListResponse> trashListResponse() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, TrashListResponse value) {
                return Codecs.<U>map(ops)
                        .field("entries", Codecs.listOf(TRASH_ENTRY), value.entries())
                        .build();
            }

            @Override
            public <U> TrashListResponse decode(DynamicOps<U> ops, U input) {
                return new TrashListResponse(
                        Codecs.read(ops, input).optionalList("entries", TRASH_ENTRY));
            }
        };
    }

    public record ProjectEntry(String id, String displayName, List<String> sourceRoots,
                               List<String> excludes) {
    }

    public static final Codec<ProjectEntry> PROJECT = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, ProjectEntry value) {
            return Codecs.<U>map(ops)
                    .field(ID, Codecs.STRING, value.id())
                    .field("displayName", Codecs.STRING, value.displayName())
                    .optionalList("sourceRoots", Codecs.STRING, value.sourceRoots())
                    // THE IGNORE RULES TRAVEL, so the crawl, Go to File and the tree all skip what the
                    // PROJECT says to skip rather than each holding its own idea of it.
                    .optionalList("excludes", Codecs.STRING, value.excludes())
                    .build();
        }

        @Override
        public <U> ProjectEntry decode(DynamicOps<U> ops, U input) {
            Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
            return new ProjectEntry(in.field(ID, Codecs.STRING),
                    in.optional("displayName", Codecs.STRING, ""),
                    in.optionalList("sourceRoots", Codecs.STRING),
                    in.optionalList("excludes", Codecs.STRING));
        }
    };

    public record ProjectsResponse(List<ProjectEntry> projects) {
    }

    public static Codec<ProjectsResponse> projectsResponse() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ProjectsResponse value) {
                return Codecs.<U>map(ops)
                        .field("projects", Codecs.listOf(PROJECT), value.projects()).build();
            }

            @Override
            public <U> ProjectsResponse decode(DynamicOps<U> ops, U input) {
                return new ProjectsResponse(Codecs.read(ops, input).optionalList("projects", PROJECT));
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

    /** @param from set only for a {@link ChangeKind#RENAMED}, which is ONE event and never a pair */
    public record FileChange(String path, ChangeKind kind, String etag, String from) {
        public FileChange(String path, ChangeKind kind, String etag) {
            this(path, kind, etag, "");
        }
    }

    public static final Codec<FileChange> FILE_CHANGE = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, FileChange value) {
            return Codecs.<U>map(ops)
                    .field(PATH, Codecs.STRING, value.path())
                    .field("kind", Codecs.enumOf(ChangeKind.class), value.kind())
                    .optional(ETAG, Codecs.STRING, value.etag(), "")
                    .optional("from", Codecs.STRING, value.from(), "")
                    .build();
        }

        @Override
        public <U> FileChange decode(DynamicOps<U> ops, U input) {
            Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
            return new FileChange(in.field(PATH, Codecs.STRING),
                    in.field("kind", Codecs.enumOf(ChangeKind.class)),
                    in.optional(ETAG, Codecs.STRING, ""),
                    in.optional("from", Codecs.STRING, ""));
        }
    };

    /** A tick's worth of changes, coalesced. One notification, however many files moved. */
    public record ChangedNotification(List<FileChange> changes) {
    }

    public static Codec<ChangedNotification> changedNotification() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, ChangedNotification value) {
                return Codecs.<U>map(ops)
                        .field("changes", Codecs.listOf(FILE_CHANGE), value.changes()).build();
            }

            @Override
            public <U> ChangedNotification decode(DynamicOps<U> ops, U input) {
                return new ChangedNotification(
                        Codecs.read(ops, input).optionalList("changes", FILE_CHANGE));
            }
        };
    }

    /** @param editing whether that peer has unsaved changes, not merely the file open */
    public record PresenceEntry(String path, String who, boolean editing) {
    }

    public static final Codec<PresenceEntry> PRESENCE_ENTRY = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, PresenceEntry value) {
            return Codecs.<U>map(ops)
                    .field(PATH, Codecs.STRING, value.path())
                    .field("who", Codecs.STRING, value.who())
                    .optional("editing", Codecs.BOOL, value.editing(), false)
                    .build();
        }

        @Override
        public <U> PresenceEntry decode(DynamicOps<U> ops, U input) {
            Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
            return new PresenceEntry(in.field(PATH, Codecs.STRING),
                    in.field("who", Codecs.STRING),
                    in.optional("editing", Codecs.BOOL, false));
        }
    };

    public record PresenceNotification(List<PresenceEntry> entries) {
    }

    public static Codec<PresenceNotification> presenceNotification() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, PresenceNotification value) {
                return Codecs.<U>map(ops)
                        .field("presence", Codecs.listOf(PRESENCE_ENTRY), value.entries()).build();
            }

            @Override
            public <U> PresenceNotification decode(DynamicOps<U> ops, U input) {
                return new PresenceNotification(
                        Codecs.read(ops, input).optionalList("presence", PRESENCE_ENTRY));
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

    public static final Codec<ProjectCapability> CAPABILITY = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, ProjectCapability value) {
            return Codecs.<U>map(ops)
                    .field("project", Codecs.STRING, value.project())
                    .optional("read", Codecs.BOOL, value.mayRead(), false)
                    .optional("write", Codecs.BOOL, value.mayWrite(), false)
                    .optional("run", Codecs.STRING, value.scripting().name(),
                            ScriptingMode.LIVE.name())
                    .build();
        }

        @Override
        public <U> ProjectCapability decode(DynamicOps<U> ops, U input) {
            Codecs.MapCodecReader<U> in = Codecs.read(ops, input);
            return new ProjectCapability(in.field("project", Codecs.STRING),
                    in.optional("read", Codecs.BOOL, false),
                    in.optional("write", Codecs.BOOL, false),
                    ScriptingMode.parse(in.optional("run", Codecs.STRING, ScriptingMode.LIVE.name())));
        }
    };

    public record CapabilitiesNotification(List<ProjectCapability> capabilities) {
    }

    public static Codec<CapabilitiesNotification> capabilitiesNotification() {
        return new Codec<>() {
            @Override
            public <U> U encode(DynamicOps<U> ops, CapabilitiesNotification value) {
                return Codecs.<U>map(ops)
                        .field("caps", Codecs.listOf(CAPABILITY), value.capabilities()).build();
            }

            @Override
            public <U> CapabilitiesNotification decode(DynamicOps<U> ops, U input) {
                return new CapabilitiesNotification(
                        Codecs.read(ops, input).optionalList("caps", CAPABILITY));
            }
        };
    }

    // ── Bytes ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Base64, because a {@code DynamicOps} has no byte-array primitive and the JSON one has no way to
     * grow one — a text encoding is what makes the same codec work over JSON and over the binary ops.
     *
     * <p>Nullable-tolerant on encode so {@code optional(..., null)} can omit it, which is what a
     * conditional read that matched sends instead of an empty array.</p>
     */
    public static final Codec<byte[]> BYTES = new Codec<>() {
        @Override
        public <U> U encode(DynamicOps<U> ops, byte[] value) {
            return ops.createString(value == null ? ""
                    : java.util.Base64.getEncoder().encodeToString(value));
        }

        @Override
        public <U> byte[] decode(DynamicOps<U> ops, U input) {
            String text = ops.getStringValue(input);
            return text == null || text.isEmpty() ? new byte[0]
                    : java.util.Base64.getDecoder().decode(text);
        }
    };
}
