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

    Mc1710Workspace() {
        // Which grammars and engines exist is a fact about language/, not about this platform. This
        // constructor's only contribution is the MOMENT: before anything opens a document. ClientProxy
        // calls it earlier still, and the overlap is free because it is idempotent.
        LanguageStack.registerAll();
    }

    /**
     * The file client, or {@code null} until there is a connection to carry it.
     *
     * <p><b>Rebound rather than rebuilt</b> when the connection is replaced — CrystalOS W11. A reconnect
     * is a different wire, and a client still holding the old one would call into a router whose peer is
     * gone and see every request time out. Replacing the client would fix that and lose something else:
     * a window hidden across the disconnect is still holding the OLD one in a {@code final} field, along
     * with every callback anything registered on it. {@link WorkspaceClient#rebind} swaps the wire and
     * keeps the object, so a retained window comes back working without knowing a reconnect happened.</p>
     *
     * <p>The null branch deliberately does <b>not</b> drop the client. Losing a connection is not losing
     * the workspace — that is precisely the state retention exists to survive — and clearing it here
     * would hand the next caller a fresh client on the next connection, which is the rebuild this method
     * exists to avoid.</p>
     */
    WorkspaceClient<Object> client() {
        ProtocolConnection<Object> connection = CgUiConnections.client();
        if (connection == null) return client;
        if (client == null) {
            // forConnection, not the constructor: this wire is shared, and a second client on it
            // would throw on fs.changed. @see WorkspaceClient#forConnection
            client = WorkspaceClient.forConnection(connection);
        } else {
            // NO GUARD HERE. The client tracks the wire it is on and answers immediately when it has not
            // moved, so a second copy of that state on this side could only ever drift from it.
            client.rebind(connection);
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
     * Re-asks who the client is, which is what makes a reconnect reach it — CrystalOS W11.
     *
     * <p><b>The connection ticking is still somebody else's</b> ({@link CgUiConnections}, on the client
     * tick); doing it here as well would drain the same mailbox twice, which is harmless and is still two
     * answers to one question. What this does is the other half, and it is why the method stopped being
     * empty: {@link #client()} rebinds when the wire has moved, and <b>nothing else ever calls it again</b>.
     * {@code CgUiScreen} asks exactly once, at editor construction, and {@code CrystalEditor} holds what it
     * was given for the life of the screen — so without a per-frame re-ask the rebind is machinery that
     * can never fire, and a window retained across a rejoin stays pointed at a dead router for good.</p>
     *
     * <p>Free when nothing has changed: {@code client()} compares the connection it is bound to and
     * returns immediately. Called from the frame loop <em>before anything reads the workspace</em>, which
     * is the ordering that lets the same frame use the repaired client.</p>
     */
    void pump(float deltaSeconds) {
        client();
    }
}
