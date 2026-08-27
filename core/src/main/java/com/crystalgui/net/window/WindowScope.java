package com.crystalgui.net.window;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UiEventKinds;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;

/**
 * How a {@link ServerWindow} or a {@link ServerFragment} registers what it can do — a <b>view</b> of
 * the session, namespaced to whoever is holding it.
 *
 * <p>The window's own scope has an empty prefix and behaves exactly as the session does. A fragment
 * attached as {@code "inventory"} gets a scope whose wire methods are prefixed {@code "inventory/"},
 * and one attached inside <em>that</em> as {@code "slots"} gets {@code "inventory/slots/"}. Nothing
 * else changes: the element handlers are the session's, the calls are the session's, and this adds a
 * string to a method name and a check that two things did not claim one name.</p>
 *
 * <h3>What is prefixed and what is not</h3>
 *
 * <table>
 *   <tr><th>Surface</th><th>Keyed by</th><th>Why that is enough</th></tr>
 *   <tr><td>{@link #on}, {@link #onActivate}</td><td>the element</td>
 *       <td>A fragment's handlers are on the fragment's own elements. A parent has nothing to collide
 *           with, and reaching in is refused by the session rather than silently winning.</td></tr>
 *   <tr><td>{@link #onCall}, {@link #call}, {@link #onNotify}, {@link #notify}</td>
 *       <td>method string, window-scoped, <b>scope-prefixed</b></td>
 *       <td>Two fragments — or two instances of one fragment — are under different names, so the same
 *           method name in each is two different methods.</td></tr>
 * </table>
 *
 * <p>The client speaks the <em>qualified</em> name, which {@link #qualify} hands over for the rare
 * fragment that needs a client counterpart. Most do not: reported events are element-keyed, and that is
 * already isolated.</p>
 */
public final class WindowScope {

    private final ServerUiSession<Object> session;
    private final ServerWindow window;

    /** {@code ""} for a window, {@code "inventory/"} for a fragment, and so on down. */
    private final String prefix;

    /** Names already attached under this scope, so two fragments cannot claim one. */
    private final Set<String> childScopes = new LinkedHashSet<>();

    WindowScope(ServerUiSession<Object> session, ServerWindow window, String prefix) {
        this.session = session;
        this.window = window;
        this.prefix = prefix;
    }

    // ── Widget events ───────────────────────────────────────────────────────

    /**
     * Runs {@code handler} when the client reports a {@code kind} interaction on {@code element}.
     *
     * <p>The lambda is recorded on the session and never goes near the element — that is what lets the
     * server keep behaviour while the client holds only a description. The element learns the event's
     * <em>name</em>, so the client knows to report it, and nothing else.</p>
     *
     * <p>Not prefixed, and does not need to be: the element <em>is</em> the key.</p>
     */
    public WindowScope on(UIElement element, String kind, Consumer<ServerUiSession.UiEventContext<Object>> handler) {
        session.on(element, kind, handler);
        return this;
    }

    /** A press, a toggle, or a commit — whatever the widget considers "the user did the thing". */
    public WindowScope onActivate(UIElement element, Consumer<ServerUiSession.UiEventContext<Object>> handler) {
        return on(element, UiEventKinds.ACTIVATE, handler);
    }

    // ── Wire methods ────────────────────────────────────────────────────────

    /** Serves a method the client may call, under this scope's name. */
    public WindowScope onCall(String method, Call.Handler<Object> handler) {
        session.onCall(qualify(method), handler);
        return this;
    }

    /** Asks the client, under this scope's name. Two callbacks: refused and never-answered differ. */
    public void call(String method, @Nullable StateMap<Object> args,
                     @Nullable Consumer<StateMap<Object>> onResult, @Nullable Consumer<String> onError) {
        session.call(qualify(method), args, onResult, onError);
    }

    /** Listens for a notification on this window, under this scope's name. */
    public WindowScope onNotify(String method, Consumer<StateMap<Object>> handler) {
        session.onNotify(qualify(method), handler);
        return this;
    }

    /** Tells the client. Nothing comes back, and nothing may be waited on. */
    public void notify(String method, @Nullable StateMap<Object> payload) {
        session.notify(qualify(method), payload);
    }

    /**
     * What a method of this scope is called on the wire — {@code "inventory/save"}.
     *
     * <p>For a fragment that genuinely needs a client counterpart to call it by name. Most do not: a
     * reported event is keyed by its element and is isolated already.</p>
     */
    public String qualify(String method) {
        return prefix.isEmpty() ? method : prefix + method;
    }

    // ── Theming ─────────────────────────────────────────────────────────────

    /**
     * Names a stylesheet this window wants, <b>in order</b>.
     *
     * <p>Order is load-bearing and must not be sorted: the engine's sheet list is flat and ordered, and
     * a later sheet wins ties at equal specificity.</p>
     *
     * <p>Only legal from {@link ServerWindow#bind} — the list is part of the {@code ui/openWindow} the
     * host is about to send, and adding one afterwards would be naming a theme the client will never
     * hear about.</p>
     */
    public WindowScope sheet(com.crystalgui.net.SheetRef ref) {
        session.addSheet(ref);
        return this;
    }

    /**
     * Names a stylesheet <b>and offers its text</b>, so a client that has never heard of it can ask.
     *
     * <p>Use this for a sheet the server authored. The one-argument form is right for a theme the
     * client is expected to already ship, where sending bytes both sides hold is waste.</p>
     */
    public WindowScope sheet(com.crystalgui.net.SheetRef ref, @Nullable String css) {
        session.addSheet(ref, css);
        return this;
    }

    /** Whether the engine's own sheet goes underneath. On by default, and almost always right. */
    public WindowScope useUserAgentSheet(boolean use) {
        session.setUseUserAgentSheet(use);
        return this;
    }

    // ── Composition ─────────────────────────────────────────────────────────

    /**
     * Attaches a fragment under {@code name}, binding it now and ticking it from here on.
     *
     * <p>The fragment's root must already be in the tree — this wires behaviour, it does not decide
     * layout. On a window that is <b>already open</b> that is still legal, and deliberately: the
     * elements have never been described, so the description they are part of has not been sent, and
     * the tree delta that carries them re-describes their reported events for the client to wire. (The
     * old blanket "nothing after open" rule would have made every fragment un-attachable.)</p>
     *
     * @throws IllegalStateException if {@code name} is already attached to this scope — two things
     *                               claiming one namespace is a wiring mistake, not something to
     *                               resolve by letting the second win
     */
    public WindowScope attach(ServerFragment fragment, String name) {
        if (fragment == null) throw new IllegalArgumentException("fragment is null");
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a fragment needs a name");
        if (name.indexOf('/') >= 0) {
            // Or the prefix stops being a path and two different nestings can spell one name.
            throw new IllegalArgumentException("a scope name may not contain '/': " + name);
        }
        if (!childScopes.add(name)) {
            throw new IllegalStateException("'" + name + "' is already attached to this scope");
        }
        window.fragments.add(fragment);
        fragment.bind(new WindowScope(session, window, prefix + name + "/"));
        return this;
    }

    // ── Odds and ends a handler reaches for ─────────────────────────────────

    /** The session underneath, for anything this view does not cover. */
    public ServerUiSession<Object> session() {
        return session;
    }

    /** The window this scope belongs to. */
    public ServerWindow window() {
        return window;
    }

    /** The wire format — always the connection's own, never a hardcoded one. */
    public DynamicOps<Object> ops() {
        return session.ops();
    }

    /** An empty payload in the connection's format, for a handler that is about to fill one in. */
    public StateMap<Object> newMap() {
        return new StateMap<>(session.ops());
    }
}
