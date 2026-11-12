package com.crystalgui.desktop.app;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;

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

    AppWindows(Desktop desktop, ApplicationKind kind) {
        this.desktop = desktop;
        this.kind = kind;
    }

    /**
     * Opens a window for this application: placed by the manager unless the spec places it, shown, and active unless
     * the spec asks for the background. The first one opened is the application's main window.
     */
    public AppWindow open(WindowSpec spec) {
        return open(desktop, kind, spec);
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

    static AppWindow open(Desktop desktop, @Nullable ApplicationKind kind, WindowSpec spec) {
        WindowFrame frame = new WindowFrame(spec.title());
        // Its owner, key and kind BEFORE it is added: the desktop places a window by its key and sizes it by its kind
        if (spec.key() != null) frame.setKey(spec.key());
        if (spec.icon() != null) frame.setIcon(spec.icon());
        frame.setPolicy(spec.policy());
        frame.setTitleShown(spec.titleShown());
        if (spec.pinned()) frame.setPinned(true);
        if (spec.content() != null) frame.setContent(spec.content());
        if (spec.owner() != null) frame.setOwnerWindow(spec.owner().frame());
        if (kind != null) {
            frame.setApplication(kind);
            if (spec.owner() == null && !hasMain(desktop, kind)) frame.markApplicationMain();
        }
        if (spec.width() > 0 && spec.height() > 0) frame.resizeTo(spec.width(), spec.height());
        if (spec.placed()) frame.moveTo(spec.left(), spec.top());
        desktop.addWindow(frame, spec.takesFocus());
        return AppWindow.of(frame);
    }

    private static boolean hasMain(Desktop desktop, ApplicationKind kind) {
        for (WindowFrame frame : desktop.windowManager().registry().windows()) {
            if (frame.application() == kind && frame.isApplicationMain()) return true;
        }
        return false;
    }
}
