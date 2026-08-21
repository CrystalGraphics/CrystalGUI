package com.crystalgui.mc.client;

import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.language.LanguageStack;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.protocol.ProtocolConnection;

/**
 * The client half of a workspace that lives on the server — Phase 4 <b>B1</b>.
 *
 * <p>This class used to be <i>both</i> halves in the client process: a {@code WorkspaceService} over
 * local disk, a {@code ServerUiSession}, a {@code WorkspaceRpc}, and an {@code InMemoryTransport} pair
 * between them. Its own javadoc explained why it was built that way — the client held no filesystem
 * handle, so the later phase would be <i>"a transport swap rather than a rewrite"</i>.</p>
 *
 * <p><b>That claim held.</b> Everything server-shaped moved to {@code CgUiWorkspaceHost}, which
 * contributes the workspace to every connection, and what is left here is one line: a
 * {@link WorkspaceClient} over the connection {@link CgUiConnections} already opened. The transport, the
 * pumping and the lifecycle are all somebody else's now, which is why {@link #pump} does nothing and
 * exists only so the caller's frame loop did not have to change with it.</p>
 *
 * <h3>Why it is lazy</h3>
 *
 * <p>A screen can be opened before the connection exists — on the title screen there is no server at
 * all. So the client is built on first use and {@link #isConnected()} answers honestly until then,
 * rather than a constructor failing or, worse, handing back something that silently answers nothing.</p>
 */
public final class Mc1710Workspace {

    /** Matches {@code CgUiWorkspaceHost.PROJECT_ID}: the id is the client's handle on the project. */
    static final String PROJECT_ID = "minecraft.workspace";

    private WorkspaceClient<Object> client;
    private ProtocolConnection<Object> boundTo;

    Mc1710Workspace() {
        // Which grammars and engines exist is a fact about language/, not about this platform. This
        // constructor's only contribution is the MOMENT: before anything opens a document. ClientProxy
        // calls it earlier still, and the overlap is free because it is idempotent.
        LanguageStack.registerAll();
    }

    /**
     * The file client, or {@code null} until there is a connection to carry it.
     *
     * <p>Rebuilt if the connection is replaced — a reconnect is a different wire, and a client still
     * holding the old one would call into a router whose peer is gone and see every request time out.</p>
     */
    WorkspaceClient<Object> client() {
        ProtocolConnection<Object> connection = CgUiConnections.client();
        if (connection == null) {
            client = null;
            boundTo = null;
            return null;
        }
        if (client == null || boundTo != connection) {
            boundTo = connection;
            client = new WorkspaceClient<>(connection);
        }
        return client;
    }

    /**
     * True once there is a connection to the server.
     *
     * <p>Before that, a call has nowhere to go. The old version asked whether a session had been given a
     * window id, for the same reason and with a worse failure: a call made too early was discarded with
     * no error at all, and the file tree simply stayed empty.</p>
     */
    boolean isConnected() {
        return CgUiConnections.client() != null;
    }

    /**
     * Nothing — {@link CgUiConnections} ticks the connection on the client tick.
     *
     * <p>Kept so the caller's frame loop reads the same, and so that anyone looking for where the
     * pumping went finds this rather than concluding it was dropped. Ticking here as well would drain
     * the same mailbox twice, which is harmless and is still two answers to one question.</p>
     */
    void pump(float deltaSeconds) {
    }
}
