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
 * <h3>Closing a window is not quitting</h3>
 *
 * <p>The distinction to hold on to. Under {@code WindowPolicy.HIDE_ON_CLOSE} the window goes away and
 * the application is still <em>running in the background</em>, with every document, the dock
 * arrangement and the undo history intact — which is what its taskbar entry brings back.
 * {@link #dispose()} is the other verb: the window is destroyed, the session written, the workbench
 * taken down and everything it registered withdrawn.</p>
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
