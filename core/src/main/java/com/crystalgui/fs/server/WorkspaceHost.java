package com.crystalgui.fs.server;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
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

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.provider.LocalFileSystem;
import com.crystalgui.fs.provider.NioFileEventSource;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

/**
 * <b>A server serving its workspace over the wire</b> - bind a connection to it and that peer can list,
 * read, write and watch files.
 *
 * <p>Answer three questions through {@link Host} - <em>where</em> the directory is, <em>who</em> a peer
 * is, and <em>what</em> they may do - and this provides the rest: a {@link WorkspaceBinding} per
 * connection, one shared {@link WatchHub}, the change and presence fan-outs, per-peer cleanup and a
 * seeded README for an empty workspace.</p>
 *
 * <pre>{@code
 * WorkspaceHost host = new WorkspaceHost(myHost);
 * host.bind(connection, peerKey);   // when a peer connects
 * host.tick();                      // every server tick -- polls and fans out changes
 * host.forget(peerKey);             // when they disconnect
 * }</pre>
 *
 * <h3>One binding per connection, because an actor is per player</h3>
 *
 * <p>A binding takes its actor at construction and checks permission per call against it, so sharing one
 * across players would authorise every request as whoever connected first. It also holds that peer's
 * watch list, which is what makes "tell <em>this</em> client what changed" mean anything.</p>
 *
 * <h3>One watch hub, though - never one per player</h3>
 *
 * <p>A watch costs an OS handle and Linux caps them per user, so N players watching one workspace must
 * not mean N subscriptions on one directory. It also means a path is stat-ed at most once per tick
 * however many peers watch it.</p>
 *
 * <h3>The root is asked for repeatedly, on purpose</h3>
 *
 * <p>{@link Host#root()} may answer null - a server has no world when a mod initialises - so it is asked
 * on every connection until it answers. A peer that arrives before there is anywhere to serve is simply
 * not bound, and binds normally on its next attempt.</p>
 */
public final class WorkspaceHost {

    /** What only a platform can answer. Three questions; everything else is this class's. */
    public interface Host {

        /**
         * Where the workspace lives, or null while that is not yet knowable.
         *
         * <p>Asked on every attempt until it answers, because "no world loaded yet" and "no workspace
         * on this host" are the same shape from here and only one of them is permanent.</p>
         */
        @Nullable
        Path root();

        /** Who may do what. @see WorkspacePermission */
        WorkspacePermission permission();

        /**
         * Who may RUN this workspace's files, and where.
         *
         * <p>A different question from {@link #permission}, which says what may be done to a file:
         * this says what may be done with what is in one. A server that lets everybody read and only
         * operators write may still let nobody run anything in their own client.</p>
         *
         * <p>Defaults to {@link WorkspaceService.ScriptingPolicy#LIVE}, which is what every host meant
         * before the question existed — an in-process workspace and a single-player world are the
         * player's own machine.</p>
         */
        default WorkspaceService.ScriptingPolicy scripting() {
            return WorkspaceService.ScriptingPolicy.LIVE;
        }

        /** What to call a peer — for a permission check, for presence, and for an audit line. */
        WorkspaceActor actorFor(Object peer);
    }

    /**
     * What a workspace never lists and never watches.
     *
     * <p>Version-control metadata and build output: large, uninteresting, and on Linux the reason a
     * watcher runs out of inotify handles. A default rather than a rule — a host serving somewhere else
     * says what its own project excludes.</p>
     */
    public static final List<String> DEFAULT_EXCLUDES =
            Arrays.asList(".git", ".gradle", ".crystalgui", "build", "out", "node_modules", "*.class");

    /** Seconds between watcher polls. */
    private static final float POLL_SECONDS = 0.5f;

    private final String projectId;
    private final String displayName;
    private final List<String> excludes;
    private final Host host;

    private final Map<Object, WorkspaceBinding<Object>> boundPeers = new ConcurrentHashMap<>();
    private final Map<Object, ProtocolConnection<Object>> connections = new ConcurrentHashMap<>();

    private volatile WorkspaceService service;
    private WatchHub hub;
    private float untilPoll = POLL_SECONDS;

    public WorkspaceHost(String projectId, String displayName, Host host) {
        this(projectId, displayName, DEFAULT_EXCLUDES, host);
    }

    public WorkspaceHost(String projectId, String displayName, List<String> excludes, Host host) {
        this.projectId = projectId;
        this.displayName = displayName;
        this.excludes = excludes;
        this.host = host;
    }

    /**
     * Offers the workspace to every connection this process opens as a server.
     *
     * <p>Server-sided at the contribution, not at the call: a client end must not host a workspace — it
     * is the consumer — and without the split a single-player process would serve itself from its own
     * client end as well, both ends answering {@code fs.*} on one wire.</p>
     */
    public void contribute() {
        Protocols.server("workspace", this::bind);
        CrystalGuiCore.LOGGER.info("[cgui-fs] workspace contributed to the protocol");
    }

    private void bind(ProtocolConnection<Object> connection) {
        Object peer = connection.peer();   // non-null: Protocols.server only binds where there is one
        WorkspaceService live = service();
        if (live == null) return;

        WorkspaceBinding<Object> binding = new WorkspaceBinding<>(
                live, hub, host.actorFor(peer), peer, PlainOps.INSTANCE);
        binding.installOn(connection);
        boundPeers.put(peer, binding);
        connections.put(peer, connection);
        CrystalGuiCore.LOGGER.info("[cgui-fs] workspace bound for {}", host.actorFor(peer).id());
    }

    /** The service, built on the first connection that finds a root. Null until then. */
    @Nullable
    public synchronized WorkspaceService service() {
        if (service != null) return service;
        Path root = host.root();
        if (root == null) return null;

        seed(root);
        // The project's own excludes, stated ONCE and honoured by the listing, the watcher and anything
        // else that asks. They were emptyList() at the watcher, which is why a workspace with a build
        // directory watched every class file in it -- see Excludes.
        WorkspaceProject project = new WorkspaceProject(
                new ProjectInfo(projectId, displayName), root, excludes);
        ProjectRegistry registry = new ProjectRegistry()
                .register(() -> Collections.singletonList(project));
        service = new WorkspaceService(registry, new LocalFileSystem(registry), host.permission())
                .setScriptingPolicy(host.scripting())
                .setWorkspaceId(identityOf(root));
        CrystalGuiCore.LOGGER.info("[cgui-fs] serving {}", root);

        // ONE source for the project, not one per player: every watch costs an OS handle and Linux caps
        // them per USER. Never throws -- a workspace that cannot be watched still works, half a second
        // behind, and refusing to serve it would be a far worse answer.
        service.attachEvents(NioFileEventSource.open(projectId, root, project.excludes()));
        hub = new WatchHub(service);
        return service;
    }

    /**
     * One tick: deliver what changed, say who is here, and re-stat when the poll is due.
     *
     * <p>Called from whatever the host's tick is. Everything in it is free when nothing has happened —
     * one non-blocking poll of the event source, a version counter for presence, and a countdown.</p>
     */
    public void tick(float deltaSeconds) {
        // EVERY TICK, which is the point: an external save reaches the client on the next tick rather
        // than at the next half-second reconcile. Drained ONCE and handed to every peer, because
        // draining is destructive and a second caller would steal the first's events.
        WorkspaceService live = service;
        if (live != null) {
            List<CgFileEvent> events = live.drainFileEvents();
            if (!events.isEmpty()) fanOut(events);
        }

        // AND WHO IS HERE, on the same tick and for the same reason: presence is what stops two people
        // finding out they were both editing when the second one saves and is refused.
        fanOutPresence();

        untilPoll -= deltaSeconds;
        if (untilPoll > 0f) return;
        untilPoll = POLL_SECONDS;

        // ONE RESCAN FOR THE SERVER, then a message each. It was one poll per peer, which stat-ed every
        // watched file once per player twice a second.
        if (service == null || hub == null) return;
        try {
            fanOut(hub.poll(serverActor()));
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.error("[cgui-fs] watcher poll failed: {}", failed.getMessage());
        }
        for (Object key : new ArrayList<>(boundPeers.keySet())) {
            if (connections.get(key) == null) boundPeers.remove(key);
        }
    }

    /**
     * Hands one drained batch to every peer.
     *
     * <p>Each watch list keeps only the paths its own client has open, so an event about a file nobody
     * here has open costs a map lookup and is dropped: an event is real and still none of that peer's
     * business, and telling it would leak which files exist to somebody who never asked.</p>
     */
    private void fanOut(List<CgFileEvent> events) {
        if (hub == null) return;
        // THE HUB DECIDES WHO HEARS WHAT, once for the batch: it coalesces per path, pairs a deletion
        // and a creation carrying one etag into a RENAME, and rescans wholesale on an overflow. None of
        // that can be done per peer without doing it N times.
        fanOut(hub.tick(serverActor(), events));
    }

    /** Sends each peer its own list. A peer with nothing to hear about is absent from the map. */
    private void fanOut(Map<Object, List<FsMessages.FileChange>> byPeer) {
        for (Map.Entry<Object, WorkspaceBinding<Object>> entry : boundPeers.entrySet()) {
            ProtocolConnection<Object> connection = connections.get(entry.getKey());
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
     * about files they never asked for. The same rule the change fan-out follows, for the same reason.</p>
     */
    private void fanOutPresence() {
        for (Map.Entry<Object, WorkspaceBinding<Object>> entry : boundPeers.entrySet()) {
            ProtocolConnection<Object> connection = connections.get(entry.getKey());
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
    private static WorkspaceActor serverActor() {
        return () -> "server";
    }

    /** Forgets a peer. Called when its connection closes, or the maps grow for the life of the server. */
    public void forget(Object peer) {
        // PRESENCE FIRST, and it is the half that is visible to other players. A client that logs out
        // cleanly sends fs.unwatch for each open file; a client that crashes, times out or loses its
        // connection sends nothing at all -- so without this it is shown as still holding those files,
        // to everybody else, for the rest of the server's life. @see WorkspacePresence#left
        WorkspaceService live = service;
        if (live != null) live.presence().left(host.actorFor(peer));
        // AND ITS SUBSCRIPTIONS, or the hub goes on stat-ing files for a player who has gone.
        if (hub != null) hub.forget(peer);

        boundPeers.remove(peer);
        connections.remove(peer);
    }

    /** How many peers hold a workspace. Diagnostics, and what a leak would show up in. */
    public int boundPeerCount() {
        return boundPeers.size();
    }

    /**
     * Creates the workspace directory, and a README the first time.
     *
     * <p>An empty file tree and a broken file tree look identical, which is the whole reason for the
     * README: the first launch needs something in it that proves a listing crossed the wire.</p>
     */
    /**
     * A stable name for this workspace — <b>read once, written once, never derived from the path</b>.
     *
     * <p>Kept in {@code .crystalgui/workspace.id} under the root, so it survives the directory being
     * renamed or the world being copied elsewhere the way a project id survives a move. Deriving it from
     * the absolute path instead would be free and would mean a rename silently becomes a different
     * workspace: every client's arrangement, and every per-workspace record it keys, quietly starts
     * again from nothing.</p>
     *
     * <p>Inside the root rather than beside it because that is the one directory a host is guaranteed to
     * be able to write to, and the dot-prefixed name is already excluded from the listing. A read or
     * write that fails answers empty, which is the same as an older server: the client falls back to a
     * hash of the project ids and nothing is broken, only less stable.</p>
     */
    private static String identityOf(Path root) {
        Path file = root.resolve(".crystalgui").resolve("workspace.id");
        try {
            if (Files.isRegularFile(file)) {
                String stored = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!stored.isEmpty()) return stored;
            }
            String minted = UUID.randomUUID().toString().replace("-", "");
            Files.createDirectories(file.getParent());
            Files.write(file, minted.getBytes(StandardCharsets.UTF_8));
            return minted;
        } catch (IOException couldNotKeepIt) {
            CrystalGuiCore.LOGGER.warn("[cgui-fs] could not keep a workspace id at {}; clients will key "
                    + "their records on the project list instead", file, couldNotKeepIt);
            return "";
        }
    }

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
    public synchronized void reset() {
        boundPeers.clear();
        connections.clear();
        service = null;
        hub = null;
        untilPoll = POLL_SECONDS;
    }
}
