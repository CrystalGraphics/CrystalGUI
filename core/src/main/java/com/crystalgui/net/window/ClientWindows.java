package com.crystalgui.net.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ClientUiSessions;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;

/**
 * Every window this client is showing over one connection — <b>the mirror of {@link ServerWindows}</b>.
 *
 * <p>Owns the two things a host used to leave to each mod, both of which are races when written by
 * hand: adopting each session as it arrives (and installing the listener <em>before</em> the first
 * window can open, because a miss there is silent), and reconciling that against a place to put a
 * window, which may not exist yet.</p>
 *
 * <h3>What it replaces</h3>
 *
 * <p>Forty lines per client mod: track whether the connection has been replaced, tear down on
 * disconnect, install {@code ClientUiSessions.onSession} at exactly the right moment, and poll every
 * client tick for "is there a screen yet and is there a window yet". The poll existed because the two
 * orderings are genuinely independent — a window can arrive before the screen has ever been opened, and
 * the screen can be opened before any window arrives. Here whichever happens second completes the
 * mount, deterministically, and the queue is the whole mechanism.</p>
 *
 * <h3>Dispatch by type, and an unknown type still works</h3>
 *
 * <p>{@link #register} is {@code MenuScreens.register}: a factory per window type, consulted when a
 * window of that type opens. Before the wire carried a type, a client host adopted <em>every</em>
 * window on the connection and ran its own {@code querySelector}s against whatever arrived — so the day
 * a second mod opened a window, its tree was handed to the first mod's behaviour: a silent no-op where
 * the ids missed, and a listener on somebody else's button where they collided.</p>
 *
 * <p>A window whose type nothing registered <b>still mounts and still works</b>. That is not a fallback,
 * it is the architecture: the client rebuilt the tree from a description and wired every reported event
 * from the description itself, so a behaviour only ever adds things this client wants for itself.</p>
 */
public final class ClientWindows {

    /**
     * type id → local behaviour. Static, because what a client can do about a window type is a fact about
     * this installation rather than about any one connection — the same reason {@code MenuScreens}'
     * registry is static and a {@code MenuType} is not per player.
     */
    private static final Map<String, Registration<?>> FACTORIES = new ConcurrentHashMap<>();

    private final ProtocolConnection<Object> connection;

    /**
     * Session → what is on screen for it. Insertion-ordered so teardown is reproducible.
     *
     * <p><b>Keyed by the session, not by its window id</b>, and that is not a preference.
     * {@code ClientUiSession} calls {@code release()} — which sets the id back to -1 — <em>before</em>
     * it emits {@code onWindowClosed}, so a lookup by id at close time misses every time and the
     * window is never taken off screen. The session object is the one identity that is stable for the
     * whole of a window's life.</p>
     */
    private final Map<ClientUiSession<Object>, Mounted> mounted = new LinkedHashMap<>();

    /** Sessions whose tree is ready and which have nowhere to go yet. @see #setMount */
    private final List<ClientUiSession<Object>> waiting = new ArrayList<>();

    @Nullable
    private WindowMount mount;

    @Nullable
    private SheetSupply sheets;

    private ClientWindows(ProtocolConnection<Object> connection) {
        this.connection = connection;
        // A description addresses widgets by tag, and an unregistered tag THROWS on decode rather than
        // degrading to a styleless div. Idempotent, and every lookup would trigger it anyway; it is here
        // so the dependency is visible at the one place trees get rebuilt.
        ElementRegistry.bootstrapBuiltins();

        /*
         * INSTALLED THE MOMENT THE CONNECTION EXISTS, which is the race this class exists to remove.
         * ClientUiSessions.onSession is told about each session BEFORE its description is requested, so
         * a listener attached late has already missed exactly the window that prompted it -- and missed
         * it in silence. A contributor binding at connection open is the only moment that cannot be
         * late.
         */
        ClientUiSessions.forConnection(connection).onSession(this::adopt);
        connection.onClosed(this::onConnectionClosed);
    }

    /** The host for this connection, created on first use. */
    public static ClientWindows of(ProtocolConnection<Object> connection) {
        return connection.attachment(ClientWindows.class, ClientWindows::new);
    }

    /** Builds the host so it starts listening for windows. @see WindowProtocol */
    static void install(ProtocolConnection<Object> connection) {
        of(connection);
    }

    // ── The type registry ───────────────────────────────────────────────────

    /**
     * Says what this client does locally about a window type, <b>type-checked against its panel</b>.
     *
     * <pre>{@code
     * ClientWindows.register(MachinePanel.TYPE, MachineClient::new);
     * }</pre>
     *
     * <p><b>The pairing is checked by the behaviour's own type.</b> {@code MachineClient} declares
     * {@code implements ClientWindowBehaviour<MachinePanel>}, so registering it against a
     * {@code WindowType<SomethingElse>} does not compile — where a pair of strings gave a runtime
     * no-op that looked exactly like a window with deliberately no behaviour.</p>
     *
     * <p>The panel itself arrives at {@link ClientWindowBehaviour#onPanelBound}, {@link WindowType#bind
     * bound} to the rebuilt tree — at mount and again after every re-describe — so a behaviour reaches
     * {@code panel.askStats} rather than {@code querySelector("#ask-stats")} guarded by an
     * {@code instanceof} that silently does nothing when the id moves.</p>
     *
     * <p>Called once at mod init, like {@code MenuScreens.register}. Idempotent per type;
     * re-registering replaces. Windows of unregistered types are unaffected and still open.</p>
     *
     * <p><b>Registered from client code, and that is the loader seam rather than a wart.</b> A shared
     * descriptor naming the behaviour would be a {@code static final} field whose initialiser resolves
     * that constructor at class init, loading a client-only class on a dedicated server. @see
     * WindowType</p>
     */
    public static <P> void register(WindowType<P> type,
                                    Function<ClientWindowContext, ClientWindowBehaviour<P>> factory) {
        if (type == null) throw new IllegalArgumentException("a behaviour needs a type");
        if (factory == null) throw new IllegalArgumentException("factory is null");
        FACTORIES.put(type.id(), new Registration<>(type, factory));
    }

    /**
     * <b>The whole client side of a {@link Panel}, in one argument.</b>
     *
     * <pre>{@code
     * ClientWindows.register(MachinePanel.TYPE);
     * }</pre>
     *
     * <p>There is no behaviour class to write because the <b>bound panel is the behaviour</b>: the
     * host binds one from the rebuilt tree and calls {@link Panel#client} on it — at mount and again
     * after every re-describe — so widget listeners are attached in the one place they can safely
     * live. {@link Panel#closed} is told when the window ends.</p>
     *
     * <p>A fresh panel is bound per re-describe rather than the old one being reused, so anything a
     * panel remembers <em>outside</em> its widget fields does not survive one. That is the right
     * default — the widgets it was remembering about are themselves new — and a panel needing more
     * than that can still register a {@link ClientWindowBehaviour} of its own against
     * {@link PanelType#windowType()}.</p>
     */
    public static <P extends Panel<M>, M> void register(PanelType<P, M> type) {
        if (type == null) throw new IllegalArgumentException("a behaviour needs a type");
        register(type.windowType(), PanelBehaviour::new);
    }

    /** Hands a freshly bound panel its own {@code client()} call. @see #register(PanelType) */
    private static final class PanelBehaviour<P extends Panel<?>> implements ClientWindowBehaviour<P> {

        /**
         * Captured once, and that is sound: the same {@link ClientWindowContext} is live for the whole
         * of a window — a re-describe swaps what it points at, never the object.
         */
        private final ClientWindowContext window;

        @Nullable
        private P panel;

        PanelBehaviour(ClientWindowContext window) {
            this.window = window;
        }

        @Override
        public void onPanelBound(P panel) {
            this.panel = panel;
            panel.client(window);
        }

        @Override
        public void onClosed(String reason) {
            if (panel != null) panel.closed(reason);
        }
    }

    /**
     * The untyped form, for a window with no panel class behind it.
     *
     * <p>Equivalent to registering against {@link WindowType#bare} — the tree is its own panel, so
     * there is nothing to bind and nothing to check.</p>
     */
    public static void register(String type,
                                Function<ClientWindowContext, ClientWindowBehaviour<UIElement>> factory) {
        if (type == null || type.isEmpty()) throw new IllegalArgumentException("a behaviour needs a type");
        if (factory == null) throw new IllegalArgumentException("factory is null");
        register(WindowType.bare(type), factory);
    }

    /** Drops a registration. Tests, and a mod unloading itself. */
    public static void unregister(String type) {
        FACTORIES.remove(type);
    }

    /** Drops a registration. @see #unregister(String) */
    public static void unregister(WindowType<?> type) {
        if (type != null) FACTORIES.remove(type.id());
    }

    /**
     * A type and the behaviour registered for it, kept together so binding stays typed.
     *
     * <p>The map holds {@code Registration<?>} because the registry is heterogeneous by nature, but
     * both methods below are written where {@code P} is still known — so the bind and the factory
     * application need no cast between them. That is the whole reason this is one record rather than
     * two parallel maps.</p>
     */
    private record Registration<P>(WindowType<P> type,
                                   Function<ClientWindowContext, ClientWindowBehaviour<P>> factory) {

        /**
         * Builds the behaviour and hands it its panel — <b>in that order, and both here</b>.
         *
         * <p>The bind happens after construction rather than as a constructor argument, so that
         * {@code onPanelBound} is the single place a panel ever arrives. A behaviour that could get one
         * two ways would put the re-wiring footgun straight back.</p>
         */
        ClientWindowBehaviour<P> build(ClientWindowContext context) {
            ClientWindowBehaviour<P> behaviour = factory.apply(context);
            behaviour.onPanelBound(type.bind(context.root()));
            return behaviour;
        }

        /**
         * Re-binds, and hands the behaviour its new panel.
         *
         * <p>The cast is <b>sound by construction</b>: a {@code Mounted} keeps the registration that
         * built its behaviour, so the two {@code P}s are the same one — the compiler simply cannot see
         * it across a heterogeneous map. One suppression here is the price of none in every mod, which
         * is the right way round.</p>
         */
        @SuppressWarnings("unchecked")
        void replaced(ClientWindowBehaviour<?> behaviour, ClientWindowContext context) {
            ((ClientWindowBehaviour<P>) behaviour).onPanelBound(type.bind(context.root()));
        }
    }

    /** Every type something has registered behaviour for. Diagnostics. */
    public static List<String> registeredTypes() {
        return new ArrayList<>(FACTORIES.keySet());
    }

    // ── The mount ───────────────────────────────────────────────────────────

    /**
     * Says where windows go, and drains anything that has been waiting for one.
     *
     * <p>A host installs this once — {@code CgUiScreen} does it while building the desktop. Setting a
     * second one does not move windows already on screen: they belong to the mount that made them.</p>
     */
    public ClientWindows setMount(@Nullable WindowMount mount) {
        this.mount = mount;
        if (mount == null || waiting.isEmpty()) return this;
        List<ClientUiSession<Object>> pending = new ArrayList<>(waiting);
        waiting.clear();
        for (ClientUiSession<Object> session : pending) present(session);
        return this;
    }

    @Nullable
    public WindowMount mount() {
        return mount;
    }

    /**
     * Where stylesheets a window names come from.
     *
     * <p>Optional. Without one, sheets are simply not applied and the window renders on the user-agent
     * sheet alone — visibly plain, never broken. @see SheetSupply</p>
     */
    public ClientWindows setSheetSupply(@Nullable SheetSupply supply) {
        this.sheets = supply;
        return this;
    }

    /** How many windows are on screen through this host. */
    public int windowCount() {
        return mounted.size();
    }

    /** How many arrived with nowhere to go yet. Non-zero before a mount is installed. */
    public int waitingCount() {
        return waiting.size();
    }

    /** Every window on screen, in the order it opened. */
    public List<ClientWindowContext> windows() {
        return Collections.unmodifiableList(new ArrayList<>(mounted.values()));
    }

    // ── Adoption ────────────────────────────────────────────────────────────

    private void adopt(ClientUiSession<Object> session) {
        session.onWindowOpened(root -> present(session));
        session.onWindowClosed(reason -> closedByServer(session, reason));
        session.onFocusRequested(() -> {
            Mounted live = mounted.get(session);
            if (live != null && live.handle != null) live.handle.focus();
        });
    }

    /**
     * A tree is ready. Mount it, replace what is on screen, or queue it.
     *
     * <p>The three cases are exactly the three states this can be in, and none of them is a poll.</p>
     */
    private void present(ClientUiSession<Object> session) {
        UIElement root = session.root();
        if (root == null) return;

        Mounted live = mounted.get(session);
        if (live != null) {
            // RE-DELIVERED. ClientUiSessions hands a repeat ui/openWindow to the existing session, which
            // decodes a FRESH tree -- so the mount is holding one nothing updates any more.
            live.root = root;
            live.handle.contentReplaced(root);
            applySheets(live);
            if (live.behaviour != null && live.registration != null) {
                try {
                    // THROUGH THE REGISTRATION, which is the only thing that still knows the panel
                    // type -- so the host does the binding rather than every behaviour naming its own
                    // type back at a framework that already knew it.
                    live.registration.replaced(live.behaviour, live);
                } catch (RuntimeException failed) {
                    CrystalGuiCore.LOGGER.error("<{}> behaviour failed on a re-describe: {}",
                            live.type(), failed.getMessage(), failed);
                }
            }
            return;
        }

        if (mount == null) {
            // Nowhere to put it YET. Not an error and not a miss: the screen may simply never have been
            // opened. @see #setMount
            if (!waiting.contains(session)) waiting.add(session);
            return;
        }

        Mounted fresh = new Mounted(session, root);
        mounted.put(session, fresh);
        try {
            fresh.handle = mount.mount(fresh);
        } catch (RuntimeException failed) {
            mounted.remove(session);
            CrystalGuiCore.LOGGER.error("Could not mount <{}>: {}", fresh.type(), failed.getMessage(), failed);
            return;
        }
        applySheets(fresh);

        Registration<?> registration = FACTORIES.get(fresh.type());
        if (registration == null) return;   // an unknown type is a window with no local extras, not a failure
        try {
            fresh.registration = registration;
            fresh.behaviour = registration.build(fresh);
        } catch (RuntimeException failed) {
            fresh.registration = null;
            // The window stays: it is the server's, it renders, and it reports its events. Only the
            // local extras are missing, and saying so is better than taking the window down with them.
            CrystalGuiCore.LOGGER.error("The behaviour for <{}> could not be built: {}",
                    fresh.type(), failed.getMessage(), failed);
        }
    }

    private void applySheets(Mounted window) {
        SheetSupply supply = sheets;
        if (supply == null || window.sheets().isEmpty()) return;
        supply.resolve(window.session, window.sheets(), window);
    }

    // ── Endings ─────────────────────────────────────────────────────────────

    private void closedByServer(ClientUiSession<Object> session, String reason) {
        Mounted live = mounted.remove(session);
        waiting.remove(session);
        if (live == null) return;
        live.finish(reason, false);
    }

    /**
     * The connection has gone: every window goes with it, and nothing is sent.
     *
     * <p>What every client mod used to poll for by watching its connection accessor go null.</p>
     */
    private void onConnectionClosed(String reason) {
        List<Mounted> live = new ArrayList<>(mounted.values());
        mounted.clear();
        waiting.clear();
        for (Mounted window : live) window.finish(reason, false);
    }

    // ── One window ──────────────────────────────────────────────────────────

    /** The context handed out, and the bookkeeping behind it. One object so the two cannot disagree. */
    private final class Mounted implements ClientWindowContext {

        private final ClientUiSession<Object> session;
        private UIElement root;

        @Nullable
        private WindowMount.MountedWindow handle;
        @Nullable
        private ClientWindowBehaviour<?> behaviour;

        /** What built {@link #behaviour}, kept so a re-describe can re-bind with the same type. */
        @Nullable
        private Registration<?> registration;

        private boolean ended;
        private boolean visible = true;

        Mounted(ClientUiSession<Object> session, UIElement root) {
            this.session = session;
            this.root = root;
        }

        @Override
        public UIElement root() {
            return root;
        }

        @Override
        public String type() {
            return session.type();
        }

        @Override
        public String title() {
            return session.title();
        }

        @Nullable
        @Override
        public String key() {
            return session.key();
        }

        @Override
        public List<SheetRef> sheets() {
            return session.sheets();
        }

        @Override
        public boolean useUserAgentSheet() {
            return session.useUserAgentSheet();
        }

        @Override
        public ClientUiSession<Object> session() {
            return session;
        }

        @Override
        public ProtocolConnection<Object> connection() {
            return connection;
        }

        @Override
        public void userClosed() {
            if (ended) return;   // already gone some other way; a mount need not know which
            mounted.remove(session);
            session.closeFromClient("closed by the user");
            finish("closed by the user", true);
        }

        @Override
        public void visibilityChanged(boolean nowVisible) {
            if (ended || visible == nowVisible) return;
            visible = nowVisible;
            session.reportVisibility(nowVisible);
        }

        /**
         * Ends this window exactly once.
         *
         * @param userDriven whether the frame is already gone on this side, in which case telling the
         *                   mount to take it off screen would be asking it to remove what it removed
         */
        void finish(String reason, boolean userDriven) {
            if (ended) return;
            ended = true;
            if (!userDriven && handle != null) {
                try {
                    handle.closedByServer(reason);
                } catch (RuntimeException failed) {
                    CrystalGuiCore.LOGGER.error("A mount failed to close <{}>: {}",
                            type(), failed.getMessage(), failed);
                }
            }
            if (behaviour == null) return;
            try {
                behaviour.onClosed(reason);
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("<{}> behaviour failed on close: {}",
                        type(), failed.getMessage(), failed);
            }
        }
    }
}
