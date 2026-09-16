package com.crystalgui.mc.v1710.net;

import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.fs.protocol.ScriptingMode;
import java.io.File;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgui.fs.server.OperatorsMayWrite;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceRoles;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * <b>The three questions {@link WorkspaceHost} cannot answer on its own</b> - where the workspace is,
 * who a peer is, and what they may do.
 *
 * <p>Minecraft's answers, and nothing else: every method below needs a {@code MinecraftServer} to
 * answer. The workspace itself - the per-connection bindings, the watcher, the poll cadence, the change
 * and presence fan-outs, the cleanup, the seeded README - is the engine's and is not repeated here.</p>
 *
 * <h3>Files live on the server's machine, and single-player is not a special case</h3>
 *
 * <p>The root is {@code <serverdir>/crystalgui/workspace}, which on a dedicated server is the server
 * directory and in single-player is the game directory, because <b>the integrated server is a
 * server</b>. So there is one code path and single-player is the remote case with a very short wire -
 * which is also what makes single-player a real test of the protocol rather than a bypass of it.</p>
 */
public final class CgUiWorkspaceHost {

    /**
     * Matches the client's handle on the project — and is {@code core}'s constant, not this host's.
     *
     * <p>It was {@code "minecraft.workspace"} here and {@code "workspace"} on 1.20.x, which is two
     * answers to a question a saved {@code Resource} keeps verbatim. @see WorkspaceHost#DEFAULT_PROJECT_ID
     */
    public static final String PROJECT_ID = WorkspaceHost.DEFAULT_PROJECT_ID;

    /**
     * The one project a server serves, until W3b makes {@code projects/} a listing rather than a
     * constant. The leaf keeps the name the directory already had, so the move is one segment deep.
     */
    private static final String PROJECT_DIR = "workspace";

    private static WorkspaceHost host;
    private static boolean registered;

    private CgUiWorkspaceHost() {
    }

    /**
     * Contributes the workspace to every connection, and starts the watcher poll.
     *
     * <p>Called from {@code CommonProxy.init()} — this is <b>server-side behaviour a dedicated server
     * needs and a client does not</b>, which is what {@code CommonProxy} is for.</p>
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        host = new WorkspaceHost(PROJECT_ID, "Workspace", new Host1710());
        host.contribute();
        FMLCommonHandler.instance().bus().register(new Handler());
    }

    /** Where the workspace is, who a peer is, and what they may do. Nothing else is asked of a host. */
    private static final class Host1710 implements WorkspaceHost.Host {

        /**
         * Null until a world loads, which is why {@code WorkspaceHost} asks per connection rather than
         * once: {@link MinecraftServer#getServer()} answers null at mod init and contribution happens
         * long before any world.
         *
         * <p><b>The world's own directory in single-player, the server's on a dedicated one</b> — the
         * WORLD and server scopes (D25-D28). {@code getFile} would answer with the data directory,
         * which {@code IntegratedServer} overrides to {@code mc.mcDataDir}: every world on one
         * installation shared a workspace, and deleting a save left its projects behind.</p>
         */
        @Override
        @Nullable
        public Path root() {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return null;
            File base;
            if (server.isDedicatedServer()) {
                base = server.getFile("");
            } else {
                WorldServer[] worlds = server.worldServers;
                // The overworld's save handler is where the world's own directory comes from. Null
                // between "a server exists" and "it has loaded a world", which is the state above.
                if (worlds == null || worlds.length == 0 || worlds[0] == null) return null;
                base = worlds[0].getSaveHandler().getWorldDirectory();
                if (base == null) return null;
            }
            return StorageLayout.projectsIn(base.toPath()).resolve(PROJECT_DIR);
        }

        @Override
        public WorkspacePermission permission() {
            return new OperatorsMayWrite(new McRoles());
        }

        /**
         * <b>Single-player runs scripts; a dedicated server does not.</b>
         *
         * <p>In single-player the integrated server IS the player's own machine, so a Run compiles and
         * executes in a JVM they already own — there is nobody to protect them from. On a dedicated
         * server the same command would be a live scripting environment inside every player's client,
         * reachable from any project they can edit, which is the surface {@link ScriptingMode} closes.
         * What is left there is {@code AUTHORIZED}: nothing runs unless the server sends it.</p>
         *
         * <p>Per SERVER rather than per actor, for now. An operator is trusted with the files and that
         * is a different question from whether their client should be running arbitrary code on their
         * behalf — and a config that grants it is the server owner's decision to make, not a default
         * to guess at.</p>
         */
        @Override
        public WorkspaceService.ScriptingPolicy scripting() {
            MinecraftServer server = MinecraftServer.getServer();
            return server != null && server.isSinglePlayer()
                    ? WorkspaceService.ScriptingPolicy.LIVE
                    : WorkspaceService.ScriptingPolicy.AUTHORIZED_ONLY;
        }

        /**
         * A player's id, which is what a permission check and an audit line both need.
         *
         * <p>Read off {@link Peer1710}, which is stable for the connection's life — an entity is not.
         * The name rather than the UUID because that is what {@link OperatorsMayWrite} matches against
         * the live player list and what a log line has to be readable as.</p>
         */
        @Override
        public WorkspaceActor actorFor(Object peer) {
            if (peer instanceof Peer1710) {
                final String name = ((Peer1710) peer).name();
                return () -> name;
            }
            if (peer instanceof EntityPlayerMP) {
                final String name = ((EntityPlayerMP) peer).getCommandSenderName();
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
            MinecraftServer server = MinecraftServer.getServer();
            // func_152596_g is "may use commands" and folds the world's allow-cheats flag into its
            // single-player branch, which is why ownership is asked directly instead.
            return server != null && server.isSinglePlayer()
                    && actorId.equalsIgnoreCase(server.getServerOwner());
        }

        @Override
        public boolean isOperator(String actorId) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager() == null) return false;
            for (Object entry : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) entry;
                if (!player.getCommandSenderName().equals(actorId)) continue;
                GameProfile profile = player.getGameProfile();
                return server.getConfigurationManager().func_152596_g(profile);
            }
            return false;
        }
    }

    /** The server tick, forwarded. The cadence and everything in it belong to {@link WorkspaceHost}. */
    public static final class Handler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (host != null) host.tick(1f / 20f);
        }
    }

    /** Forgets a peer. Called when its connection closes. */
    public static void forget(Object peer) {
        if (host != null) host.forget(peer);
    }

    /** How many peers hold a workspace. Diagnostics, and what a leak would show up in. */
    public static int boundPeers() {
        return host == null ? 0 : host.boundPeerCount();
    }

    /** Drops everything. Called on server stop, so a reload-in-place does not inherit the old service. */
    public static synchronized void reset() {
        if (host != null) host.reset();
    }
}
