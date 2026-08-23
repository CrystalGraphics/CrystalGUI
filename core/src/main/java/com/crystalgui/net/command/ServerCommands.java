package com.crystalgui.net.command;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.Call;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.StateMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The server half of {@code command/*} — actions this server offers one client.
 *
 * <h3>Per connection, and that is not incidental</h3>
 *
 * <p>Two players are not offered the same set: an operator gets <i>Reload Config</i> and nobody else
 * does. So contribution is a property of the connection rather than of the server, which also means the
 * enablement push has somewhere obvious to live — the same object already knows who it is talking to.</p>
 *
 * <h3>The handler is a {@link Call.Handler}, deliberately</h3>
 *
 * <p>The same shape {@code ProtocolConnection.onRequest} and {@code ServerUiSession.onCall} take, so an
 * action is written once and installed against whichever of the three suits it. That interchangeability
 * is why {@code Call.Handler} exists at all rather than each surface having its own functional type.</p>
 *
 * @param <T> the encoded representation, matching the connection's {@code DynamicOps}
 */
public final class ServerCommands<T> {

    /**
     * One per connection, keyed weakly.
     *
     * <p>Two of these would both register {@code command/invoke} and the second would throw. Same shape
     * and same reason as {@code WorkspaceClient.forConnection}, which was itself written after two
     * clients on one connection threw on a duplicate {@code fs.changed}.</p>
     */
    private static final Map<ProtocolConnection<?>, ServerCommands<?>> BY_CONNECTION = new WeakHashMap<>();

    private final ProtocolConnection<T> connection;
    private final Map<String, Call.Handler<T>> handlers = new LinkedHashMap<>();

    private ServerCommands(ProtocolConnection<T> connection) {
        this.connection = connection;
        connection.onRequest(CommandProtocol.INVOKE, (args, respond) -> {
            String id = args.getString(CommandProtocol.ID, "");
            Call.Handler<T> handler = handlers.get(id);
            if (handler == null) {
                // ANSWERED, not dropped. A command withdrawn between the menu opening and the user
                // clicking is an ordinary race, and the client has to be told it lost -- otherwise the
                // call waits out its deadline and reports a timeout, which reads as a slow server.
                respond.fail("no such command: '" + id + "'");
                return;
            }
            handler.invoke(args, respond);
        });
    }

    /** The commands offered to this connection, created on first use. */
    @SuppressWarnings("unchecked")
    public static synchronized <T> ServerCommands<T> forConnection(ProtocolConnection<T> connection) {
        ServerCommands<?> existing = BY_CONNECTION.get(connection);
        if (existing != null) return (ServerCommands<T>) existing;
        ServerCommands<T> created = new ServerCommands<>(connection);
        BY_CONNECTION.put(connection, created);
        return created;
    }

    /**
     * Offers a command, and tells the client immediately.
     *
     * <p>Re-contributing an id replaces it, matching {@code CommandRegistry.register} on the other side —
     * which is how a label changes without a withdraw/contribute pair racing whatever is rendering.</p>
     *
     * <p><b>The id must be in the reserved namespace</b> or the client will refuse it. Checked here as
     * well, and thrown rather than logged, because a server author's typo should fail where it was typed
     * rather than as a command that mysteriously never appears on somebody else's machine.</p>
     */
    public ServerCommands<T> contribute(String id, String label, Call.Handler<T> handler) {
        String refusal = RemoteCommandPolicy.DEFAULT.refuse(id);
        if (refusal != null) throw new IllegalArgumentException(refusal);
        handlers.put(id, handler);
        send(CommandProtocol.CONTRIBUTE, Collections.singletonList(id), entry -> {
            entry.putString(CommandProtocol.LABEL, label);
            entry.putBool(CommandProtocol.ENABLED, true);
        });
        return this;
    }

    /**
     * Says whether a command is currently available.
     *
     * <p>Pushed rather than answered on demand: the client consults enablement while it is building a
     * menu, and a round trip inside a UI gesture is worse than being one tick stale.</p>
     */
    public ServerCommands<T> setEnabled(String id, boolean enabled) {
        send(CommandProtocol.SET_ENABLED, Collections.singletonList(id),
                entry -> entry.putBool(CommandProtocol.ENABLED, enabled));
        return this;
    }

    /** Takes a command back. The client unregisters it. */
    public ServerCommands<T> withdraw(String id) {
        handlers.remove(id);
        send(CommandProtocol.WITHDRAW, Collections.singletonList(id), entry -> { });
        return this;
    }

    /** Everything currently offered to this connection. */
    public Set<String> offered() {
        return new java.util.LinkedHashSet<>(handlers.keySet());
    }

    private void send(String method, List<String> ids, java.util.function.Consumer<StateMap<T>> fill) {
        StateMap<T> payload = new StateMap<>(connection.ops());
        payload.putList(CommandProtocol.COMMANDS, new ArrayList<>(ids), (entry, id) -> {
            entry.putString(CommandProtocol.ID, id);
            fill.accept(entry);
        });
        connection.notify(method, payload);
        CrystalGuiCore.LOGGER.debug("[command] {} {}", method, ids);
    }
}
