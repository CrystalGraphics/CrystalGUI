package com.crystalgui.net;

import com.crystalgui.ui.dom.TreeSource;
import com.crystalgui.style.Styleable;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.mirror.ClientTreeMirror;
import com.crystalgui.net.mirror.NodeMirror;
import com.crystalgui.net.protocol.*;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.RateGate;
import com.crystalgui.ui.contract.RatePolicy;
import java.util.LinkedHashMap;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The client's half: rebuilds the tree a server described, and holds a content-addressed cache of
 * descriptions.
 *
 * <h3>The cache</h3>
 * <p>Descriptions are keyed by content hash, so invalidation does not exist as a problem — a changed
 * UI is simply a different key, and a stale entry can never be served. A UI the client has opened
 * before therefore costs one {@code OpenWindow} packet and nothing else, whatever its size.</p>
 *
 * <p>Scoped to this session for now. Persisting it across restarts is purely additive precisely
 * because the store is content-addressed.</p>
 */
public final class ClientUiSession<N extends Styleable, T> {

    private final DynamicOps<T> ops;

    /** hash → encoded description. Encoded, not decoded: a decoded tree is live elements, and a live
     * element can only be attached in one place, so a cache of them would be a cache of things that
     * cannot be reused. */
    private final Map<String, T> descriptionCache = new ConcurrentHashMap<>();

    private final Deque<T> mailbox = new ArrayDeque<>();

    /** Everything inbound goes through here; nothing is dispatched by type. @see #registerUiMethods */
    private final MessageRouter<T> router;

    /** False when a {@link ProtocolConnection} drains and expires for us. @see #tick() */
    private final boolean ownsConnection;

    /** How long a call waits before its error handler is told. @see ServerUiSession */
    private long callTimeoutMillis = 10_000L;

    private int windowId = -1;

    /**
     * Where handlers are registered, or {@code null} when they go straight on the router.
     *
     * <p>Non-null only in the BOUND shape — a session created by {@link ClientUiSessions} for a window
     * id it has already been told. The two other shapes (owning a transport, or riding a connection as
     * the only window on it) register directly and behave exactly as they did before 5.7, which is why
     * {@code ui/openWindow} is still theirs to listen for.</p>
     */
    @Nullable
    private UiWindowMux<T> mux;

    /** Told when this session lets go of its window, so {@link ClientUiSessions} can drop it. */
    @Nullable
    Runnable onReleased;
    private int expectedElementCount = -1;
    @Nullable
    private N root;

    /**
     * The client half of the seam -- the id table for whatever tree is currently mounted.
     *
     * <p>Replaced wholesale whenever the root is, because the numbering is derived from that tree by a
     * walk both sides run: a table outliving the tree it describes is a set of numbers nobody agrees
     * on. Null exactly when {@link #root} is.</p>
     */
    private TreeSource<N> ids;

    /**
     * <b>The mirror.</b> Applying an edit script and a delta lives there, written against the
     * {@code ui.dom} seam and naming no widget, no session and no transport.
     *
     * <p>Rebuilt whenever the tree is, since a mirror is about one tree. Null until a window opens,
     * which is also when {@link #ids} appears.</p>
     */
    @Nullable private ClientTreeMirror<N, T> mirror;

    /** How a {@code N} is described. Outlives any one tree, so it is built once. */
    private final NodeMirror<N, T> nodes;
    private List<SheetRef> sheets = List.of();
    private boolean useUserAgentSheet = true;

    /** What the server said this window is, for a client that dispatches behaviour on it. */
    private String type = "";
    private String title = "";
    @Nullable
    private String key;

    @Nullable
    private Consumer<N> onWindowOpened;
    @Nullable
    /**
     * Told {@code (code, detail)} when the server ends the window.
     *
     * <p>Both, because two consumers want different halves and neither is wrong: a PANEL branches on
     * the code, which has to mean the same thing on either side of the wire, while a HOST shows the
     * detail — "the block was broken" is what a player should read, and {@code SERVER} is not.</p>
     */
    private java.util.function.BiConsumer<String, String> onWindowClosed;

    /** Owns its own transport, router and mailbox — the shape every test and the in-memory pair use. */
    public ClientUiSession(NodeMirror<N, T> nodes, UITransport<T> transport, DynamicOps<T> ops) {
        this.ops = ops;
        this.nodes = nodes;
        this.ownsConnection = true;
        this.router = new MessageRouter<>(envelope -> transport.send(EnvelopeCodec.encode(ops, envelope)));
        registerUiMethods();
        transport.setReceiver(packet -> {
            synchronized (mailbox) {
                mailbox.add(packet);
            }
        });
    }

    /**
     * Rides a connection somebody else owns, so this window shares one wire with every other subsystem.
     *
     * <p>The other constructor is still correct and is what a test uses: it owns its transport, its
     * router and its mailbox. This one owns none of them — {@link ProtocolConnection#tick()} drains and
     * expires, and {@link #tick()} here only flushes what the tree changed.</p>
     *
     * <p><b>The only window on this connection.</b> Registrations go straight on the router, exactly as
     * they did before 5.7 — so a second of these still throws on a duplicate {@code ui/openWindow}, and
     * that is still the right failure. For more than one window use {@link ClientUiSessions}, which owns
     * the bootstrap message and hands each window a session bound to its id.</p>
     *
     * <p>Kept rather than folded into the host because it is genuinely the common case and costs a
     * lookup less: a client with one window has nothing to demultiplex.</p>
     */
    public ClientUiSession(NodeMirror<N, T> nodes, ProtocolConnection<T> connection) {
        this.ops = connection.ops();
        this.nodes = nodes;
        // A held report must still leave when nothing else is happening, and this session
        // does not own the tick that would otherwise do it. @see #flushRates
        connection.onTick(this::flushRates);
        this.ownsConnection = false;
        this.router = connection.router();
        registerUiMethods();
    }

    /**
     * Rides a connection <b>as one of several windows</b>, bound to an id somebody already knows.
     *
     * <p>Built by {@link ClientUiSessions}, which owns {@code ui/openWindow} for the connection and is
     * therefore the only thing that can know the id before the window exists. That asymmetry with the
     * server is not incidental: a {@code ServerUiSession} is <em>given</em> its id at construction, while
     * a client learns one from the wire — so on this side the bootstrap message cannot itself be
     * window-scoped, and something has to own it.</p>
     */
    ClientUiSession(NodeMirror<N, T> nodes, ProtocolConnection<T> connection, int windowId) {
        this.ops = connection.ops();
        this.nodes = nodes;
        // A held report must still leave when nothing else is happening, and this session
        // does not own the tick that would otherwise do it. @see #flushRates
        connection.onTick(this::flushRates);
        this.ownsConnection = false;
        this.router = connection.router();
        this.mux = UiWindowMux.of(connection);
        this.windowId = windowId;
        registerWindowMethods();
    }

    /** Fired once the tree exists — where a host would hand it to a {@code UIWindow} and render it. */
    public ClientUiSession<N, T> onWindowOpened(Consumer<N> handler) {
        this.onWindowOpened = handler;
        return this;
    }

    public ClientUiSession<N, T> onWindowClosed(java.util.function.BiConsumer<String, String> handler) {
        this.onWindowClosed = handler;
        return this;
    }

    /** The server asked for this window to be brought forward. @see UiMethods#FOCUS_WINDOW */
    public ClientUiSession<N, T> onFocusRequested(Runnable handler) {
        this.onFocusRequested = handler;
        return this;
    }

    @Nullable
    private Runnable onFocusRequested;

    @Nullable
    public N root() {
        return root;
    }

    public int windowId() {
        return windowId;
    }

    public List<SheetRef> sheets() {
        return sheets;
    }

    public boolean useUserAgentSheet() {
        return useUserAgentSheet;
    }

    /**
     * What kind of window this is — {@code "mymod:machine"}, or {@code ""} from a server that named
     * none.
     *
     * <p>What a client dispatches local behaviour on. Empty is not an error: a window with no type
     * still renders and still reports every event its description asked for, because a description is
     * self-sufficient. It simply has no local extras. @see UiMethods#TYPE</p>
     */
    public String type() {
        return type;
    }

    /** What to call it on screen, or {@code ""} — the frame falls back to whatever it wants. */
    public String title() {
        return title;
    }

    /** The wire format — always the connection's own. @see ServerUiSession#ops() */
    public DynamicOps<T> ops() {
        return ops;
    }

    /** Its uniqueness and persistence key, or {@code null} where the server named none. */
    @Nullable
    public String key() {
        return key;
    }

    public boolean hasCached(String descHash) {
        return descriptionCache.containsKey(descHash);
    }

    public int cacheSize() {
        return descriptionCache.size();
    }

    /** Processes everything that arrived. Called on the thread that owns the tree — never from the
     * transport's own thread, since elements are single-threaded by contract. */
    public void tick() {
        // BEFORE the guard below: a rate-limited report is held on THIS side, so it must still leave
        // when the session is riding somebody else's connection -- which is every real client.
        flushRates();
        // Riding a connection means somebody else drains and expires; there is nothing left to do here.
        if (!ownsConnection) return;
        List<T> batch;
        synchronized (mailbox) {
            if (mailbox.isEmpty()) return;
            batch = new ArrayList<>(mailbox);
            mailbox.clear();
        }
        for (T raw : batch) {
            Envelope envelope;
            try {
                envelope = EnvelopeCodec.decode(ops, raw);
            } catch (RuntimeException malformed) {
                CrystalGuiCore.LOGGER.warn("Dropping an undecodable message: {}", malformed.getMessage());
                continue;
            }
            router.accept(envelope);
        }
        router.tickTimeouts(System.currentTimeMillis());
    }

    /** The UI half of the vocabulary. RPC methods register themselves through {@link #onCall}. */
    private void registerUiMethods() {
        router.onNotify(UiMethods.OPEN_WINDOW, payload -> acceptOpenWindow(read(payload)));
        registerWindowMethods();
    }

    /**
     * Takes an {@code ui/openWindow} body, whoever read it off the wire.
     *
     * <p>Package-private so {@link ClientUiSessions} can hand one over: that class owns the notification
     * for the whole connection, because the bootstrap message is the one thing in the vocabulary that
     * cannot be routed by a window id — it is what <em>announces</em> the id.</p>
     */
    void acceptOpenWindow(StateMap<T> in) {
        {
            int protocol = in.getInt("protocol", -1);
            int id = in.getInt(UiMethods.WINDOW, -1);
            if (protocol != EnvelopeCodec.VERSION) {
                // Refuse rather than open something we will misread. A version mismatch that opens
                // anyway shows up as a UI that is subtly wrong, which is far harder to trace than a
                // window that plainly did not appear.
                CrystalGuiCore.LOGGER.error("Refusing window {}: server protocol {} but this client "
                        + "speaks {}", id, protocol, EnvelopeCodec.VERSION);
                return;
            }
            this.windowId = id;
            this.sheets = in.getList("sheets", entry -> SheetRef.CODEC.decode(ops, entry.encode()));
            this.useUserAgentSheet = in.getBool("ua", false);
            this.expectedElementCount = in.getInt("count", 0);
            // FALLBACKS, so a server that predates these fields opens exactly as it always did.
            this.type = in.getString(UiMethods.TYPE, "");
            this.title = in.getString(UiMethods.TITLE, "");
            String named = in.getString(UiMethods.KEY, "");
            this.key = named.isEmpty() ? null : named;

            String hash = in.getString("hash", "");
            T cached = descriptionCache.get(hash);
            if (cached != null) {
                buildFrom(cached);
                return;
            }
            // A REQUEST now, where it used to be a bare RequestDescription with nothing tying the answer
            // back to it. The id correlates, so two opens in flight cannot cross their descriptions.
            StateMap<T> ask = new StateMap<>(ops);
            ask.putInt(UiMethods.WINDOW, id);
            ask.putString("hash", hash);
            router.request(UiMethods.DESCRIPTION, ask.encode(),
                    answer -> {
                        StateMap<T> body = read(answer);
                        T encoded = body.getRaw("root");
                        if (encoded == null) return;
                        descriptionCache.put(body.getString("hash", hash), encoded);
                        buildFrom(encoded);
                    },
                    error -> CrystalGuiCore.LOGGER.warn("Description for window {} refused: {}", id, error));
        }
    }

    /** Everything that is scoped to one window. Registered through the mux when there is one. */
    private void registerWindowMethods() {
        /*
         * C2. Replaces an anchor's described children, then RE-DERIVES every id.
         *
         * The design that looked impossible assumed ids had to be stable. They are a depth-first
         * position, so an insertion renumbers everything after it -- but they only have to be AGREED,
         * and two peers applying the same delta to the same tree in the same order agree by
         * construction. Nothing carries an id table and the description stays a pure description.
         *
         * The count is the same cross-check open() uses. A disagreement means the two sides built
         * different structure from the same description -- a widget constructor that differs between
         * versions, which no description can reveal -- and it is refused rather than misapplied,
         * because every id past the divergence would be off by one.
         */
        bindNotify(UiMethods.TREE_OPS, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId) return;
            // Queued, never dropped, if it beats the description: the open carries a hash, so a
            // client without the tree has to ask for it, and nothing tells the server the far side is
            // not ready. A dropped delta is permanent -- Property.set returns early on an unchanged
            // value, so a widget the server has already written is never marked dirty again.
            if (defer(() -> applyTreeOps(in))) return;
            applyTreeOps(in);
        });

        bindNotify(UiMethods.STATE_DELTA, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId) return;
            if (defer(() -> applyStateDelta(in))) return;
            applyStateDelta(in);
        });
        // BRING IT FORWARD. What re-opening an already-open window means: the tree, the scroll position
        // and whatever is half-typed all stay, and only the compositor is asked to do something. A
        // re-sent ui/openWindow would work and would throw away exactly the state the window was kept
        // for. @see UiMethods#FOCUS_WINDOW
        bindNotify(UiMethods.VIEW, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId || root == null) return;
            String command = in.getString(ViewCommand.CMD, "");
            if (!ViewCommand.ALL.contains(command)) {
                // A closed vocabulary, and this is where that is enforced. A server naming a method the
                // client would then look up is the shape that turns a remote UI into a remote-code
                // surface.
                CrystalGuiCore.LOGGER.warn("Window {}: refusing an unknown view command '{}'",
                        windowId, command);
                return;
            }
            if (onViewCommand != null) onViewCommand.accept(command, in);
        });

        bindNotify(UiMethods.FOCUS_WINDOW, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId || root == null) return;
            if (onFocusRequested != null) onFocusRequested.run();
        });

        bindNotify(UiMethods.CLOSE_WINDOW, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId) return;
            // THE CODE, not the sentence. The detail is a human-readable string for a log; what a
            // panel branches on has to mean the same thing on both sides, which is what it did not
            // before -- the server was handed a reason NAME and the client this detail, so the same
            // teardown saw "NOT_VALID" on one side and "no longer valid" on the other.
            String code = in.getString("code", "");
            String detail = in.getString("reason", "");
            if (!detail.isEmpty()) {
                CrystalGuiCore.LOGGER.debug("Window {} closed by the server: {} ({})",
                        windowId, code, detail);
            }
            commitRates();
            root = null;
            ids = null;
            mirror = null;
            deferred.clear();
            release();
            if (onWindowClosed != null) onWindowClosed.accept(code, detail);
        });
    }

    /** @see #registerWindowMethods */
    /**
     * Told about each {@link ViewCommand} that arrives, already checked against the vocabulary.
     *
     * <p>The session carries them and does not act on them: focusing an element, showing a dialog and
     * retitling a window are UI-layer operations, and {@code net} deliberately does not reach into
     * {@code ui.elements}. {@code ClientWindows} installs the applier.</p>
     */
    @Nullable
    private java.util.function.BiConsumer<String, StateMap<T>> onViewCommand;

    /**
     * This window's id table, for a caller that has to resolve an id the server sent.
     *
     * <p>Null before a window opens. Read-only in practice — allocating or releasing here would put a
     * second numbering beside the mirror's.</p>
     */
    @Nullable
    public com.crystalgui.ui.dom.TreeSource<N> ids() {
        return ids;
    }

    /** @see #onViewCommand */
    public ClientUiSession<N, T> onViewCommand(
            @Nullable java.util.function.BiConsumer<String, StateMap<T>> handler) {
        this.onViewCommand = handler;
        return this;
    }

    /**
     * True only while {@link #applyStateDelta} is running — the one window in which a widget's change
     * signal is the server's doing rather than the user's.
     */
    private boolean applyingDelta;

    /** @see #registerWindowMethods */
    private void applyStateDelta(StateMap<T> in) {
        /*
         * NOTHING APPLIED HERE IS A USER INTERACTION, and saying so is the whole of the guard.
         *
         * Applying a delta calls the widget's ordinary setter, which fires the widget's ordinary
         * change signal -- and that signal is precisely what wireReportedEvents hung the report on. So
         * the server moving a slider made every client receiving it tell the server that the USER had
         * moved it, one report per viewer, for a gesture nobody made.
         *
         * It stayed invisible because it is harmless in the common case and only there: the echo
         * carries the value the server just sent, so the handler writes the model back to what it
         * already holds and Property.set returns early. It stops being harmless the moment a handler
         * COUNTS anything or records who did it -- and with two viewers it is attributed to the wrong
         * player, which is the version that cannot be shrugged off.
         *
         * shouldSuppress below is this same loop noticed from the one place it was visible: a delta
         * landing on a focused text field and resetting the caret mid-word. It stays -- it stops the
         * VALUE arriving, which is a different problem from the report leaving.
         */
        boolean wasApplying = applyingDelta;
        applyingDelta = true;
        try {
            applyEntries(in);
        } finally {
            applyingDelta = wasApplying;
        }
    }

    /**
     * Applies one delta batch, skipping anything the user is mid-edit in.
     *
     * <p>The three kinds an entry can carry, and what each is for, are on {@link NodeMirror}.</p>
     */
    /** @see ClientTreeMirror#applyStructure */
    private void applyTreeOps(StateMap<T> in) {
        if (mirror != null) mirror.applyStructure(in);
    }

    private void applyEntries(StateMap<T> in) {
        if (mirror != null) mirror.applyState(in, this::shouldSuppress);
    }

    /**
     * Registers a notification where this session's registrations go.
     *
     * <p>The {@code != windowId} check inside each handler is kept even in mux mode, where it can no
     * longer fail. It is not the routing — it never was — it is the guard against applying a message
     * that was in flight when the window closed, and a check that costs an int comparison is not worth
     * making conditional on which shape built the session.</p>
     */
    private void bindNotify(String method, MessageRouter.NotificationHandler<T> handler) {
        if (mux != null) mux.onNotify(windowId, method, handler);
        else router.onNotify(method, handler);
    }

    /**
     * Deltas that arrived <b>before the tree existed</b>, waiting for it.
     *
     * <h3>The window between opening and being able to draw</h3>
     *
     * <p>{@code ui/openWindow} carries a hash, so unless the description is already cached the client
     * has to <em>ask</em> for it — and the server is free to flush state in the meantime, because
     * nothing tells it the far side is not ready. Both delta handlers began
     * {@code if (… || root == null) return;}, so everything in that window was <b>silently dropped</b>.</p>
     *
     * <p>And permanently, which is what makes it worse than a dropped frame: {@code Property.set}
     * returns early on an unchanged value, so a widget the server has already written keeps its value
     * and is never marked dirty again. The client shows whatever the description said at open, forever,
     * for exactly the fields that changed early. A window whose first tick writes a status line loses
     * that line for the life of the window.</p>
     *
     * <p>Queued and replayed <b>in arrival order</b>, which is the whole correctness argument: ids are a
     * depth-first position, so a state delta computed after a renumber must be applied after the tree
     * delta that caused it. Bounded by one description round trip, cleared if the window is refused or
     * closed.</p>
     */
    private final Deque<Runnable> deferred = new ArrayDeque<>();

    /**
     * Told about each subtree an insert brings, so the mount can bind any nested panels inside it.
     *
     * <p>The session cannot bind panels itself -- it knows nothing about {@code Networked} -- and the
     * mount used to bind by walking from the ROOT, which is why a panel arriving through a delta was
     * never bound at all: the walk had already run, and nothing re-ran it. Per subtree, at the op, is
     * both cheaper and the only version that is correct.</p>
     */
    @Nullable
    private java.util.function.Consumer<N> onSubtreeInserted;

    /** @see #onSubtreeInserted */
    public void setOnSubtreeInserted(@Nullable java.util.function.Consumer<N> listener) {
        this.onSubtreeInserted = listener;
    }

    /**
     * Holds {@code apply} until the tree exists, and says whether it did.
     *
     * @return true when it was queued and the caller must not run it now
     */
    private boolean defer(Runnable apply) {
        if (root != null) return false;
        // MUTATION CHECK: drop the queue here and aStateChangeMadeWhileTheDescriptionIsStillInFlightIsNotLost
        // fails, which is the whole of what it is for.
        deferred.add(apply);
        return true;
    }

    /** Replays what arrived while the description was in flight, in order. */
    private void drainDeferred() {
        if (deferred.isEmpty()) return;
        List<Runnable> pending = new ArrayList<>(deferred);
        deferred.clear();
        for (Runnable apply : pending) {
            // A tree delta in the queue can refuse the window and null the root, at which point the
            // rest describe a tree that is no longer here.
            if (root == null) break;
            apply.run();
        }
    }

    /** Lets go of the window id, and of the mux slots held under it. */
    private void release() {
        if (mux != null && windowId >= 0) mux.release(windowId);
        windowId = -1;
        if (onReleased != null) onReleased.run();
    }

    private StateMap<T> read(@Nullable T payload) {
        return payload == null ? new StateMap<>(ops) : new StateMap<>(ops, payload);
    }

    /**
     * Don't overwrite what someone is actively editing.
     *
     * <p>Without this, typing into a field produces a report, the server echoes it back, and the
     * incoming value resets the caret mid-word. Narrow on purpose: only the focused element, and
     * only one that consumes text.</p>
     */
    private boolean shouldSuppress(N target) {
        return target.isFocused() && target.consumesTextInput();
    }

    /**
     * Builds the tree from a description, and takes its numbering from whichever kind it is.
     *
     * <p>A <b>pristine</b> description carries no ids, so both sides derive them from the same
     * document-order walk — which is what keeps it content-addressed and shareable between windows
     * showing the same thing. A <b>live</b> one carries each element's id, and is what a viewer joining
     * an already-reshaped window gets: ids stopped being derivable from position, so a newcomer cannot
     * compute the ones the existing viewers hold and has to be told them.</p>
     *
     * <p>The mirror is rebuilt with the source, both being about <em>this</em> tree.</p>
     */
    private void buildFrom(T encoded) {
        /*
         * CHECKED BEFORE ANYTHING IS BUILT.
         *
         * The count the server states is checked against what was decoded further down -- that is an
         * integrity check between two honest peers. This is the other question: whether to decode it at
         * all. A description naming a million elements is refused for the cost of reading the number,
         * where trusting it and counting afterwards means having already built the tree.
         */
        if (expectedElementCount > UiLimits.MAX_ELEMENTS_PER_WINDOW) {
            CrystalGuiCore.LOGGER.error("Refusing window {}: it describes {} elements, and the cap is "
                    + "{}. A UI this large wants streamed rows rather than a bigger tree.",
                    windowId, expectedElementCount, UiLimits.MAX_ELEMENTS_PER_WINDOW);
            windowId = -1;
            deferred.clear();
            return;
        }

        Map<N, Integer> carried = new java.util.LinkedHashMap<>();
        N rebuilt = nodes.decodeLive(encoded, carried::put);
        TreeSource<N> rebuiltIds = nodes.sourceOver(rebuilt);
        ClientTreeMirror<N, T> rebuiltMirror = new ClientTreeMirror<>(rebuiltIds, nodes, ops);

        for (Map.Entry<N, Integer> entry : carried.entrySet()) {
            rebuiltIds.assignAt(entry.getKey(), entry.getValue());
        }
        int actual = rebuiltMirror.number(rebuilt, carried.size());

        /*
         * THE COUNT, AND WHAT IT NOW MEANS.
         *
         * It counts DESCRIBED elements, not every element. It used to count both, so a client whose
         * widget constructor built one more internal child than the server's refused the whole window
         * -- and the description could not reveal why, because internals are never serialized. Since
         * internals are no longer numbered on either side, that skew is now invisible and harmless,
         * which is what it always should have been.
         *
         * What survives is the skew that genuinely breaks addressing: a registry or codec
         * disagreement, where the two sides build a different number of DESCRIBED elements.
         */
        if (actual != expectedElementCount) {
            CrystalGuiCore.LOGGER.error("Refusing window {}: the server described {} elements but "
                    + "rebuilding produced {}. The two sides disagree about the described tree — check "
                    + "for a version mismatch or an unregistered tag.", windowId, expectedElementCount, actual);
            windowId = -1;
            // Nothing to apply them to, and they describe a tree this client refused to build.
            deferred.clear();
            return;
        }

        root = rebuilt;
        ids = rebuiltIds;
        mirror = rebuiltMirror;
        mirror.onIrrecoverable(this::release);
        // ONE hook, doing both jobs a newly arrived subtree needs. Binding used to walk from the root,
        // so a nested panel arriving through a delta was never bound at all -- it drew correctly and
        // answered nothing.
        mirror.onSubtreeInserted(subtree -> {
            wireReportedEvents(subtree);
            if (onSubtreeInserted != null) onSubtreeInserted.accept(subtree);
        });

        wireReportedEvents(root);
        // BEFORE the callback, so a host mounting the tree sees the state the server has already sent
        // rather than the description's and one frame of catching up. @see #deferred
        drainDeferred();
        if (onWindowOpened != null) onWindowOpened.accept(root);
    }

    /**
     * Installs a listener for every interaction the description asked to hear about.
     *
     * <p>The client runs the real DOM dispatch — capture, target, bubble, hit-testing, all of it —
     * and only the outcome is reported. The server never sees a coordinate.</p>
     */
    /**
     * Attaches a listener for every kind this element was asked to report.
     *
     * <h3>What this replaces</h3>
     *
     * <p>A {@code switch} over kind names, each arm holding an {@code instanceof} chain -- the session
     * knew how to listen to a {@code Slider} because somebody had written
     * {@code if (element instanceof Slider slider)} inside it. Two consequences, both real: the
     * networking layer imported every widget it could hear from, and a widget outside the chain fell
     * to a {@code default} arm that logged <i>"which this client cannot observe"</i> and carried on --
     * so a {@code Dropdown}, a {@code TabView}, a {@code ColorSelector} and a {@code SplitView} could
     * be asked to report and silently would not.</p>
     *
     * <p>The widget now says how to listen to itself ({@link Event#attach}), so adding a reportable
     * widget touches the widget alone and this method never changes again.</p>
     */
    @SuppressWarnings("unchecked")
    private void wireReportedEvents(N element) {
        Set<String> requested = nodes.reportedEventsOf(element);
        if (!requested.isEmpty()) {
            WidgetContract<N> contract = WidgetContracts.of(element);
            for (String kind : requested) {
                Event<N, Object> event = contract == null
                        ? null : (Event<N, Object>) contract.event(kind);
                if (event == null) {
                    // Reachable only from a peer describing a kind this build's contract does not have
                    // -- a newer server against an older client. Warn and carry on, because refusing
                    // the whole window over one unlistenable event is a worse answer than a control
                    // that does not report.
                    CrystalGuiCore.LOGGER.warn(
                            "Description asked <{}> to report '{}', which its contract does not declare",
                            element.tagName(), kind);
                    continue;
                }
                event.attach(element, payload ->
                        reportRated(element, kind, event.rate(), event.encode(ops, payload)));
            }
        }
        for (N child : ids.childrenOf(element)) wireReportedEvents(child);
    }

    /**
     * A widget's own {@link RatePolicy}, applied on the way out.
     *
     * <p>Driven by the connection's tick, so a value held by a debounce still leaves when nothing else
     * is happening — a throttle clears itself only while the user keeps moving.</p>
     */
    private final RateGate<N, StateMap<T>> rates = new RateGate<>(this::report);

    /**
     * Where "now" comes from, for the rate policies. Replaceable so a test can step it rather than
     * sleep, and so a host that already has a tick clock can hand that over.
     */
    public ClientUiSession<N, T> setClock(java.util.function.LongSupplier clock) {
        rates.setClock(clock);
        return this;
    }

    /**
     * Reports at the rate the widget asked for.
     *
     * <p>The {@code applyingDelta} guard is this session's own and does not belong in the gate: a write
     * the server just handed us is not a user gesture, and only a session knows that. The gate decides
     * WHEN a report leaves; this decides whether it is a report at all.</p>
     */
    private void reportRated(N element, String kind, RatePolicy policy,
                             @Nullable StateMap<T> payload) {
        if (applyingDelta) return;
        rates.offer(element, kind, policy, payload);
    }

    /** @see RateGate#flush() */
    void flushRates() {
        rates.flush();
    }

    /** Sends everything held, whatever its policy says. What a close does, so nothing is lost. */
    private void commitRates() {
        rates.commit();
    }

    private void report(N element, String kind, @Nullable StateMap<T> payload) {
        // A report means "the user did this". A write we were just handed by the server is the one
        // thing that certainly is not. @see #applyStateDelta
        if (applyingDelta) return;
        StateMap<T> out = new StateMap<>(ops);
        out.putInt(UiMethods.WINDOW, windowId);
        out.putInt("nid", ids.idOf(element));
        out.putString("kind", kind);
        if (payload != null) out.putRaw("p", payload.encode());
        router.notify(UiMethods.EVENT, out.encode());
    }

    /**
     * Registers a client-side RPC method the server may call.
     *
     * <p>Window-scoped when this session is one of several, so two windows of the same application may
     * each offer the same method name. A method that belongs to the <em>connection</em> rather than to a
     * window — a workspace, a script runtime — registers on {@link ProtocolConnection} directly and is
     * shared by every window, which is what it wants.</p>
     */
    /**
     * The handler currently installed for each wire method this session owns.
     *
     * <p>An indirection, and the reason for it is the whole of why {@code client(io)} can run more than
     * once. A re-describe builds <b>fresh panel instances</b> over the fresh tree, so handlers
     * registered by the previous instance close over a panel that is now detached — they run, they
     * write widgets nothing draws, and nothing reports a problem. Re-running {@code client(io)} is the
     * fix, and it was blocked by the router refusing a second registration of the same method.</p>
     *
     * <p>So the ROUTER is registered once per method and dispatches through this map; re-registering
     * replaces the delegate. <b>That is not a weakening of the router's duplicate refusal</b>, which
     * exists to catch two different owners colliding on one name — within one session a repeated
     * qualified method is by construction the same panel rebinding itself, since nested panels are
     * prefixed by ids {@code ServerScope.attach} keeps unique.</p>
     */
    private final Map<String, Call.Handler<T>> callHandlers = new LinkedHashMap<>();

    private final Map<String, Consumer<StateMap<T>>> notifyHandlers = new LinkedHashMap<>();

    public ClientUiSession<N, T> onCall(String method, Call.Handler<T> handler) {
        if (callHandlers.put(method, handler) != null) return this;   // already routed; delegate swapped
        // Same handler type, so nothing that calls this moves; underneath, an RPC is now an ordinary
        // REQUEST and its correlation is the router's rather than a second id space of its own.
        MessageRouter.RequestHandler<T> bound = (payload, respond) ->
                callHandlers.get(method).invoke(read(payload), new Call.Responder<T>() {
                    @Override
                    public void ok(@Nullable StateMap<T> value) {
                        respond.ok(value == null ? null : value.encode());
                    }

                    @Override
                    public void fail(String error) {
                        respond.fail(error);
                    }
                });
        if (mux != null) mux.onRequest(windowId, method, bound);
        else router.onRequest(method, bound);
        return this;
    }

    /**
     * Calls a server-side method.
     *
     * <p>Stamped with this session's window on the way out, so the far side's {@link UiWindowMux} can
     * route it. The key is additive and a handler that does not read it is unaffected — which matters,
     * because a connection-scoped method registered straight on {@link ProtocolConnection} will receive
     * one of these unchanged and correctly ignore it.</p>
     */
    public void call(String method, @Nullable StateMap<T> args,
                     @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        StateMap<T> stamped = args == null ? new StateMap<>(ops) : args;
        stamped.putInt(UiMethods.WINDOW, windowId);
        router.request(method, stamped.encode(),
                value -> {
                    if (onResult != null) onResult.accept(read(value));
                },
                onError,
                System.currentTimeMillis() + callTimeoutMillis);
    }

    /**
     * Listens for a notification <b>on this window</b> — the mirror of
     * {@link ServerUiSession#onNotify}.
     *
     * <p>Registering on {@link ProtocolConnection#onNotify} instead is keyed by method name alone, so a
     * second window of the same application listening for the same thing is refused outright by the
     * router. Through here two windows may each name {@code app/announce} and each hear only their
     * own.</p>
     */
    public ClientUiSession<N, T> onNotify(String method, Consumer<StateMap<T>> handler) {
        if (notifyHandlers.put(method, handler) != null) return this;   // already routed
        bindNotify(method, payload -> notifyHandlers.get(method).accept(read(payload)));
        return this;
    }

    /** Tells the server, stamped with this window. Nothing comes back. */
    public void notify(String method, @Nullable StateMap<T> payload) {
        StateMap<T> stamped = payload == null ? new StateMap<>(ops) : payload;
        stamped.putInt(UiMethods.WINDOW, windowId);
        router.notify(method, stamped.encode());
    }

    /**
     * Tells the server the user closed this window, and stops serving it.
     *
     * <p>The client end of {@link UiMethods#CLOSE}. Local teardown happens either way — the window is
     * gone here whatever the far side does with the news — so this releases the mux slots and drops the
     * tree exactly as an incoming {@code ui/closeWindow} would, without waiting for one to come back.
     * Idempotent, and silent once the window has already gone.</p>
     */
    public void closeFromClient(String reason) {
        if (windowId < 0) return;
        StateMap<T> out = new StateMap<>(ops);
        out.putString("reason", reason == null ? "" : reason);
        notify(UiMethods.CLOSE, out);
        root = null;
        ids = null;
        release();
    }

    /** Tells the server whether this window is on screen. @see UiMethods#VISIBILITY */
    public void reportVisibility(boolean visible) {
        if (windowId < 0) return;
        StateMap<T> out = new StateMap<>(ops);
        out.putBool("visible", visible);
        notify(UiMethods.VISIBILITY, out);
    }

    /**
     * Asks the server for the stylesheet behind a hash. @see UiMethods#SHEET
     *
     * <p>Content-addressed, so the answer is cacheable forever and a client that already has the hash
     * need never ask. Both callbacks run on the thread that ticked the connection.</p>
     */
    public void requestSheet(String hash, Consumer<String> onCss, Consumer<String> onError) {
        StateMap<T> ask = new StateMap<>(ops);
        ask.putString("hash", hash);
        call(UiMethods.SHEET, ask,
                answer -> onCss.accept(answer.getString("css", "")),
                onError);
    }
}
