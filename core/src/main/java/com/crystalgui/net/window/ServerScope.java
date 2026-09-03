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
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.property.Property;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.net.projection.AutoProjection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import com.crystalgui.net.projection.Projections;
import java.util.Objects;
import com.crystalgui.net.ViewCommand;
import com.crystalgui.ui.contract.Event;

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

    private final ServerUiSession<UIElement, Object> session;
    private final ServerWindow<?> window;

    /** {@code ""} for the root panel, {@code "engines/"} for a nested one, and so on down. */
    private final String prefix;

    /** Ids already attached under this scope, so two children cannot claim one. */
    private final Set<String> childScopes = new LinkedHashSet<>();

    @Nullable
    private AutoProjection.Report lastAutoReport;

    ServerScope(ServerUiSession<UIElement, Object> session, ServerWindow<?> window, String prefix) {
        this.session = session;
        this.window = window;
        this.prefix = prefix;
    }

    // ── Projections: the model reaching the widgets ─────────────────────────
    //
    // The direction none of the audits covered. Before this, a panel wrote a mirror(model) method and
    // called it from tick() -- which works, and fails in four ways that are each silent: a field nobody
    // remembered to write never updates AND ITS FIRST VALUE IS RIGHT, so it looks correct on open and
    // freezes; a nested model is walked by hand; a collection cannot be expressed at all; and a window
    // nobody is watching pays for the walk anyway.
    //
    // Declared here, run by the engine before the flush, skipped entirely while no viewer is watching.

    /**
     * <b>Keeps a widget showing a value from the model.</b> Stated once; the engine keeps it true.
     *
     * <p>Deliberately the same shape as {@link #on}, because it is the same idea pointing the other
     * way:</p>
     *
     * <pre>{@code
     * io.on(power, Switch.TOGGLE, (ctx, on) -> model.setRunning(on));   // the user changes the model
     * io.project(power, Switch.CHECKED, model::isRunning);              // the model changes the screen
     * }</pre>
     *
     * <p>Read each tick, written <b>only when it differs</b> from what was written last time. So an
     * unchanged model writes no widget, marks nothing dirty and sends nothing at all.</p>
     *
     * <p><b>Your model is not touched</b> — {@code model::isRunning} is a method reference to an
     * accessor it already has. No interface, no annotations, no fields to convert.</p>
     */
    public <W extends UIElement, V> ServerScope project(W widget, State<W, V> slot, Supplier<V> from) {
        Objects.requireNonNull(slot, "slot");
        projections().onto(widget, from, value -> slot.set(widget, value));
        return this;
    }

    /**
     * As above, for a value with no {@link State} constant to name — a computed readout, or a widget
     * that carries nothing over the wire.
     *
     * <pre>{@code
     * io.project(status, () -> model.isRunning() ? "Running" : "Idle", UIText::setText);
     * io.project(coolant, () -> model.engine().coolant(), ProgressBar::setFraction);  // nesting is free
     * }</pre>
     *
     * <p>The widget comes first for the same reason it does above: it is what {@link #autoProject}
     * checks against, so anything stated here is left alone whatever order the two are called in.</p>
     */
    public <W extends UIElement, V> ServerScope project(W widget, Supplier<V> from,
                                                        java.util.function.BiConsumer<W, V> to) {
        Objects.requireNonNull(to, "to");
        projections().onto(widget, from, value -> to.accept(widget, value));
        return this;
    }

    /**
     * Keeps a container's children matching a list from the model, keyed — so an insert is an insert.
     *
     * <p>The collection case. An untouched row keeps its element, so over the wire this is one
     * {@code insert} rather than a rebuilt child list, and a reorder is a {@code move} rather than a
     * destroy-and-rebuild.</p>
     *
     * <p>The container is the projection's alone: nothing else may add children to it, and keys must be
     * unique — a duplicate is refused rather than quietly collapsing two items onto one row.</p>
     */
    public <T> ServerScope projectEach(Supplier<? extends List<T>> items, UIElement into,
                                       Function<T, Object> key, Function<T, UIElement> create,
                                       BiConsumer<UIElement, T> apply) {
        projections().each(items, into, key, create, apply);
        return this;
    }

    /**
     * Skips <b>this panel's</b> projections while {@code epoch} answers what it did last tick.
     *
     * <p>For a model large enough that one comparison per displayed field is worth avoiding. Scoped to
     * the panel that calls it, not to the window: a nested panel has its own model and its own notion
     * of having changed, and one shared gate would let either silence the other.</p>
     *
     * <p>Sound only if the epoch moves for every change that matters — one that misses a mutation makes
     * the panel miss it, silently. Opt-in, never a default.</p>
     */
    public ServerScope projectWhen(Supplier<?> epoch) {
        projections().gatedBy(epoch);
        return this;
    }

    /**
     * Wires every widget whose field name matches a model accessor, and <b>logs what it did not</b>.
     *
     * <p>A shortcut, not a requirement — {@link #project} covers everything and this covers the subset
     * whose names already line up. Anything already projected is left alone <b>in any order</b>, since
     * the check is by widget identity.</p>
     *
     * <p>The log is the feature. A convention that quietly skips a field leaves the widget at whatever
     * it was built with, which usually looks right and then never moves; the report names every field
     * whose model accessor exists and could still not be wired, with the reason.</p>
     */
    public ServerScope autoProject(Object model) {
        // The SAME boundary UiType.collect uses: a panel extending a panel contributes each level's
        // widgets, and a panel extending an ordinary widget must not have that widget's internals
        // claimed. ui.projection cannot name Networked without inverting the dependency, so the rule
        // is passed in from the side that knows what a panel is.
        AutoProjection.Report report = AutoProjection.wire(window.panel(), model, projections(),
                Networked.class::isAssignableFrom);
        if (!report.isEmpty()) {
            CrystalGuiCore.LOGGER.info("<{}> {}", window.typeId(), report);
            for (String line : report.lines()) {
                CrystalGuiCore.LOGGER.info("<{}>   {}", window.typeId(), line);
            }
        }
        lastAutoReport = report;
        return this;
    }

    /** The last {@link #autoProject} outcome, for a test that needs to assert on the misses. */
    @Nullable
    public AutoProjection.Report autoProjectReport() {
        return lastAutoReport;
    }

    /**
     * Follows an observable model field with no polling at all.
     *
     * <p>The third way, for a model whose fields are the engine's own {@link Property} — no comparison
     * per tick, because the property already suppresses an unchanged write. Invasive for a model you did
     * not write, which is why it is not the usual answer.</p>
     *
     * <p>The subscription is <b>undone when the window closes</b>. Without that the model — which
     * outlives the window — would hold a listener holding a widget holding the whole tree, which is the
     * ordinary shape of a listener leak.</p>
     */
    public <W extends UIElement, V> ServerScope bind(Property<V> source, W widget, State<W, V> slot) {
        slot.set(widget, source.get());
        window.bindings.add(source.changed.connect((old, now) -> slot.set(widget, now)));
        return this;
    }

    /** This panel's own set. @see ServerWindow#projectionsFor */
    private Projections projections() {
        return window.projectionsFor(this);
    }

    // ── Asking the view to do something ─────────────────────────────────────
    //
    // Not state. These say what should HAPPEN rather than what the UI is, so they are never part of a
    // description, are never replayed, and are dropped for a viewer that is not watching -- a focus
    // request held while a window was minimised and delivered on the way back would move the caret out
    // from under whoever had since started typing somewhere else. @see ViewCommand

    /**
     * Puts keyboard focus on a widget.
     *
     * <p>Focus that rings, deliberately: {@code :focus-visible} marks focus the user did not place with
     * a pointer, and focus arriving from a server is the clearest case there is.</p>
     */
    public ServerScope focus(UIElement widget) {
        session.viewOn(ViewCommand.FOCUS, widget, null);
        return this;
    }

    /** Scrolls a widget into view, honouring whatever scroll behaviour the sheet asked for. */
    public ServerScope scrollIntoView(UIElement widget) {
        session.viewOn(ViewCommand.SCROLL_INTO_VIEW, widget, null);
        return this;
    }

    /** Shows a {@code Dialog} that is already in the tree. Modal or not is the dialog's own business. */
    public ServerScope showDialog(UIElement dialog) {
        session.viewOn(ViewCommand.SHOW_DIALOG, dialog, null);
        return this;
    }

    /** Hides it again. Silent if it was not showing. */
    public ServerScope hideDialog(UIElement dialog) {
        session.viewOn(ViewCommand.HIDE_DIALOG, dialog, null);
        return this;
    }

    /**
     * Opens a {@code Popover} or {@code Menu} against something.
     *
     * <p>The anchor is required rather than optional: a popover exists relative to something, and one
     * opened at no position lands wherever the layout happened to leave it.</p>
     */
    public ServerScope openMenu(UIElement menu, UIElement anchor) {
        StateMap<Object> args = newMap();
        args.putInt(ViewCommand.ANCHOR, session.idOf(anchor));
        session.viewOn(ViewCommand.OPEN_MENU, menu, args);
        return this;
    }

    /**
     * Shows a tooltip on a widget.
     *
     * <p>Text, not a subtree — a tooltip is a sentence about a control, and a server able to graft an
     * arbitrary tree at a screen position would be a different feature with a different threat model.</p>
     */
    public ServerScope tooltip(UIElement widget, String text) {
        StateMap<Object> args = newMap();
        args.putString(ViewCommand.TEXT, text);
        session.viewOn(ViewCommand.TOOLTIP, widget, args);
        return this;
    }

    // ── About the window rather than the tree ───────────────────────────────

    /** Renames the window: what a caption shows and what a taskbar entry reads. */
    public ServerScope setTitle(String title) {
        StateMap<Object> args = newMap();
        args.putString(ViewCommand.TEXT, title);
        session.view(ViewCommand.SET_TITLE, args);
        return this;
    }

    /** Changes the window's icon, named as a sprite is: {@code "namespace:name"}. */
    public ServerScope setIcon(String icon) {
        StateMap<Object> args = newMap();
        args.putString(ViewCommand.TEXT, icon);
        session.view(ViewCommand.SET_ICON, args);
        return this;
    }

    /**
     * Suggests a size. A <b>hint</b>, and named so.
     *
     * <p>Where a window goes and how big it is belongs to the client's compositor and to the person
     * using it — a server that could place windows could also cover the screen with one. A host is free
     * to clamp it, ignore it, or apply it only on first open.</p>
     */
    public ServerScope geometryHint(int width, int height) {
        StateMap<Object> args = newMap();
        args.putInt(ViewCommand.WIDTH, width);
        args.putInt(ViewCommand.HEIGHT, height);
        session.view(ViewCommand.GEOMETRY_HINT, args);
        return this;
    }

    /**
     * Says something to the user that is not part of the window.
     *
     * <p>Goes to the host's own notification surface, so it survives the window closing and needs no
     * place in the layout to exist.</p>
     *
     * @param level one of {@code ViewCommand.LEVEL_INFO}, {@code LEVEL_WARN}, {@code LEVEL_ERROR}
     */
    public ServerScope notifyUser(String message, String level) {
        StateMap<Object> args = newMap();
        args.putString(ViewCommand.TEXT, message);
        args.putString(ViewCommand.LEVEL, level);
        session.view(ViewCommand.NOTIFY, args);
        return this;
    }

    /** As {@link #notifyUser(String, String)} at {@code info}. */
    public ServerScope notifyUser(String message) {
        return notifyUser(message, ViewCommand.LEVEL_INFO);
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
    public ServerScope on(UIElement element, String kind, Consumer<ServerUiSession.UiEventContext<UIElement, Object>> handler) {
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
          BiConsumer<ServerUiSession.UiEventContext<UIElement, Object>, P> handler) {
        session.on(element, event, handler);
        return this;
    }

    /** Subscribes to an event that carries nothing — a press, a close request. */
    public <W extends UIElement> ServerScope on(
            W element, Event<W, Void> event, Consumer<ServerUiSession.UiEventContext<UIElement, Object>> handler) {
        session.on(element, event, handler);
        return this;
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
     * @Override public void build(MachineModel m) { addChild(engines); }
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
        String name = child.id();
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
                child::closed));
        child.serve(slice, new ServerScope(session, window, prefix + name + "/"));
        return this;
    }

    // ── Odds and ends a handler reaches for ─────────────────────────────────

    /** The session underneath, for anything this view does not cover. Methods on it are UNQUALIFIED. */
    public ServerUiSession<UIElement, Object> session() {
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
