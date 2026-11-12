package com.crystalgui.screen;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgui.desktop.DesktopWindowMount;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * What follows the game's wire and language: the workspace on the client's connection, where a server's windows open,
 * and the language text is shaped in. The render thread notices a change each host frame and posts it to the document,
 * in every threading.
 */
final class DesktopConnection {

    private final UIDocument document;
    private final DesktopWindowMount mount;
    /** Where a server's window is offered first: the primary application's places, else null. On the document. */
    private final Supplier<WindowMount> preferred;

    /** What the render thread last posted. Render thread. */
    @Nullable
    private CgProtocolConnection<Object> posted;
    @Nullable
    private Locale postedLocale;

    /** The document's. Volatile: a host reads them from its own thread. */
    @Nullable
    private volatile Workspace workspace;
    @Nullable
    private volatile CgProtocolConnection<Object> bound;

    DesktopConnection(UIDocument document, DesktopWindowMount mount, Supplier<WindowMount> preferred) {
        this.document = document;
        this.mount = mount;
        this.preferred = preferred;
    }

    /** Posts to the document what moved since the last frame: the connection, the language. Render thread. */
    void frame(GameState game) {
        Locale locale = game.locale();
        if (!locale.equals(postedLocale)) {
            postedLocale = locale;
            document.execute(() -> FontFamilyCache.useLocale(locale));
        }
        CgProtocolConnection<Object> live = game.connection();
        if (live == posted) return;
        posted = live;
        document.execute(() -> bind(live));
    }

    /**
     * Binds the workspace and the window mount to {@code live}. On the document; free when nothing moved, so a launch
     * that needs a workspace may call it first.
     */
    void bind(@Nullable CgProtocolConnection<Object> live) {
        if (live != null) {
            Workspace current = workspace;
            if (current == null) {
                // AN ATTACHMENT ON THE CONNECTION, not a constructor: this wire is shared, and a second workspace on it
                // would be a second subscriber to fs.changed. A backup store is given by the application once the
                // server has greeted, when which workspace this is can be known.
                workspace = Workspace.of(live);
                bound = live;
            } else if (bound != live) {
                current.rebind(live);
                bound = live;
            }
        }
        // WHERE A SERVER'S WINDOWS GO. Free when the wire has not moved; windows that arrived before were queued by
        // ClientWindows and land now.
        mount.bind(live, preferred.get());
    }

    /** The workspace on the client's connection; null before one was bound. */
    @Nullable
    Workspace workspace() {
        return workspace;
    }

    /** The connection the workspace is bound to; null before one. */
    @Nullable
    CgProtocolConnection<Object> bound() {
        return bound;
    }
}
