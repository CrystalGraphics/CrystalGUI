package com.crystalgui.mc.client;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.host.ClientUiHost;
import com.crystalgui.net.host.ClientWindowContext;
import com.crystalgui.net.host.SheetSupply;
import com.crystalgui.net.host.WindowMount;
import com.crystalgui.net.protocol.ProtocolConnection;
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
 * {@code com.crystalgui.net.host}, written once for every loader. What is genuinely 1.7.10's is a
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
     * Makes sure this connection's {@link com.crystalgui.net.host.ClientUiHost} knows where windows go.
     *
     * <p>Called from the frame loop, which is free when the wire has not moved and is what makes a
     * reconnect work at all — the same per-frame re-ask {@code Mc1710Workspace.pump} does, and for the
     * same reason: a rebind nothing re-asks for is machinery that can never fire.</p>
     *
     * <p><b>Installed when the desktop exists, not when the connection does</b>, and that ordering is
     * the whole reason {@code ClientUiHost} queues. A server can open a window before the player has
     * ever pressed a key to open the screen; the window waits, and lands the moment there is somewhere
     * for it to land.</p>
     */
    public static void bind(@Nullable ProtocolConnection<Object> connection) {
        if (connection == null || connection == boundTo) return;
        boundTo = connection;
        ClientUiHost.of(connection)
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
    static SheetSupply sheetSupply() {
        return new SheetSupply((window, css) -> {
            UIWindow host = CgUiScreen.window();
            if (host == null) return;
            for (String sheet : css) {
                try {
                    host.getStyleEngine().addStylesheet(StyleSheet.parse(sheet));
                } catch (RuntimeException malformed) {
                    // A theme that will not parse is a plain window, never a missing one.
                    CrystalGuiCore.LOGGER.warn("[cgui-ui] a server sheet for <{}> would not parse: {}",
                            window.type(), malformed.getMessage());
                }
            }
        });
    }

    @Override
    public MountedWindow mount(ClientWindowContext context) {
        UIWindow host = CgUiScreen.window();
        if (host == null) {
            // Should not happen -- the host installs this mount while building the desktop, and the
            // ClientUiHost queues windows until then -- but a mount that threw would take the window
            // down with it rather than merely failing to draw it.
            throw new IllegalStateException("no UIWindow to mount <" + context.type() + "> onto");
        }

        WindowFrame frame = new WindowFrame(title(context));
        // WHAT THE SERVER NAMED, so the compositor puts it back where the user left it. Before the key
        // travelled, a client had to invent one, which meant every mod's windows shared a namespace
        // nobody was maintaining.
        if (context.key() != null) frame.setKey(context.key());
        frame.setContent(context.root());
        host.openWindowInBackground(frame);

        Mounted mounted = new Mounted(frame, context);
        frame.onDestroyed.connect(mounted::onFrameDestroyed);
        // HIDING IS NOT CLOSING. A minimised window is retained and detached, and the server should
        // stop describing a tree nobody is drawing. @see com.crystalgui.net.protocol.UiMethods#VISIBILITY
        frame.onHidden.connect(() -> context.visibilityChanged(false));
        frame.onShown.connect(persisted -> context.visibilityChanged(true));
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
        private final ClientWindowContext context;

        /** Set while the SERVER is taking this window down, so the frame's own teardown stays quiet. */
        private boolean closingFromServer;

        Mounted(WindowFrame frame, ClientWindowContext context) {
            this.frame = frame;
            this.context = context;
        }

        void onFrameDestroyed() {
            // THE USER, unless we are the ones destroying it. Without the guard a server-driven close
            // would come straight back as a ui/close -- an echo, and on a dead connection a write into
            // a socket that has gone.
            if (closingFromServer) return;
            context.userClosed();
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

        @Override
        public void contentReplaced(UIElement newRoot) {
            // The session decoded a FRESH tree, so the one in this frame is no longer being updated.
            frame.setContent(newRoot);
        }
    }
}
