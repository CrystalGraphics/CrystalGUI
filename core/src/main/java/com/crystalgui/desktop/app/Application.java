package com.crystalgui.desktop.app;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;

/**
 * <b>One running instance</b> — its main window and everything it owns.
 *
 * <p>The thing the tree had no name for. An application was a class a host constructed and then held in
 * a static field, so "which applications are running", "put that one in front" and "open this file with
 * that one" had no answer at all: the loader's own screen was the registry, and there was room in it for
 * exactly one.</p>
 *
 * <h3>Disposing one is quitting it</h3>
 *
 * <p>Not closing its window — a workbench under {@code HIDE_ON_CLOSE} is <em>running in the
 * background</em> with every document, the dock arrangement and the undo history intact, which is what
 * its taskbar entry brings back. {@link #dispose()} is the other verb: the window is destroyed, the
 * session written, the workbench taken down and everything it registered withdrawn.</p>
 *
 * <p>That distinction is also why an application's main window is exempt from eviction (D17): a hidden
 * main window is the application, and a cap on hidden windows must never quit one nobody asked to
 * quit.</p>
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
