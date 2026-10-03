package com.crystalgui.headless;

import com.crystalgraphics.net.CgTransport;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementTreeSource;

/**
 * Opens a session over the node tree, for fixtures.
 *
 * <p>The sessions are generic in the node type since 6.8 -- the mirror is authored once and a second
 * engine supplies a {@code TreeSource} and a {@code NodeMirror}, which is what the seam is for. That
 * makes every construction three arguments where it used to be one, and a fixture has no interest in
 * the choice: there is one tree in the jar. So the choice is stated here, once, rather than at each
 * of the thirty sites that open a session.
 *
 * <p>Deliberately NOT a convenience constructor on the sessions themselves: {@code net} may not name
 * an engine, and a constructor taking a bare root would put {@code UIElement} straight back into it.
 */
final class Sessions {

    private Sessions() {
    }

    /** A server session over {@code root}, on a raw transport. */
    static ServerUiSession<UIElement, Object> serve(int windowId, UIElement root,
                                                    CgTransport<Object> transport) {
        return new ServerUiSession<>(windowId, new UIElementTreeSource(root),
                new UIElementMirror<>(CgPlainOps.INSTANCE), transport, CgPlainOps.INSTANCE);
    }

    /** A server session over {@code root}, on a connection. */
    static ServerUiSession<UIElement, Object> serveOn(int windowId, UIElement root,
                                                      CgProtocolConnection<Object> connection) {
        return new ServerUiSession<>(windowId, new UIElementTreeSource(root),
                new UIElementMirror<>(connection.ops()), connection);
    }

    /** A client session on a raw transport. */
    static ClientUiSession<UIElement, Object> view(CgTransport<Object> transport) {
        return new ClientUiSession<>(new UIElementMirror<>(CgPlainOps.INSTANCE), transport,
                CgPlainOps.INSTANCE);
    }

    /** A client session on a connection. */
    static ClientUiSession<UIElement, Object> viewOn(CgProtocolConnection<Object> connection) {
        return new ClientUiSession<>(new UIElementMirror<>(connection.ops()), connection);
    }
}
