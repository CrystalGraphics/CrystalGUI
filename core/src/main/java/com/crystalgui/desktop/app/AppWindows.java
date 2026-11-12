package com.crystalgui.desktop.app;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * An application's windows: where it opens them, and how it finds them again. Reached with
 * {@link AppContext#windows()}.
 *
 * <pre>{@code
 * AppWindow main = app.windows().open(WindowSpec.titled("Planner").size(900, 600));
 * for (AppWindow window : app.windows().all()) window.minimise();
 * }</pre>
 *
 * <p>The first window an application opens is its main window, the one that is the application: closing it quits
 * it. Every later one, and every owned one, is not.</p>
 */
public final class AppWindows {

    private final Desktop desktop;
    private final ApplicationKind kind;
    @Nullable
    private final AppDocument document;

    AppWindows(Desktop desktop, ApplicationKind kind, @Nullable AppDocument document) {
        this.desktop = desktop;
        this.kind = kind;
        this.document = document;
    }

    /**
     * Opens a window for this application: placed by the manager unless the spec places it, shown, and active unless
     * the spec asks for the background. The first one opened is the application's main window.
     *
     * <p>Call it on the application's own thread: its content is built there, and for an application with a
     * document of its own the window is added to the shell's by a request.</p>
     */
    public AppWindow open(WindowSpec spec) {
        return open(desktop, kind, document, spec);
    }

    /** Its windows that are still open, in the order they opened: the main window first. */
    public List<AppWindow> all() {
        List<AppWindow> mine = new ArrayList<>();
        for (WindowFrame frame : desktop.windowManager().registry().windows()) {
            if (frame.application() == kind) mine.add(AppWindow.of(frame));
        }
        return mine;
    }

    /** Its window that holds the focus, or null when another application's window, or none, does. */
    @Nullable
    public AppWindow active() {
        WindowFrame active = desktop.windowManager().activeWindow();
        return active != null && active.application() == kind ? AppWindow.of(active) : null;
    }

    /**
     * Puts a window the workbench built (a torn-out tab, a tool window) beside {@code anchor}, as a window that is not an
     * application's main window.
     *
     * <pre>{@code
     * DockWindow torn = new DockWindow(area, layout, title);
     * AppWindows.addFloat(area, torn);
     * }</pre>
     *
     * <ul>
     *   <li>An anchor in an application's own document gets a float in that document, over the window it is in and kept
     *       inside it: its content cannot leave the thread that owns it.</li>
     *   <li>Any other anchor gets a window of the desktop of its document.</li>
     *   <li>For code that builds its own {@link WindowFrame} subclass, which only the workbench's floats do; everything
     *       else opens a window with {@link #open}.</li>
     * </ul>
     */
    public static <T extends WindowFrame> T addFloat(UIElement anchor, T frame) {
        for (UIElement node = anchor; node != null; node = node.parentElement()) {
            if (node instanceof ClientRoot client) {
                client.append(frame);
                return frame;
            }
        }
        return Desktop.of(anchor.document()).addWindow(frame);
    }

    static AppWindow open(Desktop desktop, @Nullable ApplicationKind kind, @Nullable AppDocument document,
                          WindowSpec spec) {
        // THE CONTENT STAYS IN ITS APPLICATION'S DOCUMENT; the frame, which is the shell's, holds a portal that shows it.
        ClientRoot client = null;
        if (document != null) {
            client = new ClientRoot();
            if (spec.content() != null) client.setContent(spec.content());
            document.addClient(client);
        }
        ClientRoot shown = client;
        // BUILT ON THE SHELL'S THREAD: what a frame connects belongs to the sequence that first used it.
        WindowFrame frame = document == null ? build(desktop, kind, null, null, spec)
                : document.callShell(desktop.document(), () -> build(desktop, kind, document, shown, spec));
        return AppWindow.of(frame, desktop, document, client);
    }

    private static WindowFrame build(Desktop desktop, @Nullable ApplicationKind kind, @Nullable AppDocument document,
                                     @Nullable ClientRoot client, WindowSpec spec) {
        WindowFrame frame = new WindowFrame(spec.title());
        // Its owner, key and kind BEFORE it is added: the desktop places a window by its key and sizes it by its kind
        if (spec.key() != null) frame.setKey(spec.key());
        if (spec.icon() != null) frame.setIcon(spec.icon());
        frame.setPolicy(spec.policy());
        frame.setTitleShown(spec.titleShown());
        if (spec.pinned()) frame.setPinned(true);
        if (client != null) frame.setContent(new ClientPortal(document, client));
        else if (spec.content() != null) frame.setContent(spec.content());
        if (spec.owner() != null) frame.setOwnerWindow(spec.owner().frame());
        if (kind != null) {
            frame.setApplication(kind);
            if (spec.owner() == null && !hasMain(desktop, kind)) frame.markApplicationMain();
        }
        if (spec.width() > 0 && spec.height() > 0) frame.resizeTo(spec.width(), spec.height());
        if (spec.placed()) frame.moveTo(spec.left(), spec.top());
        desktop.addWindow(frame, spec.takesFocus());
        if (client != null) document.showPopupsIn(desktop.document());
        return frame;
    }

    private static boolean hasMain(Desktop desktop, ApplicationKind kind) {
        for (WindowFrame frame : desktop.windowManager().registry().windows()) {
            if (frame.application() == kind && frame.isApplicationMain()) return true;
        }
        return false;
    }
}
