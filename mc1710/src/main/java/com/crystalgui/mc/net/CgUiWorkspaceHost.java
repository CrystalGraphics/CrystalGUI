package com.crystalgui.mc.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.LocalFileSystem;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceOperation;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.provider.NioFileEventSource;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

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
 * that: {@link WorkspaceBinding} installs onto anything with a {@code register(method, handler)}, and
 * a workspace client only ever uses its connection to call and to register. Neither needed a
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
 * <h3>One {@link WorkspaceBinding} per connection, because an actor is per player</h3>
 *
 * <p>A binding takes its actor at construction, and permission is checked per call against that
 * actor. Sharing one across players would mean every request was authorised as whoever connected first.
 * It also holds the watcher, so per-connection is what makes "tell <em>this</em> client what changed"
 * meaningful.</p>
 */
public final class CgUiWorkspaceHost {

    /** Matches {@code Mc1710Workspace.PROJECT_ID}; the id is the client's handle on the project. */
    public static final String PROJECT_ID = "minecraft.workspace";

    /**
     * What this host's workspace never lists and never watches.
     *
     * <p>Version-control metadata and build output: large, uninteresting, and on Linux the reason a
     * watcher runs out of inotify handles. Stated here rather than in {@code Excludes} because it is a
     * property of THIS deployment's workspace directory — a mod pointing a project somewhere else says
     * what its own project excludes. It was {@code emptyList()} at the watcher, so a workspace with a
     * build directory watched every class file in it.</p>
     */
    private static final List<String> DEFAULT_EXCLUDES =
            Arrays.asList(".git", ".gradle", "build", "out", "node_modules", "*.class");

    /** Seconds between watcher polls, per connection. */
    private static final float POLL_SECONDS = 0.5f;

    private static final Map<Object, WorkspaceBinding<Object>> BY_PEER = new ConcurrentHashMap<>();

    /**
     * ONE hub for the whole server, not one per player.
     *
     * <p>A watch costs an OS handle and Linux caps them per user, so N players watching one workspace
     * must not mean N subscriptions on one directory. It also means every path is stat-ed at most once
     * per tick however many peers are watching it — which is what a per-connection watcher could not do,
     * and it was doing it twice a second per file per peer.</p>
     */
    private static WatchHub hub;
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
        // SERVER-sided at the call site: a client end must not host a workspace -- it is the
        // consumer, and without the split a single-player process would serve itself from its own
        // client end as well, both ends answering fs.* on one wire.
        Protocols.server("workspace", CgUiWorkspaceHost::bindWorkspace);
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[cgui-fs] workspace contributed to the protocol");
    }

    private static void bindWorkspace(ProtocolConnection<Object> connection) {
        Object peer = connection.peer();   // non-null: Protocols.server only binds where there is one
        WorkspaceService live = service();
        if (live == null) return;

        WorkspaceBinding<Object> binding = new WorkspaceBinding<>(
                live, hub, actorFor(peer), peer, PlainOps.INSTANCE);
        binding.installOn(connection);
        BY_PEER.put(peer, binding);
        CONNECTIONS.put(peer, connection);
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
        // The project's own excludes, stated ONCE and honoured by the listing, the watcher and anything
        // else that asks. They were `emptyList()` at the watcher below, which is why a workspace with a
        // build directory watched every class file in it -- see Excludes.
        WorkspaceProject project = new WorkspaceProject(
                new ProjectInfo(PROJECT_ID, "Workspace"), root, DEFAULT_EXCLUDES);
        ProjectRegistry registry = new ProjectRegistry()
                .register(() -> Collections.singletonList(project));
        service = new WorkspaceService(registry, new LocalFileSystem(registry), new OperatorsMayWrite());
        CrystalGuiCore.LOGGER.info("[cgui-fs] serving {}", root);

        // Phase 6.2. ONE source for the project, not one per player: every watch costs an OS handle and
        // Linux caps them per USER, so N players sharing a workspace must not mean N watchers on one
        // directory. Never throws -- a workspace that cannot be watched still works, half a second
        // behind, and refusing to serve it would be a far worse answer.
        service.attachEvents(NioFileEventSource.open(PROJECT_ID, root, project.excludes()));
        hub = new WatchHub(service);
        return service;
    }

    /**
     * A player's id, which is what a permission check and an audit line both need.
     *
     * <p>Read off {@link Mc1710Peer}, which is stable for the connection's life — an entity is not.
     * The name rather than the UUID because that is what {@code OperatorsMayWrite} matches against the
     * live player list and what a log line has to be readable as.</p>
     */
    private static WorkspaceActor actorFor(Object peer) {
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

    // ── The watcher poll ────────────────────────────────────────────────────

    public static final class Handler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            // EVERY TICK, and that is the point of 6.2: an external save reaches the client on the next
            // tick rather than at the next half-second reconcile. Drained ONCE here and handed to every
            // peer, because draining is destructive and a second caller would steal the first's events.
            // Costs one non-blocking WatchService.poll() when nothing has happened.
            WorkspaceService live = service;
            if (live != null) {
                List<CgFileEvent> events = live.drainFileEvents();
                if (!events.isEmpty()) fanOut(events);
            }

            // AND WHO IS HERE, on the same tick and for the same reason: presence is what stops two
            // people finding out they were both editing when the second one saves and is refused.
            // Free when nothing has moved -- a version counter, then an exact comparison per peer.
            fanOutPresence();

            untilPoll -= 1f / 20f;
            if (untilPoll > 0f) return;
            untilPoll = POLL_SECONDS;

            // ONE RESCAN FOR THE SERVER, then a message each. It was one poll per peer, which stat-ed
            // every watched file once per player twice a second.
            if (service == null || hub == null) return;
            try {
                fanOut(hub.poll(actorForAny()));
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("[cgui-fs] watcher poll failed: {}", failed.getMessage());
            }
            for (Object key : new ArrayList<>(BY_PEER.keySet())) {
                if (CONNECTIONS.get(key) == null) BY_PEER.remove(key);
            }
        }
    }

    /**
     * Hands one drained batch to every peer — Phase 6.2.
     *
     * <p>Each watcher keeps only the paths its own client has open, so an event about a file nobody here
     * has open costs a map lookup and is dropped: an event is real and still none of that peer's
     * business, and telling it would leak which files exist to somebody who never asked.</p>
     */
    private static void fanOut(List<CgFileEvent> events) {
        if (hub == null) return;
        // THE HUB DECIDES WHO HEARS WHAT, once for the batch: it coalesces per path, pairs a deletion
        // and a creation carrying one etag into a RENAME, and rescans wholesale when the OS reports an
        // overflow. None of that can be done per peer without doing it N times.
        fanOut(hub.tick(actorForAny(), events));
    }

    /** Sends each peer its own list. A peer with nothing to hear about is absent from the map. */
    private static void fanOut(Map<Object, List<FsMessages.FileChange>> byPeer) {
        for (Map.Entry<Object, WorkspaceBinding<Object>> entry : BY_PEER.entrySet()) {
            ProtocolConnection<Object> connection = CONNECTIONS.get(entry.getKey());
            if (connection == null) continue;
            List<FsMessages.FileChange> mine = entry.getValue().changesFor(byPeer);
            if (mine.isEmpty()) continue;
            try {
                connection.notify(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                        FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                                new FsMessages.ChangedNotification(mine))));
            } catch (RuntimeException failed) {
                // One player's dispatch must not stop every other player hearing about the change.
                CrystalGuiCore.LOGGER.error("[cgui-fs] file-event dispatch failed: {}",
                        failed.getMessage());
            }
        }
    }

    /**
     * Tells each peer who else is in the files it has open.
     *
     * <p>Per peer rather than broadcast, because presence is <b>scoped to what that peer holds</b>: a
     * player hears about a file the moment somebody else opens one they have open, and hears nothing
     * about files they never asked for. The same rule the change fan-out follows, for the same reason —
     * telling everybody about everything would leak which files exist to somebody who never asked.</p>
     */
    private static void fanOutPresence() {
        for (Map.Entry<Object, WorkspaceBinding<Object>> entry : BY_PEER.entrySet()) {
            ProtocolConnection<Object> connection = CONNECTIONS.get(entry.getKey());
            if (connection == null) continue;
            FsMessages.PresenceNotification mine = entry.getValue().presenceFor();
            if (mine == null) continue;
            try {
                connection.notify(FsMethods.PRESENCE, new StateMap<>(PlainOps.INSTANCE,
                        FsMessages.presenceNotification().encode(PlainOps.INSTANCE, mine)));
            } catch (RuntimeException failed) {
                // One player's dispatch must not stop every other player hearing who is here.
                CrystalGuiCore.LOGGER.error("[cgui-fs] presence dispatch failed: {}",
                        failed.getMessage());
            }
        }
    }

    /**
     * An actor for the SERVER's own re-stat, which is not any one player's.
     *
     * <p>The hub reads a file to answer whether it moved, and reading is permission-checked — so the
     * poll needs somebody to be. It is the server itself: a change is a fact about the disk, and whether
     * a given player may hear about it is decided per subscription, which is what a peer's watch list
     * already is.</p>
     */
    private static WorkspaceActor actorForAny() {
        return () -> "server";
    }

    /** Forgets a peer. Called when its connection closes, or the maps grow for the life of the server. */
    public static void forget(Object peer) {
        // PRESENCE FIRST, and it is the half that is visible to other players. A client that logs out
        // cleanly sends fs.unwatch for each open file; a client that crashes, times out, or loses its
        // connection sends nothing at all -- so without this it is shown as still holding those files,
        // to everybody else, for the rest of the server's life. @see WorkspacePresence#left
        WorkspaceService live = service;
        if (live != null) live.presence().left(actorFor(peer));
        // AND ITS SUBSCRIPTIONS, or the hub goes on stat-ing files for a player who has gone -- the
        // per-peer half of the same leak the presence line above closes.
        if (hub != null) hub.forget(peer);

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
