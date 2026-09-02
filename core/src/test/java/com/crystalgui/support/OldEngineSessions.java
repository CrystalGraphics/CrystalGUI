package com.crystalgui.support;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UITransport;
import com.crystalgui.net.mirror.ElementNodeMirror;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.dom.ElementTreeSource;

/**
 * Opens a session over the OLD engine's tree, for the fixtures that still test it.
 *
 * <p>The sessions became generic in the node type at 6.8, so opening one is three arguments where it
 * was one. These fixtures cover the old workbench, which runs the game until 6.9, so they keep the
 * old tree -- and that is worth having rather than merely tolerating: the same session code serving
 * {@code UIElement} here and {@code UINode} in {@code net/window} is the seam being a seam. The
 * headless set has its own {@code Sessions} over the node tree, and neither knows about the other.</p>
 *
 * <p>Goes with {@code ElementTreeSource} and {@code ElementNodeMirror} at 6.9b.</p>
 */
public final class OldEngineSessions {

    private OldEngineSessions() {
    }

    /** A server session over {@code root}. */
    public static ServerUiSession<UIElement, Object> serve(int windowId, UIElement root,
                                                           UITransport<Object> transport) {
        return new ServerUiSession<>(windowId, new ElementTreeSource(root),
                new ElementNodeMirror<>(PlainOps.INSTANCE), transport, PlainOps.INSTANCE);
    }

    /** A client session. */
    public static ClientUiSession<UIElement, Object> view(UITransport<Object> transport) {
        return new ClientUiSession<>(new ElementNodeMirror<>(PlainOps.INSTANCE), transport,
                PlainOps.INSTANCE);
    }
}
