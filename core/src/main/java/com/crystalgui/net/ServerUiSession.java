package com.crystalgui.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.MessageRouter;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.net.mirror.ElementNodeMirror;
import com.crystalgui.net.mirror.NodeMirror;
import com.crystalgui.net.mirror.ServerTreeMirror;
import com.crystalgui.net.mirror.TreeOps;
import com.crystalgui.ui.dom.ElementTreeSource;

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
public final class ServerUiSession<T> {

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

    /** The panel's class name, or null. @see UiMethods#UI_CLASS */
    @Nullable
    private String uiClass;

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

    /**
     * The seam this session addresses elements through -- {@code plan_ui_rewrite.md} M0.
     *
     * <p>It holds the id table that used to be a field on every element, and it is what the mirror
     * (M2) is written against. Per-session rather than per-tree, which is the point: two sessions over
     * one tree each keep their own numbering instead of overwriting one another, and
     * {@code UIElement.setObserver holds ONE observer} stops being a constraint anything has to
     * document.</p>
     */
    private final ElementTreeSource ids;

    /**
     * <b>The mirror.</b> Everything about turning tree changes into messages lives there, written
     * against the {@code ui.dom} seam and naming no widget, no session and no transport.
     *
     * <p>It was inline here until it was noticed that a session doing this job itself pins the mirror to
     * {@code UIElement} -- which quietly voids the seam's reason to exist, since the engine swap was
     * supposed to be a port of the seam's implementation rather than of the mirror.</p>
     */
    private final ServerTreeMirror<UIElement, T> mirror;

    /** How a {@code UIElement} is described. The mirror's other half; see {@link NodeMirror}. */
    private final NodeMirror<UIElement, T> nodes;

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

        /**
         * Whether THIS viewer is watching. One flag per viewer, which was the whole of network audit
         * finding S7: a single session-wide flag meant any one viewer minimising suppressed everyone's
         * deltas, so ten players on one window were at the mercy of whichever of them looked away.
         */
        boolean visible = true;

        /** Reports refused from this viewer. @see #refuse */
        int refusals;

        /** The second this viewer's inbound count applies to, and the count. @see #withinRate */
        long rateSecond;
        int rateCount;

        /** Set once {@link #refusalThreshold} is reached: this viewer is no longer listened to. */
        boolean refused;

        /**
         * Set while hidden, and what makes coming back correct rather than merely quiet.
         *
         * <p>State deltas are skipped for a hidden viewer, so it misses them; on return it needs the
         * current state and not the next change. Re-describing is how it gets it -- the same path a
         * LATE viewer takes, and correct for the same reason, since a live description carries the ids
         * the server is already using so nothing renumbers.</p>
         */
        boolean missedState;

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
        this.ids = new ElementTreeSource(root);
        this.nodes = new ElementNodeMirror<>(ops);
        this.mirror = new ServerTreeMirror<>(ids, nodes, ops);
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
        this.ids = new ElementTreeSource(root);
        this.ops = connection.ops();
        this.nodes = new ElementNodeMirror<>(this.ops);
        this.mirror = new ServerTreeMirror<>(ids, nodes, this.ops);
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
    /** Names the panel class the far side should initialise before decoding. @see UiMethods#UI_CLASS */
    public ServerUiSession<T> setUiClass(@Nullable String uiClass) {
        this.uiClass = uiClass;
        return this;
    }

    public ServerUiSession<T> setKey(@Nullable String key) {
        this.key = key;
        return this;
    }

    /**
     * The wire format — <b>always the connection's own</b>.
     *
     * <p>Exposed so a handler builds its payloads in the same representation everything else on this
     * wire uses, rather than reaching for a hardcoded {@code PlainOps.INSTANCE} that happens to be
     * right today. A {@code StateMap} built with the wrong ops encodes values the codec on the way out
     * cannot read.</p>
     */
    public DynamicOps<T> ops() {
        return ops;
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

        elementCount = mirror.describeAndNumber();
        encodedDescription = nodes.describe(root);
        descHash = ContentHash.of(ops, encodedDescription);

        /*
         * Observe only after the snapshot: mutations before open are already in the description, and
         * marking them dirty would send a delta restating what was just sent.
         *
         * Three clears used to follow this line, defending against the old setObserver, which emitted
         * an attach per element -- so being handed a tree looked exactly like every element having just
         * been inserted, and every consumer had to remember to discard its own installation. Installing
         * an observer now reports NOTHING, an edit script describing changes and being handed a tree not
         * being one, so there is nothing to discard: the mirror cannot have recorded anything before the
         * line below puts it in place.
         */
        ids.observe(mirror);

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
        if (uiClass != null) out.putString(UiMethods.UI_CLASS, uiClass);
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
    /** This element's network id, or -1 if the client has not been described it. */
    public int idOf(UIElement element) {
        return ids.peekId(element);
    }

    /**
     * Asks the view to do something. @see ViewCommand
     *
     * <p><b>Dropped for a viewer that is not watching, never queued.</b> A focus request held while a
     * window was minimised and delivered on the way back would move the caret out from under whoever
     * had since started typing somewhere else — and it is asking about a moment that has passed.</p>
     */
    public void view(String command, @Nullable StateMap<T> args) {
        if (!open) return;
        StateMap<T> out = args == null ? new StateMap<>(ops) : args;
        out.putString(ViewCommand.CMD, command);
        out.putInt(UiMethods.WINDOW, windowId);
        T encoded = out.encode();
        for (Viewer<T> viewer : viewers) {
            if (viewer.visible) viewer.router.notify(UiMethods.VIEW, encoded);
        }
    }

    /** As {@link #view}, aimed at one element. */
    public void viewOn(String command, UIElement element, @Nullable StateMap<T> args) {
        int nid = ids.peekId(element);
        if (nid < 0) {
            CrystalGuiCore.LOGGER.warn("Session {}: '{}' names an element the client has not been "
                    + "described", windowId, command);
            return;
        }
        StateMap<T> out = args == null ? new StateMap<>(ops) : args;
        out.putInt(ViewCommand.NID, nid);
        view(command, out);
    }

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

    /**
     * Asks <b>every</b> viewer the same question, answering once per viewer.
     *
     * <p>For the questions that are about the window rather than about a person — may this close? — where
     * one answer is not enough and the first answer is not the answer. The callbacks fire once per
     * viewer, so a caller counts them; a viewer that has gone answers through {@code onError}, which is
     * still an answer.</p>
     */
    public void callEveryViewer(String method, @Nullable StateMap<T> args,
                                @Nullable Consumer<StateMap<T>> onResult,
                                @Nullable Consumer<String> onError) {
        for (Viewer<T> viewer : new ArrayList<>(viewers)) {
            request(viewer, method, args, onResult, onError);
        }
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
         * peekId() < 0 is exactly that test: an id is allocated when open() numbers the tree or when
         * flushStructure encodes the INSERT that carries the element, and a freshly built element
         * reports -1 until one of those has happened to it.
         * Without this relaxation nothing could ever be added to a live window -- no fragment, no row,
         * no lazily-built page -- which is a much bigger prohibition than the sentence justifying it.
         */
        if (open && ids.peekId(element) >= 0) {
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

    /**
     * Subscribes to an event the widget itself declares, <b>typed</b>.
     *
     * <pre>{@code
     * io.on(picker, ColorSelector.CHANGED, (ctx, colour) -> model.setColour(colour));
     * io.on(slider, Slider.VALUE_CHANGED, (ctx, value)  -> model.setRate(value));
     * }</pre>
     *
     * <h3>Why this is the form to reach for</h3>
     *
     * <p>The string form below needs a vocabulary, and a vocabulary is a <b>central list a third party
     * cannot edit</b>. A mod shipping a widget with a {@code scrub} or {@code reorder} event would have
     * to patch {@code EventKind} -- which it cannot -- or pass a bare string and lose every check.
     * Handing over the widget's own {@link Event} removes the middleman: the element owns its events,
     * and a new one is a {@code public static final} on the widget and nothing else.</p>
     *
     * <p>Three things the compiler now refuses that the string form could only catch at runtime, or not
     * at all:</p>
     *
     * <ul>
     *   <li><b>An event that is not this widget's.</b> {@code Event<W, P>} is typed in the widget, so
     *       {@code on(slider, TextField.COMMITTED, ...)} does not compile.</li>
     *   <li><b>A misspelled kind.</b> There is no string to misspell.</li>
     *   <li><b>A wrongly-typed payload.</b> The handler is handed a decoded {@code P}, so a colour
     *       arrives as an {@code Integer} rather than as {@code ctx.payload().getInt("color", 0)} --
     *       one place that knows the key and the default, instead of one per handler.</li>
     * </ul>
     *
     * <p><b>The wire is unchanged.</b> This resolves to {@link #on(UIElement, String, Consumer)} with
     * {@code event.kind()}, so registration costs one extra call and dispatch is the same map lookup by
     * the same string. Kinds remain strings <em>on the wire</em> because they have to be -- what goes
     * away is having to write one.</p>
     *
     * <p>The contract check still runs underneath, and still earns its place: {@code Dropdown extends
     * Button}, so {@code on(dropdown, Button.ACTIVATE, ...)} type-checks while a {@code Dropdown}'s
     * contract declares no {@code activate} for a client to attach. Inheritance is exactly what the
     * types cannot see.</p>
     */
    public <W extends UIElement, P> ServerUiSession<T> on(
            W element, Event<W, P> event, java.util.function.BiConsumer<UiEventContext<T>, P> handler) {
        // SANITIZED BY THE WIDGET, not by the session: what makes a payload safe is a question about
        // the widget's own configuration -- a slider's bounds and step, a field's maximum length -- and
        // nothing outside the widget class knows those.
        return on(element, event.kind(),
                ctx -> handler.accept(ctx, event.sanitize(element, event.decode(ctx.payload()))));
    }

    /**
     * Subscribes to an event that carries nothing — a press, a focus change, a close request.
     *
     * <p>A separate overload rather than a {@code BiConsumer} taking a null: the arities differ, so a
     * lambda picks the right one on its own, and a handler for a signal should not have to name a
     * parameter that is always null.</p>
     */
    public <W extends UIElement> ServerUiSession<T> on(
            W element, Event<W, Void> event, Consumer<UiEventContext<T>> handler) {
        return on(element, event.kind(), handler);
    }

    /**
     * What a handler is given. Carries no coordinates — see {@link UiMethods#EVENT}.
     *
     * @param viewer <b>who did it</b> — the peer of the connection the report arrived on, or
     *               {@code null} for a session with no peer (a test, or a local loopback). Minecraft's
     *               own container handlers receive the {@code ServerPlayer} and Unreal's RPCs carry the
     *               owning connection, for the same two reasons: <b>attribution</b> (a handler that
     *               counts anything, or writes who did it, is otherwise crediting whoever happens to be
     *               first in the viewer list) and <b>permission</b> (one viewer may be allowed to press
     *               a button another may not, which cannot even be expressed without knowing which one
     *               asked). It is also the handle {@link #callViewer} and
     *               {@link #setViewerVisible(Object, boolean)} take, so a handler can answer the viewer
     *               that spoke rather than broadcasting.
     */
    public record UiEventContext<T>(ServerUiSession<T> session, @Nullable Object viewer,
                                    UIElement element, StateMap<T> payload) {

        /**
         * Asks <b>the viewer that did this</b>, rather than broadcasting.
         *
         * <p>Almost always what a handler means: the answer is about the interaction that just
         * happened, so it belongs to whoever caused it. {@code session().call(...)} is still there for a
         * question genuinely addressed to the window rather than to a person, and it refuses when there
         * is more than one viewer and therefore no such thing as "the" client.</p>
         */
        public void call(String method, @Nullable StateMap<T> args,
                         @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
            session.callViewer(viewer, method, args, onResult, onError);
        }

        /** Says whether this viewer is watching. @see ServerUiSession#setViewerVisible(Object, boolean) */
        public void setVisible(boolean visible) {
            session.setViewerVisible(viewer, visible);
        }
    }

    /**
     * <b>Structure before state, with a renumber in between.</b>
     *
     * <p>Order is the whole correctness argument. A tree delta renumbers both sides, so any state delta
     * in the same tick must be computed <em>after</em> that and sent <em>after</em> it — the transport
     * preserves order within a stream, so the client applies them the same way round.</p>
     */
    /**
     * One tick's worth of change, structure first.
     *
     * <p><b>The visibility gate is above BOTH drains, and has to be.</b> A tree delta renumbers both
     * sides, so gating only the send -- which reads as equivalent -- lets this peer renumber while the
     * other does not, and every state delta afterwards lands on the wrong element. Silently, because an
     * id is an int and every one of them still resolves to something. Above both, the tree is simply
     * not re-described while nobody is looking and the client's numbering stays the one it was last
     * told. @see #setViewerVisible
     */
    /**
     * One tick's worth of change: <b>structure to everyone, state to whoever is looking.</b>
     *
     * <p>The asymmetry is forced and is worth stating, because the obvious symmetric version is
     * silently wrong. A tree delta <b>renumbers both sides</b> — so withholding one from a hidden
     * viewer leaves it addressing elements by numbers the server has moved on from, and every later
     * message lands somewhere plausible and incorrect. Structure therefore goes to every viewer whether
     * or not it is watching, which costs nothing in practice: structure changes are rare and state
     * deltas are the per-tick traffic.</p>
     *
     * <p>State is the opposite: it is keyed by id, so skipping it for a hidden viewer costs that viewer
     * nothing but freshness, and it is re-described on the way back. @see Viewer#missedState</p>
     *
     * <p>Nothing is drained at all when NOBODY is watching, which is the older rule and still holds:
     * the whole flush is gated together, never just the send.</p>
     */
    private void flush() {
        if (!anyViewerVisible()) return;
        flushStructure();
        StateMap<T> state = mirror.drainState();
        if (state == null) return;
        state.putInt(UiMethods.WINDOW, windowId);
        T encoded = state.encode();
        for (Viewer<T> viewer : viewers) {
            if (viewer.visible) viewer.router.notify(UiMethods.STATE_DELTA, encoded);
            else viewer.missedState = true;
        }
    }

    /** Whether anyone at all is watching. What gates the drain, and the projections above it. */
    public boolean anyViewerVisible() {
        for (Viewer<T> viewer : viewers) {
            if (viewer.visible) return true;
        }
        return viewers.isEmpty() && viewerVisible;
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
    /**
     * Sends this tick's edit script. {@link TreeOps}.
     *
     * <p>Ops are encoded here rather than when they were recorded, because an insert carries a
     * description and the subtree may have been filled in further during the same tick. Ids are
     * allocated here too, in send order, so the far side can number its own copy identically.</p>
     */
    /**
     * Re-encodes what a viewer joining NOW would be handed, from the tree as it stands.
     *
     * <p>Called from two places, and the second is easy to miss. A <b>reshape</b> obviously invalidates
     * the description. So does a plain <b>state change</b> — the description carries each widget's
     * state, so a window whose slider has moved is no longer described by the payload built at
     * {@code open()}. That is harmless while nothing re-serves it, and wrong the moment something does:
     * a viewer coming back from hidden was handed the OPENING tree and quietly resynced to values from
     * however long ago, which looks exactly like a stale delta rather than a stale description.</p>
     *
     * <p>Pristine or live according to whether the window has been reshaped — a reshaped one's ids are
     * no longer derivable from a walk, so its description has to carry them, and an untouched one stays
     * content-addressed and shareable.</p>
     */
    private void refreshDescription() {
        elementCount = ids.describedCount(root);
        encodedDescription = mirror.reshaped() ? nodes.describeLive(root, ids::idOf)
                : nodes.describe(root);
        descHash = ContentHash.of(ops, encodedDescription);
        rebuildOpenPayload();
    }

    /**
     * Sends the edit script, and re-describes the window for anyone joining later.
     *
     * <p>The re-description is this method's and not the mirror's: what a LATE viewer is handed is a
     * question about a window, not about a tree.</p>
     */
    private void flushStructure() {
        StateMap<T> out = mirror.drainStructure();
        if (out == null) return;

        // drainStructure() has already flipped the mirror's `reshaped`, which is what decides whether
        // the refreshed description carries ids.
        refreshDescription();

        notifyClient(UiMethods.TREE_OPS, out);
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
    /**
     * The machine-readable half of the next close, if a caller named one.
     *
     * <p>Defaults to the code for a server-initiated close, because that is what a bare
     * {@link #close(String)} IS: something on this side decided the window was over. The window layer
     * names a more specific one when it knows better — a client asking, a validity check failing, a
     * connection dying.</p>
     */
    private String closeCode = "SERVER";

    /** As {@link #close(String)}, naming a machine-readable reason the far side can branch on. */
    public void close(String reason, @Nullable String code) {
        if (code != null && !code.isEmpty()) this.closeCode = code;
        close(reason);
    }

    public void close(String reason) {
        if (!open) return;
        // NOT through the visibility gate. A close is the one message a hidden window must still be
        // told, because it is what ends the window rather than describing it.
        boolean wasVisible = viewerVisible;
        boolean[] were = new boolean[viewers.size()];
        for (int i = 0; i < viewers.size(); i++) {
            were[i] = viewers.get(i).visible;
            viewers.get(i).visible = true;
        }
        viewerVisible = true;
        try {
            closeInternal(reason, true);
        } finally {
            viewerVisible = wasVisible;
            for (int i = 0; i < viewers.size() && i < were.length; i++) viewers.get(i).visible = were[i];
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
        ids.observe(null);
        mirror.stop();
        if (tellPeer) {
            StateMap<T> out = new StateMap<>(ops);
            out.putString("reason", reason == null ? "" : reason);
            // A CODE beside the human-readable detail, so the far side is told WHY rather than being
            // handed a sentence. Opaque here on purpose: what the codes mean belongs to net.window, and
            // this layer naming that enum would point the dependency backwards.
            out.putString("code", closeCode);
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
            if (viewer.refused) return;
            if (!withinRate(viewer)) return;
            StateMap<T> in = read(payload);
            if (!mine(in)) return;
            int nid = in.getInt("nid", -1);
            UIElement element = ids.byId(nid);
            if (element == null) {
                CrystalGuiCore.LOGGER.warn("Session {}: event for unknown element {}", windowId, nid);
                return;
            }
            String kind = in.getString("kind", "");
            var byKind = handlers.get(element);
            var handler = byKind == null ? null : byKind.get(kind);
            if (handler == null) {
                // A client reporting something nobody asked for -- the two sides disagree about the
                // description, or somebody is making it up.
                refuse(viewer, "no handler for '" + kind + "' on element " + nid);
                return;
            }

            /*
             * WHAT A LEGAL GESTURE COULD NOT HAVE PRODUCED IS REFUSED; what it could have is SANITIZED.
             *
             * A disabled control cannot be pressed and an inert one cannot be reached, so a report about
             * either did not come from a user doing something -- it came from a client that is wrong or
             * a peer that is lying, and the only safe reading is the same for both. Chromium states the
             * rule this stack is built on: the browser process must be maximally suspicious of its IPC
             * inputs, because the renderer may be compromised.
             *
             * Note this is the ATTRIBUTE half of inertness only. A server tree has no UIWindow, so there
             * is no modal stack to consult -- and that is correct rather than a shortcut: modality is a
             * presentation decision the client makes, and a server that tried to enforce it would be
             * enforcing a guess about what is on somebody's screen.
             */
            if (!element.isEnabled()) {
                refuse(viewer, "'" + kind + "' on a DISABLED element " + nid);
                return;
            }
            if (element.isInertAttribute()) {
                refuse(viewer, "'" + kind + "' on an INERT element " + nid);
                return;
            }
            T carried = in.getRaw("p");
            // viewer.peer, because registerUiMethods runs PER VIEWER -- so this closure already knows
            // which connection the report arrived on, and attribution costs nothing to carry.
            handler.accept(new UiEventContext<>(this, viewer.peer, element,
                    carried == null ? new StateMap<>(ops) : new StateMap<>(ops, carried)));
        });
    }

    /**
     * Whether this viewer is still inside its inbound budget for the current second.
     *
     * <p>Above any real interaction by an order of magnitude — a drag reports at frame rate, so tens a
     * second — and low enough that a loop is stopped inside the second it starts. Over it, the message
     * is <b>refused rather than queued</b>: a rate limit that buffers is a slower way to run out of
     * memory, and the sender is by definition not waiting for these.</p>
     *
     * <p>Whole seconds rather than a sliding window, deliberately: a sliding window costs a timestamp
     * per message, and what is being bounded is a peer in a loop, which a coarse bucket catches just as
     * well as a fine one.</p>
     */
    private boolean withinRate(Viewer<T> viewer) {
        long second = System.currentTimeMillis() / 1000L;
        if (viewer.rateSecond != second) {
            viewer.rateSecond = second;
            viewer.rateCount = 0;
        }
        if (++viewer.rateCount <= UiLimits.MAX_INBOUND_PER_SECOND) return true;
        refuse(viewer, "more than " + UiLimits.MAX_INBOUND_PER_SECOND + " messages in one second");
        return false;
    }

    /**
     * Records a report that should not have been sent, and eventually stops listening to the sender.
     *
     * <p>One refusal is noise — a delta in flight when a control was disabled is ordinary and racy. A
     * hundred is not: it is a client that disagrees with the server about the tree, or one that is
     * being driven by something other than a user. Counting is what separates the two, and it has to be
     * <b>per viewer</b>, or one bad peer closes a window for everybody watching it.</p>
     *
     * <p>The threshold ends that VIEWER's participation and leaves the window standing, because the
     * window belongs to whoever else is watching it. Minecraft kicks on packet flood and Chromium's
     * {@code ReportBadMessage} kills the sending renderer; neither takes the document down.</p>
     */
    private void refuse(Viewer<T> viewer, String what) {
        viewer.refusals++;
        if (viewer.refusals <= REFUSAL_LOG_LIMIT) {
            CrystalGuiCore.LOGGER.warn("Session {}: refused {} (refusal {} from {})",
                    windowId, what, viewer.refusals, viewer.peer);
        }
        if (viewer.refusals != refusalThreshold) return;
        CrystalGuiCore.LOGGER.error("Session {}: {} refusals from {} — no longer listening to it. The "
                + "window stays open for everyone else.", windowId, viewer.refusals, viewer.peer);
        viewer.refused = true;
    }

    /** After this many, the log goes quiet: a flood must not become the flood. */
    private static final int REFUSAL_LOG_LIMIT = 8;

    private int refusalThreshold = 200;

    /** How many refusals a viewer gets before it stops being listened to. */
    public ServerUiSession<T> setRefusalThreshold(int refusals) {
        this.refusalThreshold = Math.max(1, refusals);
        return this;
    }

    /** How many reports this peer has had refused. */
    public int refusalsFrom(@Nullable Object peer) {
        for (Viewer<T> viewer : viewers) {
            if (java.util.Objects.equals(viewer.peer, peer)) return viewer.refusals;
        }
        return 0;
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
        for (Viewer<T> viewer : viewers) setVisible(viewer, visible);
        viewerVisible = visible;
        return this;
    }

    /**
     * Says whether ONE viewer is watching.
     *
     * <p>The fix for network audit finding S7. A window shown to ten players had a single flag, so one
     * of them minimising stopped deltas for the other nine — and with projections gated on the same
     * flag it stopped the work being done at all. Which viewer is watching is a fact about a
     * connection, not about the window.</p>
     *
     * @param peer the connection's peer, as carried by {@link UiEventContext#viewer()}
     * @return whether a viewer with that peer was found
     */
    public boolean setViewerVisible(@Nullable Object peer, boolean visible) {
        boolean found = false;
        for (Viewer<T> viewer : viewers) {
            if (!java.util.Objects.equals(viewer.peer, peer)) continue;
            setVisible(viewer, visible);
            found = true;
        }
        return found;
    }

    private void setVisible(Viewer<T> viewer, boolean visible) {
        if (viewer.visible == visible) return;
        viewer.visible = visible;
        if (!visible || !open) return;

        /*
         * COMING BACK NEEDS THE CURRENT STATE, NOT THE NEXT CHANGE.
         *
         * A delta only says what moved. A viewer that was away has missed however many of them, so
         * replaying nothing leaves it showing whatever was true when it looked away -- correct-looking
         * and stale, which is the failure mode this codebase keeps paying for.
         *
         * Re-describing is the LATE VIEWER path, and it is right here for the same reason: a live
         * description carries the ids the server is already using, so the returning viewer resyncs
         * completely without anything renumbering. The cost is that its tree is rebuilt, which is
         * honest -- a hidden window on this engine is a detached one, so there were no instances to
         * keep.
         */
        if (viewer.missedState) {
            viewer.missedState = false;
            viewer.opened = false;
            refreshDescription();
            sendOpenTo(viewer);
        }
        flush();
    }

    /** Whether EVERY viewer is watching. @see #anyViewerVisible @see #setViewerVisible(Object, boolean) */
    public boolean isViewerVisible() {
        for (Viewer<T> viewer : viewers) {
            if (!viewer.visible) return false;
        }
        return viewers.isEmpty() ? viewerVisible : true;
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

    /** The elements whose state has changed and not yet been flushed. For diagnostics and tests. */
    public Set<UIElement> pendingStateChanges() {
        return mirror.pendingStateChanges();
    }
}
