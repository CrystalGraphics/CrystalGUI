package com.crystalgui.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.MessageRouter;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dispatches by <b>window id as well as method</b>, so more than one UI session can share one wire.
 *
 * <h3>The limit this lifts</h3>
 *
 * <p>{@link MessageRouter} keys handlers by method name alone and <em>refuses</em> a duplicate — which is
 * the right failure, and it made one UI session per connection a structural fact: a second
 * {@link ServerUiSession} registering {@code ui/description} threw outright. Every message in the UI
 * vocabulary already carries {@link UiMethods#WINDOW}, and every session already re-checked it on the way
 * in ({@code ServerUiSession.mine}, and the {@code != windowId} guard on each client handler), so the id
 * was being <em>verified</em> by a handler that could only ever be one. This turns that check into the
 * lookup it always wanted to be.</p>
 *
 * <h3>Why it is a layer above the router rather than a change to it</h3>
 *
 * <p>{@code MessageRouter} is the generic transport vocabulary: it knows requests, responses,
 * notifications, cancels and correlation, and it knows nothing about windows — {@code WorkspaceRpc} and a
 * future {@code script/*} bind to it and have no window to be keyed by. Teaching it {@code UiMethods.WINDOW}
 * would put one subsystem's payload shape into the layer every other subsystem shares. So the split is
 * the same one {@link com.crystalgui.net.wire.FrameMultiplexer} already makes a layer down: the generic
 * thing carries ids, and the thing that knows what an id <em>means</em> sits on top.</p>
 *
 * <p>Practically it also means a method name is only ever registered on the router <b>once</b>, the first
 * time any window asks for it — so a subsystem binding {@code fs.read} directly on the connection and a
 * session binding {@code ui/description} through here cannot interfere unless they genuinely name the same
 * method, which is a real conflict and still throws.</p>
 *
 * <h3>What a message with no window id does</h3>
 *
 * <p><b>A request is refused; a notification is dropped with one warning.</b> Never delivered to "the only
 * window", however tempting that is while there is only one: a fallback that is correct with one window and
 * silently wrong with two is worse than the limit it replaces, because it fails exactly when the feature
 * starts being used. Outgoing calls are stamped at the session (see {@code ServerUiSession.request} and
 * {@code ClientUiSession.call}) precisely so that this branch stays unreachable in normal operation.</p>
 *
 * @param <T> the encoded representation, matching the connection's {@code DynamicOps}
 */
public final class UiWindowMux<T> {

    /**
     * One mux per connection, keyed weakly.
     *
     * <p>Two of these over one router would each install their own handler for a method and the second
     * would throw — so "the mux for this connection" has to be a single answer, not something a caller
     * remembers to share. Same shape and same reason as {@code WorkspaceClient.forConnection}, which was
     * itself written after two clients on one connection threw on a duplicate {@code fs.changed}.</p>
     */
    private static final Map<ProtocolConnection<?>, UiWindowMux<?>> BY_CONNECTION = new WeakHashMap<>();

    private final MessageRouter<T> router;
    private final DynamicOps<T> ops;

    /** method → window → handler. The outer map's key set is what has been installed on the router. */
    private final Map<String, Map<Integer, MessageRouter.RequestHandler<T>>> requests = new LinkedHashMap<>();
    private final Map<String, Map<Integer, MessageRouter.NotificationHandler<T>>> notifications = new LinkedHashMap<>();

    /** Every window id that has registered anything, so {@link #windows()} can answer without a scan. */
    private final Set<Integer> windows = new LinkedHashSet<>();

    /** (method, window) pairs already complained about, so a chatty peer costs one line. */
    private final Set<String> warned = new LinkedHashSet<>();

    private UiWindowMux(ProtocolConnection<T> connection) {
        this.router = connection.router();
        this.ops = connection.ops();
    }

    /** The mux for this connection, created on first use. */
    @SuppressWarnings("unchecked")
    public static synchronized <T> UiWindowMux<T> of(ProtocolConnection<T> connection) {
        UiWindowMux<?> existing = BY_CONNECTION.get(connection);
        if (existing != null) return (UiWindowMux<T>) existing;
        UiWindowMux<T> created = new UiWindowMux<>(connection);
        BY_CONNECTION.put(connection, created);
        return created;
    }

    // ── Registration ────────────────────────────────────────────────────────────────────────────

    /** Serves {@code method} for one window. Throws if that pair is already served. */
    public synchronized UiWindowMux<T> onRequest(int window, String method,
                                                 MessageRouter.RequestHandler<T> handler) {
        Map<Integer, MessageRouter.RequestHandler<T>> byWindow = requests.get(method);
        if (byWindow == null) {
            byWindow = new LinkedHashMap<>();
            requests.put(method, byWindow);
            // Installed once, for the life of the connection. Deliberately never removed: MessageRouter
            // has no unregister, and adding one to serve this would make a method name's binding depend
            // on teardown order across subsystems. An installed handler with no windows behind it costs
            // a map lookup and answers "no such window", which is the truth.
            final String bound = method;
            router.onRequest(method, (payload, respond) -> dispatchRequest(bound, payload, respond));
        }
        if (byWindow.putIfAbsent(window, handler) != null) {
            throw new IllegalStateException("window " + window + " already serves '" + method + "'");
        }
        windows.add(window);
        return this;
    }

    /** Listens for {@code method} on one window. Throws if that pair is already listened for. */
    public synchronized UiWindowMux<T> onNotify(int window, String method,
                                                MessageRouter.NotificationHandler<T> handler) {
        Map<Integer, MessageRouter.NotificationHandler<T>> byWindow = notifications.get(method);
        if (byWindow == null) {
            byWindow = new LinkedHashMap<>();
            notifications.put(method, byWindow);
            final String bound = method;
            router.onNotify(method, payload -> dispatchNotification(bound, payload));
        }
        if (byWindow.putIfAbsent(window, handler) != null) {
            throw new IllegalStateException("window " + window + " already listens for '" + method + "'");
        }
        windows.add(window);
        return this;
    }

    /**
     * Forgets everything registered for {@code window}.
     *
     * <p>Called when a session closes. Without it a closed window's handlers keep answering, which is the
     * <em>opposite</em> of the guard the per-handler window check was there for: a message still in flight
     * when a window closed would be applied to a session that has already gone.</p>
     */
    public synchronized void release(int window) {
        for (Map<Integer, MessageRouter.RequestHandler<T>> byWindow : requests.values()) {
            byWindow.remove(window);
        }
        for (Map<Integer, MessageRouter.NotificationHandler<T>> byWindow : notifications.values()) {
            byWindow.remove(window);
        }
        windows.remove(window);
    }

    /** Every window id with something registered. */
    public synchronized Set<Integer> windows() {
        return new LinkedHashSet<>(windows);
    }

    /** How many windows share this connection. */
    public synchronized int windowCount() {
        return windows.size();
    }

    // ── Dispatch ────────────────────────────────────────────────────────────────────────────────

    private void dispatchRequest(String method, @Nullable T payload, MessageRouter.Responder<T> respond) {
        int window = windowOf(payload);
        MessageRouter.RequestHandler<T> handler;
        synchronized (this) {
            Map<Integer, MessageRouter.RequestHandler<T>> byWindow = requests.get(method);
            handler = byWindow == null ? null : byWindow.get(window);
        }
        if (handler == null) {
            // ANSWERED, not dropped. A caller waiting on a request whose window has gone would otherwise
            // sit until its deadline and then report a timeout, which reads as a slow peer rather than a
            // closed window -- the same distinction ServerUiSession's description handler already makes
            // when it is asked for a hash it no longer serves.
            respond.fail("no window " + window + " on this connection for '" + method + "'");
            return;
        }
        handler.handle(payload, respond);
    }

    private void dispatchNotification(String method, @Nullable T payload) {
        int window = windowOf(payload);
        MessageRouter.NotificationHandler<T> handler;
        synchronized (this) {
            Map<Integer, MessageRouter.NotificationHandler<T>> byWindow = notifications.get(method);
            handler = byWindow == null ? null : byWindow.get(window);
        }
        if (handler == null) {
            warnOnce(method, window);
            return;
        }
        handler.handle(payload);
    }

    /** {@code -1} when the payload is absent or carries no window, which no UI message should be. */
    private int windowOf(@Nullable T payload) {
        if (payload == null) return -1;
        try {
            return new StateMap<>(ops, payload).getInt(UiMethods.WINDOW, -1);
        } catch (RuntimeException notAMap) {
            return -1;
        }
    }

    private synchronized void warnOnce(String method, int window) {
        if (warned.add(method + "#" + window)) {
            CrystalGuiCore.LOGGER.warn("No window {} on this connection for '{}' — dropping. Open windows: {}",
                    window, method, windows);
        }
    }
}
