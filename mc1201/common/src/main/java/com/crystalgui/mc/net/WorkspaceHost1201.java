package com.crystalgui.mc.net;

import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.fs.server.WorkspaceOperation;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The server's workspace on MC 1.20.x: where it lives, who may write to it, and who is asking.
 *
 * <p>Everything else -- per-peer bindings, the change and presence fan-out, the poll cadence, the seed
 * -- is {@link WorkspaceHost} in {@code core/}.</p>
 *
 * <p>The {@link MinecraftServer} is PUSHED by each loader's start/stop events rather than pulled through
 * an SPI: {@code ServerLifecycleHooks} is Forge's and Fabric captures it from an event, so a pull would
 * need a per-loader implementation to answer one field.</p>
 */
public final class WorkspaceHost1201 {

    private static final String PROJECT_ID = "workspace";
    /**
     * The one project a server serves, until W3b makes {@code projects/} a listing rather than a
     * constant. The leaf keeps the name the directory already had, so the move is one segment deep.
     */
    private static final String PROJECT_DIR = "workspace";

    private WorkspaceHost1201() {}

    private static boolean registered;
    private static WorkspaceHost host;
    private static volatile MinecraftServer currentServer;

    /** Called by each loader when its server starts and stops. */
    public static void setServer(@Nullable MinecraftServer server) {
        currentServer = server;
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        host = new WorkspaceHost(PROJECT_ID, "Workspace", new Host1201());
        host.contribute();
    }

    /** Called once a server tick by each loader. */
    public static void tick(float deltaSeconds) {
        if (host != null) host.tick(deltaSeconds);
    }

    /** Drops a peer's bindings. Wired to {@code Connections.onPeerClosed}. */
    public static void forget(Object peer) {
        if (host != null) host.forget(peer);
    }

    public static int boundPeers() {
        return host == null ? 0 : host.boundPeerCount();
    }

    public static synchronized void reset() {
        if (host != null) host.reset();
        host = null;
        registered = false;
        currentServer = null;
    }

    private static final class Host1201 implements WorkspaceHost.Host {

        /**
         * <b>The world's own directory in single-player, the server's on a dedicated one</b> — the
         * WORLD and server scopes (D25-D28).
         *
         * <p>{@code getServerDirectory} would answer with the data directory, which
         * {@code IntegratedServer} overrides to {@code minecraft.gameDirectory}: every world on one
         * installation shared a workspace, and deleting a save left its projects behind.</p>
         */
        @Override
        @Nullable
        public Path root() {
            MinecraftServer server = currentServer;
            if (server == null) return null;
            Path base = server.isDedicatedServer()
                    ? server.getServerDirectory().toPath()
                    : server.getWorldPath(LevelResource.ROOT);
            return base == null ? null : StorageLayout.projectsIn(base).resolve(PROJECT_DIR);
        }

        @Override
        public WorkspacePermission permission() {
            return new OperatorsMayWrite();
        }

        /**
         * Live scripting in single-player, granted per player on a dedicated server: a script runs on
         * the server, so who may write one is the server's call.
         */
        @Override
        public WorkspaceService.ScriptingPolicy scripting() {
            MinecraftServer server = currentServer;
            return server != null && !server.isDedicatedServer()
                    ? WorkspaceService.ScriptingPolicy.LIVE
                    : WorkspaceService.ScriptingPolicy.AUTHORIZED_ONLY;
        }

        @Override
        public WorkspaceActor actorFor(Object peer) {
            if (peer instanceof Peer1201) {
                final String name = ((Peer1201) peer).name();
                return () -> name;
            }
            if (peer instanceof ServerPlayer) {
                final String name = ((ServerPlayer) peer).getGameProfile().getName();
                return () -> name;
            }
            final String fallback = String.valueOf(peer);
            return () -> fallback;
        }
    }

    /** Everyone reads; operators write. In single-player the one player is an operator. */
    private static final class OperatorsMayWrite implements WorkspacePermission {

        @Override
        public boolean allows(WorkspaceActor actor, WorkspaceProject project, CgPath path,
                              WorkspaceOperation operation) {
            if (operation == WorkspaceOperation.READ) return true;

            MinecraftServer server = currentServer;
            if (server == null || server.getPlayerList() == null) return false;

            // The owner of a single-player world, whatever the cheats flag says: cheats gate COMMANDS
            // and have nothing to say about editing files in your own save. On 1.7.10 the op check
            // folded the flag in, so the host of a fresh world could list the workspace and not write
            // to it -- a correct-looking refusal with no way to tell it from a real one.
            GameProfile owner = server.getSingleplayerProfile();
            if (owner != null && owner.getName() != null && owner.getName().equalsIgnoreCase(actor.id())) {
                return true;
            }

            ServerPlayer player = server.getPlayerList().getPlayerByName(actor.id());
            // Not connected any more. Refusing strands nobody: a player who has left has nothing in
            // flight that a write would complete.
            return player != null && server.getPlayerList().isOp(player.getGameProfile());
        }
    }
}
