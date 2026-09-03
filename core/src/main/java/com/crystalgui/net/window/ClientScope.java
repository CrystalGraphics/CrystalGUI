package com.crystalgui.net.window;

import com.crystalgui.ui.dom.UIElement;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.fs.client.Workspace;
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

    private final ClientUiSession<UIElement, Object> session;
    private final ClientWindowContext window;

    /** {@code ""} for the root panel, {@code "engines/"} for a nested one, and so on down. */
    private final String prefix;

    ClientScope(ClientUiSession<UIElement, Object> session, ClientWindowContext window, String prefix) {
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
    public ClientUiSession<UIElement, Object> session() {
        return session;
    }

    /** The window this panel is mounted in — title, key, sheets, {@code userClosed}. */
    /**
     * <b>Adds a control of the viewer's own</b> to a served element — the one door for it.
     *
     * <pre>{@code
     * io.addLocal(row, new Button("Copy"));    // no round trip, no server involvement
     * }</pre>
     *
     * <p>{@link Networked#client} runs over <em>every</em> build of the tree and its javadoc already
     * says to write it as though nothing had been set up before. What it could not do is <em>add</em>
     * anything: a child appended by hand is an ordinary described child, so the next {@code insert} the
     * server sends lands one index off — silently, because an index is an int and every one of them
     * still resolves to something. This marks the child first, which is what makes it invisible to the
     * mirror and keeps it out of the described positions.</p>
     *
     * <p>The child is <b>owned by this panel instance</b>. A re-describe builds a fresh tree and fresh
     * panels, so the old locals go with the old tree and {@code client(io)} adds them again — which is
     * exactly what that hook running on every bind is for.</p>
     *
     * <p>A local control follows served state through the served widget's own signal: a state delta
     * runs the ordinary setter, which fires the ordinary signal. There is no binding API here because
     * there is nothing for one to do.</p>
     */
    public ClientScope addLocal(UIElement parent, UIElement child) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
        child.markLocal();
        parent.append(child);
        return this;
    }

    /**
     * <b>The workspace on this connection</b> — the same filesystem the editor reads.
     *
     * <pre>{@code
     * io.workspace().files().list(CgPath.ofProject("mymod.proj"))
     *         .then(entries -> …);
     * }</pre>
     *
     * <p>A panel that shows files reads them through the fs protocol, never through the mirror. Shipping
     * a listing as described elements makes a directory of ten thousand files into ten thousand
     * elements, re-sent whenever anything in it changes — and the workspace already has watches,
     * etags, chunked reads and a permission model that a hand-rolled listing does not.</p>
     *
     * <p>Connection-scoped, not window-scoped: two panels on one client share one workspace, one cache
     * and one set of watches.</p>
     */
    public Workspace workspace() {
        return Workspace.of(window.connection());
    }

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
