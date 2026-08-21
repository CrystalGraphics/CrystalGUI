package com.crystalgui.fs;

import com.crystalgui.net.protocol.Call;
import com.crystalgui.serialization.StateMap;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds a {@link WorkspaceService} onto the UI channel's RPC.
 *
 * <p>The server half of the protocol. Registers one handler per method in {@link WorkspaceProtocol} and
 * does nothing else — every decision about who may do what, and whether a write is stale, was already
 * made by the service. This layer only moves values.</p>
 *
 * <h3>Every failure becomes a code, never a stack trace</h3>
 * <p>A {@link CgFileSystemException} answers with its {@link CgFileError} name; a
 * {@link WorkspaceConflictException} answers {@code CONFLICT} plus the etag the file actually has. Both
 * go through {@code respond.fail}, so a client branches on a value rather than matching message text —
 * and an unexpected exception is reported as {@code UNKNOWN} rather than leaking a server-side message
 * that may name a directory.</p>
 *
 * @param <T> the wire format
 */
public final class WorkspaceRpc<T> {

    private final WorkspaceService service;
    private final WorkspaceActor actor;
    private final WorkspaceWatcher watcher;

    /**
     * @param actor who every request from this connection is attributed to. One session is one player, so
     *              the actor is bound here rather than travelling in each call — a client that could name
     *              its own actor could name somebody else's.
     */
    public WorkspaceRpc(WorkspaceService service, WorkspaceActor actor) {
        this.service = service;
        this.actor = actor;
        this.watcher = new WorkspaceWatcher(service);
    }

    /**
     * Sends a server-initiated call. {@code ServerUiSession::call} satisfies this.
     *
     * <p>Separate from {@link Registrar} because pushing is a different capability from answering, and a
     * host that only wants to serve requests should not have to supply one.</p>
     */
    @FunctionalInterface
    public interface Notifier<T> {
        void notify(String method, StateMap<T> args);
    }

    /**
     * Somewhere handlers can be registered.
     *
     * <p>Both {@code MessageRouter::onRequest} and {@code ServerUiSession::onCall} satisfy this, so binding
     * the workspace does not depend on which of them a host happens to hold — and a test can install onto
     * a bare registry without standing up a session.</p>
     */
    @FunctionalInterface
    public interface Registrar<T> {
        void register(String method, Call.Handler<T> handler);
    }

    /** Registers every method. */
    public void installOn(Registrar<T> registry) {
        registry.register(WorkspaceProtocol.PROJECTS, (args, respond) -> guard(respond, () -> {
            StateMap<T> out = new StateMap<>(args.ops());
            out.putList(WorkspaceProtocol.PROJECT_LIST, service.projects(actor), (entry, project) -> {
                entry.putString(WorkspaceProtocol.ID, project.id());
                entry.putString(WorkspaceProtocol.DISPLAY_NAME, project.displayName());
            });
            respond.ok(out);
        }));

        registry.register(WorkspaceProtocol.CAPABILITIES, (args, respond) -> guard(respond, () -> {
            StateMap<T> out = new StateMap<>(args.ops());
            fillCapabilities(out);
            respond.ok(out);
        }));

        registry.register(WorkspaceProtocol.MANIFEST, (args, respond) -> guard(respond, () -> {
            CgPath directory = path(args);
            StateMap<T> out = new StateMap<>(args.ops());
            out.putList(WorkspaceProtocol.ENTRIES, service.manifest(actor, directory), (entry, file) -> {
                entry.putString(WorkspaceProtocol.NAME, file.name());
                entry.putBool(WorkspaceProtocol.DIRECTORY, file.isDirectory());
                entry.putDouble(WorkspaceProtocol.SIZE, file.size());
                entry.putDouble(WorkspaceProtocol.MTIME, file.mtime());
                entry.putString(WorkspaceProtocol.ETAG, file.etag());
            });
            respond.ok(out);
        }));

        registry.register(WorkspaceProtocol.READ, (args, respond) -> guard(respond, () -> {
            CgPath target = path(args);
            // STAT FIRST. It is what enforces the cap before an allocation, and what decides inline
            // versus chunked without reading a byte the caller may not be able to receive.
            CgFileEntry entry = service.stat(actor, target);
            if (entry.size() > WorkspaceService.MAX_FILE_BYTES) {
                throw CgFileSystemException.tooLarge(target, entry.size(), WorkspaceService.MAX_FILE_BYTES);
            }

            // CONDITIONAL. If the client already holds this revision, say so and send nothing --
            // and note this is checked against the STAT, so it costs no read at all.
            String known = args.has(WorkspaceProtocol.IF_NONE_MATCH)
                    ? args.getString(WorkspaceProtocol.IF_NONE_MATCH, null) : null;
            if (known != null && known.equals(entry.etag())) {
                respond.ok(new StateMap<T>(args.ops())
                        .putBool(WorkspaceProtocol.UNCHANGED, true)
                        .putString(WorkspaceProtocol.ETAG, entry.etag()));
                return;
            }

            WorkspaceService.FileContent content = service.read(actor, target);
            if (content.content().length <= INLINE_MAX_BYTES) {
                respond.ok(new StateMap<T>(args.ops())
                        .putBytes(WorkspaceProtocol.CONTENT, content.content())
                        .putString(WorkspaceProtocol.ETAG, content.etag()));
                return;
            }

            // Too big for one message -- not as a policy but as a fact: the transport bounds a single
            // reassembled message, so a large file CANNOT arrive whole however patient anyone is.
            String id = openTransfer(content);
            respond.ok(new StateMap<T>(args.ops())
                    .putBool(WorkspaceProtocol.CHUNKED, true)
                    .putString(WorkspaceProtocol.TRANSFER, id)
                    .putInt(WorkspaceProtocol.SIZE, content.content().length)
                    .putString(WorkspaceProtocol.ETAG, content.etag()));
        }));

        registry.register(WorkspaceProtocol.WRITE_DELTA, (args, respond) -> guard(respond, () -> {
            CgPath target = path(args);
            // An ABSENT etag means unconditional, exactly as fs.write reads it -- but a delta with no
            // base revision is nonsense rather than a shortcut, so this one insists on having it.
            String expected = args.has(WorkspaceProtocol.ETAG)
                    ? args.getString(WorkspaceProtocol.ETAG, null) : null;
            if (expected == null) {
                throw new CgFileSystemException(CgFileError.INVALID_PATH,
                        "fs.writeDelta needs the etag its changes are against: " + target);
            }

            // Read, apply, write. service.write's own re-stat is still what guarantees the file did not
            // move; this read exists to have something to apply the changes TO, and a race between the
            // two is caught there rather than duplicated here.
            WorkspaceService.FileContent base = service.read(actor, target);
            if (!expected.equals(base.etag())) {
                throw new WorkspaceConflictException(target, expected, base.etag());
            }

            Rope document = Rope.of(new String(base.content(), StandardCharsets.UTF_8));
            List<Change> changes = new ArrayList<>();
            for (StateMap<T> change : args.getList(WorkspaceProtocol.CHANGES, e -> e)) {
                changes.add(new Change(
                        change.getInt(WorkspaceProtocol.FROM, 0),
                        change.getInt(WorkspaceProtocol.TO, 0),
                        change.getString(WorkspaceProtocol.INSERT, "")));
            }
            Rope updated = ChangeSet.of(document.length(), changes).apply(document);

            String etag = service.write(actor, target,
                    updated.toString().getBytes(StandardCharsets.UTF_8), expected);
            // This side already knows -- see WRITE. Otherwise the next poll reports the client's own
            // change back to it as if somebody else had made it.
            watcher.noteWritten(target, etag);
            respond.ok(new StateMap<T>(args.ops()).putString(WorkspaceProtocol.ETAG, etag));
        }));

        registry.register(WorkspaceProtocol.READ_CHUNK, (args, respond) -> guard(respond, () -> {
            String id = args.getString(WorkspaceProtocol.TRANSFER, "");
            Transfer transfer = transfers.get(id);
            if (transfer == null) {
                // Expired, completed, or invented. All three are the same answer -- saying which would
                // let a client probe for what other transfers exist.
                throw new CgFileSystemException(CgFileError.FILE_NOT_FOUND, "no such transfer: " + id);
            }
            int offset = args.getInt(WorkspaceProtocol.OFFSET, 0);
            int asked = args.getInt(WorkspaceProtocol.LENGTH, CHUNK_BYTES);
            if (offset < 0 || offset > transfer.content.length) {
                throw new CgFileSystemException(CgFileError.INVALID_PATH,
                        "offset " + offset + " is outside a " + transfer.content.length + " byte transfer");
            }
            int length = Math.min(Math.min(asked <= 0 ? CHUNK_BYTES : asked, CHUNK_BYTES),
                    transfer.content.length - offset);
            byte[] slice = new byte[length];
            System.arraycopy(transfer.content, offset, slice, 0, length);
            boolean eof = offset + length >= transfer.content.length;
            // Released on the chunk that finishes it, so a completed transfer stops costing memory
            // immediately rather than at the next sweep.
            if (eof) transfers.remove(id);
            else transfer.touchedAt = System.currentTimeMillis();
            respond.ok(new StateMap<T>(args.ops())
                    .putBytes(WorkspaceProtocol.CONTENT, slice)
                    .putInt(WorkspaceProtocol.OFFSET, offset)
                    .putBool(WorkspaceProtocol.EOF, eof));
        }));

        registry.register(WorkspaceProtocol.WRITE, (args, respond) -> guard(respond, () -> {
            // An ABSENT etag means "unconditional"; an empty string would be a real etag that never
            // matches, so the two must not collapse into one another.
            String expected = args.has(WorkspaceProtocol.ETAG)
                    ? args.getString(WorkspaceProtocol.ETAG, null) : null;
            CgPath target = path(args);
            String etag = service.write(actor, target, args.getBytes(WorkspaceProtocol.CONTENT), expected);
            // This side already knows -- see noteWritten. Otherwise the next poll reports the client's
            // own save back to it as somebody else's change.
            watcher.noteWritten(target, etag);
            respond.ok(new StateMap<T>(args.ops()).putString(WorkspaceProtocol.ETAG, etag));
        }));

        registry.register(WorkspaceProtocol.CREATE, (args, respond) -> guard(respond, () -> {
            CgPath target = path(args);
            String etag = service.create(actor, target, args.getBytes(WorkspaceProtocol.CONTENT));
            watcher.noteWritten(target, etag);
            respond.ok(new StateMap<T>(args.ops()).putString(WorkspaceProtocol.ETAG, etag));
        }));

        registry.register(WorkspaceProtocol.MKDIR, (args, respond) -> guard(respond, () -> {
            service.mkdir(actor, path(args));
            respond.ok(null);
        }));

        registry.register(WorkspaceProtocol.DELETE, (args, respond) -> guard(respond, () -> {
            CgPath target = path(args);
            String trashId = service.deleteToTrash(actor, target,
                    args.getBool(WorkspaceProtocol.RECURSIVE, false), expectedEtag(args));
            // The path is gone, so there is nothing left to poll and no etag to remember. Without this the
            // watcher keeps stat-ing a file that no longer exists and reports its own caller's deletion
            // back to it as an external change.
            watcher.unwatch(target);
            // The trash id rides back on the DELETE response so an undo needs no second call -- and so a
            // client that never undoes simply ignores a field.
            StateMap<T> result = new StateMap<>(args.ops());
            if (trashId != null) result.putString(WorkspaceProtocol.TRASH_ID, trashId);
            respond.ok(result);
        }));

        registry.register(WorkspaceProtocol.RESTORE, (args, respond) -> guard(respond, () -> {
            CgPath restored = service.restore(actor, args.getString(WorkspaceProtocol.TRASH_ID, ""));
            respond.ok(new StateMap<T>(args.ops())
                    .putString(WorkspaceProtocol.PATH, restored.toString()));
        }));

        registry.register(WorkspaceProtocol.PURGE, (args, respond) -> guard(respond, () -> {
            service.purge(actor, args.getString(WorkspaceProtocol.TRASH_ID, ""));
            respond.ok(null);
        }));

        registry.register(WorkspaceProtocol.TRASH_LIST, (args, respond) -> guard(respond, () -> {
            var entries = service.trashList(actor,
                    CgPath.parse(args.getString(WorkspaceProtocol.PATH, "")).project());
            respond.ok(new StateMap<T>(args.ops()).putList(WorkspaceProtocol.ENTRIES, entries,
                    (out, entry) -> out
                            .putString(WorkspaceProtocol.TRASH_ID, entry.id())
                            .putString(WorkspaceProtocol.PATH, entry.originalPath().toString())
                            .putString(WorkspaceProtocol.ACTOR, entry.actor())
                            .putBool(WorkspaceProtocol.DIRECTORY, entry.directory())
                            .putInt(WorkspaceProtocol.SIZE, (int) entry.size())));
        }));

        registry.register(WorkspaceProtocol.RENAME, (args, respond) -> guard(respond, () -> {
            CgPath from = CgPath.parse(args.getString(WorkspaceProtocol.FROM, ""));
            CgPath to = CgPath.parse(args.getString(WorkspaceProtocol.TO, ""));
            service.rename(actor, from, to, args.getBool(WorkspaceProtocol.OVERWRITE, false),
                    expectedEtag(args));
            // Same reasoning as the delete above, for the SOURCE only: the destination is a path this
            // client has never read, so it has no etag to seed a watch with and no business watching it
            // until it opens it.
            watcher.unwatch(from);
            respond.ok(null);
        }));

        registry.register(WorkspaceProtocol.WATCH, (args, respond) -> guard(respond, () -> {
            CgPath path = path(args);
            // AUTHORISED like any read. Watching a file you may not read would otherwise leak its
            // existence and every subsequent change to it.
            service.read(actor, path);
            watcher.watch(actor, path);
            respond.ok(null);
        }));

        registry.register(WorkspaceProtocol.UNWATCH, (args, respond) -> guard(respond, () -> {
            watcher.unwatch(path(args));
            respond.ok(null);
        }));

    }

    /**
     * Polls the watched files and pushes one {@code fs.changed} per file that moved.
     *
     * <p>Called by the host on whatever cadence suits it — a tick, a timer. Nothing here schedules
     * itself: {@code core/} has no clock it should be using, and how often a server can afford to stat is
     * the host's judgement, not the engine's.</p>
     *
     * @return how many notifications were sent
     */
    public int pollAndNotify(Notifier<T> notifier, com.crystalgui.serialization.DynamicOps<T> ops) {
        List<WorkspaceWatcher.Change> changes = watcher.poll(actor);
        for (WorkspaceWatcher.Change change : changes) {
            StateMap<T> args = new StateMap<T>(ops)
                    .putString(WorkspaceProtocol.PATH, change.path().toString())
                    .putString(WorkspaceProtocol.KIND, change.kind());
            if (change.etag() != null) args.putString(WorkspaceProtocol.ETAG, change.etag());
            notifier.notify(WorkspaceProtocol.CHANGED, args);
        }
        return changes.size();
    }

    // ── Chunked transfers (P6.1.10 D11) ─────────────────────────────────────

    /**
     * Below this, a read answers with the bytes inline and nothing else happens.
     *
     * <p>1 MB, chosen against the transport rather than by taste: one message must fit inside the
     * multiplexer's reassembly bound with room for every other stream sharing the connection. Above it,
     * a transfer is opened and the client pulls.</p>
     */
    public static final int INLINE_MAX_BYTES = 1024 * 1024;

    /**
     * The most one {@code fs.readChunk} will answer with — 256 KB, one credit window.
     *
     * <p>Matching the window means a chunk is in flight as a single burst rather than stalling halfway
     * for a {@code WINDOW_UPDATE}, and it is a ceiling rather than a fixed size, so a client asking for
     * less gets less and a client asking for more is clamped rather than refused.</p>
     */
    public static final int CHUNK_BYTES = 256 * 1024;

    /** How long an untouched transfer survives. A client that abandoned a download must not leak it. */
    private static final long TRANSFER_TTL_MILLIS = 60_000L;

    /**
     * How many a single actor may hold open at once.
     *
     * <p>Each one holds its file in memory, so this is the actual memory bound: {@code MAX_CONCURRENT ×
     * MAX_FILE_BYTES} worst case. It is deliberately small. <b>The better fix is a ranged read on
     * {@code CgFileSystem}</b>, which would let a transfer hold a path and an etag instead of bytes —
     * recorded here rather than in a plan, because this is the line that would change.</p>
     */
    private static final int MAX_CONCURRENT_TRANSFERS = 4;

    private static final class Transfer {
        final byte[] content;
        long touchedAt;

        Transfer(byte[] content, long touchedAt) {
            this.content = content;
            this.touchedAt = touchedAt;
        }
    }

    /** Insertion-ordered, so evicting the oldest is what happens when a client opens too many. */
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();

    private long nextTransferId;

    private String openTransfer(WorkspaceService.FileContent content) {
        long now = System.currentTimeMillis();
        transfers.entrySet().removeIf(entry -> now - entry.getValue().touchedAt > TRANSFER_TTL_MILLIS);
        while (transfers.size() >= MAX_CONCURRENT_TRANSFERS) {
            String oldest = transfers.keySet().iterator().next();
            transfers.remove(oldest);
        }
        String id = actor.id() + ":" + (++nextTransferId);
        transfers.put(id, new Transfer(content.content(), now));
        return id;
    }

    /** How many transfers this actor has open. Diagnostics, and what a leak would show up in. */
    public int openTransfers() {
        return transfers.size();
    }

    /** The watcher, for a host that wants to seed or inspect it directly. */
    /**
     * Tells the client its permissions have changed.
     *
     * <p><b>Pushed, because the client cannot know.</b> An operator being promoted mid-session is not
     * something a file listing reveals, and a client that only ever asked at connect would go on drawing
     * a greyed-out Delete for the rest of the session. The host calls this when whatever backs its
     * {@link WorkspacePermission} moves.</p>
     *
     * <p><b>The notifier must send a REQUEST</b> — {@code (method, args) -> connection.call(method, args,
     * null, null)}, exactly as {@link #pollAndNotify}'s callers do. {@code WorkspaceClient} registers its
     * inbound methods through {@link Registrar}, which is {@code onRequest}, and {@code MessageRouter}
     * keys request and notification handlers separately — so a {@code notify} here finds nobody home and
     * fails silently, which is precisely how it failed the first time it was written.</p>
     */
    public void notifyCapabilities(Notifier<T> notifier, com.crystalgui.serialization.DynamicOps<T> ops) {
        StateMap<T> out = new StateMap<>(ops);
        fillCapabilities(out);
        notifier.notify(WorkspaceProtocol.CAPABILITIES, out);
    }

    private void fillCapabilities(StateMap<T> out) {
        out.putList(WorkspaceProtocol.PROJECT_CAPABILITIES, service.capabilities(actor),
                (entry, capability) -> {
                    entry.putString(WorkspaceProtocol.PROJECT, capability.project());
                    entry.putBool(WorkspaceProtocol.MAY_READ, capability.mayRead());
                    entry.putBool(WorkspaceProtocol.MAY_WRITE, capability.mayWrite());
                });
    }

    public WorkspaceWatcher watcher() {
        return watcher;
    }

    private static <T> CgPath path(StateMap<T> args) {
        return CgPath.parse(args.getString(WorkspaceProtocol.PATH, ""));
    }

    /**
     * The etag a caller is acting on, or {@code null} for "unconditionally".
     *
     * <p><b>Absent and null must mean the same thing</b>, and the distinction is not academic: the codec
     * omits absent optionals rather than writing them null (descriptions are content-addressed, see
     * {@code UIDescriptionCodec}), so a client that simply does not know an etag sends no key at all. A
     * {@code getString(ETAG, "")} here would turn that into an empty-string expectation and refuse every
     * such call as a conflict against a file whose etag is never {@code ""}.</p>
     */
    private static <T> String expectedEtag(StateMap<T> args) {
        return args.has(WorkspaceProtocol.ETAG) ? args.getString(WorkspaceProtocol.ETAG, null) : null;
    }

    /**
     * Runs a handler, turning any failure into a coded refusal.
     *
     * <p>The catch-all is deliberate and is a security property, not tidiness: an unexpected exception's
     * message can easily contain a server-side absolute path, and {@code respond.fail} sends its argument
     * to the client.</p>
     */
    private static <T> void guard(Call.Responder<T> respond, Runnable body) {
        try {
            body.run();
        } catch (WorkspaceConflictException e) {
            respond.fail(WorkspaceProtocol.ERROR_CONFLICT + " " + e.getActualEtag());
        } catch (CgFileSystemException e) {
            respond.fail(e.getError().name());
        } catch (RuntimeException e) {
            respond.fail(CgFileError.UNKNOWN.name());
        }
    }
}
