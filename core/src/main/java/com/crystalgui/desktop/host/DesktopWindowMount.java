package com.crystalgui.desktop.host;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.net.ViewCommand;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.ScopedSheets;
import com.crystalgui.net.window.SheetSupply;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>Where a server's window lands</b> — a {@link WindowFrame} on the desktop, wherever that desktop is.
 *
 * <p>This was the 1.7.10 loader's, and it named one Minecraft type: none. Its whole platform content was
 * a static lookup of the screen's {@code UIDocument}, which is a field here. The rest — opening in the
 * background, the close matrix, the sheets, the view commands — is the same on any host with a
 * compositor.</p>
 *
 * <h3>It is a window, not a screen</h3>
 *
 * <p>A host has one surface and is meant to: a second is a second claim on the input pump, the GL
 * handoff, the persistence and the modal stack, and only one of them can be in front. A panel that
 * needed its own screen could not coexist with an editor, which is the whole thing a compositor is
 * for.</p>
 *
 * <h3>It opens in the background, deliberately</h3>
 *
 * <p>{@link Desktop#addWindow(WindowFrame, boolean)} with {@code false}: these windows are opened by a
 * <em>server</em> pushing a UI, not by the user asking for one, and taking the keyboard out from under
 * whatever is being typed is the one thing every windowing system agreed to stop doing. The frame asks
 * for attention instead, and a user gesture is what brings it forward.</p>
 *
 * <h3>The one thing a mount owes back</h3>
 *
 * <p>{@link ClientWindowContext#userClosed()} when the <b>user</b> closes it. Not when the server does
 * and not when the connection drops — the host already knows about those, and reporting them would
 * echo. {@code WindowFrame.onDestroyed} is where that comes from, and {@code userClosed} is idempotent
 * precisely so this listener need not work out which of the two happened.</p>
 */
public final class DesktopWindowMount implements WindowMount {

    private final UIDocument document;

    /** The connection currently carrying it, so rebinding to the same one is free. */
    @Nullable
    private ProtocolConnection<Object> boundTo;

    /**
     * Where a window's own sheets are held, refcounted and scoped.
     *
     * <p>Per mount because the style engine they go into is: one document hosts every window on this
     * host, so "is this sheet already installed" is a question about the host and not about any one
     * window.</p>
     */
    private final ScopedSheets sheets;

    public DesktopWindowMount(UIDocument document) {
        this.document = document;
        this.sheets = new ScopedSheets(new ScopedSheets.Host() {
            @Override
            public void add(StyleSheet sheet, Styleable root) {
                document.styles().addStylesheet(sheet, root);
            }

            @Override
            public void remove(StyleSheet sheet, Styleable root) {
                // The ROOT overload: one parse serves every window of a type, so removing it wholesale
                // would unstyle the others -- silently, and only ever with two of them open.
                document.styles().removeStylesheet(sheet, root);
            }
        });
    }

    /**
     * Makes sure this connection's {@link ClientWindows} knows where windows go.
     *
     * <p>Called from the frame loop, which is free when the wire has not moved and is what makes a
     * reconnect work at all: a rebind nothing re-asks for is machinery that can never fire.</p>
     *
     * <p><b>Installed when the desktop exists, not when the connection does</b>, and that ordering is
     * the whole reason {@code ClientWindows} queues. A server can open a window before the player has
     * ever pressed a key to open the screen; the window waits, and lands the moment there is somewhere
     * for it to land.</p>
     *
     * @param preferred what should be offered the window first — an application's mount, which honours
     *                  an editor-tab or tool-window hint and hands everything else straight back here.
     *                  Null on a host with no application, where every window opens on the desktop,
     *                  which is the hint working rather than failing
     */
    public void bind(@Nullable ProtocolConnection<Object> connection, @Nullable WindowMount preferred) {
        if (connection == null || connection == boundTo) return;
        boundTo = connection;
        WindowMount mount = preferred == null ? this : preferred;
        ClientWindows.of(connection)
                .setSheetSupply(sheetSupply())
                .setMount(mount);
        CrystalGuiCore.LOGGER.info("[cgui-ui] server windows will open on the {}",
                preferred == null ? "desktop" : "application");
    }

    private SheetSupply sheetSupply() {
        return new SheetSupply(
                (window, css) -> {
                    for (String sheet : css) sheets.acquire(sheet, window.root());
                },
                (window, css) -> {
                    for (String sheet : css) sheets.release(sheet, window.root());
                });
    }

    @Override
    public MountedWindow mount(ClientWindowContext context) {
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
        Desktop.of(document).addWindow(frame, false);

        Mounted mounted = new Mounted(frame, context);

        frame.onDestroyed.connect(mounted::onFrameDestroyed);
        // HIDING IS NOT CLOSING. A minimised window is retained and detached, and the server should stop
        // describing a tree nobody is drawing. @see com.crystalgui.net.protocol.UiMethods#VISIBILITY
        frame.onHidden.connect(() -> context.visibilityChanged(false));
        frame.onShown.connect(persisted -> context.visibilityChanged(true));
        // AND THE WHOLE COMPOSITOR going away, which no individual window's onHidden reports: suspending
        // takes the desktop off the tree without touching any window, so a server would otherwise go on
        // describing a tree nobody is drawing for as long as the screen is closed.
        mounted.desktopWatch = Desktop.of(document).onSuspendedChanged.connect(
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
    private final class Mounted implements MountedWindow {

        private final WindowFrame frame;

        /** Undone when the window goes, or the compositor keeps a dead window's report alive. */
        @Nullable
        private Connection desktopWatch;
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
            if (frame.state() == WindowState.DESTROYED) return;
            if (frame.state() == WindowState.HIDDEN) frame.show(false);
            Desktop.of(document).activate(frame);
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
