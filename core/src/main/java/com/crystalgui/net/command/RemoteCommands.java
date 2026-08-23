package com.crystalgui.net.command;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The client half of {@code command/*} — server-contributed commands, in this client's registry.
 *
 * <p>A contributed command is an ordinary {@link Command}: the palette enumerates it, a menu renders it,
 * the keymap resolves it, and nothing downstream knows or cares that running it sends a packet. That is
 * the point — a server-driven action should not be a second kind of action with its own surface.</p>
 *
 * <h3>Enablement is a local cache, not a question</h3>
 *
 * <p>{@code isEnabled} is consulted while a menu is being built, so it has to answer immediately. The
 * server pushes {@link CommandProtocol#SET_ENABLED} and this holds the answer; asking across the wire as
 * the menu opened would put a round trip inside a UI gesture, which is worse than being one tick stale.
 * An id nobody has said anything about is <b>enabled</b> — the same reasoning 5.4 needs, and for the same
 * reason: a wrongly-greyed command is a thing the user cannot do and cannot explain, while a wrongly-live
 * one fails with a message the server wrote.</p>
 *
 * <h3>Refusals are per command and are said out loud</h3>
 *
 * <p>One entry refused must not cost the rest of the batch. And a refusal is logged with its reason,
 * because a command that silently never appears is indistinguishable from a server that never sent it —
 * the same "live and inert look identical" trap the engine bands already record.</p>
 *
 * @param <T> the encoded representation, matching the connection's {@code DynamicOps}
 */
public final class RemoteCommands<T> {

    private final ProtocolConnection<T> connection;
    private final CommandRegistry registry;
    private final RemoteCommandPolicy policy;

    /** Ids this connection contributed, in arrival order, so {@link #withdrawAll} is exact. */
    private final Set<String> contributed = new LinkedHashSet<>();

    /** Last enablement the server pushed. Absent means enabled. @see #isEnabled */
    private final Map<String, Boolean> enablement = new LinkedHashMap<>();

    @Nullable
    private Consumer<String> onFailure;

    private RemoteCommands(ProtocolConnection<T> connection, CommandRegistry registry,
                           RemoteCommandPolicy policy) {
        this.connection = connection;
        this.registry = registry;
        this.policy = policy;
        connection.onNotify(CommandProtocol.CONTRIBUTE, this::acceptContribution);
        connection.onNotify(CommandProtocol.WITHDRAW, this::acceptWithdrawal);
        connection.onNotify(CommandProtocol.SET_ENABLED, this::acceptEnablement);
    }

    /**
     * Listens on {@code connection} and installs what it contributes into {@code registry}.
     *
     * <p>Not memoised, unlike {@code WorkspaceClient.forConnection}: a second of these on one connection
     * would register {@code command/contribute} twice and the router refuses a duplicate, which is the
     * right failure and needs no help. The registry is a parameter rather than
     * {@code CommandRegistry.global()} so a test — and a host that wants server commands quarantined —
     * can say where they land.</p>
     */
    public static <T> RemoteCommands<T> install(ProtocolConnection<T> connection,
                                                CommandRegistry registry) {
        return install(connection, registry, RemoteCommandPolicy.DEFAULT);
    }

    /** @see #install(ProtocolConnection, CommandRegistry) */
    public static <T> RemoteCommands<T> install(ProtocolConnection<T> connection,
                                                CommandRegistry registry,
                                                RemoteCommandPolicy policy) {
        return new RemoteCommands<>(connection, registry, policy);
    }

    /**
     * Told when an invocation is refused, with the server's reason.
     *
     * <p>Where a host shows it is the host's business — a notification balloon, the status bar. But it
     * has to be told: a command that fails silently is indistinguishable from a keybinding that was
     * never wired up, which is the most confusing failure a command system can have.</p>
     */
    public RemoteCommands<T> onFailure(Consumer<String> handler) {
        this.onFailure = handler;
        return this;
    }

    /** Ids currently contributed by this connection. */
    public Set<String> contributed() {
        return new LinkedHashSet<>(contributed);
    }

    /** Whether the server last said this one was available. Unknown means yes. @see RemoteCommands */
    public boolean isEnabled(String id) {
        Boolean known = enablement.get(id);
        return known == null || known.booleanValue();
    }

    /**
     * Removes everything this connection contributed.
     *
     * <p><b>Call when the peer goes away.</b> A disconnected server's commands are still in the palette
     * and still look live; running one waits out its call timeout and then reports a failure whose real
     * cause — that there is nobody to ask — happened minutes earlier.</p>
     */
    public void withdrawAll() {
        for (String id : contributed) registry.unregister(id);
        contributed.clear();
        enablement.clear();
    }

    // ── Inbound ─────────────────────────────────────────────────────────────────────────────────

    private void acceptContribution(StateMap<T> in) {
        for (StateMap<T> entry : in.getList(CommandProtocol.COMMANDS, e -> e)) {
            String id = entry.getString(CommandProtocol.ID, "");

            String refusal = policy.refuse(id);
            if (refusal != null) {
                CrystalGuiCore.LOGGER.warn("[command] refused a server command: {}", refusal);
                continue;
            }
            if (!contributed.contains(id) && contributed.size() >= RemoteCommandPolicy.MAX_COMMANDS) {
                CrystalGuiCore.LOGGER.warn("[command] refused '{}': this connection has already "
                        + "contributed {} commands", id, RemoteCommandPolicy.MAX_COMMANDS);
                continue;
            }

            String label = policy.sanitiseLabel(entry.getString(CommandProtocol.LABEL, ""), id);
            if (entry.has(CommandProtocol.ENABLED)) {
                enablement.put(id, entry.getBool(CommandProtocol.ENABLED, true));
            }

            // The id is captured; the enablement is READ AT ASK TIME out of the map. Capturing the
            // boolean would freeze whatever was true when the command was contributed, and then
            // command/setEnabled would arrive, update nothing anybody reads, and the menu would go on
            // rendering the first answer forever.
            final String bound = id;
            registry.register(Command.of(id, label)
                    .run(context -> invoke(bound))
                    .enabledWhen(context -> isEnabled(bound)));
            contributed.add(id);
        }
    }

    private void acceptWithdrawal(StateMap<T> in) {
        for (StateMap<T> entry : in.getList(CommandProtocol.COMMANDS, e -> e)) {
            String id = entry.getString(CommandProtocol.ID, "");
            // Only what THIS connection contributed. A server naming an id it never registered must not
            // be able to unregister one of ours -- which the namespace floor already prevents, and this
            // makes true a second way, because the floor is one string comparison away from a typo.
            if (!contributed.remove(id)) continue;
            registry.unregister(id);
            enablement.remove(id);
        }
    }

    private void acceptEnablement(StateMap<T> in) {
        for (StateMap<T> entry : in.getList(CommandProtocol.COMMANDS, e -> e)) {
            String id = entry.getString(CommandProtocol.ID, "");
            if (!contributed.contains(id)) continue;
            enablement.put(id, entry.getBool(CommandProtocol.ENABLED, true));
        }
    }

    // ── Outbound ────────────────────────────────────────────────────────────────────────────────

    private void invoke(String id) {
        StateMap<T> args = new StateMap<>(connection.ops());
        args.putString(CommandProtocol.ID, id);
        connection.call(CommandProtocol.INVOKE, args, null, error -> {
            CrystalGuiCore.LOGGER.warn("[command] '{}' was refused: {}", id, error);
            if (onFailure != null) onFailure.accept(error);
        });
    }
}
