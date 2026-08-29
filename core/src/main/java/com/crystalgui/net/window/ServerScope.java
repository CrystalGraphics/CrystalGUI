package com.crystalgui.net.window;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.EventKind;

/**
 * What a panel's {@link Networked#serve} is handed — <b>its registration surface, namespaced to its
 * place in the window</b>.
 *
 * <p>The scope is what makes a panel location-independent: the same {@code serve} body registers the
 * same names whether the panel is the root of its own window or nested three levels deep in someone
 * else's, because the scope it was handed carries the answer. The root's prefix is empty; a panel
 * {@linkplain #attach attached} under it has its wire methods qualified by its <b>element id</b> —
 * {@code "save"} becomes {@code "engines/save"} — and nesting concatenates. The id is the one name
 * both sides already share (the description carries it), so the client derives the identical prefix
 * from the identical tree and nothing can drift.</p>
 *
 * <h3>What is prefixed and what is not</h3>
 *
 * <table>
 *   <tr><th>Surface</th><th>Keyed by</th><th>Why that is enough</th></tr>
 *   <tr><td>{@link #on}, {@link #onActivate}</td><td>the element</td>
 *       <td>A panel's handlers are on its own elements. A parent has nothing to collide with, and
 *           reaching in is refused by the session rather than silently winning.</td></tr>
 *   <tr><td>{@link #onCall}, {@link #call}, {@link #onNotify}, {@link #notify}</td>
 *       <td>method string, window-scoped, <b>scope-prefixed</b></td>
 *       <td>Two children — or two instances of one panel class — are under different ids, so the same
 *           method name in each is two different methods.</td></tr>
 * </table>
 *
 * <p>A scope is a <b>view, not a layer</b>: everything registered here lands on the window's one
 * session, on the one connection the window was opened over. The prefix is a string on the method
 * name and nothing else.</p>
 */
public final class ServerScope {

    private final ServerUiSession<Object> session;
    private final ServerWindow<?> window;

    /** {@code ""} for the root panel, {@code "engines/"} for a nested one, and so on down. */
    private final String prefix;

    /** Ids already attached under this scope, so two children cannot claim one. */
    private final Set<String> childScopes = new LinkedHashSet<>();

    ServerScope(ServerUiSession<Object> session, ServerWindow<?> window, String prefix) {
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
    public ServerScope on(UIElement element, String kind, Consumer<ServerUiSession.UiEventContext<Object>> handler) {
        session.on(element, kind, handler);
        return this;
    }

    /**
     * Subscribes to an event the widget declares, typed.
     *
     * <pre>{@code io.on(picker, ColorSelector.CHANGED, (ctx, colour) -> model.setColour(colour)); }</pre>
     *
     * <p><b>The form to reach for.</b> No kind vocabulary to consult, no string to misspell, a decoded
     * payload, and an event belonging to another widget will not compile. @see
     * ServerUiSession#on(UIElement, Event, java.util.function.BiConsumer)</p>
     */
    public <W extends UIElement, P> ServerScope on(
            W element, Event<W, P> event,
          BiConsumer<ServerUiSession.UiEventContext<Object>, P> handler) {
        session.on(element, event, handler);
        return this;
    }

    /** Subscribes to an event that carries nothing — a press, a close request. */
    public <W extends UIElement> ServerScope on(
            W element, Event<W, Void> event, Consumer<ServerUiSession.UiEventContext<Object>> handler) {
        session.on(element, event, handler);
        return this;
    }

    /** A press, a toggle, or a commit — whatever the widget considers "the user did the thing". */
    public ServerScope onActivate(UIElement element, Consumer<ServerUiSession.UiEventContext<Object>> handler) {
        return on(element, EventKind.ACTIVATE, handler);
    }

    // ── Wire methods ────────────────────────────────────────────────────────

    /** Serves a method the client may call, under this panel's name. */
    public ServerScope onCall(String method, Call.Handler<Object> handler) {
        session.onCall(qualify(method), handler);
        return this;
    }

    /** Asks the client, under this panel's name. Two callbacks: refused and never-answered differ. */
    public void call(String method, @Nullable StateMap<Object> args,
                     @Nullable Consumer<StateMap<Object>> onResult, @Nullable Consumer<String> onError) {
        session.call(qualify(method), args, onResult, onError);
    }

    /** Listens for a notification on this window, under this panel's name. */
    public ServerScope onNotify(String method, Consumer<StateMap<Object>> handler) {
        session.onNotify(qualify(method), handler);
        return this;
    }

    /** Tells the client. Nothing comes back, and nothing may be waited on. */
    public void notify(String method, @Nullable StateMap<Object> payload) {
        session.notify(qualify(method), payload);
    }

    /**
     * What a method of this scope is called on the wire — {@code "engines/save"}.
     *
     * <p>The client's {@link ClientScope} qualifies with the identical prefix, derived from the
     * identical ids, so the two sides agree without either naming a string.</p>
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
     * <p>Only legal from {@link Networked#serve} — the list is part of the {@code ui/openWindow} the
     * host is about to send, and adding one afterwards would be naming a theme the client will never
     * hear about. The scope existing only at bind time is what enforces that.</p>
     */
    public ServerScope sheet(SheetRef ref) {
        session.addSheet(ref);
        return this;
    }

    /**
     * Names a stylesheet <b>and offers its text</b>, so a client that has never heard of it can ask.
     *
     * <p>Use this for a sheet the server authored. The one-argument form is right for a theme the
     * client is expected to already ship, where sending bytes both sides hold is waste.</p>
     */
    public ServerScope sheet(SheetRef ref, @Nullable String css) {
        session.addSheet(ref, css);
        return this;
    }

    /** Whether the engine's own sheet goes underneath. On by default, and almost always right. */
    public ServerScope useUserAgentSheet(boolean use) {
        session.setUseUserAgentSheet(use);
        return this;
    }

    // ── Composition ─────────────────────────────────────────────────────────

    /**
     * Hands a nested panel its slice of the model and its own scope — <b>how a UI composes</b>.
     *
     * <pre>{@code
     * public EnginePanel engines;                          // a panel is an element: just a field
     *
     * @Override public void layout(MachineModel m) { addChild(engines); }
     * @Override public void serve(MachineModel m, ServerScope io) {
     *     io.attach(engines, m.engines());                 // props down: the SLICE, not the model
     * }
     * }</pre>
     *
     * <p>The child's {@link Networked#serve} runs against a scope prefixed by the child's element id —
     * the field name, which the field walk already stamped — so its {@code "save"} is
     * {@code "engines/save"} on the wire, on both sides, with nobody naming a string. The child is
     * ticked with its slice after this window's own tick, and told {@code closed} when the window
     * ends.</p>
     *
     * <p>The child's element must already be in the tree — this wires behaviour, it does not decide
     * layout. The parent hands the child the narrowest slice it honestly needs; the child mutates it
     * through ordinary Java calls, and the dirty set does not care which object wrote a widget. When
     * the parent must <em>react</em> to the child, the child exposes a plain callback — never a
     * session message, which would be a round trip to the room you are standing in.</p>
     *
     * @throws IllegalStateException if a child with this id is already attached to this scope — two
     *                               things claiming one namespace is a wiring mistake, not something
     *                               to resolve by letting the second win
     */
    public <C extends UIElement & Networked<S>, S> ServerScope attach(C child, @Nullable S slice) {
        if (child == null) throw new IllegalArgumentException("child is null");
        String name = child.getId();
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a nested " + child.getClass().getSimpleName()
                    + " needs an id to scope its methods under — declare it as a field of the parent "
                    + "and the field name becomes one");
        }
        if (name.indexOf('/') >= 0) {
            // Or the prefix stops being a path and two different nestings can spell one name.
            throw new IllegalArgumentException("a scope id may not contain '/': " + name);
        }
        if (!childScopes.add(name)) {
            throw new IllegalStateException("'" + name + "' is already attached to this scope");
        }
        window.attached.add(new ServerWindow.Attached(
                () -> child.tick(slice),
                reason -> child.closed(reason)));
        child.serve(slice, new ServerScope(session, window, prefix + name + "/"));
        return this;
    }

    // ── Odds and ends a handler reaches for ─────────────────────────────────

    /** The session underneath, for anything this view does not cover. Methods on it are UNQUALIFIED. */
    public ServerUiSession<Object> session() {
        return session;
    }

    /** The window this scope belongs to. */
    public ServerWindow<?> window() {
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
