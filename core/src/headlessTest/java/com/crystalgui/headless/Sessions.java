package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UITransport;
import com.crystalgui.net.mirror.UINodeMirror;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeTreeSource;

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
 * an engine, and a constructor taking a bare root would put {@code UINode} straight back into it.
 */
final class Sessions {

    private Sessions() {
    }

    /** A server session over {@code root}, on a raw transport. */
    static ServerUiSession<UINode, Object> serve(int windowId, UINode root,
                                                 UITransport<Object> transport) {
        return new ServerUiSession<>(windowId, new UINodeTreeSource(root),
                new UINodeMirror<>(PlainOps.INSTANCE), transport, PlainOps.INSTANCE);
    }

    /** A server session over {@code root}, on a connection. */
    static ServerUiSession<UINode, Object> serveOn(int windowId, UINode root,
                                                   ProtocolConnection<Object> connection) {
        return new ServerUiSession<>(windowId, new UINodeTreeSource(root),
                new UINodeMirror<>(connection.ops()), connection);
    }

    /** A client session on a raw transport. */
    static ClientUiSession<UINode, Object> view(UITransport<Object> transport) {
        return new ClientUiSession<>(new UINodeMirror<>(PlainOps.INSTANCE), transport,
                PlainOps.INSTANCE);
    }

    /** A client session on a connection. */
    static ClientUiSession<UINode, Object> viewOn(ProtocolConnection<Object> connection) {
        return new ClientUiSession<>(new UINodeMirror<>(connection.ops()), connection);
    }
}
