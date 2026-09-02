package com.crystalgui.net;

import com.crystalgui.style.Styleable;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.mirror.NodeMirror;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every UI window a client is showing over one connection — one {@link ClientUiSession} per window id.
 *
 * <h3>Why the client needs a host and the server does not</h3>
 *
 * <p>A {@link ServerUiSession} is <em>given</em> its window id at construction, so it can register
 * window-scoped handlers immediately and needs nothing above it. A client learns an id from the wire, and
 * the message that carries it — {@code ui/openWindow} — is therefore the one thing in the vocabulary that
 * <b>cannot itself be window-scoped</b>: it is what announces the window. Something has to own it for the
 * whole connection and hand each announcement to the right session. That is this.</p>
 *
 * <p>The alternative — letting each unbound {@code ClientUiSession} listen and take whichever open
 * arrives first — reads as simpler and is not a design: with two windows opening, which session gets
 * which is decided by registration order, and the failure is two windows that render each other's
 * trees. There is no id to check against yet, so nothing downstream could even detect it.</p>
 *
 * <h3>Sessions are created, not registered</h3>
 *
 * <p>A window appears because the server opened one, so a client cannot pre-declare them. {@link
 * #onSession} is told about each as it is created — before the description is requested, so a caller may
 * attach {@link ClientUiSession#onWindowOpened} and its own RPC methods and be sure of hearing the first
 * one. Attaching afterwards would silently miss exactly the window that prompted the callback.</p>
 *
 * @param <T> the encoded representation, matching the connection's {@code DynamicOps}
 */
public final class ClientUiSessions<N extends Styleable, T> {

    private final ProtocolConnection<T> connection;
    private final Map<Integer, ClientUiSession<N, T>> sessions = new LinkedHashMap<>();

    @Nullable
    private Consumer<ClientUiSession<N, T>> onSession;

    /**
     * Told the panel class an {@code ui/openWindow} names, BEFORE the session sees the message — so
     * the installer can initialise the class while the description is still in flight (a cache hit
     * decodes synchronously inside {@code acceptOpenWindow}, so later is too late). Static, because
     * which classes this installation can show is a fact about the installation, not a connection.
     */
    @Nullable
    private static Consumer<String> uiClassLoader;

    /**
     * Builds the mirror a session applies deltas through -- installed once, exactly as the UI-class
     * loader is, and for the same reason: which tree an installation mirrors is a fact about the
     * installation, not about a connection. It lives here as a factory rather than as a named type
     * because this package may not name an engine; {@code net.window} installs the one we ship.
     */
    @Nullable
    private static Function<DynamicOps<?>, ? extends NodeMirror<?, ?>> mirrorFactory;

    /** Installs the mirror factory. One factory; the window layer owns it. */
    public static void setMirrorFactory(@Nullable Function<DynamicOps<?>, ? extends NodeMirror<?, ?>> factory) {
        mirrorFactory = factory;
    }

    /** Installs the UI-class loader. One consumer; the window layer owns it. @see UiMethods#UI_CLASS */
    public static void setUiClassLoader(@Nullable Consumer<String> loader) {
        uiClassLoader = loader;
    }

    private ClientUiSessions(ProtocolConnection<T> connection) {
        this.connection = connection;
        connection.router().onNotify(UiMethods.OPEN_WINDOW, payload -> accept(
                payload == null ? new StateMap<>(connection.ops())
                        : new StateMap<>(connection.ops(), payload)));
    }

    /**
     * The host for this connection, created on first use.
     *
     * <p><b>Throws if a plain {@link ClientUiSession} already rides this connection</b>, and the message
     * comes from {@code MessageRouter}'s duplicate check rather than from a check here — the two are
     * mutually exclusive precisely because both want {@code ui/openWindow}, and letting the router say
     * so keeps one statement of the rule.</p>
     */
    @SuppressWarnings("unchecked")
    public static <N extends Styleable, T> ClientUiSessions<N, T> forConnection(ProtocolConnection<T> connection) {
        // Held by the connection rather than in a static WeakHashMap here — one statement of
        // "the X for this connection", and it dies with the connection. @see ProtocolConnection#attachment
        return (ClientUiSessions<N, T>) connection.attachment(
                ClientUiSessions.class, c -> new ClientUiSessions<>(c));
    }

    /**
     * Called for each session as it is created, before its description is requested.
     *
     * <p>Set this before the first window opens. A host that installs it late has already missed one.</p>
     */
    public ClientUiSessions<N, T> onSession(Consumer<ClientUiSession<N, T>> handler) {
        this.onSession = handler;
        return this;
    }

    /** The session showing this window, or {@code null}. */
    @Nullable
    public ClientUiSession<N, T> session(int windowId) {
        return sessions.get(windowId);
    }

    /** Every window currently open, in the order they opened. */
    public Collection<ClientUiSession<N, T>> sessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public int sessionCount() {
        return sessions.size();
    }

    // ── The bootstrap ───────────────────────────────────────────────────────────────────────────

    private void accept(StateMap<T> in) {
        int id = in.getInt(UiMethods.WINDOW, -1);
        if (id < 0) {
            CrystalGuiCore.LOGGER.warn("Ignoring an openWindow with no window id");
            return;
        }

        String uiClass = in.getString(UiMethods.UI_CLASS, "");
        Consumer<String> loader = uiClassLoader;
        if (!uiClass.isEmpty() && loader != null) loader.accept(uiClass);

        ClientUiSession<N, T> session = sessions.get(id);
        if (session == null) {
            Function<DynamicOps<?>, ? extends NodeMirror<?, ?>> factory = mirrorFactory;
            if (factory == null) {
                CrystalGuiCore.LOGGER.error(
                        "Ignoring an openWindow: no mirror factory is installed, so there is nothing "
                                + "to apply this window's deltas to");
                return;
            }
            @SuppressWarnings("unchecked") // The installation supplies one tree; 6.9b collapses N.
            NodeMirror<N, T> mirror = (NodeMirror<N, T>) factory.apply(connection.ops());
            session = new ClientUiSession<>(mirror, connection, id);
            // Registered BEFORE the callback and before the description request, so a re-open arriving
            // while the first is still in flight finds the session rather than building a second one
            // that would then throw on a duplicate (method, window) pair.
            sessions.put(id, session);
            final int key = id;
            session.onReleased = () -> sessions.remove(key);
            if (onSession != null) onSession.accept(session);
        }
        // RE-DELIVERED to an existing session rather than refused. A server re-opening a window it
        // already served is how a reshape reaches a client that missed the delta, and ClientUiSession
        // already treats an open as authoritative -- it re-reads the sheets, the count and the hash.
        session.acceptOpenWindow(in);
    }
}
