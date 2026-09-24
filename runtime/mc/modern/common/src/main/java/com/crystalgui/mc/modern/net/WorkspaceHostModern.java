package com.crystalgui.mc.modern.net;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.fs.server.OperatorsMayWrite;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.fs.server.WorkspaceRoles;
import com.crystalgui.fs.server.WorkspaceService;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.16 {
import net.minecraft.world.level.storage.LevelResource;
//?}
//? if >=1.21.9 {
/*import net.minecraft.server.players.NameAndId;
*///?}

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
public final class WorkspaceHostModern {

    /**
     * The one project a server serves, until W3b makes {@code projects/} a listing rather than a
     * constant. The leaf keeps the name the directory already had, so the move is one segment deep.
     */
    private static final String PROJECT_DIR = "workspace";

    private WorkspaceHostModern() {}

    private static boolean registered;
    private static WorkspaceHost host;
    private static volatile MinecraftServer currentServer;

    /** The directory the running world is saved in. */
    public static Path worldRoot(MinecraftServer server) {
        // LevelResource is 1.16's; before it the save is a folder of the storage source's base.
        //? if >=1.16 {
        return server.getWorldPath(LevelResource.ROOT);
        //?} else {
        /*return server.getStorageSource().getBaseDir().resolve(server.getLevelIdName());
        *///?}
    }

    /** Called by each loader when its server starts and stops. */
    public static void setServer(@Nullable MinecraftServer server) {
        currentServer = server;
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        host = new WorkspaceHost(WorkspaceHost.DEFAULT_PROJECT_ID, "Workspace", new Host());
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

    private static final class Host implements WorkspaceHost.Host {

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
            // getServerDirectory() is a File before 1.21 and a Path from it; toString is the one spelling
            // both have, so this line serves every node without a directive.
            Path base = server.isDedicatedServer()
                    ? Paths.get(server.getServerDirectory().toString())
                    : worldRoot(server);
            return base == null ? null : StorageLayout.projectsIn(base).resolve(PROJECT_DIR);
        }

        @Override
        public WorkspacePermission permission() {
            return new OperatorsMayWrite(new McRoles());
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
            if (peer instanceof Peer) {
                final String name = ((Peer) peer).name();
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

    /** The two facts a write decision needs that only Minecraft knows. @see OperatorsMayWrite */
    private static final class McRoles implements WorkspaceRoles {

        @Override
        public boolean isOwner(String actorId) {
            MinecraftServer server = currentServer;
            if (server == null) return false;
            // 1.19 made the single-player owner a profile; before it is just a name.
            //? if >=1.21.9 {
            /*GameProfile owner = server.getSingleplayerProfile();
            return owner != null && owner.name() != null && owner.name().equalsIgnoreCase(actorId);
            *///?} elif >=1.19 {
            GameProfile owner = server.getSingleplayerProfile();
            return owner != null && owner.getName() != null && owner.getName().equalsIgnoreCase(actorId);
            //?} else {
            /*String owner = server.getSingleplayerName();
            return owner != null && owner.equalsIgnoreCase(actorId);
            *///?}
        }

        @Override
        public boolean isOperator(String actorId) {
            MinecraftServer server = currentServer;
            if (server == null || server.getPlayerList() == null) return false;
            ServerPlayer player = server.getPlayerList().getPlayerByName(actorId);
            // 1.21.9 asks by NameAndId.
            //? if >=1.21.9 {
            /*return player != null && server.getPlayerList().isOp(new NameAndId(player.getGameProfile()));
            *///?} else {
            return player != null && server.getPlayerList().isOp(player.getGameProfile());
            //?}
        }
    }
}
