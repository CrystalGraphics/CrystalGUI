package com.crystalgui.fs.client;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsHello;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.protocol.ScriptingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * One application's handle on the workspace its desktop owns ({@link Workspace}).
 *
 * <pre>{@code
 * WorkspaceClient workspace = desktop.workspace().client("Crystal Editor");
 *
 * workspace.read(resource).then(content -> …).onError(error -> …);
 * workspace.watch(folder, true).onChanged.connect(changes -> …);
 * workspace.capabilities().isValidName(typed);
 * }</pre>
 *
 * <p>Work is grouped by noun: {@link #files()} reads and writes, {@link #presence()} says who else has a file open
 * (another player, or another application here), {@link #capabilities()} what this actor may do, {@link #health()}
 * how this application's calls are doing. Every event is a {@code Signal} and every registration answers a
 * {@link Disposable}, so any number of consumers can subscribe and each releases its own.</p>
 *
 * <p><b>Made on the sequence that uses it</b>: replies, timeouts and events are handed back there, so its signals
 * are that sequence's. Made outside every sequence (a test), they run on the thread that delivers them.</p>
 */
public final class WorkspaceClient implements Disposable {

    private final Workspace service;
    private final String name;
    private final FsCall<Object> calls;
    private final FileOperations files;
    private final Presence presence = new Presence();
    private final Capabilities capabilities = new Capabilities();
    private final Health health = new Health();
    private final Map<Resource, Watch> watches = new LinkedHashMap<>();

    /** The connection came back and everything was re-subscribed. */
    public final Signal.Action onDidReconnect = new Signal.Action();

    /** The greeting arrived, so the server's own facts are now known. */
    public final Signal.Value<FsHello> onDidGreet = new Signal.Value<>();

    @Nullable
    private final Executor home;

    WorkspaceClient(Workspace service, String name) {
        this.service = service;
        this.name = name;
        // A wire's replies and notifications arrive on its tick thread, and this client's signals belong to the
        // sequence it was made on: every callback is handed back there.
        this.home = Broadcast.sequenceHere();
        this.calls = new FsCall<>(homed(), service.ops(), health);
        this.files = new FileOperations(calls, this::echo);
    }

    /** The workspace on this connection, as a client of its own. For a test, or a caller with no desktop. */
    public static WorkspaceClient of(CgProtocolConnection<Object> connection) {
        return Workspace.of(connection).client();
    }

    /** For a test, or a host with its own transport. */
    public static WorkspaceClient over(FsCall.Caller<Object> caller, Subscriber subscriber,
                                       CgDynamicOps<Object> ops) {
        return Workspace.over(caller, subscriber, ops).client();
    }

    /** How the workspace hears what the server says without being asked. */
    public interface Subscriber {
        void subscribe(String method, Consumer<CgStateMap<Object>> handler);
    }

    private FsCall.Caller<Object> homed() {
        return (method, args, onResult, onError) -> service.wire().call(method, args,
                result -> post(() -> onResult.accept(result)),
                error -> post(() -> onError.accept(error)));
    }

    /** Runs {@code task} where this client was made. */
    void post(Runnable task) {
        if (home == null) task.run();
        else home.execute(task);
    }

    /**
     * Runs {@code task} on the sequence this client was made on and answers false (the caller returns); true when
     * already on a sequence or when it has none, so the caller carries on.
     */
    public boolean onHome(Runnable task) {
        if (home == null || Broadcast.sequenceHere() != null) return true;
        home.execute(task);
        return false;
    }

    /** The application's name, as another application's presence shows it. Empty for an anonymous client. */
    public String name() {
        return name;
    }

    /** The workspace this is a client of. */
    public Workspace service() {
        return service;
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

    /** How this application's calls are doing: round trip, what is outstanding, the last failure. */
    public Health health() {
        return health;
    }

    /**
     * Registers where a scheme's content comes from — a decompiler, a code generator, a scratch buffer.
     *
     * <p>Per workspace, so two servers in one client keep separate tables, and shared by every application on it.
     * A module with no workspace to hand contributes through {@link ContentProviders} instead. Answers a
     * {@link Disposable}, so a mod that unloads takes its schemes with it; registering a scheme twice replaces the
     * first, which is what a hot reload wants.</p>
     *
     * <p>The project scheme may be registered too, and a project file's <b>content</b> still comes from the server:
     * a provider says what the resource holds, where a member of it is declared, and what it says, and
     * {@link #read} checks the scheme before the table.</p>
     */
    public Disposable registerScheme(String scheme, ContentProvider provider) {
        return service.registerScheme(scheme, provider);
    }

    /**
     * What provides this resource, or null when the server does. Answers a <b>timed view</b>, because this is the
     * one door every reader goes through and the cost is behind the callee's signature.
     */
    @Nullable
    public ContentProvider providerFor(Resource resource) {
        return service.providerFor(resource);
    }

    /** Every registered provider, for a caller that must subscribe to all of them. */
    public List<ContentProvider> providers() {
        return service.providers();
    }

    /**
     * Reads any resource — <b>the one door</b>: a project resource goes over the wire, anything else to whatever
     * registered its scheme.
     */
    public CgReply<FileOperations.Content> read(Resource resource) {
        // THE SCHEME BEFORE THE TABLE: a provider is there to say what the file declares, not to serve it.
        if (resource.isProject()) return files.readWhole(resource);
        ContentProvider provider = providerFor(resource);
        // A PROVIDER HAS NO ETAG, and the empty string is how it says so rather than a null nobody checks.
        if (provider != null) return provider.read(resource).map(bytes ->
                new FileOperations.Content(bytes, ""));
        return files.readWhole(resource);
    }

    /** Whether this resource refuses writes. An unregistered non-project scheme is read-only. */
    public boolean isReadOnly(Resource resource) {
        if (resource == null) return true;
        if (resource.isProject()) return false;
        ContentProvider provider = providerFor(resource);
        // Refusing to write what nobody claims is the right default: a scheme with no provider has no storage.
        return provider == null || provider.isReadOnly(resource);
    }

    /**
     * Where unsaved work and per-save history are kept, for the whole workspace. The desktop's greeting files them
     * itself ({@link Workspace#useStores}); this is for a host that knows better, or a test.
     */
    public WorkspaceClient setStorage(@Nullable CgConfigStorage storage) {
        service.setStorage(storage);
        return this;
    }

    /** Unsaved work, or null when no store was supplied. */
    @Nullable
    public Backup backup() {
        return service.backup();
    }

    /** What this file held at each of the last few saves, or null when no store was supplied. */
    @Nullable
    public LocalHistory history() {
        return service.history();
    }

    /** Every project this actor may see, as the server described them. */
    public CgReply<List<FsMessages.ProjectEntry>> projects() {
        return files.projects().map(FsMessages.ProjectsResponse::projects);
    }

    /**
     * Watches a path — a file, or a directory with or without its descendants.
     *
     * <p>Repeating a watch on one resource answers the same object, so two consumers of one folder cost one
     * subscription, and two applications on it cost one on the server. Disposing the last one unwatches.</p>
     */
    public Watch watch(Resource resource, boolean recursive) {
        Watch existing = watches.get(resource);
        if (existing != null) {
            existing.holders++;
            return existing;
        }
        Watch watch = new Watch(resource, recursive);
        watches.put(resource, watch);
        service.watch(this, resource, recursive);
        return watch;
    }

    /**
     * Tells the server, and the other applications here, that this one has or no longer has unsaved changes to a file.
     *
     * <p><b>Nobody else can say it.</b> Dirtiness is {@code version != savedVersion} on a document only this
     * application holds. Sent on the transition, never per keystroke: {@link WorkspaceDocuments} is the caller.</p>
     */
    public CgReply<String> setEditing(Resource resource, boolean dirty) {
        service.noteEditing(this, resource, dirty);
        return calls.send(FsMethods.EDITING, FsMessages.PATH_REQUEST,
                        new FsMessages.PathRequest(resource.toString(), dirty ? "dirty" : ""),
                        FsMessages.ETAG_RESPONSE)
                .map(FsMessages.EtagResponse::etag);
    }

    // ── The greeting ────────────────────────────────────────────────────────────────────────────

    /** Asks the server what it is again. The desktop's workspace asked at birth; this is for a refresh. */
    public void greet() {
        service.greet();
    }

    /**
     * Whether the server has actually answered, as opposed to {@link #server()}'s assumptions: a consumer that must
     * run <em>once the server has spoken</em> (a session restore keyed on which workspace this is) has nothing else
     * to test. False again after a rebind: a new peer has not greeted, whatever the last one said.
     */
    public boolean hasGreeted() {
        return service.hasGreeted();
    }

    /** What the server said about itself. The conservative assumptions until it has answered. */
    public FsHello server() {
        return service.server();
    }

    /**
     * Names this workspace for its backups when the server never did: the first identity given wins, and one the
     * greeting gave already has.
     */
    public void useIdentity(String identity) {
        service.useIdentity(identity);
    }

    /**
     * How two resources are decided to be one document on THIS server: identity on a case-sensitive host, a
     * lower-cased project path on one that folds. {@link Resource} equality stays strict; the folding is the store's.
     */
    public UnaryOperator<Resource> documentKeyStrategy() {
        if (server().caseSensitive()) return UnaryOperator.identity();
        return resource -> {
            CgPath path = resource.asPath();
            if (path == null) return resource;
            return Resource.of(CgPath.of(path.project().toLowerCase(Locale.ROOT),
                    path.path().toLowerCase(Locale.ROOT)));
        };
    }

    // ── Reconnect ───────────────────────────────────────────────────────────────────────────────

    /** The wire moved: re-greets and re-subscribes for every application on this workspace. */
    public void rebind(CgProtocolConnection<Object> connection) {
        service.rebind(connection);
    }

    public void rebind(FsCall.Caller<Object> caller, Subscriber subscriber, CgDynamicOps<Object> ops) {
        service.rebind(caller, subscriber, ops);
    }

    /** What the service tells this client, delivered here. */
    void wireMoved() {
        // Anything in flight over the old wire is failed rather than left pending: its answer is never coming.
        calls.rebind(homed());
        capabilities.onDidChange.emit();
        presence.onDidChange.emit();
        health.reset();
        onDidReconnect.emit();
    }

    void capabilitiesMoved() {
        capabilities.onDidChange.emit();
    }

    void presenceMoved() {
        presence.onDidChange.emit();
    }

    void deliver(List<FsMessages.FileChange> changes) {
        // GROUPED BY WATCH, so a folder's consumer gets one batch rather than one call per file.
        Map<Watch, List<FsMessages.FileChange>> grouped = new LinkedHashMap<>();
        for (FsMessages.FileChange change : changes) {
            Resource resource = Resource.parse(change.path());
            // A RENAME CONCERNS BOTH ENDS. Its `path` is the DESTINATION, so a watch on the file that moved (what
            // an open tab holds) matches neither the path nor anything under it; only `from` covers it.
            Resource origin = change.from().isEmpty() ? null : Resource.parse(change.from());
            for (Watch watch : watches.values()) {
                if (watch.covers(resource) || (origin != null && watch.covers(origin))) {
                    grouped.computeIfAbsent(watch, key -> new ArrayList<>()).add(change);
                }
            }
        }
        grouped.forEach((watch, found) -> watch.onChanged.emit(found));
    }

    /** One of this client's own saves or creates, announced to the other applications' watches. */
    private void echo(Resource resource, FsMessages.ChangeKind kind, String etag) {
        service.fanOut(this, new FsMessages.FileChange(resource.toString(), kind, etag, "", server().actor()));
    }

    /** Releases this application's watches and its place in others' presence. */
    @Override
    public void dispose() {
        for (Watch watch : new ArrayList<>(watches.values())) watch.forceDispose();
        watches.clear();
        service.release(this);
    }

    // ── Watches ─────────────────────────────────────────────────────────────────────────────────

    /** One subscription, held by however many consumers of this application asked for it. */
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
            // THE SAME RULE THE SERVER APPLIES, and literally the same code. @see CgPath#covers
            if (!resource.isProject() || !candidate.isProject()) return false;
            return resource.asPath().covers(candidate.asPath(), recursive);
        }

        @Override
        public void dispose() {
            if (--holders > 0) return;
            forceDispose();
        }

        private void forceDispose() {
            if (watches.remove(resource) == null) return;
            service.unwatch(WorkspaceClient.this, resource);
        }
    }

    // ── Presence ────────────────────────────────────────────────────────────────────────────────

    /** Who else has a file open, and who is <b>editing</b> it: other players, and other applications on this client. */
    public final class Presence {

        /** Somebody's presence changed. */
        public final Signal.Action onDidChange = new Signal.Action();

        public List<String> whoElseHasOpen(Resource resource) {
            return service.whoElseHasOpen(WorkspaceClient.this, resource);
        }

        /**
         * Who has <b>unsaved changes</b> to it. Without it two people find out they were both editing a file when
         * the second one saves and is refused.
         */
        public List<String> whoIsEditing(Resource resource) {
            return service.whoIsEditing(WorkspaceClient.this, resource);
        }
    }

    // ── Capabilities ────────────────────────────────────────────────────────────────────────────

    /**
     * What this actor may do, and what this host will accept.
     *
     * <p><b>Hints, never the authority.</b> The server re-checks every operation regardless — these exist so a menu
     * can grey a row and a dialog can refuse a name before the round trip.</p>
     */
    public final class Capabilities {

        public final Signal.Action onDidChange = new Signal.Action();

        public boolean mayRead(Resource resource) {
            FsMessages.ProjectCapability capability = forResource(resource);
            return capability == null || capability.mayRead();
        }

        public boolean mayWrite(Resource resource) {
            FsMessages.ProjectCapability capability = forResource(resource);
            return capability != null && capability.mayWrite();
        }

        /**
         * Whether this resource may be RUN here, and how. A project nobody has said anything about answers
         * {@link ScriptingMode#LIVE}; a workspace on a remote server answers {@link ScriptingMode#NONE} for the
         * client's own local projects, which no server speaks for.
         */
        public ScriptingMode scriptingMode(Resource resource) {
            FsMessages.ProjectCapability capability = forResource(resource);
            return capability == null ? ScriptingMode.LIVE : capability.scripting();
        }

        /** Whether this host tells {@code Main.java} from {@code main.java}. */
        public boolean caseSensitive() {
            return server().caseSensitive();
        }

        /** Whether a New File dialog should refuse this name, asked as the person types. */
        public boolean isValidName(String name) {
            return server().isValidName(name);
        }

        /** What a file of this size costs: everything, no services, read-only, or refused. */
        public FsHello.SizeTier sizeTierOf(long bytes) {
            return server().tierOf(bytes);
        }

        @Nullable
        private FsMessages.ProjectCapability forResource(Resource resource) {
            CgPath path = resource.asPath();
            return path == null ? null : service.capabilities().get(path.project());
        }
    }
}
