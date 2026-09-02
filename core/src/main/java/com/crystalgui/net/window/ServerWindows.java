package com.crystalgui.net.window;

import com.crystalgui.net.mirror.UINodeMirror;
import com.crystalgui.ui.dom.UINodeTreeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UiLimits;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.UINode;

/**
 * Every window one peer is being shown — <b>the server host, and the whole server side of showing a
 * UI</b>.
 *
 * <pre>{@code
 * ServerWindows.of(connection).open(MachinePanel.TYPE, machine);
 * }</pre>
 *
 * <p>That is the entire call site: allocate an id, build the panel, run its {@link Networked#serve}
 * against a fresh {@link ServerScope}, open the session, tick it every world tick, sweep its
 * validity, and end it whichever of the four ways it ends. A mod writes a {@link Networked} panel and
 * this line; there is nothing else to remember and nothing to poll.</p>
 *
 * <h3>What it replaces</h3>
 *
 * <p>A tick handler per mod walking the player list to notice a peer that {@code CgUiConnections}
 * noticed once, a name-keyed map per mod, a logout handler per mod, and a hard-coded window id per mod
 * that two mods would eventually both pick. All of it existed because there was no seat for "open a
 * window for this player <em>now</em>" — contributors bind when a connection opens, and a UI opens
 * later, on demand.</p>
 *
 * <h3>Ported from {@code ServerPlayer.openMenu}, with two deliberate divergences</h3>
 *
 * <p>The sequence is Minecraft's, both versions: allocate the next id, construct, tell the client with
 * the type and the title, start observing, tick with a validity check. The two differences are decided
 * elsewhere in this engine and are not accidents.</p>
 *
 * <ul>
 *   <li><b>Many windows per connection.</b> {@code openMenu} force-closes the previous container;
 *       CrystalOS is built for several at once. Uniqueness is per <em>key</em> instead: opening under a
 *       key already open brings the existing window forward. That is Minecraft's rule narrowed from
 *       "any window" to "the same subject", which is what it was really protecting.</li>
 *   <li><b>Close is a request.</b> A frame's X asks; the client decides, then tells us. See
 *       {@link UiMethods#CLOSE}, the direction that did not exist before this.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 *
 * <p>Everything runs from {@link ProtocolConnection#tick()} — the server thread in game — so a handler
 * may touch the world, and the tick hook sees this tick's input already delivered because hooks run
 * after the drain.</p>
 */
public final class ServerWindows {

    private final ProtocolConnection<Object> connection;

    /** Insertion-ordered, so ticking and closing are both reproducible. */
    private final Map<Integer, ServerWindow<?>> windows = new LinkedHashMap<>();

    /**
     * The next id to hand out, per connection.
     *
     * <p>What {@code EntityPlayerMP.getNextWindowId} and {@code ServerPlayer.nextContainerCounter} are.
     * Nothing in this stack allocated one before, so every mod invented a constant and hoped — and two
     * mods choosing the same number is not a subtle failure, it is {@code UiWindowMux} throwing
     * <i>"window 7001 already serves 'ui/description'"</i> on a live server.</p>
     *
     * <p>Unlike Minecraft's this does not wrap at 100: the mux keys on it, ids are released on close,
     * and an int is not a thing to be economical with.</p>
     */
    private int nextWindowId = 1;

    private boolean closing;

    private ServerWindows(ProtocolConnection<Object> connection) {
        this.connection = connection;
        connection.router().onRequest(UiMethods.REQUEST_OPEN, (payload, respond) -> {
            StateMap<Object> in = payload == null
                    ? new StateMap<>(connection.ops()) : new StateMap<>(connection.ops(), payload);
            respond.ok(requestOpen(in).encode());
        });
        // AFTER the drain, so a window's tick runs against messages that have already arrived rather
        // than against the previous tick's. @see ProtocolConnection#onTick
        connection.onTick(this::tick);
        connection.onClosed(this::onConnectionClosed);
    }

    /**
     * What a client on ANY connection is allowed to ask for, by type id.
     *
     * <p>Static because it is a statement about this deployment rather than about one player's
     * connection: a mod declares once, at registration, what its clients may open. Per-connection
     * differences are the resolver's to make — it is handed the viewer.</p>
     */
    private static final Map<String, Openable<?, ?>> OPENABLE = new LinkedHashMap<>();

    /** A declared type and the authority that decides each request for it. */
    private record Openable<P extends UINode & Networked<M>, M>(UiType<P, M> type,
                                                                   OpenResolver<M> resolver) {
    }

    /**
     * <b>Lets clients ask for this window.</b> Nothing is openable by a client until this is called.
     *
     * <p>The default is refusal, and deliberately: a client able to open any registered type could open
     * a panel over a block it is nowhere near, or one a mod only ever means to show from its own code.
     * Declaring is one line and the absence of it is safe.</p>
     *
     * <pre>{@code
     * ServerWindows.openable(FurnacePanel.TYPE, (viewer, args) -> {
     *     BlockPos pos = readPos(args);                   // UNTRUSTED -- re-derive, never dereference
     *     if (!world.isBlockLoaded(pos)) return null;     // null is a refusal, not an error
     *     return furnaceAt(pos);
     * });
     * }</pre>
     *
     * <p><b>Asking twice does not open twice</b>, and needs no second mechanism: a panel that names a
     * {@code key} brings its existing window forward instead, which is the same rule a server-side
     * {@code open} already follows.</p>
     *
     * <p>Idempotent per type — re-declaring replaces the resolver, so a reload can re-register without
     * a duplicate refusal.</p>
     */
    public static <P extends UINode & Networked<M>, M> void openable(UiType<P, M> type,
                                                                        OpenResolver<M> resolver) {
        OPENABLE.put(type.id(), new Openable<>(type, resolver));
    }

    /** Forgets every declaration. For tests, which share statics. */
    public static void resetOpenableForTesting() {
        OPENABLE.clear();
    }

    /** The host for this connection, created on first use. */
    public static ServerWindows of(ProtocolConnection<Object> connection) {
        return connection.attachment(ServerWindows.class, ServerWindows::new);
    }

    /**
     * Answers a client's request for a window.
     *
     * <p>The reply says whether it was granted and nothing else. The window, if there is one, arrives
     * through the ordinary open path — so a client has exactly one place that learns a window appeared,
     * whether it asked for it or the server decided on its own.</p>
     *
     * <p>A refusal is deliberately <b>unelaborated</b>. Saying which check failed tells a client
     * probing for windows exactly what to change; "no" is the whole answer a legitimate caller needs,
     * and the server's log has the detail for whoever is actually debugging it.</p>
     */
    private StateMap<Object> requestOpen(StateMap<Object> in) {
        StateMap<Object> out = new StateMap<>(connection.ops());
        String typeId = in.getString(UiMethods.TYPE, "");
        Openable<?, ?> declared = OPENABLE.get(typeId);
        if (declared == null) {
            // NOT DECLARED is not the same as refused, and is worth its own log line: a refusal is the
            // resolver doing its job, and this is a client asking for something no mod ever offered --
            // either a version skew or somebody trying ids to see what sticks.
            CrystalGuiCore.LOGGER.warn("A client asked to open <{}>, which is not declared openable",
                    typeId);
            out.putBool("ok", false);
            return out;
        }
        boolean opened = grant(declared, in);
        out.putBool("ok", opened);
        return out;
    }

    @SuppressWarnings("unchecked")
    private <P extends UINode & Networked<M>, M> boolean grant(Openable<P, M> declared,
                                                                  StateMap<Object> in) {
        // Never null, so a resolver need not check: a client that sends nothing sends an empty map.
        Object raw = in.getRaw("args");
        StateMap<Object> args = raw == null
                ? new StateMap<>(connection.ops()) : new StateMap<>(connection.ops(), raw);
        M model;
        try {
            model = declared.resolver().resolve(connection.peer(), args);
        } catch (RuntimeException failed) {
            // A BROKEN resolver is a refusal, not a crash. It runs on whatever thread the connection
            // ticks on, and letting it out would take the connection down over one player's request.
            CrystalGuiCore.LOGGER.error("The resolver for <{}> failed; refusing: {}",
                    declared.type().id(), failed.getMessage(), failed);
            return false;
        }
        if (model == null) return false;   // an ordinary answer

        try {
            open(declared.type(), model);
            return true;
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.error("Could not open <{}> for a client that asked: {}",
                    declared.type().id(), failed.getMessage(), failed);
            return false;
        }
    }

    /** Builds the host so its tick and close hooks are installed. @see WindowProtocol */
    static void install(ProtocolConnection<Object> connection) {
        of(connection);
    }

    /** The platform's handle for whoever is watching. */
    @Nullable
    public Object peer() {
        return connection.peer();
    }

    public ProtocolConnection<Object> connection() {
        return connection;
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /**
     * Opens a window serving a fresh panel over {@code model}: build, bind, open.
     *
     * <p>The panel is {@linkplain UiType#build built} — fields created and named, {@code layout} run
     * against the model — then its {@link Networked#serve} registers everything it can do, <b>before</b>
     * the session opens, so the handlers-before-open rule cannot be broken from a panel's own code.
     * The intersection bound is what makes the whole call typed without a cast anywhere: the one place
     * both {@code P} and {@code M} are in scope is the one place the panel's server hooks are captured
     * with their model.</p>
     *
     * <p>If the panel answers a {@link Networked#key key} and a window is already open under it,
     * <b>the existing one is brought forward and returned</b> — the fresh panel is discarded, never
     * bound and never opened. That keeps the open window's tree, its scroll position and whatever is
     * half-typed in it, which is the whole reason to prefer this over Minecraft's close-and-reopen.</p>
     *
     * @throws IllegalStateException if the key is held by a window of a different type, which is a
     *                               wiring mistake rather than something to resolve silently
     */
    public <P extends UINode & Networked<M>, M> ServerWindow<P> open(UiType<P, M> type, @Nullable M model) {
        if (type == null) throw new IllegalArgumentException("type is null");

        P panel = type.build(model);
        String key = panel.key(model);
        if (key != null) {
            ServerWindow<?> existing = byKey(key);
            if (existing != null) {
                if (existing.uiType != type) {
                    throw new IllegalStateException("key '" + key + "' is already held by a <"
                            + existing.typeId() + ">, so a <" + type.id() + "> cannot take it");
                }
                // BRING IT FORWARD rather than rebuild. A re-sent ui/openWindow would also work and
                // would throw away exactly the state the window was retained for. The panel built
                // above is discarded, unopened.
                ServerUiSession<UINode, Object> open = existing.session;
                if (open != null) open.notify(UiMethods.FOCUS_WINDOW, null);
                @SuppressWarnings("unchecked")   // same UiType instance → same P, by construction
                ServerWindow<P> same = (ServerWindow<P>) existing;
                return same;
            }
        }

        String title = panel.title(model);
        if (title == null) title = type.id();

        if (windows.size() >= UiLimits.MAX_WINDOWS_PER_CONNECTION) {
            /*
             * REFUSED, and loudly, because the alternative is worse in both directions.
             *
             * Opening anyway costs the client a session, a mirror, an id table and a tree per window,
             * with nothing bounding how many -- a server looping on open() is a memory attack the
             * transport is perfectly happy to carry, since each message is small.
             *
             * Throwing rather than answering null: this is a programming error on the server's own
             * side, in its own process, and a mod that opens sixty-five windows wants to know rather
             * than to have the sixty-fifth silently missing.
             */
            throw new IllegalStateException("this connection already has "
                    + UiLimits.MAX_WINDOWS_PER_CONNECTION + " windows open, which is the cap — see "
                    + "UiLimits.MAX_WINDOWS_PER_CONNECTION");
        }
        int id = nextWindowId++;
        ServerUiSession<UINode, Object> session = new ServerUiSession<>(id, new UINodeTreeSource(panel), new UINodeMirror<>(connection.ops()), connection)
                .setType(type.id())
                .setTitle(title)
                .setKey(key)
                // What makes the client need NO registration call: the open names the class, the
                // client initialises it (guarded), and initialising it registers its tag.
                .setUiClass(type.uiClass().getName());

        ServerWindow<P> window = new ServerWindow<>(type, panel,
                io -> panel.serve(model, io),
                () -> panel.tick(model),
                viewer -> panel.stillValid(model, viewer),
                panel::closed,
                title, key);
        window.host = this;
        window.session = session;
        window.windowId = id;
        window.live = true;
        windows.put(id, window);

        try {
            // BEFORE open(), which is what makes the handlers-before-open rule unbreakable from a
            // panel's own code rather than a thing every author has to remember.
            window.binder.accept(new ServerScope(session, window, ""));

            /*
             * SEEDED BEFORE THE DESCRIPTION IS TAKEN, and this is what makes projections complete
             * rather than merely convenient.
             *
             * open() encodes the tree as it stands. Without this run the first description carries
             * whatever the panel's constructor happened to build, and every projected field arrives
             * one state delta later -- a window that opens visibly wrong and corrects itself a tick
             * afterwards. It is the reason MachinePanel.serve used to end with a hand-written
             * mirror(model) call, and moving it here is what lets that call be deleted rather than
             * merely renamed. Covers attached children too: a child declares into the same set.
             */
            window.runProjections();

            session.onClientClosed(reason -> finish(window, CloseReason.CLIENT, reason));
            session.open();
        } catch (RuntimeException | Error failed) {
            /*
             * ROLLED BACK, or a window that refused to bind is left half-open: in the map, marked live,
             * holding an id, with its (method, window) pairs claimed on the mux -- so the next open in
             * that id throws about a window nobody has ever seen. Binding is exactly where a wiring
             * mistake is raised (a duplicate handler, two children under one id), which makes this
             * the ordinary path for a mistake rather than a theoretical one.
             */
            windows.remove(id);
            window.live = false;
            window.host = null;
            window.session = null;
            window.attached.clear();
            session.abandon("failed to open");
            throw failed;
        }
        return window;
    }

    // ── Closing ─────────────────────────────────────────────────────────────

    /** Ends a window and tells the client. Safe for one that has already ended. */
    public void close(ServerWindow<?> window, String reason) {
        finish(window, CloseReason.SERVER, reason);
    }

    /** The window open under {@code key}, or {@code null}. */
    @Nullable
    public ServerWindow<?> byKey(String key) {
        if (key == null) return null;
        for (ServerWindow<?> window : windows.values()) {
            if (key.equals(window.key())) return window;
        }
        return null;
    }

    /** Every window currently open, in the order they opened. */
    public List<ServerWindow<?>> windows() {
        return Collections.unmodifiableList(new ArrayList<>(windows.values()));
    }

    public int windowCount() {
        return windows.size();
    }

    // ── The tick ────────────────────────────────────────────────────────────

    /**
     * One tick for every window on this connection: sweep validity, advance, flush.
     *
     * <p>Validity <b>first</b>, so a window that has just stopped being valid does not get one more
     * tick and one more state delta on its way out. Minecraft checks the other way round
     * ({@code broadcastChanges()} then {@code stillValid}) and it costs it a redundant packet per
     * close; the order here is the one that does not.</p>
     */
    private void tick() {
        if (windows.isEmpty()) return;

        // COPIED, because a handler may open or close a window from inside its own tick and both
        // mutate this map. Opening from a tick is ordinary (a button that spawns a dialog), so a
        // ConcurrentModificationException there would be a crash caused by using the feature.
        List<ServerWindow<?>> live = new ArrayList<>(windows.values());
        Object viewer = connection.peer();

        for (ServerWindow<?> window : live) {
            if (!window.live) continue;
            boolean valid;
            try {
                valid = window.validity.test(viewer);
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("<{}>.stillValid failed; closing it: {}",
                        window.typeId(), failed.getMessage(), failed);
                valid = false;
            }
            if (!valid) {
                finish(window, CloseReason.NOT_VALID, "no longer valid");
            }
        }

        for (ServerWindow<?> window : live) {
            if (!window.live) continue;
            try {
                window.ticker.run();
                // Nested panels, each with the slice it was attached with, in attach order.
                for (ServerWindow.Attached child : window.attached) child.ticker().run();

                /*
                 * PROJECTIONS RUN HERE: after the panel's own tick, before the session flushes.
                 *
                 * After, so a tick that mutates the model is reflected on the same tick rather than the
                 * next -- otherwise every projected field is one tick stale, which is invisible on a
                 * slow-moving model and unmissable on a fast one.
                 *
                 * Before session.tick(), because that is what drains the dirty set: a projection that
                 * wrote afterwards would hold its change until the following flush.
                 *
                 * And SKIPPED ENTIRELY while nobody is watching, which is the thing a hand-written
                 * mirror() in tick() structurally could not be -- a minimised window went on walking its
                 * whole model sixty times a second to write values no one could see.
                 */
                ServerUiSession<UINode, Object> watching = window.session;
                if (watching != null && watching.anyViewerVisible()) window.runProjections();
            } catch (RuntimeException failed) {
                // One window's broken tick must not stop every other window on this connection --
                // the frozen ones would show no error of their own, which is what gets diagnosed as a
                // network fault. Same rule CgUiConnections.tickSafely applies one layer down.
                CrystalGuiCore.LOGGER.error("<{}>.tick failed: {}",
                        window.typeId(), failed.getMessage(), failed);
            }
            ServerUiSession<UINode, Object> session = window.session;
            // REQUIRED, and it is the one thing only the session can do: it holds this tick's dirty
            // set, so nothing else knows the set exists. A host that stopped calling it would keep
            // perfectly live windows that answer calls and never send another state update.
            if (session != null) session.tick();
        }
    }

    /**
     * The peer has gone. Every window ends, and <b>nothing is sent</b>.
     *
     * <p>What every mod used to write for itself as a logout handler — and what a mod that forgot left
     * behind: a session still observing its tree and still encoding deltas into a wire whose far end
     * had disconnected.</p>
     */
    private void onConnectionClosed(String reason) {
        closing = true;
        try {
            for (ServerWindow<?> window : new ArrayList<>(windows.values())) {
                finish(window, CloseReason.CONNECTION_LOST, reason);
            }
        } finally {
            closing = false;
            windows.clear();
        }
    }

    /**
     * The one way a window ends, whichever of the four reasons it is.
     *
     * <p><b>Ordering matters and is not obvious.</b> The window is taken out of the map and marked
     * dead <em>first</em>, so a listener that reacts by opening another window (or by closing this one
     * again) cannot re-enter this. Only then is the session ended — with a message for every reason
     * except a lost connection, where there is nobody to tell and the send would reach FML's own
     * outbound validation before it reached nothing. The panel is told last — root first, then every
     * attached child in attach order.</p>
     */
    private void finish(ServerWindow<?> window, CloseReason reason, String detail) {
        if (window == null || !window.live) return;
        window.live = false;
        if (!closing) windows.remove(window.windowId);

        ServerUiSession<UINode, Object> session = window.session;
        if (session != null) {
            if (reason == CloseReason.CONNECTION_LOST) {
                session.abandon(detail);
            } else if (reason == CloseReason.CLIENT) {
                // The session has already stood down: this reason exists BECAUSE the client told it to,
                // through the handler that ends it. Telling the client its own news would be an echo.
                session.abandon(detail);
            } else {
                session.close(detail, reason.name());
            }
        }

        // The session goes before the callbacks (it is dead, and handing over a dead one invites its
        // use); the HOST stays until after, so a teardown can still ask who was watching -- which is
        // most of what a teardown wants to know.
        window.session = null;
        try {
            window.closer.accept(reason);
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.error("<{}>.closed failed: {}",
                    window.typeId(), failed.getMessage(), failed);
        }
        for (ServerWindow.Attached child : window.attached) {
            try {
                child.closer().accept(reason);
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("A nested panel of <{}> failed on close: {}",
                        window.typeId(), failed.getMessage(), failed);
            }
        }
        // AFTER the callbacks, so a teardown can still read a projected widget, and after the attached
        // children's, since a nested panel's projections live in the same window. A projection holds
        // the model, the widget and its last value; a bind() additionally puts a listener ON THE MODEL,
        // which outlives the window -- so leaving it connected retains the whole tree through it, which
        // is the ordinary shape of a listener leak.
        window.releaseProjections();

        window.host = null;
        window.attached.clear();
    }
}
