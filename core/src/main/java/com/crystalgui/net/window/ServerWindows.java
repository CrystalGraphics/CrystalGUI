package com.crystalgui.net.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.ui.UIElement;

/**
 * Every window one peer is being shown — <b>the lifecycle {@code Protocols} left to each mod</b>.
 *
 * <p>{@code Protocols} gave a subsystem a seat at the connection. This gives a <em>window</em> a
 * lifecycle on it: allocate an id, build the session, bind, open, tick, sweep validity, and end the
 * window whichever of the four ways it ends. A mod writes a {@link ServerWindow} and calls
 * {@link #open}; there is nothing else to remember and nothing to poll.</p>
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
    private final Map<Integer, ServerWindow> windows = new LinkedHashMap<>();

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
        // AFTER the drain, so a window's tick runs against messages that have already arrived rather
        // than against the previous tick's. @see ProtocolConnection#onTick
        connection.onTick(this::tick);
        connection.onClosed(this::onConnectionClosed);
    }

    /** The host for this connection, created on first use. */
    public static ServerWindows of(ProtocolConnection<Object> connection) {
        return connection.attachment(ServerWindows.class, ServerWindows::new);
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
     * Opens a window: allocate, build, bind, open.
     *
     * <p>If {@code window} has a {@link ServerWindow#key() key} and a window is already open under it,
     * <b>the existing one is brought forward and returned</b> — the new one is never bound and never
     * opened. That keeps its tree, its scroll position and whatever is half-typed in it, which is the
     * whole reason to prefer this over Minecraft's close-and-reopen.</p>
     *
     * @throws IllegalStateException if the key is held by a window of a different {@link
     *                               ServerWindow#type() type}, which is a wiring mistake rather than
     *                               something to resolve silently
     */
    @SuppressWarnings("unchecked")
    public <W extends ServerWindow> W open(W window) {
        if (window == null) throw new IllegalArgumentException("window is null");
        if (window.live) throw new IllegalStateException("that window is already open");

        String key = window.key();
        if (key != null) {
            ServerWindow existing = byKey(key);
            if (existing != null) {
                if (!existing.type().equals(window.type())) {
                    throw new IllegalStateException("key '" + key + "' is already held by a <"
                            + existing.type() + ">, so a <" + window.type() + "> cannot take it");
                }
                // BRING IT FORWARD rather than rebuild. A re-sent ui/openWindow would also work and
                // would throw away exactly the state the window was retained for.
                ServerUiSession<Object> open = existing.session;
                if (open != null) open.notify(UiMethods.FOCUS_WINDOW, null);
                return (W) existing;
            }
        }

        UIElement root = window.root();
        if (root == null) throw new IllegalStateException("<" + window.type() + "> has no root");

        int id = nextWindowId++;
        ServerUiSession<Object> session = new ServerUiSession<>(id, root, connection)
                .setType(window.type())
                .setTitle(window.title())
                .setKey(key);

        window.host = this;
        window.session = session;
        window.windowId = id;
        window.live = true;
        windows.put(id, window);

        try {
            // BEFORE open(), which is what makes the handlers-before-open rule unbreakable from a
            // window's own code rather than a thing every author has to remember.
            window.bind(new WindowScope(session, window, ""));
            session.onClientClosed(reason -> finish(window, ServerWindow.CloseReason.CLIENT, reason));
            session.open();
        } catch (RuntimeException | Error failed) {
            /*
             * ROLLED BACK, or a window that refused to bind is left half-open: in the map, marked live,
             * holding an id, with its (method, window) pairs claimed on the mux -- so the next open in
             * that id throws about a window nobody has ever seen. Binding is exactly where a wiring
             * mistake is raised (a duplicate handler, two fragments under one name), which makes this
             * the ordinary path for a mistake rather than a theoretical one.
             */
            windows.remove(id);
            window.live = false;
            window.host = null;
            window.session = null;
            window.fragments.clear();
            session.abandon("failed to open");
            throw failed;
        }
        return window;
    }

    /**
     * Opens a window described by lambdas. @see ServerWindow#of(String, java.util.function.Supplier, java.util.function.Function)
     *
     * <p>An overload rather than making the caller write {@code .build()}: the builder has exactly one
     * destination, and a fluent chain that ends in a call nobody needs is a call somebody will forget
     * and then wonder why nothing opened.</p>
     */
    public ServerWindow open(ServerWindow.Builder<?> builder) {
        return open(builder.build());
    }

    // ── Closing ─────────────────────────────────────────────────────────────

    /** Ends a window and tells the client. Safe for one that has already ended. */
    public void close(ServerWindow window, String reason) {
        finish(window, ServerWindow.CloseReason.SERVER, reason);
    }

    /** The window open under {@code key}, or {@code null}. */
    @Nullable
    public ServerWindow byKey(String key) {
        if (key == null) return null;
        for (ServerWindow window : windows.values()) {
            if (key.equals(window.key())) return window;
        }
        return null;
    }

    /** Every window currently open, in the order they opened. */
    public List<ServerWindow> windows() {
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
        List<ServerWindow> live = new ArrayList<>(windows.values());
        Object viewer = connection.peer();

        for (ServerWindow window : live) {
            if (!window.live) continue;
            boolean valid;
            try {
                valid = window.stillValid(viewer);
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("<{}>.stillValid failed; closing it: {}",
                        window.type(), failed.getMessage(), failed);
                valid = false;
            }
            if (!valid) {
                finish(window, ServerWindow.CloseReason.NOT_VALID, "no longer valid");
            }
        }

        for (ServerWindow window : live) {
            if (!window.live) continue;
            try {
                window.tick();
                for (ServerFragment fragment : window.fragments) fragment.tick();
            } catch (RuntimeException failed) {
                // One window's broken tick must not stop every other window on this connection --
                // the frozen ones would show no error of their own, which is what gets diagnosed as a
                // network fault. Same rule CgUiConnections.tickSafely applies one layer down.
                CrystalGuiCore.LOGGER.error("<{}>.tick failed: {}",
                        window.type(), failed.getMessage(), failed);
            }
            ServerUiSession<Object> session = window.session;
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
            for (ServerWindow window : new ArrayList<>(windows.values())) {
                finish(window, ServerWindow.CloseReason.CONNECTION_LOST, reason);
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
     * outbound validation before it reached nothing.</p>
     */
    private void finish(ServerWindow window, ServerWindow.CloseReason reason, String detail) {
        if (window == null || !window.live) return;
        window.live = false;
        if (!closing) windows.remove(window.windowId);

        ServerUiSession<Object> session = window.session;
        if (session != null) {
            if (reason == ServerWindow.CloseReason.CONNECTION_LOST) {
                session.abandon(detail);
            } else if (reason == ServerWindow.CloseReason.CLIENT) {
                // The session has already stood down: this reason exists BECAUSE the client told it to,
                // through the handler that ends it. Telling the client its own news would be an echo.
                session.abandon(detail);
            } else {
                session.close(detail);
            }
        }

        // The session goes before the callback (it is dead, and handing over a dead one invites its
        // use); the HOST stays until after, so onClosed can still ask who was watching -- which is most
        // of what a teardown wants to know.
        window.session = null;
        try {
            window.onClosed(reason);
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.error("<{}>.onClosed failed: {}",
                    window.type(), failed.getMessage(), failed);
        }
        window.host = null;
        window.fragments.clear();
    }
}
