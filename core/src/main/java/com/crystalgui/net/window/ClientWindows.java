package com.crystalgui.net.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * type → local behaviour. Static, because what a client can do about a window type is a fact about
     * this installation rather than about any one connection — the same reason {@code MenuScreens}'
     * registry is static and a {@code MenuType} is not per player.
     */
    private static final Map<String, Function<ClientWindowContext, ClientWindowBehaviour>> FACTORIES =
            new ConcurrentHashMap<>();

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
     * Says what this client does locally about a window type. Idempotent per type; re-registering
     * replaces.
     *
     * <p>Called once at mod init, like {@code MenuScreens.register}. Windows of unregistered types are
     * unaffected and still open.</p>
     */
    public static void register(String type, Function<ClientWindowContext, ClientWindowBehaviour> factory) {
        if (type == null || type.isEmpty()) throw new IllegalArgumentException("a behaviour needs a type");
        if (factory == null) throw new IllegalArgumentException("factory is null");
        FACTORIES.put(type, factory);
    }

    /** Drops a registration. Tests, and a mod unloading itself. */
    public static void unregister(String type) {
        FACTORIES.remove(type);
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
            if (live.behaviour != null) {
                try {
                    live.behaviour.onContentReplaced(live);
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

        Function<ClientWindowContext, ClientWindowBehaviour> factory = FACTORIES.get(fresh.type());
        if (factory == null) return;   // an unknown type is a window with no local extras, not a failure
        try {
            fresh.behaviour = factory.apply(fresh);
        } catch (RuntimeException failed) {
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
        private ClientWindowBehaviour behaviour;

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
