package com.crystalgui.mc.client;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ViewCommand;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ScopedSheets;
import com.crystalgui.net.window.SheetSupply;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowState;

/**
 * <b>Where a server's window lands on 1.7.10</b> — a {@link WindowFrame} on the desktop
 * {@link CgUiScreen} already owns.
 *
 * <p>The entire platform surface for networked UI, and deliberately small: no session, no window id,
 * no close matrix, no dispatch by type, no poll. Those are the engine's, in
 * {@code com.crystalgui.net.window}, written once for every loader. What is genuinely 1.7.10's is a
 * frame, a desktop and a style engine.</p>
 *
 * <h3>It is a window, not a screen</h3>
 *
 * <p>There is <b>one</b> {@code GuiScreen} in this mod and there is meant to be: a second one is a
 * second claim on the input pump, the GL state handoff, the desktop's persistence and the modal stack,
 * and only one of them can be in front. A panel that needed its own screen could not coexist with the
 * editor, which is the whole thing a compositor is for.</p>
 *
 * <h3>It opens in the background, deliberately</h3>
 *
 * <p>{@link UIWindow#openWindowInBackground} rather than {@code openWindow}: these windows are opened by
 * a <em>server</em> pushing a UI, not by the user asking for one, and taking the keyboard out from under
 * whatever is being typed is the one thing every windowing system agreed to stop doing. The frame asks
 * for attention instead, and a user gesture — F8 — is what brings it forward.</p>
 *
 * <h3>The one thing a mount owes back</h3>
 *
 * <p>{@link ClientWindowContext#userClosed()} when the <b>user</b> closes it. Not when the server does
 * and not when the connection drops — the host already knows about those, and reporting them would
 * echo. {@code WindowFrame.onDestroyed} is where that comes from, and {@code userClosed} is idempotent
 * precisely so this listener need not work out which of the two happened.</p>
 */
public final class CgUiWindowMount implements WindowMount {

    /** The one mount. Stateless, so there is no reason for a second. */
    private static final CgUiWindowMount INSTANCE = new CgUiWindowMount();

    /** The connection currently carrying it, so rebinding to the same one is free. */
    private static ProtocolConnection<Object> boundTo;

    private CgUiWindowMount() {
    }

    /**
     * Makes sure this connection's {@link com.crystalgui.net.window.ClientWindows} knows where windows go.
     *
     * <p>Called from the frame loop, which is free when the wire has not moved and is what makes a
     * reconnect work at all — the same per-frame re-ask {@code Mc1710Workspace.pump} does, and for the
     * same reason: a rebind nothing re-asks for is machinery that can never fire.</p>
     *
     * <p><b>Installed when the desktop exists, not when the connection does</b>, and that ordering is
     * the whole reason {@code ClientWindows} queues. A server can open a window before the player has
     * ever pressed a key to open the screen; the window waits, and lands the moment there is somewhere
     * for it to land.</p>
     */
    public static void bind(@Nullable ProtocolConnection<Object> connection) {
        if (connection == null || connection == boundTo) return;
        boundTo = connection;
        ClientWindows.of(connection)
                .setSheetSupply(sheetSupply())
                .setMount(INSTANCE);
        CrystalGuiCore.LOGGER.info("[cgui-ui] server windows will open on the desktop");
    }

    /**
     * Turns the sheets a window names into CSS and applies them to the one style engine.
     *
     * <p>Built here rather than in {@code core} because parsing a stylesheet is one of the things a
     * server-safe class may not do — {@code StyleSheet}'s class initialiser reads {@code default.css}
     * through {@code CgIO}, so the whole class is unloadable on a dedicated server.</p>
     */
    /**
     * Where a window's own sheets are held, refcounted and scoped.
     *
     * <p>Static because the style engine they go into is: one {@code UIWindow} hosts every window on
     * this client, so "is this sheet already installed" is a question about the client and not about
     * any one window.</p>
     */
    private static final ScopedSheets SHEETS = new ScopedSheets(new ScopedSheets.Host() {
        @Override
        public void add(StyleSheet sheet) {
            UIWindow host = CgUiScreen.window();
            if (host != null) host.getStyleEngine().addStylesheet(sheet);
        }

        @Override
        public void remove(StyleSheet sheet) {
            UIWindow host = CgUiScreen.window();
            if (host != null) host.getStyleEngine().removeStylesheet(sheet);
        }
    });

    static SheetSupply sheetSupply() {
        return new SheetSupply(
                (window, css) -> {
                    for (String sheet : css) SHEETS.acquire(window.type(), sheet);
                },
                (window, css) -> {
                    for (String sheet : css) SHEETS.release(window.type(), sheet);
                });
    }


    @Override
    public MountedWindow mount(ClientWindowContext context) {
        UIWindow host = CgUiScreen.window();
        if (host == null) {
            // Should not happen -- the host installs this mount while building the desktop, and the
            // ClientWindows queues windows until then -- but a mount that threw would take the window
            // down with it rather than merely failing to draw it.
            throw new IllegalStateException("no UIWindow to mount <" + context.type() + "> onto");
        }

        WindowFrame frame = new WindowFrame(title(context));
        // WHAT THE SERVER NAMED, so the compositor puts it back where the user left it. Before the key
        // travelled, a client had to invent one, which meant every mod's windows shared a namespace
        // nobody was maintaining.
        if (context.key() != null) frame.setKey(context.key());
        // THE CONTENT'S ANSWER, on both routes that can take this window away: the caption's close
        // button, and the retention cap evicting it while it is hidden. Wiring one guard is what makes
        // "there is unsaved work here" mean the same thing to the user, to the compositor and to the
        // server -- which asks the very same panels before closing a window itself.
        frame.setDiscardGuard(context::mayClose);
        frame.setContent(context.root());
        host.openWindowInBackground(frame);

        Mounted mounted = new Mounted(frame, context);

        frame.onDestroyed.connect(mounted::onFrameDestroyed);
        // HIDING IS NOT CLOSING. A minimised window is retained and detached, and the server should
        // stop describing a tree nobody is drawing. @see com.crystalgui.net.protocol.UiMethods#VISIBILITY
        frame.onHidden.connect(() -> context.visibilityChanged(false));
        frame.onShown.connect(persisted -> context.visibilityChanged(true));
        // AND THE WHOLE COMPOSITOR going away, which no individual window's onHidden reports: suspending
        // takes the desktop off the tree without touching any window, so a server would otherwise go on
        // describing a tree nobody is drawing for as long as the screen is closed.
        mounted.desktopWatch = host.onDesktopSuspendedChanged.connect(
                shown -> context.visibilityChanged(shown && frame.state() == WindowState.VISIBLE));
        return mounted;
    }

    private static String title(ClientWindowContext context) {
        String named = context.title();
        if (!named.isEmpty()) return named;
        // A server that named no title still gets a caption, and the type is the most useful thing
        // available -- more so than a blank bar, which reads as a broken window.
        return context.type().isEmpty() ? "Window" : context.type();
    }

    /** One frame, and which side is currently ending it. */
    private static final class Mounted implements MountedWindow {

        private final WindowFrame frame;

        /** Undone when the window goes, or the compositor keeps a dead window's report alive. */
        @Nullable
        private com.crystalgui.core.signal.Connection desktopWatch;
        private final ClientWindowContext context;

        /** Set while the SERVER is taking this window down, so the frame's own teardown stays quiet. */
        private boolean closingFromServer;

        Mounted(WindowFrame frame, ClientWindowContext context) {
            this.frame = frame;
            this.context = context;
        }

        void onFrameDestroyed() {
            if (desktopWatch != null) {
                desktopWatch.disconnect();
                desktopWatch = null;
            }
            // THE USER, unless we are the ones destroying it. Without the guard a server-driven close
            // would come straight back as a ui/close -- an echo, and on a dead connection a write into
            // a socket that has gone.
            if (closingFromServer) return;
            // WHY, not merely THAT. The retention cap discarding a hidden window is the client running
            // out of room, and telling a server "the user closed it" is how a workspace comes back
            // missing the panels somebody had open.
            if (frame.isBeingEvicted()) context.evicted();
            else context.userClosed();
        }

        @Override
        public void closedByServer(String reason) {
            if (frame.state() == WindowState.DESTROYED) return;
            closingFromServer = true;
            try {
                frame.destroy();
            } finally {
                closingFromServer = false;
            }
        }

        @Override
        public void focus() {
            UIWindow host = CgUiScreen.window();
            if (host == null || frame.state() == WindowState.DESTROYED) return;
            if (frame.state() == WindowState.HIDDEN) frame.show(false);
            host.desktop().activate(frame);
        }

        /**
         * What a server may ask of the WINDOW rather than the tree, applied to the frame it got.
         *
         * <p>This was the default no-op for a release, so {@code io.setIcon}, {@code io.setTitle} and
         * {@code io.notifyUser} were shipped on the server side and dropped here — every one of them
         * looked wired up from the panel's end. The geometry hint stays unapplied on purpose: a record
         * the user made outranks a hint, and nothing here yet knows whether one was applied.</p>
         */
        @Override
        public void viewCommand(String command, StateMap<Object> args) {
            if (frame.state() == WindowState.DESTROYED) return;
            switch (command) {
                case ViewCommand.SET_TITLE:
                    frame.setTitle(args.getString(ViewCommand.TEXT, frame.getTitle()));
                    break;
                case ViewCommand.SET_ICON: {
                    String icon = args.getString(ViewCommand.TEXT, "");
                    frame.setIcon(icon.isEmpty() ? null : icon);
                    break;
                }
                case ViewCommand.NOTIFY:
                    frame.requestAttention();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void contentReplaced(UIElement newRoot) {
            // The session decoded a FRESH tree, so the one in this frame is no longer being updated.
            frame.setContent(newRoot);
        }
    }
}
