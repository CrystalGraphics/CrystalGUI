package com.crystalgui.net.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ClientUiSessions;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

/**
 * Every window this client is showing over one connection — <b>the mirror of {@link ServerWindows}</b>.
 *
 * <p>Owns the two things a host used to leave to each mod, both of which are races when written by
 * hand: adopting each session as it arrives (and installing the listener <em>before</em> the first
 * window can open, because a miss there is silent), and reconciling that against a place to put a
 * window, which may not exist yet.</p>
 *
 * <h3>Zero lines per UI</h3>
 *
 * <p>{@code MenuScreens.register} with nothing left to register: {@code ui/openWindow} names the
 * panel's class, this host initialises it — <b>guarded</b>: loaded without running anything, checked
 * to be a {@link Networked}, and only then initialised — which registers its tag, so the description
 * decodes. When the tree mounts, the host binds the panel's fields, runs {@link Networked#bound} and
 * {@link Networked#client}, and does the same for every {@link Networked} element nested anywhere in
 * it. The panel declared everything; a mod's client init has nothing left to say.</p>
 *
 * <p>The guard is the safety story: a server can only cause classes that are genuinely
 * {@code Networked} panels to initialise — UI declarations by construction — and a name that is
 * absent or fails the check is logged once and skipped, leaving a window that plainly did not decode
 * rather than one that subtly misbehaves.</p>
 */
public final class ClientWindows {

    /** Class names already handed to {@link #ensureUiClass}, so a bad one is logged once. */
    private static final Set<String> NAMED = ConcurrentHashMap.newKeySet();

    static {
        // The moment the window layer is loaded on a client, openWindow panel names start resolving.
        ClientUiSessions.setUiClassLoader(ClientWindows::ensureUiClass);
        ClientUiSessions.setMirrorFactory(UIElementMirror::new);
    }

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
    private final Map<ClientUiSession<UIElement, Object>, Mounted> mounted = new LinkedHashMap<>();

    /** Sessions whose tree is ready and which have nowhere to go yet. @see #setMount */
    private final List<ClientUiSession<UIElement, Object>> waiting = new ArrayList<>();

    @Nullable
    private WindowMount mount;

    @Nullable
    private SheetSupply sheets;

    private ClientWindows(ProtocolConnection<Object> connection) {
        this.connection = connection;
        // A description addresses widgets by tag, and an unregistered tag THROWS on decode rather than
        // degrading to a styleless div. Idempotent, and every lookup would trigger it anyway; it is here
        // so the dependency is visible at the one place trees get rebuilt.
        // `UIElementRegistry.bootstrap()`, which runs every `NodeKinds` service once -- the new engine's
        // answer to a hand-written list of builtins, and what makes the registry's contents a function
        // of what is on the classpath rather than of what somebody remembered to add.
        UIElementRegistry.bootstrap();

        /*
         * INSTALLED THE MOMENT THE CONNECTION EXISTS, which is the race this class exists to remove.
         * ClientUiSessions.onSession is told about each session BEFORE its description is requested, so
         * a listener attached late has already missed exactly the window that prompted it -- and missed
         * it in silence. A contributor binding at connection open is the only moment that cannot be
         * late.
         */
        ClientUiSessions.<UIElement, Object>forConnection(connection).onSession(this::adopt);
        connection.onClosed(this::onConnectionClosed);
    }

    /**
     * The client's own window host, or {@code null} before it has connected to anything.
     *
     * <p>There is exactly one on a client — one process, one connection to one server — which is what
     * makes {@link #requestOpen} static. Cleared when that connection closes, so a stale one is never
     * handed out after a disconnect; a reconnect installs the next one over it.</p>
     */
    @Nullable
    private static ClientWindows CLIENT;

    /** @see #CLIENT */
    @Nullable
    public static ClientWindows client() {
        return CLIENT;
    }

    /** The host for this connection, created on first use. */
    public static ClientWindows of(ProtocolConnection<Object> connection) {
        return connection.attachment(ClientWindows.class, ClientWindows::new);
    }

    /** Builds the host so it starts listening for windows. @see WindowProtocol */
    static void install(ProtocolConnection<Object> connection) {
        ClientWindows windows = of(connection);
        // RECORDED, and this is the one place it can be: Protocols.client binds only where a connection
        // has no peer, which is the client's own end. So whatever arrives here IS the client's
        // connection, and nothing has to be told which one that is.
        CLIENT = windows;
        connection.onClosed(reason -> {
            if (CLIENT == windows) CLIENT = null;
        });
    }

    // ── Panel classes, from the wire ────────────────────────────────────────

    /**
     * Initialises the panel class an {@code ui/openWindow} named — <b>guarded</b>: loaded without
     * running anything, and initialised only if it is genuinely a {@link Networked}, so a misbehaving
     * server can start nothing but a UI declaration. Initialising it runs the panel's {@code UiType}
     * declaration, which registers its tag (and its nested field types), which is what lets the
     * description decode. Failures are logged once per name; the window then plainly fails to decode
     * rather than subtly misbehaving.
     */
    private static void ensureUiClass(String name) {
        if (!NAMED.add(name)) return;
        try {
            Class<?> named = Class.forName(name, false, ClientWindows.class.getClassLoader());
            if (!Networked.class.isAssignableFrom(named)) {
                CrystalGuiCore.LOGGER.error("openWindow named {} as its panel, which is not a "
                        + "Networked element; refusing to initialise it", name);
                return;
            }
            Class.forName(name, true, ClientWindows.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            CrystalGuiCore.LOGGER.error("openWindow named panel class {} but this client cannot "
                    + "load it: {}", name, absent.toString());
        }
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
        List<ClientUiSession<UIElement, Object>> pending = new ArrayList<>(waiting);
        waiting.clear();
        for (ClientUiSession<UIElement, Object> session : pending) present(session);
        return this;
    }

    @Nullable
    public WindowMount mount() {
        return mount;
    }

    /**
     * <b>Asks the server for a window.</b>
     *
     * <p>A request, so a refusal is something you learn rather than something you wait for. A
     * notification's failure is silence, and silence is what a lost packet looks like too — the player
     * presses the key and nothing happens, forever, with no way to tell a rule from a fault.</p>
     *
     * <p>The reply says only <b>whether</b> one is coming. The window itself arrives through the
     * ordinary mount path, so there is one place that handles "a window appeared" no matter who asked —
     * {@code onGranted} is not where to look for the tree. It costs nothing in time either: the server
     * queues the window before it answers, so both leave in the same flush.</p>
     *
     * <pre>{@code
     * StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
     * args.putInt("x", pos.getX());   // a CLAIM; the server re-derives from it
     * ClientWindows.requestOpen(FurnacePanel.TYPE, args, granted -> {
     *     if (!granted) player.addChatMessage(new ChatComponentText("You are too far away."));
     * });
     * }</pre>
     *
     * <p><b>Static, and takes no connection</b>, because there is only one it could mean: a client has
     * one connection, to the server it is playing on. Asking to open a window on somebody else's
     * connection is not a thing a client does — that is the server's side of this exchange.</p>
     *
     * <p><b>⚠ The single-player trap.</b> Asking for a window almost always means opening a
     * {@code GuiScreen}, and one whose {@code doesGuiPauseGame()} returns {@code true} stops the
     * integrated server ticking — so the connection is never pumped, this request is never answered,
     * and it dies at its timeout. It is invisible on a dedicated server, which is the configuration
     * nobody tests the wire in, and presents as "it works in multiplayer but not single-player".</p>
     *
     * @param args      what the server should re-derive its model from. <b>Untrusted</b> on the far side
     * @param onGranted told {@code true} if a window was opened, {@code false} if it was refused,
     *                  unanswered, or this client is not connected to anything
     */
    public static <P extends UIElement & Networked<M>, M> void requestOpen(
            UiType<P, M> type, @Nullable StateMap<Object> args,
            @Nullable Consumer<Boolean> onGranted) {
        ClientWindows windows = CLIENT;
        if (windows == null) {
            // NOT CONNECTED is answered rather than thrown: a key bound to this can be pressed on a
            // title screen, and that is not a programming error.
            CrystalGuiCore.LOGGER.warn("Asked to open <{}> with no connection to a server", type.id());
            if (onGranted != null) onGranted.accept(false);
            return;
        }
        windows.ask(type, args, onGranted);
    }

    /**
     * As {@link #requestOpen(UiType, StateMap, Consumer)}, naming the type by <b>id</b>.
     *
     * <p>For a caller that holds only what a session recorded: a restored layout has a type id string
     * and no {@code UiType} object, and loading the panel class to get one would defeat the point of a
     * lazy tab. It grants nothing extra — the id is what travels either way, and the server's
     * {@link #openable} declaration is the authority in both cases.</p>
     */
    public static void requestOpen(String typeId, @Nullable StateMap<Object> args,
                                   @Nullable Consumer<Boolean> onGranted) {
        ClientWindows windows = CLIENT;
        if (windows == null) {
            CrystalGuiCore.LOGGER.warn("Asked to open <{}> with no connection to a server", typeId);
            if (onGranted != null) onGranted.accept(false);
            return;
        }
        windows.ask(typeId, args, onGranted);
    }

    private <P extends UIElement & Networked<M>, M> void ask(
            UiType<P, M> type, @Nullable StateMap<Object> args,
            @Nullable java.util.function.Consumer<Boolean> onGranted) {
        ask(type.id(), args, onGranted);
    }

    private void ask(String typeId, @Nullable StateMap<Object> args,
                     @Nullable java.util.function.Consumer<Boolean> onGranted) {
        StateMap<Object> out = new StateMap<>(connection.ops());
        out.putString(UiMethods.TYPE, typeId);
        if (args != null) out.putRaw("args", args.encode());
        connection.router().request(UiMethods.REQUEST_OPEN, out.encode(),
                answer -> {
                    if (onGranted == null) return;
                    StateMap<Object> in = answer == null
                            ? new StateMap<>(connection.ops())
                            : new StateMap<>(connection.ops(), answer);
                    onGranted.accept(in.getBool("ok", false));
                },
                error -> {
                    // A TIMEOUT IS NOT A REFUSAL, and the caller is told the same thing for both --
                    // because from where it stands they are the same: no window. What separates them is
                    // a log line, which is where somebody debugging should look.
                    CrystalGuiCore.LOGGER.warn("Asking to open <{}> failed: {}", typeId, error);
                    if (onGranted != null) onGranted.accept(false);
                });
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

    private void adopt(ClientUiSession<UIElement, Object> session) {
        session.onWindowOpened(root -> present(session));
        session.onWindowClosed((code, detail) -> closedByServer(session, code, detail));
        session.onCall(UiMethods.REQUEST_CLOSE, (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putBool("ok", mayClose(session));
            respond.ok(out);
        });
        session.onViewCommand((command, args) -> {
            Mounted live = mounted.get(session);
            if (live == null || live.root == null) return;
            ViewCommands.apply(command, args, session.ids(), live.root, live.handle);
        });
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
    private void present(ClientUiSession<UIElement, Object> session) {
        UIElement root = session.root();
        if (root == null) return;

        Mounted live = mounted.get(session);
        if (live != null) {
            // RE-DELIVERED. ClientUiSessions hands a repeat ui/openWindow to the existing session, which
            // decodes a FRESH tree -- so the mount is holding one nothing updates any more.
            live.root = root;
            live.handle.contentReplaced(root);
            applySheets(live);
            if (live.panelsBound) {
                try {
                    // Fresh instances over the fresh tree, and client() re-run over them -- which is
                    // what closes the stale-closure gap this comment used to record.
                    live.bindPanels(false);
                } catch (RuntimeException failed) {
                    CrystalGuiCore.LOGGER.error("<{}> could not be re-bound after a re-describe: {}",
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

        if (!(root instanceof Networked)) return;   // a bare tree still renders and reports its events
        try {
            fresh.bindPanels(true);
            fresh.panelsBound = true;
        } catch (RuntimeException failed) {
            fresh.panelsBound = false;
            // The window stays: it is the server's, it renders, and it reports its events. Only the
            // local extras are missing, and saying so is better than taking the window down with them.
            CrystalGuiCore.LOGGER.error("The panel for <{}> could not be bound: {}",
                    fresh.type(), failed.getMessage(), failed);
        }
    }

    private void applySheets(Mounted window) {
        SheetSupply supply = sheets;
        if (supply == null || window.sheets().isEmpty()) return;
        supply.resolve(window.session, window.sheets(), window);
    }

    /**
     * Asks every panel in a window whether it may close. <b>One refusal is enough.</b>
     *
     * <p>Unanimity rather than a majority or the root's opinion alone, because what is being protected
     * is somebody's unsaved work: a nested panel holding a half-typed field has as much right to stop
     * the window as the panel containing it, and there is no sensible way to close "most of" a window.</p>
     *
     * <p>A panel that throws is taken to have consented. Refusing on its behalf would make a bug in one
     * panel into a window nobody can shut.</p>
     */
    private boolean mayClose(ClientUiSession<UIElement, Object> session) {
        Mounted live = mounted.get(session);
        if (live == null) return true;
        for (Networked<?> panel : live.panels) {
            try {
                if (!panel.mayClose()) return false;
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("<{}>.mayClose failed; taking that as consent: {}",
                        live.type(), failed.getMessage(), failed);
            }
        }
        return true;
    }

    /** Removes every child a viewer added under {@code element}. @see ClientScope#addLocal */
    private static void dropLocals(UIElement element) {
        for (UIElement child : new ArrayList<>(element.children())) {
            if (child.isLocal()) element.remove(child);
            else dropLocals(child);
        }
    }

    /**
     * Finds every {@link Networked} element under {@code element}, carrying the id-path prefix its
     * scope derives from — the client half of {@link ServerScope#attach}'s naming rule.
     */
    private static void collectNested(UIElement element, String prefix,
                                      List<Networked<?>> panels, List<String> prefixes) {
        for (UIElement child : element.children()) {
            if (child instanceof Networked) {
                String path = prefix + child.id() + "/";
                UiType.bindFields(child);
                panels.add((Networked<?>) child);
                prefixes.add(path);
                collectNested(child, path, panels, prefixes);
            } else {
                collectNested(child, prefix, panels, prefixes);
            }
        }
    }

    // ── Endings ─────────────────────────────────────────────────────────────

    private void closedByServer(ClientUiSession<UIElement, Object> session, String code, String detail) {
        Mounted live = mounted.remove(session);
        waiting.remove(session);
        if (live == null) return;
        // The DETAIL goes to the host, which shows it, and the CODE to the panel, which branches on it.
        live.finish(detail, code, false);
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
        for (Mounted window : live) window.finish(reason, CloseReason.CONNECTION_LOST.name(), false);
    }

    // ── One window ──────────────────────────────────────────────────────────

    /** The context handed out, and the bookkeeping behind it. One object so the two cannot disagree. */
    private final class Mounted implements ClientWindowContext {

        private final ClientUiSession<UIElement, Object> session;
        private UIElement root;

        @Nullable
        private WindowMount.MountedWindow handle;

        /** Every bound panel in the tree, root first — who hears {@code closed}. */
        private final List<Networked<?>> panels = new ArrayList<>();

        /** Whether the first bind succeeded, so a re-describe knows to re-bind. */
        private boolean panelsBound;

        private boolean ended;
        private boolean visible = true;

        Mounted(ClientUiSession<UIElement, Object> session, UIElement root) {
            this.session = session;
            this.root = root;
        }

        /**
         * Binds the root panel and every nested one, runs their {@link Networked#bound}, and — first
         * mount only — their {@link Networked#client}, each under the scope its id path derives.
         *
         * <p>Root first, then nested panels in document order, which is also the order the server
         * attached them in a tree built by the same class.</p>
         */
        void bindPanels(boolean firstMount) {
            // WHATEVER THE LAST BIND ADDED, gone before this one runs. client(io) is written as though
            // nothing had been set up before -- that is its whole contract -- so it calls addLocal again,
            // and on a re-delivered openWindow the tree may be the SAME one. Without this the viewer's
            // own controls double on every re-bind, which nothing anywhere reports.
            if (!firstMount) dropLocals(root);
            List<Networked<?>> found = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            UiType.bindFields(root);
            found.add((Networked<?>) root);
            prefixes.add("");
            collectNested(root, "", found, prefixes);

            /*
             * ONE HOOK, RUN ON EVERY BIND -- and running it again is the point rather than a cost.
             *
             * A re-describe builds FRESH PANEL INSTANCES over the fresh tree. So a client() that ran
             * only on the first mount left every wire handler closed over a panel that is now
             * detached: it runs, it writes widgets nothing draws, and nothing anywhere reports a
             * problem. That was a known gap, recorded in plan/net-window-host.md and worked around here with a
             * comment rather than fixed.
             *
             * What blocked the fix was the router refusing a second registration of the same method.
             * ClientUiSession now routes each method once and dispatches through a swappable delegate,
             * so re-running replaces the handler instead of colliding -- see its callHandlers field for
             * why that is not a weakening of the duplicate rule.
             */
            for (int i = 0; i < found.size(); i++) {
                found.get(i).client(new ClientScope(session, this, prefixes.get(i)));
            }
            panels.clear();
            panels.addAll(found);
        }

        @Override
        public boolean mayClose() {
            return ClientWindows.this.mayClose(session);
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
        public Presentation presentation() {
            return Presentation.parse(session.presentation());
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
        public ClientUiSession<UIElement, Object> session() {
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
            finish("closed by the user", CloseReason.CLIENT.name(), true);
        }

        @Override
        public void evicted() {
            if (ended) return;
            mounted.remove(session);
            session.closeFromClient("evicted to stay under the retention cap");
            finish("evicted to stay under the retention cap", CloseReason.RETENTION.name(), true);
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
        void finish(String detail, String code, boolean userDriven) {
            if (ended) return;
            ended = true;
            // Its sheets go with it. Before the callbacks, so a host tearing down its frame is not
            // briefly showing a window whose styling has already been withdrawn.
            if (sheets != null) sheets.released(this);
            if (!userDriven && handle != null) {
                try {
                    handle.closedByServer(detail);
                } catch (RuntimeException failed) {
                    CrystalGuiCore.LOGGER.error("A mount failed to close <{}>: {}",
                            type(), failed.getMessage(), failed);
                }
            }
            for (Networked<?> panel : panels) {
                try {
                    panel.closed(CloseReason.parse(code));
                } catch (RuntimeException failed) {
                    CrystalGuiCore.LOGGER.error("<{}> failed on close: {}",
                            type(), failed.getMessage(), failed);
                }
            }
        }
    }
}
