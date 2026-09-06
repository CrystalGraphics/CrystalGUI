package com.crystalgui.net.command;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.net.protocol.Protocols;

import javax.annotation.Nullable;

/**
 * Wires {@code command/*} onto every connection — the contributor {@link Protocols} invokes.
 *
 * <h3>The side is decided by whether there is a peer, exactly as the workspace decides it</h3>
 *
 * <p>A <b>server</b> connection has a peer handle and offers commands; a <b>client</b> connection has
 * none and consumes them. Without that test a single-player process would bind both ends of its own
 * wire, so one side would answer {@code command/invoke} and the other would also try to, and whichever
 * registered first would win — the same failure {@code CgUiWorkspaceHost} already guards against, and it
 * is not hypothetical there.</p>
 *
 * <h3>Explicit registration, and one place to say no</h3>
 *
 * <p>{@link #register()} is called by a loader's common init, like every other contributor: a static
 * initialiser would make the protocol's existence depend on class-loading order, which this engine
 * refuses elsewhere for the same reason. {@link #setPolicy} is how a host declines server-driven commands
 * outright, and it has to be set <em>before</em> registration because a contributor only binds
 * connections opened after it — a policy changed later would apply to nobody currently connected, which
 * is the kind of half-applied setting that reads as the setting not working.</p>
 */
public final class CommandProtocolBinding {

    private static boolean registered;

    private static RemoteCommandPolicy policy = RemoteCommandPolicy.DEFAULT;

    @Nullable
    private static CommandRegistry target;

    private CommandProtocolBinding() {
    }

    /**
     * What a client will accept. Set before {@link #register()}.
     *
     * @param accepted {@link RemoteCommandPolicy#REFUSE_ALL} to take none
     */
    public static synchronized void setPolicy(RemoteCommandPolicy accepted) {
        if (registered) {
            // Refused rather than applied, because applying it would be a lie: contribution binds at
            // connection-open, so a change now reaches nothing already open and everything opened later,
            // which is a state nobody can reason about.
            throw new IllegalStateException("the command protocol is already registered; "
                    + "set the policy before register()");
        }
        policy = accepted == null ? RemoteCommandPolicy.DEFAULT : accepted;
    }

    /**
     * Where a client's server commands land. Defaults to {@link CommandRegistry#global()}.
     *
     * <p>A separate registry is the shape a host would want to quarantine them into — visible in a
     * palette section of its own rather than mixed with the application's own actions.</p>
     */
    public static synchronized void setRegistry(@Nullable CommandRegistry registry) {
        target = registry;
    }

    /** Contributes {@code command/*}. Idempotent. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Protocols.contribute("command", connection -> {
            if (connection.peer() == null) {
                RemoteCommands.install(connection,
                        target == null ? CommandRegistry.global() : target, policy);
            } else {
                ServerCommands.forConnection(connection);
            }
        });
        CrystalGuiCore.LOGGER.info("[command] contributed to the protocol");
    }

    /** <b>Tests only.</b> A live process registers once and never unregisters. */
    public static synchronized void resetForTesting() {
        registered = false;
        policy = RemoteCommandPolicy.DEFAULT;
        target = null;
    }
}
