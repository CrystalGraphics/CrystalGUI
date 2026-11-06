package com.crystalgui.desktop.app;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;

/**
 * One <b>running</b> application — its main window, and everything that window's workbench owns.
 *
 * <p>You never construct one. {@link ApplicationKind#launch} builds it, {@link ApplicationRegistry}
 * holds it, and you meet it as the return of {@code applications().launch(...)} or as an entry in
 * {@code applications().running()}. The taskbar groups its windows, the switcher lists them, and
 * "open with" routes a file to it through {@link #open}.</p>
 *
 * <h3>What you can ask one to do</h3>
 *
 * <ul>
 *   <li>{@link #kind()} — the manifest it was launched from; its id, name and icon.</li>
 *   <li>{@link #mainWindow()} — the window that <em>is</em> the application, for raising and grouping.</li>
 *   <li>{@link #open(Resource)} — show this file in the instance already running.</li>
 *   <li>{@link #activate()} — bring it to the front, which is what a second launch of a
 *       single-instance application does instead of starting another.</li>
 * </ul>
 *
 * <h3>Closing its main window quits it</h3>
 *
 * <p>A window closes under {@code DESTROY_ON_CLOSE}, the default, and when the destroyed window is an
 * application's main window the registry quits the application: {@link #dispose()}, then off the running list.
 * So an application never hooks its own window; it releases what it holds in {@code dispose()}, which also runs
 * at a host's shutdown with the window still up. Minimised, or with the desktop closed, it keeps running. Under
 * {@code WindowPolicy.HIDE_ON_CLOSE} the X hides the window instead and the application runs on in the
 * background, which is what its taskbar entry brings back.</p>
 *
 * <ul>
 *   <li>{@code dispose()} must be idempotent: a host quitting it destroys the window, which reaches it again.</li>
 *   <li>Its thread is the document's; GPU resources are released on {@code CgHostThread.RENDER}.</li>
 * </ul>
 *
 * <p>It is also why a main window is exempt from hidden-window eviction: a hidden main window <em>is</em>
 * the application, and a cap on hidden windows must never quit one nobody asked to quit.</p>
 */
public interface Application extends Disposable {

    /** The manifest this was launched from. */
    ApplicationKind kind();

    /** The window this application IS. Never null: an application with no window is not running. */
    WindowFrame mainWindow();

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
