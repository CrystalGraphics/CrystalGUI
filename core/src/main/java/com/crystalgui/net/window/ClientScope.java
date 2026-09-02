package com.crystalgui.net.window;

import com.crystalgui.ui.dom.UINode;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

/**
 * What a panel's {@link Networked#client} is handed — <b>the client-side mirror of
 * {@link ServerScope}</b>, namespaced to this panel's place in the window.
 *
 * <p>The root panel's scope has an empty prefix and behaves exactly as the session does. A nested
 * panel's wire methods are qualified by its <b>element id path</b> — the same derivation the server's
 * scope makes from the same ids, which is what makes the two halves of {@code "engines/save"} agree
 * by construction: the one string that used to be a coordination hazard is a fact of the tree, and
 * the tree is what the description already synchronizes.</p>
 *
 * <p>A scope is a <b>view, not a layer</b>: every call and notification from any nesting depth is an
 * ordinary envelope with the same window id on the same session on the same connection. The prefix is
 * a string on the method name and nothing else.</p>
 */
public final class ClientScope {

    private final ClientUiSession<UINode, Object> session;
    private final ClientWindowContext window;

    /** {@code ""} for the root panel, {@code "engines/"} for a nested one, and so on down. */
    private final String prefix;

    ClientScope(ClientUiSession<UINode, Object> session, ClientWindowContext window, String prefix) {
        this.session = session;
        this.window = window;
        this.prefix = prefix;
    }

    // ── Wire methods, under this panel's name ───────────────────────────────

    /** Serves a method the server may call on this panel. */
    public ClientScope onCall(String method, Call.Handler<Object> handler) {
        session.onCall(qualify(method), handler);
        return this;
    }

    /** Asks the server. Two callbacks: refused and never-answered differ, and only one is worth retrying. */
    public void call(String method, @Nullable StateMap<Object> args,
                     @Nullable Consumer<StateMap<Object>> onResult, @Nullable Consumer<String> onError) {
        session.call(qualify(method), args, onResult, onError);
    }

    /** Listens for a notification aimed at this panel. */
    public ClientScope onNotify(String method, Consumer<StateMap<Object>> handler) {
        session.onNotify(qualify(method), handler);
        return this;
    }

    /** Tells the server. Nothing comes back, and nothing may be waited on. */
    public void notify(String method, @Nullable StateMap<Object> payload) {
        session.notify(qualify(method), payload);
    }

    /** What a method of this scope is called on the wire — {@code "engines/save"}. */
    public String qualify(String method) {
        return prefix.isEmpty() ? method : prefix + method;
    }

    // ── Odds and ends a handler reaches for ─────────────────────────────────

    /** This window's session, for anything this view does not cover. Methods on it are UNQUALIFIED. */
    public ClientUiSession<UINode, Object> session() {
        return session;
    }

    /** The window this panel is mounted in — title, key, sheets, {@code userClosed}. */
    public ClientWindowContext window() {
        return window;
    }

    /** The wire format — always the connection's own, never a hardcoded one. */
    public DynamicOps<Object> ops() {
        return session.ops();
    }

    /** An empty payload in the connection's format, for a handler about to fill one in. */
    public StateMap<Object> newMap() {
        return new StateMap<>(session.ops());
    }
}
