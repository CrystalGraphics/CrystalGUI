package com.crystalgui.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.MessageRouter;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.TextField;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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
public final class ClientUiSession<T> {

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
    private UIElement root;
    private List<SheetRef> sheets = List.of();
    private boolean useUserAgentSheet = true;

    /** What the server said this window is, for a client that dispatches behaviour on it. */
    private String type = "";
    private String title = "";
    @Nullable
    private String key;

    @Nullable
    private Consumer<UIElement> onWindowOpened;
    @Nullable
    private Consumer<String> onWindowClosed;

    /** Owns its own transport, router and mailbox — the shape every test and the in-memory pair use. */
    public ClientUiSession(UITransport<T> transport, DynamicOps<T> ops) {
        this.ops = ops;
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
    public ClientUiSession(ProtocolConnection<T> connection) {
        this.ops = connection.ops();
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
    ClientUiSession(ProtocolConnection<T> connection, int windowId) {
        this.ops = connection.ops();
        this.ownsConnection = false;
        this.router = connection.router();
        this.mux = UiWindowMux.of(connection);
        this.windowId = windowId;
        registerWindowMethods();
    }

    /** Fired once the tree exists — where a host would hand it to a {@code UIWindow} and render it. */
    public ClientUiSession<T> onWindowOpened(Consumer<UIElement> handler) {
        this.onWindowOpened = handler;
        return this;
    }

    public ClientUiSession<T> onWindowClosed(Consumer<String> handler) {
        this.onWindowClosed = handler;
        return this;
    }

    /** The server asked for this window to be brought forward. @see UiMethods#FOCUS_WINDOW */
    public ClientUiSession<T> onFocusRequested(Runnable handler) {
        this.onFocusRequested = handler;
        return this;
    }

    @Nullable
    private Runnable onFocusRequested;

    @Nullable
    public UIElement root() {
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
        bindNotify(UiMethods.TREE_DELTA, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId || root == null) return;

            for (StateMap<T> entry : in.getList("entries", e -> e)) {
                int nid = entry.getInt("nid", -1);
                UIElement anchor = NetworkIds.find(root, nid);
                if (anchor == null) {
                    CrystalGuiCore.LOGGER.warn("Tree delta for unknown element {}", nid);
                    continue;
                }
                anchor.clearDescribedChildrenFor();
                T children = entry.getRaw("children");
                if (children == null) continue;
                for (T child : ops.getListValue(children)) {
                    UIElement decoded = UIDescriptionCodec.CODEC.decode(ops, child);
                    anchor.addDescribedChildFrom(decoded);
                    // WIRE THE NEW SUBTREE. A reported event is a listener this side attaches because
                    // the description asked for it, and a delta brings elements that have never been
                    // through buildFrom -- so without this a widget added after open renders correctly,
                    // responds to the mouse, and reports nothing at all to the server.
                    wireReportedEvents(decoded);
                }
            }

            int assigned = NetworkIds.assign(root);
            int expected = in.getInt("count", assigned);
            if (assigned != expected) {
                CrystalGuiCore.LOGGER.error("Refusing a tree delta: the server numbered {} elements and "
                        + "this client derived {} — the two sides are building different structure, so "
                        + "every id past the divergence would be wrong", expected, assigned);
                root = null;
                release();
                return;
            }
            expectedElementCount = assigned;
        });

        bindNotify(UiMethods.STATE_DELTA, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId || root == null) return;
            for (StateMap<T> entry : in.getList("entries", e -> e)) {
                int nid = entry.getInt("nid", -1);
                UIElement target = NetworkIds.find(root, nid);
                if (target == null) {
                    CrystalGuiCore.LOGGER.warn("State update for unknown element {}", nid);
                    continue;
                }
                if (shouldSuppress(target)) continue;
                T state = entry.getRaw("s");
                if (state == null) continue;
                try {
                    target.readStateFrom(new StateMap<>(ops, state));
                } catch (RuntimeException bad) {
                    // Per-entry, so one malformed update cannot take the rest of the batch with it.
                    CrystalGuiCore.LOGGER.warn("Bad state for element {}: {}", nid, bad.getMessage());
                }
            }
        });

        // BRING IT FORWARD. What re-opening an already-open window means: the tree, the scroll position
        // and whatever is half-typed all stay, and only the compositor is asked to do something. A
        // re-sent ui/openWindow would work and would throw away exactly the state the window was kept
        // for. @see UiMethods#FOCUS_WINDOW
        bindNotify(UiMethods.FOCUS_WINDOW, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId || root == null) return;
            if (onFocusRequested != null) onFocusRequested.run();
        });

        bindNotify(UiMethods.CLOSE_WINDOW, payload -> {
            StateMap<T> in = read(payload);
            if (in.getInt(UiMethods.WINDOW, windowId) != windowId) return;
            String reason = in.getString("reason", "");
            root = null;
            release();
            if (onWindowClosed != null) onWindowClosed.accept(reason);
        });
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
    private boolean shouldSuppress(UIElement target) {
        return target.isFocused() && target.consumesTextInput();
    }

    private void buildFrom(T encoded) {
        UIElement rebuilt = UIDescriptionCodec.CODEC.decode(ops, encoded);
        int actual = NetworkIds.assign(rebuilt);

        // Ids are positions in a document-order walk, so they only agree if both sides built the same
        // structure. Internals are never serialized, so a client whose widget constructors differ from
        // the server's would shift every id past the difference and apply updates to the wrong
        // elements — silently. The count is the cheapest thing that catches it.
        if (actual != expectedElementCount) {
            CrystalGuiCore.LOGGER.error("Refusing window {}: server described {} elements but rebuilding "
                    + "produced {}. The two sides' widget constructors disagree — check for a version "
                    + "mismatch.", windowId, expectedElementCount, actual);
            windowId = -1;
            return;
        }

        root = rebuilt;
        wireReportedEvents(root);
        if (onWindowOpened != null) onWindowOpened.accept(root);
    }

    /**
     * Installs a listener for every interaction the description asked to hear about.
     *
     * <p>The client runs the real DOM dispatch — capture, target, bubble, hit-testing, all of it —
     * and only the outcome is reported. The server never sees a coordinate.</p>
     */
    private void wireReportedEvents(UIElement element) {
        for (String kind : element.getReportedEvents()) {
            switch (kind) {
                case UiEventKinds.ACTIVATE -> {
                    if (element instanceof Button button) {
                        button.attachListener(() -> report(element, kind, null));
                    }
                }
                case UiEventKinds.TOGGLE -> {
                    if (element instanceof Checkbox checkbox) {
                        checkbox.attachListener(checked -> report(element, kind,
                                new StateMap<T>(ops).putBool("checked", checked)));
                    } else if (element instanceof Switch toggle) {
                        toggle.attachListener(checked -> report(element, kind,
                                new StateMap<T>(ops).putBool("checked", checked)));
                    }
                }
                case UiEventKinds.VALUE -> {
                    if (element instanceof Slider slider) {
                        slider.attachListener(value -> report(element, kind,
                                new StateMap<T>(ops).putFloat("value", value)));
                    }
                }
                case UiEventKinds.TEXT -> {
                    if (element instanceof TextField field) {
                        field.attachListener(text -> report(element, kind,
                                new StateMap<T>(ops).putString("text", text)));
                    }
                }
                default -> CrystalGuiCore.LOGGER.warn(
                        "Description asked to report '{}' on <{}>, which this client cannot observe",
                        kind, element.tagName());
            }
        }
        for (UIElement child : element.getChildren()) wireReportedEvents(child);
    }

    private void report(UIElement element, String kind, @Nullable StateMap<T> payload) {
        StateMap<T> out = new StateMap<>(ops);
        out.putInt(UiMethods.WINDOW, windowId);
        out.putInt("nid", element.getNetworkId());
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
    public ClientUiSession<T> onCall(String method, Call.Handler<T> handler) {
        // Same handler type, so nothing that calls this moves; underneath, an RPC is now an ordinary
        // REQUEST and its correlation is the router's rather than a second id space of its own.
        MessageRouter.RequestHandler<T> bound = (payload, respond) ->
                handler.invoke(read(payload), new Call.Responder<T>() {
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
    public ClientUiSession<T> onNotify(String method, Consumer<StateMap<T>> handler) {
        bindNotify(method, payload -> handler.accept(read(payload)));
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
