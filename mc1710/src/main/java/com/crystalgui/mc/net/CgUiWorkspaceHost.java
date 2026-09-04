package com.crystalgui.mc.net;

import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.fs.protocol.ScriptingMode;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.fs.server.WorkspaceOperation;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.project.WorkspaceProject;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

/**
 * The three questions {@link WorkspaceHost} cannot answer on its own — <b>where, who, and may they</b>.
 *
 * <p>This class was 403 lines and served the workspace itself: the per-connection bindings, the shared
 * watcher, the poll cadence, both fan-outs, the per-peer cleanup and the seeded README. None of that is
 * about Minecraft, and all of it now lives in {@code fs.server.WorkspaceHost}. What is left is what
 * genuinely needs a {@code MinecraftServer} to answer, and every method below names one.</p>
 *
 * <h3>Files live on the server's machine, and single-player is not a special case</h3>
 *
 * <p>The root is {@code <serverdir>/crystalgui/workspace} through
 * {@link MinecraftServer#getFile(String)} — which on a dedicated server is the server directory and in
 * single-player is the game directory, because <b>the integrated server is a server</b>. So there is one
 * code path, and single-player is the remote case with a very short wire.</p>
 */
public final class CgUiWorkspaceHost {

    /** Matches the client's handle on the project. */
    public static final String PROJECT_ID = "minecraft.workspace";

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
        host = new WorkspaceHost(PROJECT_ID, "Workspace", new Mc1710Host());
        host.contribute();
        FMLCommonHandler.instance().bus().register(new Handler());
    }

    /** Where the workspace is, who a peer is, and what they may do. Nothing else is asked of a host. */
    private static final class Mc1710Host implements WorkspaceHost.Host {

        /**
         * Null until a world loads, which is why {@code WorkspaceHost} asks per connection rather than
         * once: {@link MinecraftServer#getServer()} answers null at mod init and contribution happens
         * long before any world.
         */
        @Override
        @Nullable
        public Path root() {
            MinecraftServer server = MinecraftServer.getServer();
            return server == null ? null : server.getFile("crystalgui/workspace").toPath();
        }

        @Override
        public WorkspacePermission permission() {
            return new OperatorsMayWrite();
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
         * <p>Read off {@link Mc1710Peer}, which is stable for the connection's life — an entity is not.
         * The name rather than the UUID because that is what {@link OperatorsMayWrite} matches against
         * the live player list and what a log line has to be readable as.</p>
         */
        @Override
        public WorkspaceActor actorFor(Object peer) {
            if (peer instanceof Mc1710Peer) {
                final String name = ((Mc1710Peer) peer).name();
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

    /**
     * <b>Everyone reads; operators write.</b>
     *
     * <p>{@code ALLOW_ALL} was correct when the workspace was one player's local disk and is wrong the
     * moment the files are on somebody else's machine. This is the smallest policy that is actually
     * defensible, and it deliberately does not invent a permission model: <b>it reuses Minecraft's
     * own</b>.</p>
     *
     * <p>A read is allowed to any connected player because the workspace is the server's shared content,
     * like a datapack. If that turns out to be wrong for somebody, the fix is a per-project permission
     * rather than tightening this one — which is why the check takes the project it was given.</p>
     */
    private static final class OperatorsMayWrite implements WorkspacePermission {
        @Override
        public boolean allows(WorkspaceActor actor, WorkspaceProject project, CgPath path,
                              WorkspaceOperation operation) {
            if (operation == WorkspaceOperation.READ) return true;
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager() == null) return false;
            // THE OWNER OF A SINGLE-PLAYER WORLD, whatever the cheats flag says.
            //
            // func_152596_g is the "may use commands" check and it folds the world's allow-cheats flag
            // into its single-player branch -- correct for commands and wrong here. Cheats gate
            // COMMANDS; they have nothing to say about whether somebody may edit files in their own
            // save directory. Found in game: a fresh world has cheats off, so the host of a local world
            // could list the workspace and not write to it, and the refusal was a correct-looking
            // NO_PERMISSIONS with no way to tell it from a real one.
            if (server.isSinglePlayer() && actor.id().equalsIgnoreCase(server.getServerOwner())) {
                return true;
            }
            for (Object entry : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) entry;
                if (!player.getCommandSenderName().equals(actor.id())) continue;
                GameProfile profile = player.getGameProfile();
                return server.getConfigurationManager().func_152596_g(profile);
            }
            // Not connected any more. Refusing is the safe answer and cannot strand anyone: a player who
            // has left has nothing in flight that a write would complete.
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
