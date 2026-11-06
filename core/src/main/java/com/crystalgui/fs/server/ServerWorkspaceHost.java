package com.crystalgui.fs.server;

import com.crystalgui.core.storage.CrystalGuiStorage;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.world.CgPlayers;

/**
 * {@link WorkspaceHost.Host} over the running server: where its workspace is, who is asking and what they may do,
 * all from CrystalGraphics' {@link CgServers} and {@link CgPlayers}, so no platform host answers for it.
 *
 * <pre>{@code
 * WorkspaceHost workspace = new WorkspaceHost(WorkspaceHost.DEFAULT_PROJECT_ID, "Workspace", ServerWorkspaceHost.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>The root is {@code <server directory>/crystalgui/workspace}: the world's own directory in single-player, the
 *       server's on a dedicated one. The integrated server is a server, so single-player is the remote case with a
 *       short wire.</li>
 *   <li>Scripts run live in single-player, where the player owns the machine, and only when authorised on a
 *       dedicated server.</li>
 * </ul>
 */
public final class ServerWorkspaceHost implements WorkspaceHost.Host {

    /** The one answer. */
    public static final ServerWorkspaceHost INSTANCE = new ServerWorkspaceHost();

    /** The one project a server serves; the leaf keeps the directory's old name. */
    private static final String PROJECT_DIR = "workspace";

    private static final WorkspacePermission PERMISSION = new OperatorsMayWrite(new WorkspaceRoles() {
        @Override
        public boolean isOwner(String actorId) {
            return CgPlayers.isOwner(actorId);
        }

        @Override
        public boolean isOperator(String actorId) {
            return CgPlayers.isOperator(actorId);
        }
    });

    private ServerWorkspaceHost() {
    }

    /** Null until a world loads, which is why the workspace asks per connection rather than once. */
    @Override
    @Nullable
    public Path root() {
        Path base = CgServers.directory();
        return base == null ? null : CrystalGuiStorage.projectsIn(base).resolve(PROJECT_DIR);
    }

    @Override
    public WorkspacePermission permission() {
        return PERMISSION;
    }

    @Override
    public WorkspaceService.ScriptingPolicy scripting() {
        return CgServers.running() && !CgServers.dedicated()
                ? WorkspaceService.ScriptingPolicy.LIVE
                : WorkspaceService.ScriptingPolicy.AUTHORIZED_ONLY;
    }

    /** The name, which is what {@link OperatorsMayWrite} matches against the live player list and a log line reads. */
    @Override
    public WorkspaceActor actorFor(Object peer) {
        final String name = peer instanceof CgPeer ? ((CgPeer) peer).name() : String.valueOf(peer);
        return () -> name;
    }
}
