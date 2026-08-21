package com.crystalgui.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.MessageRouter;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITreeObserver;

import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The server's half of a GUI: it owns the tree, keeps the behaviour, and never lays anything out.
 *
 * <h3>No {@code UIWindow} anywhere</h3>
 * <p>That absence is the entire headless story, and it is structural rather than a flag. No window
 * means no Taffy tree, no style engine, no layout pass — and therefore no path into text
 * measurement, which is the one thing in this engine that genuinely needs a font stack. A flag would
 * only be an assertion that something else is supposed to honour; not holding a window is a fact.</p>
 *
 * <p>This mirrors what LDLib2 does, incidentally: its {@code ModularUI.init} is
 * {@code @OnlyIn(Dist.CLIENT)} and its server tick only ticks elements and syncs.</p>
 *
 * <h3>One session per viewer</h3>
 * <p>Two players opening the same block get a tree each. Per-player UI state — which tab is showing,
 * what is half-typed in a field — is then independent by construction rather than by careful
 * bookkeeping. Fanning one tree out to several viewers needs per-viewer version state and is not
 * what this is.</p>
 */
public final class ServerUiSession<T> implements UITreeObserver {

    private final int windowId;
    private final UIElement root;
    private final DynamicOps<T> ops;

    private final List<SheetRef> sheets = new ArrayList<>();
    private boolean useUserAgentSheet = true;

    /** Arrives on the transport's thread, drained on ours. See {@link UITransport}. */
    private final Deque<T> mailbox = new ArrayDeque<>();

    private final Set<UIElement> dirtyState = new LinkedHashSet<>();
    private final Set<UIElement> dirtyIdentity = new LinkedHashSet<>();

    /** Element -> kind -> lambda. Lives here, never on the element: that is what keeps behaviour on
     * the side that owns it while the client holds only a description. */
    private final Map<UIElement, Map<String, Consumer<UiEventContext<T>>>> handlers = new LinkedHashMap<>();

    private String descHash;
    private T encodedDescription;
    /**
     * Everything inbound goes through here, and nothing is dispatched by type.
     *
     * <p>What used to be an {@code instanceof} chain over five packet types is now three registrations
     * in {@link #registerUiMethods}, and adding a sixth message touches this class alone rather than the
     * union, both codec switches and every session.</p>
     */
    private final MessageRouter<T> router;

    /**
     * How long a call waits before its error handler is told.
     *
     * <p>Kept as a field rather than folded into the router because it is a policy of <em>this</em>
     * session, not of the routing machinery: a UI call and a file transfer want different patience, and
     * the router already takes the deadline per request so it need not hold an opinion.</p>
     */
    private long callTimeoutMillis = 10_000L;

    private int elementCount;
    private boolean open = false;

    /** False when a {@link ProtocolConnection} drains and expires for us. @see #tick() */
    private final boolean ownsConnection;

    /** Owns its own transport, router and mailbox — the shape every test and the in-memory pair use. */
    public ServerUiSession(int windowId, UIElement root, UITransport<T> transport, DynamicOps<T> ops) {
        this.windowId = windowId;
        this.root = root;
        this.ops = ops;
        this.ownsConnection = true;
        this.router = new MessageRouter<>(envelope -> transport.send(EnvelopeCodec.encode(ops, envelope)));
        transport.setReceiver(packet -> {
            synchronized (mailbox) {
                mailbox.add(packet);
            }
        });
        registerUiMethods();
    }

    /**
     * Rides a connection somebody else owns, so this window shares one wire with every other subsystem.
     *
     * <p>The other constructor is still correct and is what a test uses: it owns its transport, its
     * router and its mailbox. This one owns none of them — {@link ProtocolConnection#tick()} drains and
     * expires, and {@link #tick()} here only flushes what the tree changed.</p>
     *
     * <p><b>One UI session per connection.</b> A second would register {@code ui/description} twice and
     * {@link MessageRouter} refuses a duplicate outright, which is the right failure: two windows on one
     * wire need the router to dispatch on the window id as well as the method, and that is the same
     * change multi-viewer fan-out needs. Until then, one window per peer is enforced rather than
     * assumed.</p>
     */
    public ServerUiSession(int windowId, UIElement root, ProtocolConnection<T> connection) {
        this.windowId = windowId;
        this.root = root;
        this.ops = connection.ops();
        this.ownsConnection = false;
        this.router = connection.router();
        registerUiMethods();
    }

    public int windowId() {
        return windowId;
    }

    public UIElement root() {
        return root;
    }

    public String descHash() {
        return descHash;
    }

    /** Stylesheets to apply, in order. Order is load-bearing: cross-sheet ties at equal specificity
     * fall back to registration order, so both sides must register identically. */
    public ServerUiSession<T> addSheet(SheetRef sheet) {
        sheets.add(sheet);
        return this;
    }

    public ServerUiSession<T> setUseUserAgentSheet(boolean use) {
        this.useUserAgentSheet = use;
        return this;
    }

    /**
     * Serializes the tree, starts observing it, and tells the client to open.
     *
     * <p>Only the hash goes out. The client asks for the body if it needs it, so re-opening a UI it
     * has already seen costs one small packet regardless of how large the tree is.</p>
     */
    public void open() {
        if (open) throw new IllegalStateException("Session " + windowId + " is already open");
        open = true;

        elementCount = NetworkIds.assign(root);
        encodedDescription = UIDescriptionCodec.CODEC.encode(ops, root);
        descHash = ContentHash.of(ops, encodedDescription);

        // Observe only after the snapshot: mutations before open are already in the description, and
        // marking them dirty would send a delta restating what was just sent.
        root.setObserver(this);
        dirtyState.clear();
        dirtyIdentity.clear();

        StateMap<T> out = new StateMap<>(ops);
        out.putInt("protocol", EnvelopeCodec.VERSION);
        out.putString("hash", descHash);
        out.putInt("count", elementCount);
        out.putBool("ua", useUserAgentSheet);
        List<T> encodedSheets = new ArrayList<>(sheets.size());
        for (SheetRef ref : sheets) encodedSheets.add(SheetRef.CODEC.encode(ops, ref));
        out.putRaw("sheets", ops.createList(encodedSheets));
        notifyClient(UiMethods.OPEN_WINDOW, out);
    }

    /**
     * Drains everything that arrived, then flushes once. Called from the host's tick.
     *
     * <p>Order matters. Draining <em>fully</em> before flushing is what makes a tick's worth of
     * handler-driven mutations collapse into one update: a handler that changes a widget ten times
     * marks it dirty ten times and is read once, at the end.</p>
     */
    public void tick() {
        if (!open) return;
        // Riding a connection means somebody else already drained and expired this frame. Doing it again
        // would be harmless on the mailbox (it is empty) and wrong on the timeouts, which would then be
        // swept once per session on a shared wire rather than once per connection.
        if (ownsConnection) {
            drainMailbox();
            router.tickTimeouts(System.currentTimeMillis());
        }
        flush();
    }

    /** Registers a server-side RPC method the client may call. */
    public ServerUiSession<T> onCall(String method, Call.Handler<T> handler) {
        // The handler type is unchanged, so nothing that calls this moves. What changed is underneath:
        // an RPC is now an ordinary REQUEST, so its correlation, timeout and "answer exactly once" are
        // the router's for every method rather than a second id space kept for this one.
        router.onRequest(method, (payload, respond) ->
                handler.invoke(read(payload), new Call.Responder<T>() {
                    @Override
                    public void ok(@Nullable StateMap<T> value) {
                        respond.ok(value == null ? null : value.encode());
                    }

                    @Override
                    public void fail(String error) {
                        respond.fail(error);
                    }
                }));
        return this;
    }

    /** Calls a client-side method. {@code onResult} may be null for fire-and-forget. */
    public void call(String method, @Nullable StateMap<T> args,
                     @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        router.request(method, args == null ? null : args.encode(),
                value -> {
                    if (onResult != null) onResult.accept(read(value));
                },
                onError,
                System.currentTimeMillis() + callTimeoutMillis);
    }

    // ── Server-held behaviour ───────────────────────────────────────────────

    /**
     * Runs {@code handler} when the client reports a {@code kind} interaction on {@code element}.
     *
     * <p>The lambda is recorded here, on the session, and never anywhere near the element — which is
     * what lets a server keep its behaviour while the client holds only a description. The element
     * learns the event's <em>name</em> so the client knows to report it; nothing else.</p>
     */
    public ServerUiSession<T> on(UIElement element, String kind, Consumer<UiEventContext<T>> handler) {
        if (open) {
            throw new IllegalStateException("Handlers must be registered before open() — the set of "
                    + "reported events is part of the description the client has already been sent");
        }
        element.addReportedEvent(kind);
        handlers.computeIfAbsent(element, e -> new LinkedHashMap<>()).put(kind, handler);
        return this;
    }

    /** A press, a toggle, or a commit — whatever the widget considers "the user did the thing". */
    public ServerUiSession<T> onActivate(UIElement element, Consumer<UiEventContext<T>> handler) {
        return on(element, UiEventKinds.ACTIVATE, handler);
    }

    /** What a handler is given. Carries no coordinates — see {@link UiMethods#EVENT}. */
    public record UiEventContext<T>(ServerUiSession<T> session, UIElement element, StateMap<T> payload) {
    }

    private void flush() {
        if (dirtyState.isEmpty()) return;
        List<T> entries = new ArrayList<>(dirtyState.size());
        for (UIElement element : dirtyState) {
            if (element.getNetworkId() < 0) continue;   // never numbered: not part of the open tree
            StateMap<T> state = new StateMap<>(ops);
            element.writeStateTo(state);
            StateMap<T> entry = new StateMap<>(ops);
            entry.putInt("nid", element.getNetworkId());
            entry.putRaw("s", state.encode());
            entries.add(entry.encode());
        }
        dirtyState.clear();
        dirtyIdentity.clear();
        if (entries.isEmpty()) return;
        StateMap<T> out = new StateMap<>(ops);
        out.putRaw("entries", ops.createList(entries));
        notifyClient(UiMethods.STATE_DELTA, out);
    }

    public void close(String reason) {
        if (!open) return;
        open = false;
        root.setObserver(null);
        StateMap<T> out = new StateMap<>(ops);
        out.putString("reason", reason == null ? "" : reason);
        notifyClient(UiMethods.CLOSE_WINDOW, out);
    }

    public boolean isOpen() {
        return open;
    }

    private void drainMailbox() {
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
                CrystalGuiCore.LOGGER.warn("Session {}: dropping an undecodable message: {}",
                        windowId, malformed.getMessage());
                continue;
            }
            router.accept(envelope);
        }
    }

    /** The UI half of the vocabulary. RPC methods register themselves through {@link #onCall}. */
    private void registerUiMethods() {
        router.onRequest(UiMethods.DESCRIPTION, (payload, respond) -> {
            StateMap<T> in = read(payload);
            if (!mine(in)) {
                respond.fail("wrong window");
                return;
            }
            String wanted = in.getString("hash", "");
            if (!wanted.equals(descHash)) {
                // Answered rather than dropped, which the old RequestDescription could not do: a client
                // asking for a description this session no longer serves now learns that, instead of
                // waiting on a reply that was never coming.
                respond.fail("this session serves " + descHash + ", not " + wanted);
                return;
            }
            StateMap<T> out = new StateMap<>(ops);
            out.putInt(UiMethods.WINDOW, windowId);
            out.putString("hash", descHash);
            out.putRaw("root", encodedDescription);
            respond.ok(out.encode());
        });

        router.onNotify(UiMethods.EVENT, payload -> {
            StateMap<T> in = read(payload);
            if (!mine(in)) return;
            int nid = in.getInt("nid", -1);
            UIElement element = NetworkIds.find(root, nid);
            if (element == null) {
                CrystalGuiCore.LOGGER.warn("Session {}: event for unknown element {}", windowId, nid);
                return;
            }
            String kind = in.getString("kind", "");
            var byKind = handlers.get(element);
            var handler = byKind == null ? null : byKind.get(kind);
            if (handler == null) {
                // A client reporting something nobody asked for. Not fatal, but not normal either --
                // it means the two sides disagree about the description.
                CrystalGuiCore.LOGGER.warn("Session {}: no handler for '{}' on element {}",
                        windowId, kind, nid);
                return;
            }
            T carried = in.getRaw("p");
            handler.accept(new UiEventContext<>(this, element,
                    carried == null ? new StateMap<>(ops) : new StateMap<>(ops, carried)));
        });
    }

    private StateMap<T> read(@Nullable T payload) {
        return payload == null ? new StateMap<>(ops) : new StateMap<>(ops, payload);
    }

    /**
     * Whether this message is for this window.
     *
     * <p>One transport serves one session, so this is not routing between concurrent windows: it is the
     * guard the old {@code packet.windowId() != windowId} check was — a message still in flight when a
     * window closed must not be applied to whatever session took its place.</p>
     */
    private boolean mine(StateMap<T> in) {
        return in.getInt(UiMethods.WINDOW, windowId) == windowId;
    }

    private void notifyClient(String method, StateMap<T> payload) {
        payload.putInt(UiMethods.WINDOW, windowId);
        router.notify(method, payload.encode());
    }

    // ── UITreeObserver ──────────────────────────────────────────────────────

    @Override
    public void onAttached(UIElement element) {
        // Structural deltas arrive in the next milestone; recorded now so nothing is lost meanwhile.
        dirtyIdentity.add(element);
    }

    @Override
    public void onDetached(UIElement element) {
        dirtyState.remove(element);
        dirtyIdentity.remove(element);
    }

    @Override
    public void onStateDirty(UIElement element) {
        dirtyState.add(element);
    }

    @Override
    public void onIdentityDirty(UIElement element) {
        dirtyIdentity.add(element);
    }

    /** Visible for tests until deltas exist — what the next flush would have to cover. */
    public Set<UIElement> pendingStateChanges() {
        return Set.copyOf(dirtyState);
    }
}
