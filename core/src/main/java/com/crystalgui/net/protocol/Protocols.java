package com.crystalgui.net.protocol;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.UITransport;
import com.crystalgui.serialization.DynamicOps;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where a subsystem says "I speak part of this protocol" — the one obvious place to register, and the
 * thing the protocol layer was missing.
 *
 * <p>The layers below this were already general: {@link Envelope} carries a method string and an opaque
 * payload, {@link MessageRouter} dispatches on the string, and neither names a UI type. What did not
 * exist was anywhere to <em>put</em> a handler: a router was reachable only by constructing a
 * {@code ServerUiSession}, so a workspace, a script runtime, or anything else on a dedicated server had a
 * protocol it could speak and no seat at it.</p>
 *
 * <h3>Two halves, after {@code ScriptRuntimes}</h3>
 *
 * <p>Deliberately the same shape as the language layer's registry, because it is the same problem: what
 * the shell can do is not something the shell can know. The <b>static</b> half is a set of contributors,
 * added once from a subsystem's own {@code register()}. The <b>instance</b> half is
 * {@link ProtocolConnection}, one per peer, built by {@link #open} with every contributor bound onto it.
 * <b>Adds, never replaces</b>, so registration order does not matter and two subsystems cannot evict each
 * other.</p>
 *
 * <h3>Why the registry is global and a router is not</h3>
 *
 * <p>CustomNPC+'s {@code PacketHandler} maps a packet type to a handler and is a singleton, which works
 * because a packet carries everything needed to act on it. A router holds pending requests correlated to
 * <em>one peer</em>, so it cannot be shared: two players with a call in flight would collide on ids. So
 * what is global is the list of contributors, and what is per-connection is everything stateful. A
 * subsystem still registers exactly once, at init, and never thinks about connections again.</p>
 *
 * <pre>{@code
 * // once, at mod init -- the CNPC+ moment
 * Protocols.contribute("workspace", new Protocols.Contributor() {
 *     @Override public <T> void bind(ProtocolConnection<T> connection) {
 *         new WorkspaceRpc<T>(service, actorFor(connection.peer())).installOn(connection::onRequest);
 *     }
 * });
 *
 * // per connection, wherever a peer appears
 * ProtocolConnection<Object> connection = Protocols.open(transport, PlainOps.INSTANCE, wire::pump, player);
 * // every tick
 * connection.tick();
 * }</pre>
 *
 * <h3>Method names are namespaced, and that is the whole collision story</h3>
 *
 * <p>{@code ui/*}, {@code workspace/*}, {@code script/*} — LSP's convention, already documented on
 * {@link UiMethods}. Two subsystems on one connection cannot collide unless they choose the same prefix,
 * and {@link MessageRouter} refuses a duplicate registration outright rather than letting the second win
 * silently.</p>
 */
public final class Protocols {

    /**
     * Binds one subsystem onto a connection.
     *
     * <p>A generic <em>method</em> rather than a generic interface, so one contributor serves a
     * connection of any ops — the real wire is {@code PlainOps}, a headless test may be {@code JsonOps},
     * and a contributor should not have to care. The cost is that this cannot be a lambda; it is an
     * anonymous class, which is still one class per <em>subsystem</em> rather than one per message.</p>
     */
    public interface Contributor {
        <T> void bind(ProtocolConnection<T> connection);
    }

    /** Insertion-ordered so binding order is reproducible, keyed so a double-register is visible. */
    private static final Map<String, Contributor> CONTRIBUTORS = new LinkedHashMap<>();

    private Protocols() {
    }

    /**
     * Says a subsystem speaks part of the protocol. Called by its own {@code register()}, once.
     *
     * @param name        what is contributing, for diagnostics — {@code "workspace"}, {@code "script"}
     * @param contributor invoked for every connection opened <b>after</b> this call — binding happens
     *                    in {@link #open}, so a connection that already exists does not get it. That is
     *                    why contribution belongs at mod init, before any peer can have arrived, and why
     *                    this is not a hot-swap mechanism
     * @throws IllegalStateException if {@code name} is already registered, which is a wiring mistake
     *                               rather than something to resolve silently
     */
    public static synchronized void contribute(String name, Contributor contributor) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a contributor needs a name");
        if (contributor == null) throw new IllegalArgumentException("contributor is null");
        Contributor previous = CONTRIBUTORS.putIfAbsent(name, contributor);
        if (previous != null) {
            throw new IllegalStateException("'" + name + "' already contributes to the protocol");
        }
        CrystalGuiCore.LOGGER.debug("Protocol contributor registered: {}", name);
    }

    /** What has registered. Diagnostics — the answer to "is my subsystem actually wired". */
    public static synchronized Set<String> contributors() {
        return Set.copyOf(CONTRIBUTORS.keySet());
    }

    /**
     * Opens a connection to one peer and binds every contributor onto it.
     *
     * @param transport the encoded-message transport for this peer
     * @param ops       its representation
     * @param pump      moves bytes underneath — {@code wireTransport::pump}, or a no-op in memory. Called
     *                  by {@link ProtocolConnection#tick()}, so nothing has to remember it separately
     * @param peer      the platform's player handle, or {@code null} on a client
     */
    public static synchronized <T> ProtocolConnection<T> open(
            UITransport<T> transport, DynamicOps<T> ops, Runnable pump, @Nullable Object peer) {
        ProtocolConnection<T> connection = new ProtocolConnection<>(transport, ops, pump, peer);
        for (Map.Entry<String, Contributor> entry : CONTRIBUTORS.entrySet()) {
            try {
                entry.getValue().bind(connection);
            } catch (RuntimeException failed) {
                // One broken contributor must not cost the connection every other subsystem on it.
                CrystalGuiCore.LOGGER.error("Protocol contributor '{}' failed to bind: {}",
                        entry.getKey(), failed.getMessage(), failed);
            }
        }
        return connection;
    }

    /** Drops every contributor. <b>Tests only</b> — a live process registers once and never unregisters. */
    public static synchronized void resetForTesting() {
        CONTRIBUTORS.clear();
    }
}
