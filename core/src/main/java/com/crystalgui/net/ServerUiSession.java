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

    /**
     * The CSS behind each sheet this session named, by hash — what {@code ui/sheet} answers with.
     *
     * <p>Only for sheets whose text was actually handed over. A {@link SheetRef} naming a theme the
     * client is expected to have locally carries no text and is not in here, which is the difference
     * between "here is a sheet" and "you already have this one".</p>
     */
    private final Map<String, String> sheetSource = new LinkedHashMap<>();

    /** What kind of window this is — the client dispatches its local behaviour on it. @see #setType */
    private String type = "";

    /** What to call it on screen. @see #setTitle */
    private String title = "";

    /** Uniqueness and persistence key, or null. @see #setKey */
    @Nullable
    private String key;

    /**
     * Whether the client is actually showing this window.
     *
     * <p>False suppresses the <b>entire</b> flush — structure as well as state, and that is not an
     * optimisation detail but the correctness argument. A tree delta renumbers both sides, so
     * suppressing the send while still renumbering here would leave the two peers disagreeing about
     * every id past the change, and the next state delta would land on the wrong elements. Gating above
     * both means the server's tree is simply not re-described while nobody is looking at it, and the
     * client's numbering stays the one it was last told. @see #setViewerVisible</p>
     */
    private boolean viewerVisible = true;

    /** Arrives on the transport's thread, drained on ours. See {@link UITransport}. */
    private final Deque<T> mailbox = new ArrayDeque<>();

    private final Set<UIElement> dirtyState = new LinkedHashSet<>();
    private final Set<UIElement> dirtyIdentity = new LinkedHashSet<>();

    /**
     * Elements whose described children changed — the input to {@code ui/treeDelta}.
     *
     * <p>Separate from {@link #dirtyIdentity}, which is about an element's own id/class/enabled state.
     * A structural change is about the SHAPE around an element, and the two coincide only by accident.</p>
     */
    private final Set<UIElement> structuralAnchors = new LinkedHashSet<>();

    /** Element -> kind -> lambda. Lives here, never on the element: that is what keeps behaviour on
     * the side that owns it while the client holds only a description. */
    private final Map<UIElement, Map<String, Consumer<UiEventContext<T>>>> handlers = new LinkedHashMap<>();

    private String descHash;
    private T encodedDescription;
    /**
     * One client watching this window: its router, and who it is.
     *
     * <p>{@code peer} is the platform's own handle, opaque here — it exists so a handler can tell
     * <em>which</em> viewer acted, which is the first thing that stops being obvious once there is more
     * than one.</p>
     */
    private static final class Viewer<T> {
        final MessageRouter<T> router;
        @Nullable
        final Object peer;

        /**
         * Where this viewer's handlers are registered, or {@code null} when they go straight on the
         * router.
         *
         * <p>Null is the OWNED-TRANSPORT shape, and it is not a degraded case: that router was built by
         * this session and serves nothing else, so there is no second window to be confused with and a
         * demultiplexer would only add a lookup. A viewer riding a {@link ProtocolConnection} shares its
         * router with every other subsystem and every other window, which is what the mux is for.</p>
         */
        @Nullable
        final UiWindowMux<T> mux;

        boolean opened;

        Viewer(MessageRouter<T> router, @Nullable Object peer, @Nullable UiWindowMux<T> mux) {
            this.router = router;
            this.peer = peer;
            this.mux = mux;
        }
    }

    /**
     * Everyone watching. Usually one; the point of C1 is that it need not be.
     *
     * <p>The tree is the session's, not a viewer's — so a fan-out is a list of <em>routers</em> rather
     * than a list of sessions over one tree. The alternative was rejected on a fact about the engine:
     * {@code UIElement.setObserver} holds ONE observer, so two sessions cannot both watch one tree, and
     * making it a list would put a per-viewer cost on every mutation in the application to serve a case
     * most windows never have.</p>
     */
    private final List<Viewer<T>> viewers = new ArrayList<>();

    /**
     * Every method {@link #onCall} registered, kept so a viewer that joins later gets them.
     *
     * <p>Without this a late joiner is served the UI vocabulary and refused every application method,
     * which fails as METHOD_NOT_FOUND on one client and works on another — a difference nothing in the
     * code would explain.</p>
     */
    private final Map<String, Call.Handler<T>> serverMethods = new LinkedHashMap<>();

    /** Every notification {@link #onNotify} listened for, replayed onto a viewer that joins later. */
    private final Map<String, Consumer<StateMap<T>>> serverNotifications = new LinkedHashMap<>();

    /**
     * How long a call waits before its error handler is told.
     *
     * <p>Kept as a field rather than folded into the router because it is a policy of <em>this</em>
     * session, not of the routing machinery: a UI call and a file transfer want different patience, and
     * the router already takes the deadline per request so it need not hold an opinion.</p>
     */
    private long callTimeoutMillis = 10_000L;

    /** What {@link #open()} sent, replayed verbatim to anyone who joins afterwards. */
    @Nullable
    private T openPayload;

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
        transport.setReceiver(packet -> {
            synchronized (mailbox) {
                mailbox.add(packet);
            }
        });
        addViewer(new MessageRouter<>(envelope -> transport.send(EnvelopeCodec.encode(ops, envelope))),
                null, null);
    }

    /**
     * Rides a connection somebody else owns, so this window shares one wire with every other subsystem.
     *
     * <p>The other constructor is still correct and is what a test uses: it owns its transport, its
     * router and its mailbox. This one owns none of them — {@link ProtocolConnection#tick()} drains and
     * expires, and {@link #tick()} here only flushes what the tree changed.</p>
     *
     * <p><b>As many windows per connection as you like</b>, since 5.7. This used to say the opposite,
     * and the reason is worth keeping: a second session registered {@code ui/description} twice and
     * {@link MessageRouter} refused the duplicate outright — correctly, since it keys by method name
     * alone. Registration now goes through {@link UiWindowMux}, which keys by {@code (method, window)}.
     * The per-handler {@code mine(…)} checks below are kept: they were the guard against a message
     * still in flight when a window closed, and that is a different question from routing.</p>
     */
    public ServerUiSession(int windowId, UIElement root, ProtocolConnection<T> connection) {
        this.windowId = windowId;
        this.root = root;
        this.ops = connection.ops();
        this.ownsConnection = false;
        addViewer(connection.router(), connection.peer(), UiWindowMux.of(connection));
    }

    // ── C1: fan-out ─────────────────────────────────────────────────────────

    /**
     * Adds a viewer. If the window is already open, it is told immediately.
     *
     * <p>That second half is what makes late joining work at all: a player who opens a shared window
     * after it was created must not wait for the next mutation to discover it exists.</p>
     *
     * <p><b>Viewers and windows are different axes, and both now work.</b> This adds a second <em>client
     * watching one window</em>; {@link UiWindowMux} is what allows a second <em>window on one client</em>.
     * They compose — the mux is per connection and this session registers into each viewer's own.</p>
     */
    public ServerUiSession<T> addViewer(ProtocolConnection<T> connection) {
        Viewer<T> viewer = addViewer(connection.router(), connection.peer(), UiWindowMux.of(connection));
        if (open) sendOpenTo(viewer);
        return this;
    }

    /**
     * Stops sending to a viewer. Safe to call for one that was never added.
     *
     * <p>Does not close the window: a window with no viewers left is a legitimate state — the server is
     * still holding the tree, and somebody may join. Closing it here would make a reconnect lose work.</p>
     */
    public boolean removeViewer(ProtocolConnection<T> connection) {
        MessageRouter<T> router = connection.router();
        // Released as well as dropped. A viewer removed without this leaves its (method, window) pairs
        // claimed on that connection's mux, so re-adding the same viewer -- a reconnect, a re-open --
        // throws "window N already serves 'ui/description'" for a window nobody is watching.
        boolean removed = viewers.removeIf(viewer -> {
            if (viewer.router != router) return false;
            if (viewer.mux != null) viewer.mux.release(windowId);
            return true;
        });
        return removed;
    }

    /** How many clients are watching. */
    public int viewerCount() {
        return viewers.size();
    }

    private Viewer<T> addViewer(MessageRouter<T> router, @Nullable Object peer,
                                @Nullable UiWindowMux<T> mux) {
        Viewer<T> viewer = new Viewer<>(router, peer, mux);
        viewers.add(viewer);
        registerUiMethods(viewer);
        return viewer;
    }

    /** Registers a request handler where this viewer's registrations go. @see Viewer#mux */
    private void bindRequest(Viewer<T> viewer, String method, MessageRouter.RequestHandler<T> handler) {
        if (viewer.mux != null) viewer.mux.onRequest(windowId, method, handler);
        else viewer.router.onRequest(method, handler);
    }

    /** Registers a notification handler where this viewer's registrations go. @see Viewer#mux */
    private void bindNotify(Viewer<T> viewer, String method, MessageRouter.NotificationHandler<T> handler) {
        if (viewer.mux != null) viewer.mux.onNotify(windowId, method, handler);
        else viewer.router.onNotify(method, handler);
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

    /**
     * Names a sheet <b>and offers its text</b>, so a client that cannot resolve the ref locally can ask
     * for it over {@code ui/sheet}.
     *
     * <p>Use this for a sheet the server authored. {@link #addSheet(SheetRef)} alone is right for a
     * sheet the client is expected to already have — a shipped theme named by id — where sending the
     * bytes would be sending something both sides already hold.</p>
     */
    public ServerUiSession<T> addSheet(SheetRef sheet, @Nullable String css) {
        sheets.add(sheet);
        if (css != null) sheetSource.put(sheet.hash(), css);
        return this;
    }

    public ServerUiSession<T> setUseUserAgentSheet(boolean use) {
        this.useUserAgentSheet = use;
        return this;
    }

    /**
     * What kind of window this is — {@code "mymod:machine"}. Travels on {@code ui/openWindow}.
     *
     * <p>What {@code S2DPacketOpenWindow}'s inventory type and {@code ClientboundOpenScreenPacket}'s
     * {@code MenuType} carry, and what a client dispatches its local behaviour on. Without it every
     * window on a connection looks alike to a client, so whichever subscriber ran first took all of
     * them — including another mod's.</p>
     *
     * <p><b>A client that does not recognise the type still shows the window.</b> That is the one place
     * this is better than Minecraft's, where an unknown {@code MenuType} is a broken screen: a
     * description is self-sufficient, so an unknown type renders and interacts correctly and merely has
     * no local extras.</p>
     */
    public ServerUiSession<T> setType(@Nullable String type) {
        this.type = type == null ? "" : type;
        return this;
    }

    /** What to call it on screen. The side that opens a window is the side that knows what it is. */
    public ServerUiSession<T> setTitle(@Nullable String title) {
        this.title = title == null ? "" : title;
        return this;
    }

    /**
     * Uniqueness and persistence key, or {@code null} for "always a new window".
     *
     * <p>Two things at once, and they agree: a host refuses to open a second window under a key already
     * open (Minecraft's close-the-previous-container rule, narrowed to the same <em>subject</em> rather
     * than applied to every window), and the client's frame takes it so the desktop can restore its
     * geometry.</p>
     */
    public ServerUiSession<T> setKey(@Nullable String key) {
        this.key = key;
        return this;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    @Nullable
    public String key() {
        return key;
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
        // setObserver walks the subtree and reports every element as attached, so the snapshot just
        // taken would otherwise be followed by a delta restating the whole tree.
        structuralAnchors.clear();

        rebuildOpenPayload();
        for (Viewer<T> viewer : viewers) sendOpenTo(viewer);
    }

    /**
     * Rebuilds what a viewer is told when it joins.
     *
     * <p>Called from {@link #open()} and again from {@link #flushStructure()}, and the second is the one
     * that was missing. <b>A tree delta changes the description hash</b>, so a payload captured at open
     * describes a tree the session no longer serves — and a viewer added after a reshape is told that
     * stale hash, asks for it, and is refused with <i>"this session serves X, not Y"</i>.</p>
     *
     * <p>Each feature was correct alone: C1 replays the payload so a late viewer sees exactly what the
     * first one saw, and C2 renumbers and re-hashes. Together they were not, which is what
     * {@code aViewerAddedAfterAReshapeStillGetsTheWindow} now pins — found by running both in one
     * client rather than by either test.</p>
     */
    private void rebuildOpenPayload() {
        StateMap<T> out = new StateMap<>(ops);
        out.putInt("protocol", EnvelopeCodec.VERSION);
        out.putString("hash", descHash);
        out.putInt("count", elementCount);
        out.putBool("ua", useUserAgentSheet);
        // ADDITIVE, and every reader takes a fallback -- so an older client ignores these and an older
        // server sends none, with no version bump and no negotiation. @see UiMethods#TYPE
        out.putString(UiMethods.TYPE, type);
        out.putString(UiMethods.TITLE, title);
        // OMITTED rather than written empty when absent: "" is a legal key and would be indistinguishable
        // from "this window has none", which is the difference between deduping and not.
        if (key != null) out.putString(UiMethods.KEY, key);
        List<T> encodedSheets = new ArrayList<>(sheets.size());
        for (SheetRef ref : sheets) encodedSheets.add(SheetRef.CODEC.encode(ops, ref));
        out.putRaw("sheets", ops.createList(encodedSheets));
        out.putInt(UiMethods.WINDOW, windowId);
        openPayload = out.encode();
    }

    /**
     * Tells one viewer the window exists.
     *
     * <p>The payload is built once in {@link #open()} and kept, so a viewer joining an hour later is sent
     * exactly what the first one saw. Rebuilding it per viewer would re-encode the description and
     * re-hash it, and any drift between the two would show as a client fetching a body that no longer
     * matches the hash it was given.</p>
     */
    private void sendOpenTo(Viewer<T> viewer) {
        if (viewer.opened || openPayload == null) return;
        viewer.opened = true;
        viewer.router.notify(UiMethods.OPEN_WINDOW, openPayload);
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
            long now = System.currentTimeMillis();
            for (Viewer<T> viewer : viewers) viewer.router.tickTimeouts(now);
        }
        flush();
    }

    /** Registers a server-side RPC method the client may call. */
    public ServerUiSession<T> onCall(String method, Call.Handler<T> handler) {
        // The handler type is unchanged, so nothing that calls this moves. What changed is underneath:
        // an RPC is now an ordinary REQUEST, so its correlation, timeout and "answer exactly once" are
        // the router's for every method rather than a second id space kept for this one.
        // On EVERY viewer, and on any that joins later -- remembered so addViewer can replay them.
        // A method served to whoever connected first and refused to everyone else is the shape of bug
        // that only appears on a busy server.
        serverMethods.put(method, handler);
        for (Viewer<T> viewer : viewers) registerCall(viewer, method, handler);
        return this;
    }

    /**
     * Listens for a notification <b>on this window</b>.
     *
     * <p>The half of the vocabulary a session did not have, and its absence pushed every caller onto
     * {@link ProtocolConnection#onNotify} — which is keyed by method name alone, so two windows of the
     * same application listening for the same thing meant the second one <em>threw at open</em>. The
     * worked example taught exactly that pattern, in a codebase whose whole point since 5.7 is that a
     * connection carries several windows.</p>
     *
     * <p>Window-scoped through {@link UiWindowMux}, so two windows may each name {@code app/ping} and
     * each hear only their own. A notification that belongs to the <em>connection</em> rather than to a
     * window — a workspace, a script runtime — still registers on {@code ProtocolConnection} directly
     * and is shared by every window, which is what it wants.</p>
     */
    public ServerUiSession<T> onNotify(String method, Consumer<StateMap<T>> handler) {
        // Remembered so a viewer added later gets it, exactly as onCall's methods are. A handler served
        // to whoever connected first and missing for everyone else is the shape of bug that only appears
        // on a busy server.
        serverNotifications.put(method, handler);
        for (Viewer<T> viewer : viewers) registerNotification(viewer, method, handler);
        return this;
    }

    /**
     * Tells every viewer of this window. Nothing comes back — that is what makes it a notification.
     *
     * <p>Stamped with the window on the way out, so the far side's mux can route it and a second window
     * of the same application hears nothing of it.</p>
     */
    public void notify(String method, @Nullable StateMap<T> payload) {
        notifyClient(method, payload == null ? new StateMap<>(ops) : payload);
    }

    /**
     * Calls a client-side method on the one viewer.
     *
     * <p>Throws when there is more than one, deliberately: a request has exactly one answer, so
     * "call the client" stops meaning anything the moment there are several. Use
     * {@link #callViewer} and say which.</p>
     */
    public void call(String method, @Nullable StateMap<T> args,
                     @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        if (viewers.size() != 1) {
            throw new IllegalStateException("call() is ambiguous with " + viewers.size()
                    + " viewers — use callViewer(peer, …) and name one");
        }
        request(viewers.get(0), method, args, onResult, onError);
    }

    /** Calls a client-side method on one named viewer. */
    public void callViewer(@Nullable Object peer, String method, @Nullable StateMap<T> args,
                           @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        for (Viewer<T> viewer : viewers) {
            if (viewer.peer == peer || (peer != null && peer.equals(viewer.peer))) {
                request(viewer, method, args, onResult, onError);
                return;
            }
        }
        if (onError != null) onError.accept("no such viewer");
    }

    private void request(Viewer<T> viewer, String method, @Nullable StateMap<T> args,
                         @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        // STAMPED, so the far side's mux can route it. Without this every session-scoped call arrives
        // with no window and is refused -- and it would be refused only once a SECOND window existed,
        // which is the shape of bug that ships. The key is additive and a handler that does not read it
        // is unaffected.
        StateMap<T> stamped = args == null ? new StateMap<>(ops) : args;
        stamped.putInt(UiMethods.WINDOW, windowId);
        viewer.router.request(method, stamped.encode(),
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
        /*
         * THE RULE IS NARROWER THAN IT USED TO BE, and the old one was broader than its own reason.
         *
         * It refused every registration after open(), justified by "the set of reported events is part
         * of the description the client has already been sent". True of an element the client HAS been
         * sent -- and a tree delta re-describes a subtree in full, reported events included, and
         * ClientUiSession.wireReportedEvents runs over every element a delta brings. So an element that
         * has never been numbered has not been described, and wiring it is not late at all.
         *
         * getNetworkId() < 0 is exactly that test: NetworkIds.assign numbers what open() and every
         * flushStructure describe, and a freshly built element reports -1 until it is in one of those.
         * Without this relaxation nothing could ever be added to a live window -- no fragment, no row,
         * no lazily-built page -- which is a much bigger prohibition than the sentence justifying it.
         */
        if (open && element.getNetworkId() >= 0) {
            throw new IllegalStateException("Handlers for an element the client has already been "
                    + "described must be registered before open() — the set of reported events is part "
                    + "of that description. An element added since (network id -1) may be wired now.");
        }
        Map<String, Consumer<UiEventContext<T>>> byKind =
                handlers.computeIfAbsent(element, e -> new LinkedHashMap<>());
        /*
         * REFUSED rather than replaced. This was a bare put: a second registration for the same
         * (element, kind) silently won, and which lambda ran was decided by registration order -- the
         * exact failure MessageRouter's duplicate check exists to prevent one layer down.
         *
         * It matters most for composition, where it is not a typo but a boundary: a parent that wires an
         * element its child already wired has reached inside the child, and the two behaviours cannot
         * both run. A parent that wants to KNOW rather than to intercept takes a plain Java callback
         * from the child instead.
         */
        if (byKind.containsKey(kind)) {
            throw new IllegalStateException("'" + kind + "' on <" + element.tagName()
                    + "> is already handled by this session; one handler per element and kind");
        }
        element.addReportedEvent(kind);
        byKind.put(kind, handler);
        return this;
    }

    /** A press, a toggle, or a commit — whatever the widget considers "the user did the thing". */
    public ServerUiSession<T> onActivate(UIElement element, Consumer<UiEventContext<T>> handler) {
        return on(element, UiEventKinds.ACTIVATE, handler);
    }

    /** What a handler is given. Carries no coordinates — see {@link UiMethods#EVENT}. */
    public record UiEventContext<T>(ServerUiSession<T> session, UIElement element, StateMap<T> payload) {
    }

    /**
     * <b>Structure before state, with a renumber in between.</b>
     *
     * <p>Order is the whole correctness argument. A tree delta renumbers both sides, so any state delta
     * in the same tick must be computed <em>after</em> that and sent <em>after</em> it — the transport
     * preserves order within a stream, so the client applies them the same way round.</p>
     */
    private void flush() {
        /*
         * NOBODY IS LOOKING, so nothing is sent -- and nothing is RECOMPUTED either, which is the part
         * that has to be got right. Suppressing only the send while still running flushStructure would
         * renumber this side and not the other, and every state delta after the window came back would
         * address the wrong elements. Both halves stay pending: dirtyState and structuralAnchors keep
         * accumulating, and the flush that runs when the window comes back describes the tree as it is
         * then, in one pass. @see #setViewerVisible
         */
        if (!viewerVisible) return;
        flushStructure();
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

    /**
     * Sends {@code ui/treeDelta} for every anchor whose children changed, then renumbers.
     *
     * <p>An anchor is re-described <em>in full</em> rather than as a list of inserts and removes. That is
     * a deliberate trade and the reasoning is the same one the description itself makes: a minimal edit
     * script would have to be computed against what the client has, which the server does not keep, and
     * getting it subtly wrong produces a tree that is plausibly-but-not-actually right. Re-describing a
     * subtree is bounded by that subtree, and the common case — one row appended to one list — sends
     * that list rather than the window.</p>
     */
    private void flushStructure() {
        if (!open || structuralAnchors.isEmpty()) return;

        // SHALLOWEST ONLY. Adding a subtree dirties every parent inside it, and re-describing an anchor
        // already covers everything beneath it -- so a descendant entry is both redundant and wrong,
        // since the client would have replaced it before reaching the entry that names it.
        List<UIElement> anchors = new ArrayList<>();
        for (UIElement candidate : structuralAnchors) {
            if (candidate.getNetworkId() < 0) continue;
            boolean covered = false;
            for (UIElement other : structuralAnchors) {
                if (other != candidate && other.getNetworkId() >= 0 && isAncestor(other, candidate)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) anchors.add(candidate);
        }
        structuralAnchors.clear();
        if (anchors.isEmpty()) return;

        List<T> entries = new ArrayList<>(anchors.size());
        for (UIElement anchor : anchors) {
            List<T> described = new ArrayList<>();
            for (UIElement child : anchor.describedChildrenFor()) {
                described.add(UIDescriptionCodec.CODEC.encode(ops, child));
            }
            StateMap<T> entry = new StateMap<>(ops);
            entry.putInt("nid", anchor.getNetworkId());
            entry.putRaw("children", ops.createList(described));
            entries.add(entry.encode());
        }

        // RENUMBER, then say what the new total is. The client re-derives the same numbering from the
        // same tree, and the count is the same cross-check open() uses -- a disagreement is refused
        // rather than silently misapplied.
        elementCount = NetworkIds.assign(root);
        encodedDescription = UIDescriptionCodec.CODEC.encode(ops, root);
        descHash = ContentHash.of(ops, encodedDescription);
        // The hash just moved, so what a LATE VIEWER is told has to move with it. @see #rebuildOpenPayload
        rebuildOpenPayload();

        StateMap<T> out = new StateMap<>(ops);
        out.putRaw("entries", ops.createList(entries));
        out.putInt("count", elementCount);
        out.putString("hash", descHash);
        notifyClient(UiMethods.TREE_DELTA, out);
    }

    private static boolean isAncestor(UIElement maybeAncestor, UIElement element) {
        for (UIElement walk = element.getParent(); walk != null; walk = walk.getParent()) {
            if (walk == maybeAncestor) return true;
        }
        return false;
    }

    /**
     * Closes the window and stops answering for it.
     *
     * <p><b>The release is after the notification, not before.</b> {@code ui/closeWindow} is itself a
     * window-scoped message on the way out, and letting go of the slot first would be the same
     * dismiss-before-dispatch mistake light dismiss already pays for elsewhere — here it would only
     * matter to a peer echoing something back, which is exactly the kind of "only under load, only
     * sometimes" fault that is unfindable later.</p>
     */
    public void close(String reason) {
        if (!open) return;
        // NOT through the visibility gate. A close is the one message a hidden window must still be
        // told, because it is what ends the window rather than describing it.
        boolean wasVisible = viewerVisible;
        viewerVisible = true;
        try {
            closeInternal(reason, true);
        } finally {
            viewerVisible = wasVisible;
        }
    }

    /**
     * Stops serving this window <b>without telling the peer</b> — because the peer has gone.
     *
     * <p>What a connection-lost teardown calls. Everything {@link #close} does except the message:
     * observing stops, the window's slots on the mux are handed back, and the session is no longer
     * open. Sending would be encoding a packet into a wire whose far end is a disconnected player, and
     * on 1.7.10 that reaches FML's own outbound validation before it reaches nothing.</p>
     */
    public void abandon(String reason) {
        if (!open) return;
        closeInternal(reason, false);
    }

    private void closeInternal(String reason, boolean tellPeer) {
        open = false;
        root.setObserver(null);
        if (tellPeer) {
            StateMap<T> out = new StateMap<>(ops);
            out.putString("reason", reason == null ? "" : reason);
            notifyClient(UiMethods.CLOSE_WINDOW, out);
        }

        // Hands the (method, window) pairs back, so the id may be reused and a message still in flight
        // for this window is refused rather than applied to whatever takes its place. That second half
        // is what the per-handler mine(...) check was for when one handler was all there could be.
        for (Viewer<T> viewer : viewers) {
            if (viewer.mux != null) viewer.mux.release(windowId);
        }
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
            // The owned-transport shape has exactly one viewer by construction, so this is not a
            // broadcast: it is "the one router", written without assuming the index.
            for (Viewer<T> viewer : viewers) viewer.router.accept(envelope);
        }
    }

    /** The UI half of the vocabulary, on one viewer. RPC methods come through {@link #onCall}. */
    private void registerUiMethods(Viewer<T> viewer) {
        for (Map.Entry<String, Call.Handler<T>> entry : serverMethods.entrySet()) {
            registerCall(viewer, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Consumer<StateMap<T>>> entry : serverNotifications.entrySet()) {
            registerNotification(viewer, entry.getKey(), entry.getValue());
        }

        // THE USER CLOSED IT. The direction that did not exist -- see UiMethods.CLOSE. Handled here
        // rather than by whatever opened the window, so a session cannot be left open by a host that
        // forgot to listen; the callback is what a host reacts to.
        bindNotify(viewer, UiMethods.CLOSE, payload -> {
            StateMap<T> in = read(payload);
            if (!mine(in)) return;
            String reason = in.getString("reason", "closed by the user");
            // The window is already gone on the far side, so there is nothing to tell it. Ending the
            // session with a message would be answering a report of a departure.
            abandon(reason);
            if (onClientClosed != null) onClientClosed.accept(reason);
        });

        // IS ANYBODY LOOKING. @see UiMethods#VISIBILITY
        bindNotify(viewer, UiMethods.VISIBILITY, payload -> {
            StateMap<T> in = read(payload);
            if (!mine(in)) return;
            setViewerVisible(in.getBool("visible", true));
        });

        /*
         * THE SHEET BEHIND A HASH, answered exactly as a description is and for the same reasons:
         * content-addressed, so it is fetched once however many windows name it; refused rather than
         * dropped when we do not have it, so a client learns instead of waiting out a deadline.
         *
         * A ref with no text behind it is not a failure -- it names a theme the client is expected to
         * hold locally -- so the refusal says which of the two it is.
         */
        bindRequest(viewer, UiMethods.SHEET, (payload, respond) -> {
            StateMap<T> in = read(payload);
            if (!mine(in)) {
                respond.fail("wrong window");
                return;
            }
            String wanted = in.getString("hash", "");
            String css = sheetSource.get(wanted);
            if (css == null) {
                respond.fail("this session offers no source for sheet " + wanted);
                return;
            }
            StateMap<T> out = new StateMap<>(ops);
            out.putInt(UiMethods.WINDOW, windowId);
            out.putString("hash", wanted);
            out.putString("css", css);
            respond.ok(out.encode());
        });
        bindRequest(viewer, UiMethods.DESCRIPTION, (payload, respond) -> {
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

        bindNotify(viewer, UiMethods.EVENT, payload -> {
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

    /**
     * Tells every viewer. Encoded once, sent N times.
     *
     * <p>The encode is deliberately outside the loop: a state delta on a busy window would otherwise be
     * re-serialised per viewer, which is the cost that makes people avoid fan-out in the first place.</p>
     */
    private void notifyClient(String method, StateMap<T> payload) {
        payload.putInt(UiMethods.WINDOW, windowId);
        T encoded = payload.encode();
        for (Viewer<T> viewer : viewers) viewer.router.notify(method, encoded);
    }

    private void registerNotification(Viewer<T> viewer, String method, Consumer<StateMap<T>> handler) {
        bindNotify(viewer, method, payload -> handler.accept(read(payload)));
    }

    /**
     * Whether the client is showing this window, and therefore whether anything is flushed at all.
     *
     * <p>Driven by {@code ui/visibility} from the client, or set directly by a test. Turning it back on
     * flushes immediately rather than waiting for the next tick, so a window that comes back is correct
     * on the frame it comes back rather than one behind.</p>
     */
    public ServerUiSession<T> setViewerVisible(boolean visible) {
        if (viewerVisible == visible) return this;
        viewerVisible = visible;
        if (visible && open) flush();
        return this;
    }

    public boolean isViewerVisible() {
        return viewerVisible;
    }

    /**
     * Told when the <b>client</b> closed this window, with the reason it gave.
     *
     * <p>The session has already stopped serving by the time this runs — it is a report, not a veto. A
     * window is gone on the side that closed it, so there is nothing here that could refuse.</p>
     */
    public ServerUiSession<T> onClientClosed(Consumer<String> handler) {
        this.onClientClosed = handler;
        return this;
    }

    @Nullable
    private Consumer<String> onClientClosed;

    private void registerCall(Viewer<T> viewer, String method, Call.Handler<T> handler) {
        // WINDOW-SCOPED like the rest, so two windows of the same application may each offer `app/save`.
        // The counterpart is that #request stamps the window on the way out; a server-held method whose
        // caller is NOT this session -- a workspace, a script runtime -- belongs on ProtocolConnection
        // directly, where it is connection-scoped and shared by every window, which is what it wants.
        bindRequest(viewer, method, (payload, respond) ->
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
    }

    // ── UITreeObserver ──────────────────────────────────────────────────────

    @Override
    public void onAttached(UIElement element) {
        dirtyIdentity.add(element);
        noteStructuralChange(element);
    }

    @Override
    public void onDetached(UIElement element) {
        dirtyState.remove(element);
        dirtyIdentity.remove(element);
        // CAPTURED NOW, and it has to be. UIElement calls setObserver(null) "before the parent link is
        // cleared, so an observer can still see where it was" -- by flush time getParent() is null and
        // there is nothing left to anchor the delta to.
        noteStructuralChange(element);
    }

    /**
     * Records where the tree changed, as an ANCHOR the client already knows a number for.
     *
     * <p>Walks up from the changed element's parent past anything the client has never seen — a subtree
     * grafted in one go has several new elements, and only the first ancestor that existed at the last
     * numbering can be addressed. An element that has never been numbered reports {@code -1}, which is
     * exactly the test.</p>
     */
    private void noteStructuralChange(UIElement element) {
        UIElement anchor = element.getParent();
        while (anchor != null && anchor.getNetworkId() < 0) anchor = anchor.getParent();
        if (anchor != null) structuralAnchors.add(anchor);
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
