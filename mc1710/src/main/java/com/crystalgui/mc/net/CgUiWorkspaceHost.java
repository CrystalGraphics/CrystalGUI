package com.crystalgui.mc.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.LocalFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceOperation;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4 <b>B1/B2/B4</b> — the workspace is served <em>by the server</em>, over the real connection.
 *
 * <p>This is the vision the filesystem layer was built for, and the reason {@code Mc1710Workspace}'s
 * javadoc reserved it as <i>"a transport swap rather than a rewrite"</i>. It turned out to be exactly
 * that: {@link WorkspaceRpc} already installed onto anything with a {@code register(method, handler)},
 * and {@code WorkspaceClient} only ever used its session to call and to register. Neither needed a
 * redesign — they needed somewhere to be plugged in, which is what {@link Protocols} became.</p>
 *
 * <h3>Files live on the server's machine, and single-player is not a special case</h3>
 *
 * <p>The root is {@code <serverdir>/crystalgui/workspace} through
 * {@link MinecraftServer#getFile(String)} — which on a dedicated server is the server directory and in
 * single-player is the game directory, because <b>the integrated server is a server</b>. So there is one
 * code path, and the single-player case is the remote case with a very short wire. That is what makes it
 * testable at all: a bug that only appears when the two halves are genuinely apart would otherwise wait
 * for a dedicated server to find it.</p>
 *
 * <h3>One {@link WorkspaceRpc} per connection, because an actor is per player</h3>
 *
 * <p>{@code WorkspaceRpc} binds an actor at construction, and permission is checked per call against that
 * actor. Sharing one across players would mean every request was authorised as whoever connected first.
 * It also holds the watcher, so per-connection is what makes "tell <em>this</em> client what changed"
 * meaningful.</p>
 */
public final class CgUiWorkspaceHost {

    /** Matches {@code Mc1710Workspace.PROJECT_ID}; the id is the client's handle on the project. */
    public static final String PROJECT_ID = "minecraft.workspace";

    /** Seconds between watcher polls, per connection. */
    private static final float POLL_SECONDS = 0.5f;

    private static final Map<Object, WorkspaceRpc<Object>> BY_PEER = new ConcurrentHashMap<>();
    private static final Map<Object, ProtocolConnection<Object>> CONNECTIONS = new ConcurrentHashMap<>();

    private static volatile WorkspaceService service;
    private static boolean registered;
    private static float untilPoll = POLL_SECONDS;

    private CgUiWorkspaceHost() {
    }

    /**
     * Contributes the workspace to every connection, and starts the watcher poll.
     *
     * <p>Called from {@code CommonProxy.init()} — this is <b>server-side behaviour that a dedicated
     * server needs and a client does not</b>, which is what {@code CommonProxy} is for.</p>
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Protocols.contribute("workspace", new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
                bindWorkspace(connection);
            }
        });
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[cgui-fs] workspace contributed to the protocol");
    }

    @SuppressWarnings("unchecked")
    private static <T> void bindWorkspace(ProtocolConnection<T> connection) {
        // A CLIENT connection has no peer, and must not host a workspace: it is the consumer. Without
        // this, a single-player process would serve itself from its own client end as well -- both ends
        // answering fs.* on one wire, with whichever registered first winning.
        Object peer = connection.peer();
        if (peer == null) return;

        WorkspaceService live = service();
        if (live == null) return;

        WorkspaceRpc<T> rpc = new WorkspaceRpc<>(live, actorFor(peer));
        rpc.installOn(connection::onRequest);
        BY_PEER.put(peer, (WorkspaceRpc<Object>) rpc);
        CONNECTIONS.put(peer, (ProtocolConnection<Object>) connection);
        CrystalGuiCore.LOGGER.info("[cgui-fs] workspace bound for {}", actorFor(peer).id());
    }

    /**
     * Built on first use rather than at registration, because the root is not knowable at mod init.
     *
     * <p>{@link MinecraftServer#getServer()} is null until a world loads, and contribution happens long
     * before that — so this is memoised on the first connection instead, which is always after.</p>
     */
    private static synchronized WorkspaceService service() {
        if (service != null) return service;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;

        Path root = server.getFile("crystalgui/workspace").toPath();
        seed(root);
        ProjectRegistry registry = new ProjectRegistry().register(() -> Collections.singletonList(
                new WorkspaceProject(PROJECT_ID, "Workspace", root)));
        service = new WorkspaceService(registry, new LocalFileSystem(registry), new OperatorsMayWrite());
        CrystalGuiCore.LOGGER.info("[cgui-fs] serving {}", root);
        return service;
    }

    /** A player's id, which is what a permission check and an audit line both need. */
    private static WorkspaceActor actorFor(Object peer) {
        if (peer instanceof EntityPlayerMP) {
            final String name = ((EntityPlayerMP) peer).getCommandSenderName();
            return () -> name;
        }
        final String fallback = String.valueOf(peer);
        return () -> fallback;
    }

    // ── B4: a real permission ───────────────────────────────────────────────

    /**
     * <b>Everyone reads; operators write.</b>
     *
     * <p>{@code ALLOW_ALL} was correct for what {@code Mc1710Workspace} was — one player, local disk, no
     * one to guard against — and is wrong the moment the files are on somebody else's machine. This is
     * the smallest policy that is actually defensible, and it deliberately does not invent a permission
     * model: <b>it reuses Minecraft's own</b>. {@code func_152596_g} is the "may use commands" check, and
     * it already answers correctly for the case that would otherwise need special handling — in
     * single-player it is true for the world's owner when cheats are on, so the host of a local world
     * keeps the access they had.</p>
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

    // ── The watcher poll ────────────────────────────────────────────────────

    public static final class Handler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            untilPoll -= 1f / 20f;
            if (untilPoll > 0f) return;
            untilPoll = POLL_SECONDS;

            List<Object> gone = new ArrayList<>();
            for (Map.Entry<Object, WorkspaceRpc<Object>> entry : BY_PEER.entrySet()) {
                ProtocolConnection<Object> connection = CONNECTIONS.get(entry.getKey());
                if (connection == null) {
                    gone.add(entry.getKey());
                    continue;
                }
                try {
                    entry.getValue().pollAndNotify(
                            (method, args) -> connection.call(method, args, null, null),
                            PlainOps.INSTANCE);
                } catch (RuntimeException failed) {
                    // One player's watcher must not stop every other player being polled.
                    CrystalGuiCore.LOGGER.error("[cgui-fs] watcher poll failed: {}", failed.getMessage());
                }
            }
            for (Object key : gone) BY_PEER.remove(key);
        }
    }

    /** Forgets a peer. Called when its connection closes, or the maps grow for the life of the server. */
    public static void forget(Object peer) {
        BY_PEER.remove(peer);
        CONNECTIONS.remove(peer);
    }

    /** How many peers hold a workspace. Diagnostics, and what a leak would show up in. */
    public static int boundPeers() {
        return BY_PEER.size();
    }

    /**
     * Creates the workspace directory, and a README the first time.
     *
     * <p>An empty file tree and a broken file tree look identical, which is the whole reason for the
     * README: the first launch needs something in it that proves a listing crossed the wire.</p>
     */
    private static void seed(Path root) {
        try {
            Files.createDirectories(root);
            Path readme = root.resolve("README.md");
            if (!Files.exists(readme)) {
                List<String> lines = Arrays.asList(
                        "# CrystalGUI workspace",
                        "",
                        "This directory lives on the SERVER. The editor reaches it over the same",
                        "protocol a remote workspace uses -- the client holds no filesystem handle.",
                        "",
                        "In single-player that server is the integrated one, which is why there is only",
                        "one code path and no special case.");
                Files.write(readme, lines, Charset.forName("UTF-8"));
            }
        } catch (IOException e) {
            // Not fatal: the tree is empty and the editor still opens. Failing the screen because a
            // README could not be written would be a worse trade.
            CrystalGuiCore.LOGGER.warn("Could not seed the workspace at " + root, e);
        }
    }

    /** Drops everything. Called on server stop, so a reload-in-place does not inherit the old service. */
    public static synchronized void reset() {
        BY_PEER.clear();
        CONNECTIONS.clear();
        service = null;
    }
}
