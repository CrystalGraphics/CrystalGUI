package com.crystalgui.desktop.app;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowBounds;

/**
 * How a workbench opens its floating windows: tool windows and torn-out editors are windows of the desktop, so they can
 * leave the window they came out of, while what they show stays in the workbench's document.
 *
 * <pre>{@code
 * workbench.useFloats(FloatSpace.of(context.windows(), mainWindow));    // an application with a document of its own
 * AppWindow tool = floats.openOwned(WindowSpec.titled("Library").toolWindow().content(view));
 * AppWindow torn = floats.open(WindowSpec.titled("notes.md").content(area));    // a peer, not owned
 * }</pre>
 *
 * <ul>
 *   <li>An owned window stays above the window it came from and goes with it; a peer is a window of its own.</li>
 *   <li>{@link #onDesktop} is for a workbench with no application around it (a test, a bare document): its windows' content
 *       is in the shell's document.</li>
 * </ul>
 */
public final class FloatSpace {

    private final Function<WindowSpec, AppWindow> opener;
    private final boolean inApplication;
    @Nullable
    private final AppWindow main;

    private FloatSpace(Function<WindowSpec, AppWindow> opener, @Nullable AppWindow main, boolean inApplication) {
        this.opener = opener;
        this.inApplication = inApplication;
        this.main = main;
    }

    /** Opens through {@code windows}, owned by {@code main}: the application's own windows. */
    public static FloatSpace of(AppWindows windows, @Nullable AppWindow main) {
        return new FloatSpace(windows::open, main, true);
    }

    /** Opens on {@code desktop} with content in the shell's document, owned by {@code main} when there is one. */
    public static FloatSpace onDesktop(Desktop desktop, @Nullable ApplicationKind kind, @Nullable AppWindow main) {
        return new FloatSpace(spec -> AppWindows.openOnDesktop(desktop, kind, spec), main, false);
    }

    /** A window of its own. */
    public AppWindow open(WindowSpec spec) {
        return opener.apply(spec);
    }

    /** A window owned by the main window: above it, and minimised and closed with it; a peer when there is no main window. */
    public AppWindow openOwned(WindowSpec spec) {
        return opener.apply(main == null ? spec : spec.ownedBy(main));
    }

    /**
     * Turns a point in the document the floats came out of into a desktop point, the space a window is placed in. An
     * application's own document is not the desktop's: its origin is its window's client area.
     */
    public float[] toDesktop(float x, float y) {
        if (!inApplication || main == null) return new float[] {x, y};
        WindowBounds client = main.clientBounds(0f, 0f);
        return new float[] {client.left() + x, client.top() + y};
    }

    /** The window the floats came out of, or null. */
    @Nullable
    public AppWindow main() {
        return main;
    }
}
