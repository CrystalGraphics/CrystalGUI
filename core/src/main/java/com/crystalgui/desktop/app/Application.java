package com.crystalgui.desktop.app;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.fs.Resource;

/**
 * One running application: the object a kind's factory returns, which holds its window and everything that window's
 * content owns. You implement it; the registry holds it, the taskbar groups its windows, and "open with" routes a
 * file to it through {@link #open}.
 *
 * <pre>{@code
 * final class Planner implements Application {
 *     private final AppWindow main;
 *
 *     Planner(AppContext app) {
 *         main = app.windows().open(WindowSpec.titled("Planner").icon("mymod:planner").content(new PlannerView()));
 *     }
 *
 *     @Override public ApplicationKind kind() { return KIND; }
 *     @Override public AppWindow mainWindow() { return main; }
 *     @Override public boolean open(Resource file) { return false; }     // not a file this opens
 *     @Override public void activate() { main.activate(); }
 *     @Override public void dispose() { main.destroy(); }               // and release what it holds
 * }
 * }</pre>
 *
 * <h3>Closing its main window quits it</h3>
 *
 * <p>The first window an application opens is its main window. When that window is destroyed, which is what the X
 * does under {@code DESTROY_ON_CLOSE}, the registry calls {@link #dispose()} and takes it off the running list, so an
 * application never hooks its own window. Minimised, or with the desktop closed, it keeps running. A main window
 * opened with {@link WindowSpec#hideOnClose()} is hidden by the X instead, and the application runs on in the
 * background until its taskbar entry brings the window back.</p>
 *
 * <ul>
 *   <li>{@link #dispose()} must be idempotent: a host shutting down destroys the window, which reaches it again.</li>
 *   <li>It runs on the application's own thread; release GPU resources on {@code CgHostThread.RENDER}.</li>
 *   <li>{@link #mainWindow()} is never null: an application with no window is not running.</li>
 *   <li>A hidden main window is exempt from hidden-window eviction, since it <em>is</em> the application.</li>
 * </ul>
 */
public interface Application extends Disposable {

    /** The manifest this was launched from. */
    ApplicationKind kind();

    /** The window this application IS. Never null: an application with no window is not running. */
    AppWindow mainWindow();

    /**
     * Opens {@code resource} in this instance.
     *
     * <p>What "open with" resolves to, and what a second launch of a {@link ApplicationKind#singleInstance()
     * single-instance} application does with its argument instead of starting another one — the same
     * thing a second {@code open} on macOS or a second command line on Windows does.</p>
     *
     * @return whether this application took it; false is an ordinary answer and means "not mine"
     */
    boolean open(Resource resource);

    /** Brings it forward: shown if hidden, raised if behind, focused either way. */
    void activate();

    /**
     * A stable name for this instance's private files, or null when it keeps none.
     *
     * <p>Scoped from the desktop's storage by {@link ApplicationKind#id()}, so two applications sharing
     * one config directory do not write each other's {@code settings.json} — D20.</p>
     */
    @Nullable
    default String storageScope() {
        return kind().id();
    }
}
