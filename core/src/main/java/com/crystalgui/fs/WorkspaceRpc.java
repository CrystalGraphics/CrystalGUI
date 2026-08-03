package com.crystalgui.fs;

import com.crystalgui.net.RpcRegistry;
import com.crystalgui.serialization.StateMap;

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

    /**
     * @param actor who every request from this connection is attributed to. One session is one player, so
     *              the actor is bound here rather than travelling in each call — a client that could name
     *              its own actor could name somebody else's.
     */
    public WorkspaceRpc(WorkspaceService service, WorkspaceActor actor) {
        this.service = service;
        this.actor = actor;
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
            String etag = service.write(actor, path(args),
                    args.getBytes(WorkspaceProtocol.CONTENT), expected);
            respond.ok(new StateMap<T>(args.ops()).putString(WorkspaceProtocol.ETAG, etag));
        }));

        registry.register(WorkspaceProtocol.CREATE, (args, respond) -> guard(respond, () -> {
            String etag = service.create(actor, path(args), args.getBytes(WorkspaceProtocol.CONTENT));
            respond.ok(new StateMap<T>(args.ops()).putString(WorkspaceProtocol.ETAG, etag));
        }));

        registry.register(WorkspaceProtocol.MKDIR, (args, respond) -> guard(respond, () -> {
            service.mkdir(actor, path(args));
            respond.ok(null);
        }));

    }

    private static <T> CgPath path(StateMap<T> args) {
        return CgPath.parse(args.getString(WorkspaceProtocol.PATH, ""));
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
