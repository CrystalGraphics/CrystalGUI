package com.crystalgui.fs.client;

import com.crystalgui.fs.protocol.ScriptingMode;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.UiBudget;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsHello;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * The client's handle on a workspace that lives on the server.
 *
 * <pre>{@code
 * Workspace workspace = Workspace.of(connection);
 *
 * workspace.read(resource).then(content -> …).onError(error -> …);
 * workspace.watch(folder, true).onChanged.connect(changes -> …);
 * workspace.capabilities().isValidName(typed);
 * }</pre>
 *
 * <p>Work is grouped by noun: {@link #files()} reads and writes, {@link #presence()} says who else has
 * a file open, {@link #capabilities()} what this actor may do, {@link #health()} how the connection is
 * doing. Every event is a {@code Signal} and every registration answers a {@link Disposable}, so any
 * number of consumers can subscribe and each releases its own.</p>
 *
 * <p><b>One per connection</b>, held as an attachment on it — a second workspace in one process is
 * ordinary (two servers in a dev client, a dock window), and each keeps its own watches, caches and
 * scheme table.</p>
 */
public final class Workspace implements Disposable {

    private final FsCall<Object> calls;
    private final FileOperations files;
    private final Presence presence = new Presence();
    private final Capabilities capabilities = new Capabilities();
    private final Health health = new Health();
    private final Map<Resource, Watch> watches = new LinkedHashMap<>();

    /** Where a non-project scheme's content comes from. @see #schemes */
    private final Map<String, ContentProvider> providers = new LinkedHashMap<>();

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
        // DRAINED INTO THIS WORKSPACE'S OWN TABLE, and kept in step. A language module contributes at
        // mod init, long before any world is joined -- so it cannot be handed a workspace, and it must
        // not name one either. @see ContentProviders
        adoptContributions();
        ContentProviders.onDidChange.connect(this::adoptContributions);
    }

    private void adoptContributions() {
        for (ContentProviders.Contribution contribution : ContentProviders.all()) {
            // A SCHEME REGISTERED DIRECTLY ON THIS WORKSPACE WINS, because it was registered against
            // THIS server -- a contribution is a process-wide default and an instance registration is a
            // statement about one connection.
            providers.putIfAbsent(contribution.scheme(), contribution.provider());
        }
    }

    /** The workspace on this connection, created on first ask and shared by every later caller. */
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
        void subscribe(String method, Consumer<StateMap<Object>> handler);
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
     * Registers where a scheme's content comes from — a decompiler, a code generator, a scratch buffer.
     *
     * <p>Per workspace, so two servers in one client keep separate tables and one server's library
     * scheme cannot answer the other's requests. A module that has no workspace to hand contributes
     * through {@link ContentProviders} instead.</p>
     *
     * <p>Answers a {@link Disposable}, so a mod that unloads takes its schemes with it. Registering a
     * scheme twice replaces the first, which is what a hot reload wants.</p>
     *
     * <p>The project scheme may be registered too, and a project file's <b>content</b> still comes from
     * the server: a provider answers three questions and only one is about bytes — what the resource
     * holds ({@link ContentProvider#symbolOf}), where a member of it is declared
     * ({@link ContentProvider#locate}), and what it says. The first two are the language engine's even
     * for the author's own files. {@link #read} checks the scheme before the table, so a provider here
     * is never asked for a project file's bytes.</p>
     */
    public Disposable registerScheme(String scheme, ContentProvider provider) {
        providers.put(scheme, provider);
        return () -> providers.remove(scheme, provider);
    }

    /**
     * What provides this resource, or null when the server does.
     *
     * <p>Answers a <b>timed view</b> of the provider, because this is the one door every reader goes
     * through and the cost is behind the callee's signature: {@code symbolOf(Resource)} reads exactly
     * like a property getter and was measured at a 761ms compile the first time it was asked about a
     * class, from a tab presentation the dock re-reads on every strip rebuild. Instrumenting callers
     * would mean remembering to, in every widget that ever asks a provider anything. @see UiBudget</p>
     */
    @Nullable
    public ContentProvider providerFor(Resource resource) {
        if (resource == null) return null;
        ContentProvider provider = providers.get(resource.scheme());
        // ONE WRAPPER PER PROVIDER, kept: a fresh one per call would allocate on a path the dock takes
        // per tab per rebuild, and would make two answers about one scheme unequal.
        return provider == null ? null : timed.computeIfAbsent(provider, Timed::new);
    }

    private final Map<ContentProvider, ContentProvider> timed = new LinkedHashMap<>();

    /**
     * Times a provider's <b>synchronous</b> answers against the frame budget.
     *
     * <p>Only the synchronous ones. {@code read} and {@code locate} answer a {@link Reply} — the work is
     * off the frame thread by construction, so timing the call measures the submit and reports nothing
     * worth hearing. What is left is what a tab presentation and a tree row bind ask while painting.</p>
     */
    private static final class Timed implements ContentProvider {

        private final ContentProvider delegate;

        Timed(ContentProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public Reply<byte[]> read(Resource resource) {
            return delegate.read(resource);
        }

        @Override
        public Reply<TextPoint> locate(Resource resource, String member) {
            return delegate.locate(resource, member);
        }

        @Override
        public Reply<String> write(Resource resource, byte[] content) {
            return delegate.write(resource, content);
        }

        @Override
        public Disposable onDidResolveSymbol(Consumer<Resource> listener) {
            return delegate.onDidResolveSymbol(listener);
        }

        @Override
        @Nullable
        public SymbolInfo symbolOf(Resource resource) {
            long started = UiBudget.begin();
            try {
                return delegate.symbolOf(resource);
            } finally {
                UiBudget.end(started, "symbolOf " + resource);
            }
        }

        @Override
        @Nullable
        public String displayName(Resource resource) {
            long started = UiBudget.begin();
            try {
                return delegate.displayName(resource);
            } finally {
                UiBudget.end(started, "displayName " + resource);
            }
        }

        @Override
        public String languageFileName(Resource resource) {
            long started = UiBudget.begin();
            try {
                return delegate.languageFileName(resource);
            } finally {
                UiBudget.end(started, "languageFileName " + resource);
            }
        }

        @Override
        public boolean isReadOnly(Resource resource) {
            return delegate.isReadOnly(resource);
        }
    }

    /** Every registered provider, for a caller that must subscribe to all of them. */
    public List<ContentProvider> providers() {
        return List.copyOf(providers.values());
    }

    /**
     * Reads any resource — <b>the one door</b>.
     *
     * <p>A project resource goes over the wire; anything else goes to whatever registered its scheme.
     * The routing lives here so no caller above ever has to ask which kind of thing it is holding.</p>
     */
    public Reply<FileOperations.Content> read(Resource resource) {
        // THE SCHEME BEFORE THE TABLE. A project file's bytes are the server's whatever has registered
        // to describe the scheme -- a provider is there to say what the file DECLARES, not to serve it.
        if (resource.isProject()) return files.readWhole(resource);
        ContentProvider provider = providerFor(resource);
        // A PROVIDER HAS NO ETAG, and the empty string is how it says so rather than a null nobody
        // remembers to check: an etag is the server's record of a file it holds, and a decompiled class
        // is not one. Whoever reads this answers "no etag" the same way for both.
        if (provider != null) return provider.read(resource).map(bytes ->
                new FileOperations.Content(bytes, ""));
        return files.readWhole(resource);
    }

    /** Whether this resource refuses writes. An unregistered non-project scheme is read-only. */
    public boolean isReadOnly(Resource resource) {
        if (resource == null) return true;
        // Same rule as `read`: a project file is the server's, and whether it may be written is the
        // server's answer rather than a provider's.
        if (resource.isProject()) return false;
        ContentProvider provider = providerFor(resource);
        // REFUSING TO WRITE SOMETHING NOBODY CLAIMS is the right default: a scheme with no provider has
        // no storage behind it at all.
        return provider == null || provider.isReadOnly(resource);
    }

    /**
     * Where unsaved work and per-save history are kept.
     *
     * <p>Supplied by the host, because only it knows where this client's own writable data lives — a
     * config directory on one loader, a profile folder on another. Without one a workspace still works
     * and simply offers no hot exit and no timeline.</p>
     */
    public Workspace setStorage(@Nullable ConfigStorage storage) {
        // A DIRECTORY EACH, and not tidiness: both stores own everything in the store they are given --
        // Backup#discardAll clears it, LocalHistory sweeps it to a file cap. Sharing one meant a
        // successful save, which discards backups, deleted the history it had just written.
        this.backup = storage == null ? null : new Backup(storage.scoped(StorageLayout.BACKUPS));
        this.history = storage == null ? null : new LocalHistory(storage.scoped(StorageLayout.HISTORY));
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

    /**
     * Tells the server this client has, or no longer has, unsaved changes to a file.
     *
     * <p><b>Nobody else can say it.</b> Dirtiness is {@code version != savedVersion} on a document only
     * this client holds, so the server can neither observe it nor infer it — a file with no writes
     * coming is equally one nobody has touched and one somebody has been typing in for ten minutes.
     * That is the question presence exists to answer, and it is why "who has it open" alone is the
     * weaker signal: two people find out they were both editing when the second one saves and is
     * refused.</p>
     *
     * <p>Sent on the transition, never per keystroke. {@link WorkspaceDocuments} is the caller, because
     * a document's state is what changed.</p>
     */
    public Reply<String> setEditing(Resource resource, boolean dirty) {
        return calls.send(FsMethods.EDITING, FsMessages.pathRequest(),
                        new FsMessages.PathRequest(resource.toString(), dirty ? "dirty" : ""),
                        FsMessages.etagResponse())
                .map(FsMessages.EtagResponse::etag);
    }

    // ── The greeting ────────────────────────────────────────────────────────────────────────────

    /** Asks what this server is. First, because four client decisions depend on the answer. */
    public Reply<FsHello> greet() {
        return calls.coalesced("hello", FsMethods.HELLO, FsMessages.pathRequest(),
                        new FsMessages.PathRequest(""), FsHello.CODEC)
                .then(answer -> {
                    if (answer == null) return;
                    hello = answer;
                    greeted = true;
                    onDidGreet.emit(answer);
                    askCapabilities();
                });
    }

    /**
     * ...and what this actor may DO here, which nothing ever asked.
     *
     * <p>Both halves of this existed and no wire joined them: the server registered a handler for
     * {@code fs/capabilities} and the client subscribed to a <em>notification</em> of the same name,
     * which nothing on any host ever sent. So {@code capabilities()} answered out of an empty map for
     * every project on every host — and an empty map is indistinguishable from a permissive one, which
     * is why it looked like it worked. The same shape as presence before it was joined up.</p>
     *
     * <p>Asked on the greeting, because the two questions have the same lifetime: what this server is,
     * and what this actor may do on it. Pushed updates still arrive on the subscription — a
     * promotion, a project opened — and this is what makes the first answer exist to be updated.</p>
     */
    public Reply<FsMessages.CapabilitiesNotification> askCapabilities() {
        return calls.coalesced("capabilities", FsMethods.CAPABILITIES, FsMessages.pathRequest(),
                        new FsMessages.PathRequest(""), FsMessages.capabilitiesNotification())
                .then(answer -> {
                    if (answer != null) capabilities.apply(answer);
                });
    }

    private boolean greeted;

    /**
     * Whether the server has actually answered, as opposed to {@link #server()}'s assumptions.
     *
     * <p>The two are not the same question and {@code server()} cannot answer this one: it returns a
     * usable {@link FsHello} from the moment the client exists, because every caller of it needs an
     * answer rather than a null. So a consumer that must run <em>once the server has spoken</em> — a
     * session restore keyed on which workspace this is, chiefly — has nothing to test but this.</p>
     *
     * <p>False again after a {@link #rebind}: a new peer has not greeted, whatever the last one said.</p>
     */
    public boolean hasGreeted() {
        return greeted;
    }

    /** What the server said about itself. The conservative assumptions until it has answered. */
    public FsHello server() {
        return hello;
    }

    /**
     * How two resources are decided to be one document on THIS server.
     *
     * <p>Identity on a case-sensitive host; a lower-cased project path on one that folds. Handed to
     * {@code Documents.setKeyStrategy}, where it decides whether {@code Main.java} and {@code main.java}
     * are one open document. {@link Resource} equality stays strict — the folding is the store's, as
     * VS Code keeps {@code URI} strict and folds in {@code extUri}.</p>
     */
    public UnaryOperator<Resource> documentKeyStrategy() {
        if (hello.caseSensitive()) return UnaryOperator.identity();
        return resource -> {
            CgPath path = resource.asPath();
            if (path == null) return resource;
            return Resource.of(CgPath.of(path.project().toLowerCase(Locale.ROOT),
                    path.path().toLowerCase(Locale.ROOT)));
        };
    }

    // ── Reconnect ───────────────────────────────────────────────────────────────────────────────

    /**
     * The wire moved. Re-asks the greeting and re-subscribes everything that was watched.
     *
     * <p><b>A subscription is an intent to be re-issued, never a record to be trusted.</b> "I have
     * already asked the server to watch this" is a fact about a <em>peer</em>, so across a reconnect it
     * records promises the new peer never made — and change notifications then stop permanently for
     * exactly the files that were open, with no error and no log line.</p>
     */
    public void rebind(ProtocolConnection<Object> connection) {
        rebind(connection::call, connection::onNotify, connection.ops());
    }

    public void rebind(FsCall.Caller<Object> caller, Subscriber subscriber, DynamicOps<Object> ops) {
        // NOT A NEW OBJECT: the workbench, the index and every open document hold this one. What moves
        // is where its calls go and who it is listening to.
        this.calls.rebind(caller);
        subscribe(subscriber, ops);
        capabilities.clear();
        presence.clear();
        health.reset();
        // NOT GREETED UNTIL THIS ONE ANSWERS. The field describes a PEER, and a reconnect is a different
        // peer -- the same rule the watches below follow, and for the same reason.
        greeted = false;
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
            // A RENAME CONCERNS BOTH ENDS. Its `path` is the DESTINATION, so a watch on the file that
            // moved -- which is what an open tab holds -- matches neither the path nor anything under
            // it, and the one event that exists to stop a tab being closed would have been filtered out
            // before reaching it. The watcher on the folder it moved INTO is covered by `path`; the
            // watcher on the file itself is covered only by `from`.
            Resource origin = change.from().isEmpty() ? null : Resource.parse(change.from());
            for (Watch watch : watches.values()) {
                if (watch.covers(resource) || (origin != null && watch.covers(origin))) {
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
         * <p>The question worth asking before you start typing: without it two people find out they
         * were both editing a file when the second one saves and is refused.</p>
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
            // PUSHED STATE DESCRIBES A SERVER NOBODY IS TALKING TO. Kept across a rebind it would show
            // players from the world you just left as having your files open.
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
        /**
         * Whether this resource may be RUN here, and how.
         *
         * <p>A project nobody has said anything about answers {@link ScriptingMode#LIVE}, which is what
         * every host meant before the question existed. The one place that is deliberately overridden
         * is a workspace on a remote server, where the client's own local projects answer
         * {@link ScriptingMode#NONE}: no server speaks for those files, so nobody can authorise
         * them.</p>
         */
        public ScriptingMode scriptingMode(Resource resource) {
            FsMessages.ProjectCapability capability = forResource(resource);
            return capability == null ? ScriptingMode.LIVE : capability.scripting();
        }

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
