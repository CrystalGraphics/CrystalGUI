package com.crystalgui.fs.client;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsHello;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The workspace, from the client</b> — one entry point, sub-facades by noun, nothing static.
 *
 * <pre>{@code
 * workspace.files().read(resource).then(content -> …).onError(error -> …);
 * workspace.watch(folder, true).onChanged.connect(changes -> …);
 * workspace.capabilities().isValidName(typed);
 * }</pre>
 *
 * <h3>What it replaces</h3>
 *
 * <p>{@code plan_fs_rewrite.md} N15. {@code WorkspaceClient} was 832 lines doing seven jobs: an RPC
 * facade, an etag cache, a content cache, a watch memo, capability and presence caches with their push
 * handlers, a chunked-pull state machine, and connection rebinding with a static memo — with three
 * parallel {@code Map<CgPath, …>} fields declared wherever each feature happened to land.</p>
 *
 * <p>Four of its subscriptions were single slots ({@code onFileChanged}, {@code onCapabilitiesChanged},
 * {@code onPresenceChanged}, {@code onRebound}), so a second subscriber silently evicted the first —
 * which is N16, and why every event here is a {@code Signal} and every registration answers a
 * {@link Disposable}.</p>
 *
 * <h3>One per connection, and it is an attachment</h3>
 *
 * <p>Never a static and never a registry keyed by connection: a second workspace in one process is
 * ordinary (two servers in a dev client, a dock window), and {@code WorkspaceClient.forConnection}'s
 * static {@code WeakHashMap} outlived the mechanism written to replace it.</p>
 */
public final class Workspace implements Disposable {

    private final FsCall<Object> calls;
    private final FileOperations files;
    private final Presence presence = new Presence();
    private final Capabilities capabilities = new Capabilities();
    private final Health health = new Health();
    private final Map<Resource, Watch> watches = new LinkedHashMap<>();

    /** Unsaved work and per-save history, both client-local. Absent until a host supplies a store. */
    @Nullable
    private Backup backup;
    @Nullable
    private LocalHistory history;

    /** The greeting, once it has arrived. Until then, the conservative assumptions. @see FsHello */
    private FsHello hello = FsHello.unknown();

    /** The connection came back and everything was re-subscribed. */
    public final Signal.Action onDidReconnect = new Signal.Action();

    /** The greeting arrived, so the server's own facts are now known. */
    public final Signal.Value<FsHello> onDidGreet = new Signal.Value<>();

    private Workspace(FsCall.Caller<Object> caller, Subscriber subscriber, DynamicOps<Object> ops) {
        this.calls = new FsCall<>(caller, ops, health);
        this.files = new FileOperations(calls);
        subscribe(subscriber, ops);
    }

    /**
     * The workspace on this connection, created on first ask.
     *
     * <p>{@code ProtocolConnection.attachment} keyed by class, which is the mechanism the static map it
     * replaces was written to make unnecessary — and which nothing ever moved onto.</p>
     */
    public static Workspace of(ProtocolConnection<Object> connection) {
        return connection.attachment(Workspace.class, wire -> {
            Workspace workspace = new Workspace(wire::call,
                    (method, handler) -> wire.onNotify(method, handler),
                    wire.ops());
            workspace.greet();
            return workspace;
        });
    }

    /** For a test, or a host with its own transport. */
    public static Workspace over(FsCall.Caller<Object> caller, Subscriber subscriber,
                                 DynamicOps<Object> ops) {
        Workspace workspace = new Workspace(caller, subscriber, ops);
        workspace.greet();
        return workspace;
    }

    /** How this workspace hears what the server says without being asked. */
    public interface Subscriber {
        void subscribe(String method, java.util.function.Consumer<StateMap<Object>> handler);
    }

    // ── The facades ─────────────────────────────────────────────────────────────────────────────

    public FileOperations files() {
        return files;
    }

    public Presence presence() {
        return presence;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    /** How the connection is doing — round trip, what is outstanding, the last failure. */
    public Health health() {
        return health;
    }

    /**
     * Where unsaved work and per-save history are kept.
     *
     * <p>Supplied by the host, because only it knows where this client's own writable data lives — a
     * config directory on one loader, a profile folder on another. Without one, a workspace still works
     * and simply offers no hot exit and no timeline: both are strictly additive, and a client with
     * nowhere to write must not refuse to run.</p>
     */
    public Workspace setStorage(@Nullable com.crystalgui.core.storage.ConfigStorage storage) {
        this.backup = storage == null ? null : new Backup(storage);
        this.history = storage == null ? null : new LocalHistory(storage);
        return this;
    }

    /** Unsaved work, or null when no store was supplied. */
    @Nullable
    public Backup backup() {
        return backup;
    }

    /** What this file held at each of the last few saves, or null when no store was supplied. */
    @Nullable
    public LocalHistory history() {
        return history;
    }

    /** Every project this actor may see, as the server described them. */
    public Reply<List<FsMessages.ProjectEntry>> projects() {
        return files.projects().map(FsMessages.ProjectsResponse::projects);
    }

    /**
     * Watches a path — a file, or a directory with or without its descendants.
     *
     * <p>Repeating a watch on one resource answers the same object, so two consumers of one folder cost
     * one subscription. Disposing the last one unwatches.</p>
     */
    public Watch watch(Resource resource, boolean recursive) {
        Watch existing = watches.get(resource);
        if (existing != null) {
            existing.holders++;
            return existing;
        }
        Watch watch = new Watch(resource, recursive);
        watches.put(resource, watch);
        calls.send(FsMethods.WATCH, FsMessages.pathRequest(),
                new FsMessages.PathRequest(resource.toString(), recursive ? "recursive" : ""),
                FsMessages.etagResponse());
        return watch;
    }

    // ── The greeting ────────────────────────────────────────────────────────────────────────────

    /** Asks what this server is. First, because four client decisions depend on the answer. */
    public Reply<FsHello> greet() {
        return calls.coalesced("hello", FsMethods.HELLO, FsMessages.pathRequest(),
                        new FsMessages.PathRequest(""), FsHello.CODEC)
                .then(answer -> {
                    if (answer == null) return;
                    hello = answer;
                    onDidGreet.emit(answer);
                });
    }

    /** What the server said about itself. The conservative assumptions until it has answered. */
    public FsHello server() {
        return hello;
    }

    /**
     * How two resources are decided to be one document on THIS server.
     *
     * <p>Identity on a case-sensitive host; a lower-cased project path on one that folds. Handed to
     * {@code Documents.setKeyStrategy}, which is where it decides whether {@code Main.java} and
     * {@code main.java} are one open document. {@link Resource} equality stays strict, exactly as
     * VS Code keeps {@code URI} strict and folds in {@code extUri}.</p>
     */
    public java.util.function.UnaryOperator<Resource> documentKeyStrategy() {
        if (hello.caseSensitive()) return java.util.function.UnaryOperator.identity();
        return resource -> {
            CgPath path = resource.asPath();
            if (path == null) return resource;
            return Resource.of(CgPath.of(path.project().toLowerCase(Locale.ROOT),
                    path.path().toLowerCase(Locale.ROOT)));
        };
    }

    // ── Reconnect ───────────────────────────────────────────────────────────────────────────────

    /**
     * The wire moved. Re-ask the greeting and re-subscribe everything that was watched.
     *
     * <p><b>A subscription is an INTENT to be re-issued, never a record to be trusted.</b> The client's
     * memo of what it had told the server is a fact about a <em>peer</em>, so after a reconnect it
     * records promises the new peer never made — and the old client's {@code finishRead} saw the path
     * already present, never re-asked, and change notifications stopped permanently for exactly the
     * files that were open. No error, no log line, and an editor that simply stopped noticing edits.</p>
     */
    public void rebind(FsCall.Caller<Object> caller, Subscriber subscriber, DynamicOps<Object> ops) {
        // NOT A NEW OBJECT: the workbench, the index and every open document hold this one. What moves
        // is where its calls go and who it is listening to.
        this.calls.rebind(caller);
        subscribe(subscriber, ops);
        capabilities.clear();
        presence.clear();
        health.reset();
        greet();
        for (Watch watch : new ArrayList<>(watches.values())) {
            calls.send(FsMethods.WATCH, FsMessages.pathRequest(),
                    new FsMessages.PathRequest(watch.resource().toString(),
                            watch.recursive() ? "recursive" : ""),
                    FsMessages.etagResponse());
        }
        onDidReconnect.emit();
    }

    private void subscribe(Subscriber subscriber, DynamicOps<Object> ops) {
        subscriber.subscribe(FsMethods.CHANGED, args ->
                deliver(FsMessages.changedNotification().decode(ops, args.encode())));
        subscriber.subscribe(FsMethods.PRESENCE, args ->
                presence.apply(FsMessages.presenceNotification().decode(ops, args.encode())));
        subscriber.subscribe(FsMethods.CAPABILITIES, args ->
                capabilities.apply(FsMessages.capabilitiesNotification().decode(ops, args.encode())));
    }

    private void deliver(FsMessages.ChangedNotification notification) {
        // GROUPED BY WATCH, so a folder's consumer gets one batch rather than one call per file --
        // which is what coalescing on the server is for and would be undone by delivering per change.
        Map<Watch, List<FsMessages.FileChange>> grouped = new LinkedHashMap<>();
        for (FsMessages.FileChange change : notification.changes()) {
            Resource resource = Resource.parse(change.path());
            for (Watch watch : watches.values()) {
                if (watch.covers(resource)) {
                    grouped.computeIfAbsent(watch, key -> new ArrayList<>()).add(change);
                }
            }
        }
        grouped.forEach((watch, changes) -> watch.onChanged.emit(changes));
    }

    @Override
    public void dispose() {
        for (Watch watch : new ArrayList<>(watches.values())) watch.forceDispose();
        watches.clear();
    }

    // ── Watches ─────────────────────────────────────────────────────────────────────────────────

    /** One subscription, held by however many consumers asked for it. */
    public final class Watch implements Disposable {
        private final Resource resource;
        private final boolean recursive;
        private int holders = 1;

        /** A tick's worth of changes under this path. Coalesced on the server, batched here. */
        public final Signal.Value<List<FsMessages.FileChange>> onChanged = new Signal.Value<>();

        private Watch(Resource resource, boolean recursive) {
            this.resource = resource;
            this.recursive = recursive;
        }

        public Resource resource() {
            return resource;
        }

        public boolean recursive() {
            return recursive;
        }

        boolean covers(Resource candidate) {
            if (candidate.equals(resource)) return true;
            String mine = resource.toString();
            String theirs = candidate.toString();
            if (!theirs.startsWith(mine)) return false;
            String rest = theirs.substring(mine.length());
            if (!rest.startsWith("/")) return false;
            return recursive || rest.indexOf('/', 1) < 0;
        }

        @Override
        public void dispose() {
            if (--holders > 0) return;
            forceDispose();
        }

        private void forceDispose() {
            if (watches.remove(resource) == null) return;
            calls.send(FsMethods.UNWATCH, FsMessages.pathRequest(),
                    new FsMessages.PathRequest(resource.toString()), FsMessages.etagResponse());
        }
    }

    // ── Presence ────────────────────────────────────────────────────────────────────────────────

    /** Who else has a file open, and who is <b>editing</b> it. */
    public static final class Presence {
        private final Map<String, List<String>> open = new LinkedHashMap<>();
        private final Map<String, List<String>> editing = new LinkedHashMap<>();

        /** Somebody's presence changed. */
        public final Signal.Action onDidChange = new Signal.Action();

        public List<String> whoElseHasOpen(Resource resource) {
            return open.getOrDefault(resource.toString(), List.of());
        }

        /**
         * Who has <b>unsaved changes</b> to it.
         *
         * <p>The question that matters, and the one presence could not answer: two people found out
         * they were both editing a file when the second one saved and was refused.</p>
         */
        public List<String> whoIsEditing(Resource resource) {
            return editing.getOrDefault(resource.toString(), List.of());
        }

        void apply(FsMessages.PresenceNotification notification) {
            open.clear();
            editing.clear();
            for (FsMessages.PresenceEntry entry : notification.entries()) {
                open.computeIfAbsent(entry.path(), key -> new ArrayList<>()).add(entry.who());
                if (entry.editing()) {
                    editing.computeIfAbsent(entry.path(), key -> new ArrayList<>()).add(entry.who());
                }
            }
            onDidChange.emit();
        }

        void clear() {
            // PUSHED STATE DESCRIBING A SERVER NOBODY IS TALKING TO. Kept across a rebind it would
            // show players from the world you just left as having your files open.
            open.clear();
            editing.clear();
        }
    }

    // ── Capabilities ────────────────────────────────────────────────────────────────────────────

    /**
     * What this actor may do, and what this host will accept.
     *
     * <p><b>Hints, never the authority.</b> The server re-checks every operation regardless — these
     * exist so a menu can grey a row and a dialog can refuse a name before the round trip, not so the
     * client can decide.</p>
     */
    public final class Capabilities {
        private final Map<String, FsMessages.ProjectCapability> byProject = new LinkedHashMap<>();

        public final Signal.Action onDidChange = new Signal.Action();

        public boolean mayRead(Resource resource) {
            FsMessages.ProjectCapability capability = forResource(resource);
            return capability == null || capability.mayRead();
        }

        public boolean mayWrite(Resource resource) {
            FsMessages.ProjectCapability capability = forResource(resource);
            return capability != null && capability.mayWrite();
        }

        /** Whether this host tells {@code Main.java} from {@code main.java}. */
        public boolean caseSensitive() {
            return hello.caseSensitive();
        }

        /** Whether a New File dialog should refuse this name, asked as the person types. */
        public boolean isValidName(String name) {
            return hello.isValidName(name);
        }

        /** What a file of this size costs: everything, no services, read-only, or refused. */
        public FsHello.SizeTier sizeTierOf(long bytes) {
            return hello.tierOf(bytes);
        }

        @Nullable
        private FsMessages.ProjectCapability forResource(Resource resource) {
            CgPath path = resource.asPath();
            return path == null ? null : byProject.get(path.project());
        }

        void apply(FsMessages.CapabilitiesNotification notification) {
            byProject.clear();
            for (FsMessages.ProjectCapability capability : notification.capabilities()) {
                byProject.put(capability.project(), capability);
            }
            onDidChange.emit();
        }

        void clear() {
            byProject.clear();
        }
    }
}
