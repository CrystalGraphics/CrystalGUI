package com.crystalgui.fs.server;

import com.crystalgui.fs.provider.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsHello;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/**
 * One connection's end of the filesystem — decode, ask the service, encode.
 *
 * <pre>{@code
 * WorkspaceBinding<T> binding = new WorkspaceBinding<>(service, hub, actor, peer, ops);
 * binding.installOn(connection);
 * }</pre>
 *
 * <p>Twenty methods over {@code fs.protocol}'s codecs, so a field written on one side is provably the
 * field read on the other. It also owns the three things that belong to one connection: the
 * {@link WorkspaceAudit} that records and rate-limits this actor's mutations, the
 * {@link RecentOperations} table that makes a write retried after a timeout idempotent, and this peer's
 * entry in the shared {@link WatchHub}.</p>
 *
 * <p><b>One per connection</b>, dying with it — which is what makes {@link #close} the single place a
 * disconnected peer's subscriptions and presence are dropped. A second server in one process, which a
 * dev environment is, gets its own.</p>
 */
public final class WorkspaceBinding<T> {

    /** How many bytes are sent inline before a read becomes a transfer the client pulls through. */
    public static final int INLINE_LIMIT = 256 * 1024;

    /** One window of a transfer. Large enough to be worth a round trip, small enough to interleave. */
    public static final int CHUNK_BYTES = 64 * 1024;

    private final WorkspaceService service;
    private final WatchHub hub;
    private final WorkspaceActor actor;
    private final Object peer;
    private final DynamicOps<T> ops;
    private final WorkspaceAudit audit;
    private final RecentOperations operations = new RecentOperations();

    /**
     * Open transfers, by id — <b>{@code (path, etag, size)} and never the bytes</b>.
     *
     * <p>Holding a snapshot of the whole file means four peers opening four
     * 100 MB files cost 400 MB of server heap to send bytes already on disk. Its own javadoc named the
     * ranged read as the fix; the provider serves one now.</p>
     */
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();

    private final AtomicLong transferIds = new AtomicLong();

    private record Transfer(CgPath path, String etag, long size) {
    }

    public WorkspaceBinding(WorkspaceService service, WatchHub hub, WorkspaceActor actor,
                            Object peer, DynamicOps<T> ops) {
        this(service, hub, actor, peer, ops, new WorkspaceAudit());
    }

    public WorkspaceBinding(WorkspaceService service, WatchHub hub, WorkspaceActor actor,
                            Object peer, DynamicOps<T> ops, WorkspaceAudit audit) {
        this.service = Objects.requireNonNull(service, "service");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.peer = Objects.requireNonNull(peer, "peer");
        this.ops = Objects.requireNonNull(ops, "ops");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public WorkspaceAudit audit() {
        return audit;
    }

    /**
     * Registers every method <b>and</b> makes this binding findable from the connection.
     *
     * <p>The attachment is what {@code ServerScope.workspace()} reads: a panel serving a window on this
     * wire gets the filesystem bound to the same peer, with the actor already decided, rather than
     * re-shipping a listing through the UI mirror.</p>
     */
    public void installOn(ProtocolConnection<T> connection) {
        installOn(connection::onRequest);
        // A factory that ignores the connection, because this binding cannot be built from one: it needs
        // the service, the hub and the actor, all of which are the host's to decide.
        connection.attachment(WorkspaceBinding.class, wire -> this);
    }

    /**
     * A view of the workspace as the peer this binding was bound for.
     *
     * <p>What a server-side panel is handed. @see ServerWorkspace</p>
     */
    public ServerWorkspace workspace() {
        return new ServerWorkspace(service, actor);
    }

    /** Registers every method. */
    public void installOn(Registrar<T> registry) {
        registry.register(FsMethods.HELLO, (args, respond) ->
                answer(respond, FsHello.CODEC, hello()));

        registry.register(FsMethods.PROJECTS, (args, respond) -> guard(respond, () -> {
            List<FsMessages.ProjectEntry> out = new ArrayList<>();
            for (ProjectInfo info : service.projects(actor)) {
                out.add(new FsMessages.ProjectEntry(info.id(), info.displayName(),
                        info.sourceRoots(), info.excludes()));
            }
            return encode(FsMessages.projectsResponse(), new FsMessages.ProjectsResponse(out));
        }));

        registry.register(FsMethods.LIST, (args, respond) -> guard(respond, () -> {
            FsMessages.ListRequest request = decode(FsMessages.listRequest(), args);
            List<FsMessages.Entry> entries = new ArrayList<>();
            for (CgFileEntry entry : service.manifest(actor, CgPath.parse(request.path()))) {
                entries.add(new FsMessages.Entry(entry.name(), entry.isDirectory(),
                        entry.size(), entry.mtime()));
            }
            return encode(FsMessages.listResponse(), new FsMessages.ListResponse(entries));
        }));

        registry.register(FsMethods.STAT, (args, respond) -> guard(respond, () -> {
            CgPath path = CgPath.parse(decode(FsMessages.pathRequest(), args).path());
            CgFileEntry entry = service.stat(actor, path);
            return encode(FsMessages.statResponse(), new FsMessages.StatResponse(entry.etag(),
                    entry.isDirectory(), entry.size(), entry.mtime(), false));
        }));

        registry.register(FsMethods.READ, (args, respond) -> guard(respond, () -> {
            FsMessages.ReadRequest request = decode(FsMessages.readRequest(), args);
            CgPath path = CgPath.parse(request.path());
            CgFileEntry entry = service.stat(actor, path);

            // CONDITIONAL, so re-opening a tab costs one small message. HTTP's If-None-Match.
            if (!request.ifNoneMatch().isEmpty() && request.ifNoneMatch().equals(entry.etag())) {
                return encode(FsMessages.readResponse(), new FsMessages.ReadResponse(
                        entry.etag(), new byte[0], true, "", entry.size()));
            }
            // Watching is what having a file open MEANS; the two were one message before and stay one.
            hub.watch(peer, actor, path, false);
            service.presence().opened(actor, path);

            if (entry.size() > INLINE_LIMIT) {
                String id = "t-" + transferIds.incrementAndGet();
                transfers.put(id, new Transfer(path, entry.etag(), entry.size()));
                return encode(FsMessages.readResponse(), new FsMessages.ReadResponse(
                        entry.etag(), new byte[0], false, id, entry.size()));
            }
            byte[] content = service.read(actor, path).content();
            return encode(FsMessages.readResponse(), new FsMessages.ReadResponse(
                    entry.etag(), content, false, "", entry.size()));
        }));

        registry.register(FsMethods.READ_CHUNK, (args, respond) -> guard(respond, () -> {
            FsMessages.ChunkRequest request = decode(FsMessages.chunkRequest(), args);
            Transfer transfer = transfers.get(request.transfer());
            if (transfer == null) {
                throw new CgFileSystemException(CgFileError.FILE_NOT_FOUND,
                        "no such transfer: " + request.transfer());
            }
            int length = Math.min(request.length() <= 0 ? CHUNK_BYTES : request.length(), CHUNK_BYTES);
            byte[] window = service.readRange(actor, transfer.path(), request.offset(), length);
            boolean eof = request.offset() + window.length >= transfer.size();
            if (eof) transfers.remove(request.transfer());
            return encode(FsMessages.chunkResponse(), new FsMessages.ChunkResponse(window, eof));
        }));

        registry.register(FsMethods.WRITE, (args, respond) -> mutate(respond, () -> {
            FsMessages.WriteRequest request = decode(FsMessages.writeRequest(), args);
            CgPath path = CgPath.parse(request.path());
            String repeat = operations.answerFor(request.op());
            if (repeat != null) return repeat;

            String etag = request.create()
                    ? service.create(actor, path, request.content())
                    : service.write(actor, path, request.content(),
                            request.etag().isEmpty() ? null : request.etag());
            hub.noteWritten(path, etag);
            service.presence().setEditing(actor, path, false);
            audit.record(actor, WorkspaceOperation.WRITE, path);
            operations.record(request.op(), etag);
            return etag;
        }));

        registry.register(FsMethods.CREATE, (args, respond) -> mutate(respond, () -> {
            FsMessages.WriteRequest request = decode(FsMessages.writeRequest(), args);
            CgPath path = CgPath.parse(request.path());
            String repeat = operations.answerFor(request.op());
            if (repeat != null) return repeat;

            requireValidName(path);
            String etag = service.create(actor, path, request.content());
            hub.noteWritten(path, etag);
            audit.record(actor, WorkspaceOperation.WRITE, path);
            operations.record(request.op(), etag);
            return etag;
        }));

        registry.register(FsMethods.MKDIR, (args, respond) -> mutate(respond, () -> {
            CgPath path = CgPath.parse(decode(FsMessages.pathRequest(), args).path());
            requireValidName(path);
            service.mkdir(actor, path);
            audit.record(actor, WorkspaceOperation.WRITE, path);
            return "";
        }));

        registry.register(FsMethods.DELETE, (args, respond) -> mutate(respond, () -> {
            FsMessages.PathRequest request = decode(FsMessages.pathRequest(), args);
            CgPath path = CgPath.parse(request.path());
            String repeat = operations.answerFor(request.op());
            if (repeat != null) return repeat;

            hub.noteDeleted(path);
            String trashId = service.deleteToTrash(actor, path, true, null);
            service.presence().closed(actor, path);
            audit.record(actor, WorkspaceOperation.WRITE, path);
            operations.record(request.op(), trashId);
            return trashId;
        }));

        // WHAT MAKES A DELETE REVERSIBLE. `delete` answers a trash id and nothing could redeem it, so
        // the id was a receipt for something the client had no way to ask for.
        registry.register(FsMethods.RESTORE, (args, respond) -> mutate(respond, () -> {
            FsMessages.PathRequest request = decode(FsMessages.pathRequest(), args);
            String repeat = operations.answerFor(request.op());
            if (repeat != null) return repeat;

            CgPath restored = service.restore(actor, request.path());
            hub.noteWritten(restored, null);
            audit.record(actor, WorkspaceOperation.WRITE, restored);
            operations.record(request.op(), restored.toString());
            return restored.toString();
        }));

        registry.register(FsMethods.RENAME, (args, respond) -> mutate(respond, () -> {
            FsMessages.MoveRequest request = decode(FsMessages.moveRequest(), args);
            CgPath from = CgPath.parse(request.from());
            CgPath to = CgPath.parse(request.to());
            String repeat = operations.answerFor(request.op());
            if (repeat != null) return repeat;

            requireValidName(to);
            service.rename(actor, from, to, request.overwrite());
            String etag = service.stat(actor, to).etag();
            // STATED, never inferred. A rename the server performed is a fact, and the delete-and-create
            // pairing is a heuristic for the ones that happen outside.
            hub.noteRenamed(from, to, etag);
            audit.record(actor, WorkspaceOperation.WRITE, to);
            operations.record(request.op(), etag);
            return etag;
        }));

        /*
         * COPY IS A SERVER VERB because a copy's bytes are already on the server. The client did it as
         * a read and a create -- a 40 MB download followed by a 40 MB upload -- and could not express a
         * directory at all, since a read of one is an error.
         */
        registry.register(FsMethods.COPY, (args, respond) -> mutate(respond, () -> {
            FsMessages.MoveRequest request = decode(FsMessages.moveRequest(), args);
            CgPath from = CgPath.parse(request.from());
            CgPath to = CgPath.parse(request.to());
            String repeat = operations.answerFor(request.op());
            if (repeat != null) return repeat;

            requireValidName(to);
            service.copy(actor, from, to, request.overwrite());
            String etag = service.stat(actor, to).etag();
            hub.noteWritten(to, etag);
            audit.record(actor, WorkspaceOperation.WRITE, to);
            operations.record(request.op(), etag);
            return etag;
        }));

        /*
         * WHAT MAKES THE TRASH SOMETHING YOU CAN LOOK IN. `delete` answered a trash id and `restore`
         * redeemed one, so the only recoverable deletions were the ones a client still held a receipt
         * for -- which is this session's, and only until it forgot. Everything deleted before that was
         * on disk, kept, and unreachable.
         */
        registry.register(FsMethods.TRASH_LIST, (args, respond) -> guard(respond, () -> {
            FsMessages.PathRequest request = decode(FsMessages.pathRequest(), args);
            String project = CgPath.parse(request.path()).project();
            List<FsMessages.TrashEntry> out = new ArrayList<>();
            for (WorkspaceTrash.Entry entry : service.trashList(actor, project)) {
                out.add(new FsMessages.TrashEntry(entry.id(), entry.originalPath().toString(),
                        entry.actor(), entry.deletedAt(), entry.directory(), entry.size()));
            }
            return encode(FsMessages.trashListResponse(), new FsMessages.TrashListResponse(out));
        }));

        registry.register(FsMethods.PURGE, (args, respond) -> mutate(respond, () -> {
            FsMessages.PathRequest request = decode(FsMessages.pathRequest(), args);
            String repeat = operations.answerFor(request.op());
            if (repeat != null) return repeat;

            // AUTHORISED AGAINST WHERE IT CAME FROM, which is what `service.purge` does -- an id says
            // nothing about permissions, and destroying somebody's deleted file is a write where it
            // used to live.
            if (!service.purge(actor, request.path())) {
                throw new CgFileSystemException(CgFileError.FILE_NOT_FOUND,
                        "no such trash entry: " + request.path());
            }
            audit.record(actor, WorkspaceOperation.WRITE, null);
            operations.record(request.op(), request.path());
            return request.path();
        }));

        registry.register(FsMethods.WATCH, (args, respond) -> guard(respond, () -> {
            FsMessages.PathRequest request = decode(FsMessages.pathRequest(), args);
            CgPath path = CgPath.parse(request.path());
            // AUTHORISED WITH A STAT. `service.read` reads the whole file to make the same check, so
            // subscribing to a 40 MB log allocated 40 MB and threw it away.
            service.stat(actor, path);
            hub.watch(peer, actor, path, "recursive".equals(request.op()));
            service.presence().opened(actor, path);
            return null;
        }));

        registry.register(FsMethods.UNWATCH, (args, respond) -> guard(respond, () -> {
            CgPath path = CgPath.parse(decode(FsMessages.pathRequest(), args).path());
            hub.unwatch(peer, path);
            service.presence().closed(actor, path);
            return null;
        }));

        // DIRTINESS IS THE CLIENT'S TO REPORT. The server cannot observe it -- a file with no writes
        // coming is equally one nobody has touched and one somebody has been typing in for ten minutes.
        registry.register(FsMethods.EDITING, (args, respond) -> guard(respond, () -> {
            FsMessages.PathRequest request = decode(FsMessages.pathRequest(), args);
            CgPath path = CgPath.parse(request.path());
            // AUTHORISED AS A READ, which is what having it open already required. Saying you are
            // editing something you may not read would advertise that it exists.
            service.stat(actor, path);
            service.presence().setEditing(actor, path, "dirty".equals(request.op()));
            return null;
        }));

        registry.register(FsMethods.CAPABILITIES, (args, respond) ->
                answer(respond, FsMessages.capabilitiesNotification(), capabilities()));
    }

    /**
     * What this peer should be told about who else is here — or <b>null when it already knows</b>.
     *
     * <p>Scoped to the paths <em>this</em> actor has open, which is the only scope that is both useful
     * and safe: a peer hears about a file the moment somebody else opens one it is holding, and hears
     * nothing about files it never asked for. Telling everyone about everything would leak which files
     * exist to somebody who never asked — the rule the change fan-out already follows.</p>
     *
     * <p>Two gates, and both are needed. The version counter makes the common tick free. The equality
     * check makes it <b>exact</b>: presence is global, so anybody's change bumps the version for
     * everybody, and without it every peer would be re-sent its own unchanged picture on every one of
     * somebody else's keystroke-driven transitions.</p>
     */
    @Nullable
    public FsMessages.PresenceNotification presenceFor() {
        int version = service.presence().version();
        if (version == lastPresenceVersion) return null;
        lastPresenceVersion = version;

        List<FsMessages.PresenceEntry> entries = new ArrayList<>();
        for (CgPath path : service.presence().pathsOpenBy(actor)) {
            List<String> editing = service.presence().whoElseIsEditing(actor, path);
            // OTHERS ONLY. The client's map is "who else", so filtering here is what makes its own
            // accessor mean what it is named -- and a peer listing itself would show you your own name
            // as somebody to worry about.
            for (String who : service.presence().whoElseHasOpen(actor, path)) {
                entries.add(new FsMessages.PresenceEntry(path.toString(), who, editing.contains(who)));
            }
        }
        if (entries.equals(lastPresence)) return null;
        lastPresence = entries;
        return new FsMessages.PresenceNotification(entries);
    }

    /** @see #presenceFor */
    private int lastPresenceVersion = -1;

    /** @see #presenceFor */
    @Nullable
    private List<FsMessages.PresenceEntry> lastPresence;

    /** What this peer may do, per project. A HINT — the server re-checks every operation regardless. */
    public FsMessages.CapabilitiesNotification capabilities() {
        List<FsMessages.ProjectCapability> out = new ArrayList<>();
        for (WorkspaceService.ProjectCapability capability : service.capabilities(actor)) {
            out.add(new FsMessages.ProjectCapability(capability.project(),
                    capability.mayRead(), capability.mayWrite()));
        }
        return new FsMessages.CapabilitiesNotification(out);
    }

    /** The greeting: this server's version and its own facts. @see FsHello */
    public FsHello hello() {
        boolean caseSensitive = service.caseSensitive();
        return new FsHello(FsHello.VERSION, caseSensitive, FsHello.WINDOWS_RESERVED, 255,
                FsHello.DEFAULT_SERVICES_TIER, FsHello.DEFAULT_READ_ONLY_TIER,
                WorkspaceService.MAX_FILE_BYTES);
    }

    /** This peer's editing flag, set from what the client reports about its own document. */
    public void setEditing(CgPath path, boolean dirty) {
        service.presence().setEditing(actor, path, dirty);
    }

    /** What this peer should be told about, this tick. Empty when there is nothing. */
    public List<FsMessages.FileChange> changesFor(Map<Object, List<FsMessages.FileChange>> byPeer) {
        List<FsMessages.FileChange> mine = byPeer.get(peer);
        return mine == null ? List.of() : mine;
    }

    /** Everything this peer had open goes when the connection does. */
    public void close() {
        hub.forget(peer);
        service.presence().left(actor);
        transfers.clear();
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    /** Where a handler is registered. The connection's {@code onRequest}, in production. */
    public interface Registrar<T> {
        void register(String method, Call.Handler<T> handler);
    }

    @FunctionalInterface
    private interface Answering<T> {
        @Nullable
        StateMap<T> answer();
    }

    @FunctionalInterface
    private interface Mutating {
        String perform();
    }

    private <A> StateMap<T> encode(Codec<A> codec, A value) {
        return new StateMap<>(ops, codec.encode(ops, value));
    }

    private <A> A decode(Codec<A> codec, StateMap<T> args) {
        return codec.decode(ops, args.encode());
    }

    private <A> void answer(Call.Responder<T> respond, Codec<A> codec, A value) {
        respond.ok(encode(codec, value));
    }

    /**
     * Runs a read and turns every failure into a coded one.
     *
     * <p>Every failure carries a code, and a conflict carries the etag the file actually holds — which
     * is the only actionable thing in the only failure that needs action.</p>
     */
    private void guard(Call.Responder<T> respond, Answering<T> work) {
        try {
            respond.ok(work.answer());
        } catch (WorkspaceConflictException conflict) {
            respond.fail(FsError.CONFLICT + " " + conflict.getActualEtag());
        } catch (CgFileSystemException failed) {
            respond.fail(FsError.of(failed.getError(), failed.getMessage()).code()
                    + " " + failed.getMessage());
        } catch (RuntimeException unexpected) {
            respond.fail(FsError.FAILED + " " + unexpected);
        }
    }

    /**
     * The same, for a mutation: rate-limited, audited on refusal, and answering an etag.
     *
     * <p>The limit is checked <b>before</b> the work, so a refusal costs nothing, and a refusal is
     * audited so a flood leaves a record of itself rather than only of what got through.</p>
     */
    private void mutate(Call.Responder<T> respond, Mutating work) {
        if (!audit.allow(actor)) {
            audit.refused(actor, WorkspaceOperation.WRITE, null, "rate limit");
            respond.fail(FsError.RATE_LIMITED + " too many changes; slow down");
            return;
        }
        guard(respond, () -> encode(FsMessages.etagResponse(),
                new FsMessages.EtagResponse(work.perform())));
    }

    /**
     * Refuses a name the host itself would refuse — <b>before</b> the round trip has any effect.
     *
     * <p>The client asks the same question from {@link FsHello} when the dialog is open, so a person
     * sees the refusal as they type. This is the authority: a client is a hint and never a gate.</p>
     */
    private void requireValidName(CgPath path) {
        String name = path.name();
        if (name != null && !name.isEmpty() && !hello().isValidName(name)) {
            audit.refused(actor, WorkspaceOperation.WRITE, path, "invalid name");
            throw new CgFileSystemException(CgFileError.INVALID_PATH,
                    "'" + name + "' is not a name this host will accept");
        }
    }
}
