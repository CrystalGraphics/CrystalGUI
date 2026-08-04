package com.crystalgui.fs;

import com.crystalgui.net.RpcRegistry;
import com.crystalgui.serialization.StateMap;

import java.util.List;

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
     * <p>Both {@code RpcRegistry::register} and {@code ServerUiSession::onCall} satisfy this, so binding
     * the workspace does not depend on which of them a host happens to hold — and a test can install onto
     * a bare registry without standing up a session.</p>
     */
    @FunctionalInterface
    public interface Registrar<T> {
        void register(String method, RpcRegistry.Handler<T> handler);
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
            WorkspaceService.FileContent content = service.read(actor, path(args));
            respond.ok(new StateMap<T>(args.ops())
                    .putBytes(WorkspaceProtocol.CONTENT, content.content())
                    .putString(WorkspaceProtocol.ETAG, content.etag()));
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
            service.delete(actor, target, args.getBool(WorkspaceProtocol.RECURSIVE, false),
                    expectedEtag(args));
            // The path is gone, so there is nothing left to poll and no etag to remember. Without this the
            // watcher keeps stat-ing a file that no longer exists and reports its own caller's deletion
            // back to it as an external change.
            watcher.unwatch(target);
            respond.ok(null);
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

    /** The watcher, for a host that wants to seed or inspect it directly. */
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
    private static <T> void guard(RpcRegistry.Responder<T> respond, Runnable body) {
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
