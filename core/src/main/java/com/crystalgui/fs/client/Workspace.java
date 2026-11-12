package com.crystalgui.fs.client;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.async.UiBudget;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.storage.CrystalGuiStorage;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsHello;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgraphics.job.reply.CgReply;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The workspace on one connection, owned by the desktop: the wire, what the server said about itself, who may do
 * what, what is watched, where backups and history are kept, and which applications are using it. One per
 * connection, on the services sequence; every application holds a {@link WorkspaceClient} of its own.
 *
 * <pre>{@code
 * // The desktop, once per connection (it rebinds on a reconnect)
 * Workspace workspace = Workspace.of(connection);
 *
 * // An application, on its own sequence: its reads, writes and events come back there
 * WorkspaceClient mine = workspace.client("Crystal Editor");
 * mine.read(resource).then(content -> …);
 * mine.watch(folder, true).onChanged.connect(changes -> …);
 * }</pre>
 *
 * <p>Two applications with one file open agree <b>through the file</b>: a save by one is announced to the other's
 * watches at once (without the round trip), a clean copy reloads, a dirty one conflicts on its next save. Each
 * keeps its own document models.</p>
 *
 * <p>Easy to get wrong: a client is made on the sequence that will use it, since that is where its callbacks go;
 * made outside every sequence its callbacks run on the thread that delivers them. Dispose a client with its
 * application, or its watches live on.</p>
 */
public final class Workspace {

    /** Locks the service's tables: the services sequence writes them, a client may read from its own. */
    private final Object lock = new Object();
    @Nullable
    private final Executor serial;
    private volatile FsCall.Caller<Object> caller;
    private volatile CgDynamicOps<Object> ops;
    private final FsCall<Object> calls;
    private final List<WorkspaceClient> clients = new CopyOnWriteArrayList<>();

    /** The server's subscriptions, one per resource however many applications watch it. */
    private final Map<Resource, Subscription> watches = new LinkedHashMap<>();
    /** What each application has unsaved, for the others' presence. */
    private final Map<WorkspaceClient, Set<Resource>> editing = new IdentityHashMap<>();

    private volatile FsHello hello = FsHello.unknown();
    private volatile boolean greeted;
    private volatile Map<String, FsMessages.ProjectCapability> capabilities = Map.of();
    private volatile Map<String, List<String>> serverOpen = Map.of();
    private volatile Map<String, List<String>> serverEditing = Map.of();

    /** Where a non-project scheme's content comes from. */
    private final Map<String, ContentProvider> providers = new ConcurrentHashMap<>();
    private final Map<ContentProvider, ContentProvider> timed = new ConcurrentHashMap<>();
    @Nullable
    private final Connection contributions;

    @Nullable
    private volatile Backup backup;
    @Nullable
    private volatile LocalHistory history;
    @Nullable
    private volatile Function<String, CgConfigStorage> storeFor;

    private static final class Subscription {
        boolean recursive;
        final Set<WorkspaceClient> holders = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private Workspace(FsCall.Caller<Object> wire, WorkspaceClient.Subscriber subscriber, CgDynamicOps<Object> ops) {
        // The wire answers on its tick thread; the service's state belongs to the services sequence.
        this.serial = Broadcast.sequenceHere() != null ? UiSequence.services() : null;
        this.caller = wire;
        this.ops = ops;
        this.calls = new FsCall<>(homed(), ops, new Health());
        subscribe(subscriber);
        // A language module contributes at mod init, long before any world is joined, so it cannot be handed a
        // workspace: its providers are drained into this one and kept in step. @see ContentProviders
        adoptContributions();
        Connection held = null;
        try {
            held = ContentProviders.onDidChange.subscribe(this::adoptContributions);
        } catch (IllegalStateException neitherSequenceNorHost) {
            // Built on a thread nothing is delivered to (a bare test): the contributions at birth are all it sees.
        }
        this.contributions = held;
    }

    /** The workspace on this connection, created on first ask and shared by every later caller. */
    public static Workspace of(CgProtocolConnection<Object> connection) {
        return connection.attachment(Workspace.class, wire -> {
            Workspace workspace = new Workspace(wire::call, wire::onNotify, wire.ops());
            workspace.greet();
            return workspace;
        });
    }

    /** For a test, or a host with its own transport. */
    public static Workspace over(FsCall.Caller<Object> caller, WorkspaceClient.Subscriber subscriber,
                                 CgDynamicOps<Object> ops) {
        Workspace workspace = new Workspace(caller, subscriber, ops);
        workspace.greet();
        return workspace;
    }

    /** An application's handle, answering on the calling sequence. {@code name} is how others see it in presence. */
    public WorkspaceClient client(String name) {
        WorkspaceClient made = new WorkspaceClient(this, name);
        clients.add(made);
        return made;
    }

    public WorkspaceClient client() {
        return client("");
    }

    // ── Threading ───────────────────────────────────────────────────────────────────────────────

    private void onServices(Runnable task) {
        Runnable locked = () -> {
            synchronized (lock) {
                task.run();
            }
        };
        if (serial == null) locked.run();
        else serial.execute(locked);
    }

    private FsCall.Caller<Object> homed() {
        return (method, args, onResult, onError) -> caller.call(method, args,
                result -> onServices(() -> onResult.accept(result)),
                error -> onServices(() -> onError.accept(error)));
    }

    void eachClient(Consumer<WorkspaceClient> action) {
        for (WorkspaceClient each : clients) each.post(() -> action.accept(each));
    }

    // ── Facts the clients read ──────────────────────────────────────────────────────────────────

    FsCall.Caller<Object> wire() {
        return (method, args, onResult, onError) -> caller.call(method, args, onResult, onError);
    }

    CgDynamicOps<Object> ops() {
        return ops;
    }

    /** What the server said about itself. The conservative assumptions until it has answered. */
    public FsHello server() {
        return hello;
    }

    /** Whether the server has actually answered, as opposed to {@link #server()}'s assumptions. False again after a rebind. */
    public boolean hasGreeted() {
        return greeted;
    }

    Map<String, FsMessages.ProjectCapability> capabilities() {
        return capabilities;
    }

    @Nullable
    Backup backup() {
        return backup;
    }

    @Nullable
    LocalHistory history() {
        return history;
    }

    // ── The greeting ────────────────────────────────────────────────────────────────────────────

    /** Asks what this server is, and what this actor may do on it. First, because four client decisions depend on it. */
    public void greet() {
        onServices(this::greetNow);
    }

    private void greetNow() {
        calls.coalesced("hello", FsMethods.HELLO, FsMessages.PATH_REQUEST,
                        new FsMessages.PathRequest(""), FsHello.CODEC)
                .then(answer -> {
                    if (answer == null) return;
                    hello = answer;
                    greeted = true;
                    // The greeting names this workspace, which is what its backups and history are filed under.
                    if (!answer.workspaceId().isEmpty()) useIdentity(answer.workspaceId());
                    eachClient(client -> client.onDidGreet.emit(answer));
                    askCapabilities();
                });
    }

    private void askCapabilities() {
        calls.coalesced("capabilities", FsMethods.CAPABILITIES, FsMessages.PATH_REQUEST,
                        new FsMessages.PathRequest(""), FsMessages.CAPABILITIES_NOTIFICATION)
                .then(answer -> {
                    if (answer != null) applyCapabilities(answer);
                });
    }

    // ── Where unsaved work and history are kept ─────────────────────────────────────────────────

    /**
     * Tells the service how to find a store for a workspace's identity: the desktop's, once. With it the greeting
     * is all a workspace needs to file its backups.
     */
    public Workspace useStores(@Nullable Function<String, CgConfigStorage> stores) {
        this.storeFor = stores;
        if (stores != null && greeted && !hello.workspaceId().isEmpty()) useIdentity(hello.workspaceId());
        return this;
    }

    /**
     * Files backups and history under {@code identity}. The first identity wins: a workspace is one thing however
     * many applications ask. An application whose server never named it passes the hash of its projects.
     */
    public void useIdentity(String identity) {
        Function<String, CgConfigStorage> stores = storeFor;
        if (stores == null || backup != null) return;
        setStorage(stores.apply(identity));
    }

    /** Where unsaved work and per-save history are kept; without one there is no hot exit and no timeline. */
    public Workspace setStorage(@Nullable CgConfigStorage storage) {
        // A DIRECTORY EACH, and not tidiness: both stores own everything in the store they are given --
        // Backup#discardAll clears it, LocalHistory sweeps it to a file cap.
        this.backup = storage == null ? null : new Backup(storage.scoped(CrystalGuiStorage.BACKUPS));
        this.history = storage == null ? null : new LocalHistory(storage.scoped(CrystalGuiStorage.HISTORY));
        return this;
    }

    // ── Content providers ───────────────────────────────────────────────────────────────────────

    private void adoptContributions() {
        for (ContentProviders.Contribution contribution : ContentProviders.all()) {
            // A scheme registered directly on this workspace wins: it was registered against this server,
            // and a contribution is a process-wide default.
            providers.putIfAbsent(contribution.scheme(), contribution.provider());
        }
    }

    Disposable registerScheme(String scheme, ContentProvider provider) {
        providers.put(scheme, provider);
        return () -> providers.remove(scheme, provider);
    }

    @Nullable
    ContentProvider providerFor(Resource resource) {
        if (resource == null) return null;
        ContentProvider provider = providers.get(resource.scheme());
        // ONE WRAPPER PER PROVIDER, kept: a fresh one per call would allocate on a path the dock takes per tab.
        return provider == null ? null : timed.computeIfAbsent(provider, Timed::new);
    }

    List<ContentProvider> providers() {
        return List.copyOf(providers.values());
    }

    /**
     * Times a provider's <b>synchronous</b> answers against the frame budget: the cost is behind the callee's
     * signature ({@code symbolOf} reads like a getter and was measured at 761 ms the first time it was asked), and
     * this is the one door every reader goes through. @see UiBudget
     */
    private static final class Timed implements ContentProvider {

        private final ContentProvider delegate;

        Timed(ContentProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public CgReply<byte[]> read(Resource resource) {
            return delegate.read(resource);
        }

        @Override
        public CgReply<TextPoint> locate(Resource resource, String member) {
            return delegate.locate(resource, member);
        }

        @Override
        public CgReply<String> write(Resource resource, byte[] content) {
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

    // ── Watches ─────────────────────────────────────────────────────────────────────────────────

    /** An application asks for a watch: the first holder, of any application, subscribes on the server. */
    void watch(WorkspaceClient who, Resource resource, boolean recursive) {
        onServices(() -> {
            Subscription have = watches.get(resource);
            boolean send = have == null || (recursive && !have.recursive);
            if (have == null) {
                have = new Subscription();
                watches.put(resource, have);
            }
            have.recursive |= recursive;
            have.holders.add(who);
            if (send) sendWatch(resource, have.recursive);
            presenceMoved();
        });
    }

    /** An application let go of a watch: the last holder unsubscribes. */
    void unwatch(WorkspaceClient who, Resource resource) {
        onServices(() -> {
            Subscription have = watches.get(resource);
            if (have == null) return;
            have.holders.remove(who);
            if (have.holders.isEmpty()) {
                watches.remove(resource);
                calls.send(FsMethods.UNWATCH, FsMessages.PATH_REQUEST,
                        new FsMessages.PathRequest(resource.toString()), FsMessages.ETAG_RESPONSE);
            }
            presenceMoved();
        });
    }

    private void sendWatch(Resource resource, boolean recursive) {
        calls.send(FsMethods.WATCH, FsMessages.PATH_REQUEST,
                new FsMessages.PathRequest(resource.toString(), recursive ? "recursive" : ""),
                FsMessages.ETAG_RESPONSE);
    }

    // ── What the server pushes ──────────────────────────────────────────────────────────────────

    private void subscribe(WorkspaceClient.Subscriber subscriber) {
        CgDynamicOps<Object> wireOps = ops;
        subscriber.subscribe(FsMethods.CHANGED, args -> {
            FsMessages.ChangedNotification n = FsMessages.CHANGED_NOTIFICATION.decode(wireOps, args.encode());
            onServices(() -> changed(n.changes()));
        });
        subscriber.subscribe(FsMethods.PRESENCE, args -> {
            FsMessages.PresenceNotification n = FsMessages.PRESENCE_NOTIFICATION.decode(wireOps, args.encode());
            onServices(() -> applyPresence(n));
        });
        subscriber.subscribe(FsMethods.CAPABILITIES, args -> {
            FsMessages.CapabilitiesNotification n = FsMessages.CAPABILITIES_NOTIFICATION.decode(wireOps, args.encode());
            onServices(() -> applyCapabilities(n));
        });
    }

    private void changed(List<FsMessages.FileChange> changes) {
        for (FsMessages.FileChange change : changes) {
            // A RENAME CONCERNS THE STORES TOO: a file is usually renamed while nothing has it open, and then
            // nothing is left to notice. This is the one place every change arrives.
            if (change.kind() == FsMessages.ChangeKind.RENAMED && !change.from().isEmpty()) {
                moveStores(Resource.parse(change.from()), Resource.parse(change.path()));
            }
        }
        eachClient(client -> client.deliver(changes));
    }

    private void moveStores(Resource from, Resource to) {
        Backup b = backup;
        LocalHistory h = history;
        if (b != null) b.rename(from, to);
        if (h != null) h.rename(from, to);
    }

    /**
     * A save, a create or a delete one application made, announced to every <em>other</em> application's watches
     * now: the server announces it to all of them a round trip later, which an etag already makes harmless.
     */
    void fanOut(WorkspaceClient source, FsMessages.FileChange change) {
        for (WorkspaceClient each : clients) {
            if (each != source) each.post(() -> each.deliver(List.of(change)));
        }
    }

    private void applyCapabilities(FsMessages.CapabilitiesNotification notification) {
        Map<String, FsMessages.ProjectCapability> next = new LinkedHashMap<>();
        for (FsMessages.ProjectCapability capability : notification.capabilities()) {
            next.put(capability.project(), capability);
        }
        capabilities = Collections.unmodifiableMap(next);
        eachClient(WorkspaceClient::capabilitiesMoved);
    }

    private void applyPresence(FsMessages.PresenceNotification notification) {
        Map<String, List<String>> open = new LinkedHashMap<>();
        Map<String, List<String>> typing = new LinkedHashMap<>();
        for (FsMessages.PresenceEntry entry : notification.entries()) {
            open.computeIfAbsent(entry.path(), key -> new ArrayList<>()).add(entry.who());
            if (entry.editing()) typing.computeIfAbsent(entry.path(), key -> new ArrayList<>()).add(entry.who());
        }
        serverOpen = open;
        serverEditing = typing;
        presenceMoved();
    }

    private void presenceMoved() {
        eachClient(WorkspaceClient::presenceMoved);
    }

    // ── Presence: the server's peers, and the other applications on this client ─────────────────

    /** Who else has {@code resource} open: the server's peers, then the other applications here. */
    List<String> whoElseHasOpen(WorkspaceClient me, Resource resource) {
        List<String> out = new ArrayList<>(serverOpen.getOrDefault(resource.toString(), List.of()));
        synchronized (lock) {
            Subscription have = watches.get(resource);
            if (have != null) {
                for (WorkspaceClient other : have.holders) {
                    if (other != me && !other.name().isEmpty() && !out.contains(other.name())) out.add(other.name());
                }
            }
        }
        return out;
    }

    /** Who has unsaved changes to {@code resource}: the server's peers, then the other applications here. */
    List<String> whoIsEditing(WorkspaceClient me, Resource resource) {
        List<String> out = new ArrayList<>(serverEditing.getOrDefault(resource.toString(), List.of()));
        synchronized (lock) {
            for (Map.Entry<WorkspaceClient, Set<Resource>> each : editing.entrySet()) {
                WorkspaceClient other = each.getKey();
                if (other != me && !other.name().isEmpty() && each.getValue().contains(resource)
                        && !out.contains(other.name())) {
                    out.add(other.name());
                }
            }
        }
        return out;
    }

    void noteEditing(WorkspaceClient who, Resource resource, boolean dirty) {
        onServices(() -> {
            Set<Resource> mine = editing.computeIfAbsent(who, key -> new LinkedHashSet<>());
            if (dirty ? mine.add(resource) : mine.remove(resource)) presenceMoved();
        });
    }

    // ── Reconnect ───────────────────────────────────────────────────────────────────────────────

    /** The wire moved: re-asks the greeting and re-subscribes everything watched. */
    public void rebind(CgProtocolConnection<Object> connection) {
        rebind(connection::call, connection::onNotify, connection.ops());
    }

    public void rebind(FsCall.Caller<Object> next, WorkspaceClient.Subscriber subscriber, CgDynamicOps<Object> nextOps) {
        onServices(() -> {
            // NOT A NEW OBJECT: every application holds a client of this one. What moves is where its calls go and
            // who it is listening to. A subscription is an intent to be re-issued, never a record to be trusted:
            // "already asked" is a fact about a peer, and a reconnect is a different one.
            this.caller = next;
            this.ops = nextOps;
            calls.rebind(homed());
            subscribe(subscriber);
            capabilities = Map.of();
            serverOpen = Map.of();
            serverEditing = Map.of();
            greeted = false;
            greetNow();
            for (Map.Entry<Resource, Subscription> each : new ArrayList<>(watches.entrySet())) {
                sendWatch(each.getKey(), each.getValue().recursive);
            }
            eachClient(WorkspaceClient::wireMoved);
        });
    }

    /** Detaches an application's client: its watches are released and others stop seeing it. */
    void release(WorkspaceClient client) {
        clients.remove(client);
        onServices(() -> {
            for (Resource resource : new ArrayList<>(watches.keySet())) unwatch(client, resource);
            editing.remove(client);
            presenceMoved();
        });
    }

    /** How many applications hold a client now. */
    public int clientCount() {
        return clients.size();
    }

    /** Stops listening for contributions. The wire is the connection's. */
    public void dispose() {
        if (contributions != null) contributions.disconnect();
        for (WorkspaceClient each : new ArrayList<>(clients)) each.dispose();
    }
}
