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

    /** The application these windows belong to. */
    public AppDocument application() {
        return document;
    }

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
     * Opens a window of {@code kind} on {@code desktop} whose content lives in the shell's own document: what a workbench
     * does when it was not launched as an application with a document of its own.
     */
    public static AppWindow openOnDesktop(Desktop desktop, @Nullable ApplicationKind kind, WindowSpec spec) {
        return open(desktop, kind, null, spec);
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
        for (String cssClass : spec.classes()) frame.addClass(cssClass);
        if (spec.tool()) frame.setToolWindow(true);
        if (!spec.maximizable()) {
            frame.maximizeButton().setDisplayed(false);
            frame.setMaximizable(false);
        }
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
        if (client != null) {
            document.showPopupsIn(desktop.document());
            document.windowOpened(frame);
            // ITS CLIENT GOES WITH IT: a window opened and closed over and over (a tool window, a torn-out tab) would
            // otherwise leave a root in the document per opening, laid out every frame for nothing.
            frame.onDestroyed.connect(() -> document.execute(() -> document.removeClient(client)));
        }
        return frame;
    }

    private static boolean hasMain(Desktop desktop, ApplicationKind kind) {
        for (WindowFrame frame : desktop.windowManager().registry().windows()) {
            if (frame.application() == kind && frame.isApplicationMain()) return true;
        }
        return false;
    }
}
