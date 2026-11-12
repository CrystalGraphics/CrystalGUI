package com.crystalgui.desktop;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.app.AppDocument;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.ClientPortal;
import com.crystalgui.desktop.app.ClientRoot;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.ViewCommand;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.ScopedSheets;
import com.crystalgui.net.window.SheetSupply;
import com.crystalgui.net.window.UiType;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * Where a server's window lands: a {@link WindowFrame} on the desktop, showing a tree that lives in the document of the
 * connection that described it.
 *
 * <pre>{@code
 * DesktopWindowMount mount = new DesktopWindowMount(shell, () -> documents.openServer("connection"));
 * mount.bind(connection, null);                                  // each frame; free when the wire has not moved
 * mount.bind(connection, new DesktopWindowMount.Preferred(app.windowMount(mount), appDocument));
 * }</pre>
 *
 * <ul>
 *   <li><b>One document per connection.</b> The session decodes and edits a window's tree on the thread that delivers
 *       {@code ui/}, which is that document's; the frame in the shell shows it through a {@link ClientPortal}. Without
 *       a document builder the tree lives in the shell's and the frame holds it directly.</li>
 *   <li><b>An application's mount</b> (the workbench's editor tab and tool window) runs on its application's thread and
 *       is handed a portal in place of the tree; what it hands back, and what the context reports, cross threads here.
 *       What it declines opens as a frame.</li>
 *   <li><b>Windows open in the background</b>: a server pushing a UI must not take the keyboard from what is being typed.
 *       The frame asks for attention instead.</li>
 *   <li>It owes the context one report: {@link ClientWindowContext#userClosed()} when the <em>user</em> closes the frame,
 *       never when the server or the connection did.</li>
 * </ul>
 */
public final class DesktopWindowMount implements WindowMount {

    /** An application's mount, and the document it runs in: null when it runs in the shell's. */
    public record Preferred(WindowMount mount, @Nullable AppDocument document) {
    }

    private final UIDocument shell;
    @Nullable
    private final Supplier<AppDocument> documents;
    /** The connection's document, made when the first connection binds; null where none is built. */
    @Nullable
    private volatile AppDocument host;
    @Nullable
    private CgProtocolConnection<Object> boundTo;
    private final ScopedSheets sheets;

    /** A mount that keeps every tree in the shell's document. */
    public DesktopWindowMount(UIDocument shell) {
        this(shell, null);
    }

    /** @param documents builds the connection's document, on the shell's thread, once */
    public DesktopWindowMount(UIDocument shell, @Nullable Supplier<AppDocument> documents) {
        this.shell = shell;
        this.documents = documents;
        this.sheets = new ScopedSheets(new ScopedSheets.Host() {
            @Override
            public void add(StyleSheet sheet, Styleable root) {
                treeDocument().styles().addStylesheet(sheet, root);
            }

            @Override
            public void remove(StyleSheet sheet, Styleable root) {
                // The root overload: one parse serves every window of a type.
                treeDocument().styles().removeStylesheet(sheet, root);
            }
        });
    }

    /** The connection's document, built on first ask; the shell's thread. Null where none is built. */
    @Nullable
    AppDocument host() {
        if (host == null && documents != null) host = documents.get();
        return host;
    }

    private UIDocument treeDocument() {
        AppDocument connection = host;
        return connection == null ? shell : connection.document();
    }

    /**
     * Makes sure this connection's {@link ClientWindows} knows where windows go. The shell's thread, from the frame
     * loop: free when the wire has not moved, and what makes a reconnect work.
     *
     * <p>Installed when the desktop exists, not when the connection does: {@code ClientWindows} queues what a server
     * opens before then.</p>
     *
     * @param preferred offered each window first; it honours an editor-tab or tool-window hint and hands the rest back
     *                  here. Null on a host with no application
     */
    public void bind(@Nullable CgProtocolConnection<Object> connection, @Nullable Preferred preferred) {
        if (connection == null || connection == boundTo) return;
        // The connection test owns ui/openWindow for its run: installing the window host would take it.
        if (WindowProtocol.clientHostStandsDown()) return;
        boundTo = connection;
        AppDocument own = host();
        // Only CrystalGUI's own methods are delivered off the game thread: the connection is shared. The workspace and
        // commands belong to the desktop; the windows to the connection's document.
        connection.dispatchThrough("command/", shell::execute);
        connection.dispatchThrough("fs/", shell::execute);
        connection.dispatchThrough("ui/", own != null ? own::execute : shell::execute);
        WindowMount mount = preferred == null ? this : own == null ? preferred.mount() : through(own, preferred);
        ClientWindows.of(connection).setSheetSupply(sheetSupply()).setMount(mount);
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
        if (context instanceof Routed routed) {
            // From an application's mount, on its thread: the window is the connection's to open.
            return routed.home.toConnection(() -> open(routed.original, routed.client));
        }
        AppDocument connection = host;
        if (connection == null) return openInShell(context);
        return open(context, connection.openClient(context.root()));
    }

    /** The connection's thread: opens a frame in the shell showing {@code client}. */
    private MountedWindow open(ClientWindowContext context, ClientRoot client) {
        AppDocument connection = host;
        WindowFrame frame;
        try {
            frame = connection.callShell(shell, () -> {
                ClientPortal portal = connection.portalTo(client);
                WindowFrame built = frame(context, portal, connection);
                connection.showPopupsIn(shell);
                return built;
            });
        } catch (RuntimeException failed) {
            release(connection, client);
            throw failed;
        }
        return wire(frame, context, connection, client);
    }

    /** No document of the connection's own: the tree is the shell's. */
    private MountedWindow openInShell(ClientWindowContext context) {
        return wire(frame(context, context.root(), null), context, null, null);
    }

    /** The shell's thread: a frame for {@code context} showing {@code content}, added in the background. */
    private WindowFrame frame(ClientWindowContext context, UIElement content, @Nullable AppDocument connection) {
        Desktop desktop = Desktop.of(shell);
        WindowFrame frame = new WindowFrame(title(context));
        // What the server named, so the compositor puts it back where the user left it.
        if (context.key() != null) frame.setKey(context.key());
        // One guard for both routes that take a window away: the close button and the retention cap. The content
        // answers it, on its own thread.
        frame.setDiscardGuard(connection == null ? context::mayClose : () -> connection.ask(context::mayClose));
        UiType.Application app = UiType.applicationOf(context.type());
        ApplicationKind kind = app == null ? null : desktop.applications().byId(app.id());
        if (kind != null) frame.setApplication(kind).markApplicationMain();
        try {
            frame.setContent(content);
            desktop.addWindow(frame, false);
        } catch (RuntimeException failed) {
            String name = app != null ? app.name() : frame.getTitle();
            desktop.reportFailure(app != null ? app.id() : null, name, name + " could not be opened.", failed);
            throw failed;
        }
        return frame;
    }

    private MountedWindow wire(WindowFrame frame, ClientWindowContext context, @Nullable AppDocument connection,
                               @Nullable ClientRoot client) {
        Mounted mounted = new Mounted(frame, context, connection, client);
        // On the shell's thread, where the frame's signals belong.
        Runnable connect = () -> {
            frame.onDestroyed.connect(mounted::onFrameDestroyed);
            // Hiding is not closing: a minimised window is detached, and the server should stop describing it.
            frame.onHidden.connect(() -> mounted.toConnection(() -> context.visibilityChanged(false)));
            frame.onShown.connect(persisted -> mounted.toConnection(() -> context.visibilityChanged(true)));
            // The whole compositor going away reports through no window's onHidden.
            mounted.desktopWatch = Desktop.of(shell).onSuspendedChanged.connect(shown ->
                    mounted.toConnection(() -> context.visibilityChanged(shown && frame.state() == WindowState.VISIBLE)));
        };
        if (shell.isCurrent()) connect.run();
        else if (connection != null) connection.callShell(shell, () -> {
            connect.run();
            return null;
        });
        else shell.execute(connect);
        return mounted;
    }

    private static void release(AppDocument connection, ClientRoot client) {
        connection.removeClient(client);
    }

    private static String title(ClientWindowContext context) {
        String named = context.title();
        if (!named.isEmpty()) return named;
        // A blank bar reads as a broken window.
        return context.type().isEmpty() ? "Window" : context.type();
    }

    /** One frame, and which side is ending it. */
    private final class Mounted implements MountedWindow {

        private final WindowFrame frame;
        private final ClientWindowContext context;
        @Nullable
        private final AppDocument connection;
        @Nullable
        private final ClientRoot client;
        private final AtomicBoolean released = new AtomicBoolean();
        /** Undone when the window goes, or the compositor keeps a dead window's report alive. */
        @Nullable
        private Connection desktopWatch;
        /** Set while the server takes this window down, so the frame's own teardown stays quiet. */
        private boolean closingFromServer;

        Mounted(WindowFrame frame, ClientWindowContext context, @Nullable AppDocument connection,
                @Nullable ClientRoot client) {
            this.frame = frame;
            this.context = context;
            this.connection = connection;
            this.client = client;
        }

        /** Runs {@code work} on the connection's thread, where the session may be touched. */
        void toConnection(Runnable work) {
            if (connection == null) work.run();
            else connection.execute(work);
        }

        private void onShell(Runnable work) {
            shell.execute(work);
        }

        /** The shell's thread. */
        void onFrameDestroyed() {
            if (desktopWatch != null) {
                desktopWatch.disconnect();
                desktopWatch = null;
            }
            boolean fromServer = closingFromServer;
            boolean evicted = frame.isBeingEvicted();
            toConnection(() -> {
                if (connection != null && released.compareAndSet(false, true)) release(connection, client);
                // The user, unless we are the ones destroying it: a server-driven close would come straight back as a
                // ui/close. Eviction is the client running out of room, which a server must not hear as "closed".
                if (fromServer) return;
                if (evicted) context.evicted();
                else context.userClosed();
            });
        }

        @Override
        public void closedByServer(String reason) {
            onShell(() -> {
                if (frame.state() == WindowState.DESTROYED) return;
                closingFromServer = true;
                try {
                    frame.destroy();
                } finally {
                    closingFromServer = false;
                }
            });
        }

        @Override
        public void focus() {
            onShell(() -> {
                if (frame.state() == WindowState.DESTROYED) return;
                if (frame.state() == WindowState.HIDDEN) frame.show(false);
                Desktop.of(shell).windowManager().activate(frame);
            });
        }

        /**
         * What a server may ask of the window rather than the tree. The geometry hint stays unapplied: a record the user
         * made outranks it.
         */
        @Override
        public void viewCommand(String command, CgStateMap<Object> args) {
            onShell(() -> {
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
            });
        }

        @Override
        public void contentReplaced(UIElement newRoot) {
            // The session decoded a fresh tree; the one shown is no longer being updated.
            if (client != null) client.setContent(newRoot);
            else frame.setContent(newRoot);
        }
    }

    // ── An application's mount ──────────────────────────────────────────────────────────────────────────────────

    /** Offers each window to an application's mount on that application's thread, handing it a portal for the tree. */
    private WindowMount through(AppDocument connection, Preferred preferred) {
        Home home = new Home(preferred.document(), connection);
        return context -> {
            ClientRoot client = connection.openClient(context.root());
            Routed routed = new Routed(context, client, home);
            MountedWindow inner;
            try {
                inner = home.inside(() -> preferred.mount().mount(routed));
            } catch (RuntimeException failed) {
                routed.release();
                throw failed;
            }
            return new Forwarded(inner, routed);
        };
    }

    /** The thread an application's mount runs on, and the way between it and the connection's. */
    private final class Home {

        @Nullable
        private final AppDocument application;
        private final AppDocument connection;

        Home(@Nullable AppDocument application, AppDocument connection) {
            this.application = application;
            this.connection = connection;
        }

        /** From the connection's thread: runs {@code work} on the application's, and waits. */
        <T> T inside(Supplier<T> work) {
            return application != null ? application.ask(work) : connection.callShell(shell, work);
        }

        /** Runs {@code work} on the application's thread without waiting. */
        void post(Runnable work) {
            if (application != null) application.execute(work);
            else shell.execute(work);
        }

        /** From the application's thread: runs {@code work} on the connection's, and waits. */
        <T> T toConnection(Supplier<T> work) {
            if (connection.document().isCurrent()) return work.get();
            return application != null ? application.callShell(connection.document(), work) : connection.ask(work);
        }
    }

    /** What an application's mount sees: the window, with a portal in the place of the tree. */
    private final class Routed implements ClientWindowContext {

        final ClientWindowContext original;
        final ClientRoot client;
        final Home home;
        private final AtomicBoolean released = new AtomicBoolean();
        @Nullable
        private ClientPortal portal;

        Routed(ClientWindowContext original, ClientRoot client, Home home) {
            this.original = original;
            this.client = client;
            this.home = home;
        }

        /** The connection's thread. */
        void release() {
            if (released.compareAndSet(false, true)) host.removeClient(client);
        }

        private void onConnection(Runnable work) {
            host.execute(work);
        }

        /** Built on the application's thread, where it is shown. */
        @Override
        public UIElement root() {
            if (portal == null) {
                portal = host.portalTo(client);
                // Its popups are the shell's to show, once.
                shell.execute(() -> host.showPopupsIn(shell));
            }
            return portal;
        }

        @Override
        public String type() {
            return original.type();
        }

        @Override
        public String title() {
            return original.title();
        }

        @Override
        @Nullable
        public String key() {
            return original.key();
        }

        @Override
        public Presentation presentation() {
            return original.presentation();
        }

        @Override
        public List<SheetRef> sheets() {
            return original.sheets();
        }

        @Override
        public boolean useUserAgentSheet() {
            return original.useUserAgentSheet();
        }

        @Override
        public ClientUiSession<UIElement, Object> session() {
            return original.session();
        }

        @Override
        public CgProtocolConnection<Object> connection() {
            return original.connection();
        }

        @Override
        public boolean mayClose() {
            return home.toConnection(original::mayClose);
        }

        @Override
        public void evicted() {
            onConnection(() -> {
                release();
                original.evicted();
            });
        }

        @Override
        public void userClosed() {
            onConnection(() -> {
                release();
                original.userClosed();
            });
        }

        @Override
        public void visibilityChanged(boolean visible) {
            onConnection(() -> original.visibilityChanged(visible));
        }
    }

    /** An application's window as the connection's thread sees it: what it is told runs on the application's. */
    private static final class Forwarded implements MountedWindow {

        private final MountedWindow inner;
        private final Routed routed;

        Forwarded(MountedWindow inner, Routed routed) {
            this.inner = inner;
            this.routed = routed;
        }

        @Override
        public void closedByServer(String reason) {
            routed.release();
            routed.home.post(() -> inner.closedByServer(reason));
        }

        @Override
        public void focus() {
            routed.home.post(inner::focus);
        }

        @Override
        public void contentReplaced(UIElement newRoot) {
            // The portal shows the client, which keeps showing whatever the session decoded last.
            routed.client.setContent(newRoot);
        }

        @Override
        public void viewCommand(String command, CgStateMap<Object> args) {
            routed.home.post(() -> inner.viewCommand(command, args));
        }
    }
}
