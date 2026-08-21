package com.crystalgui.net.protocol;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.UITransport;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * One peer's end of the protocol — a {@link MessageRouter} with a transport under it and a name for who
 * is on the other side.
 *
 * <p>This is the thing a subsystem binds to. It exists because a router was previously reachable only by
 * constructing a {@code ServerUiSession}: the protocol layer was general, the <em>wiring</em> was not, so
 * a workspace or a script runtime on a dedicated server had nowhere to register.</p>
 *
 * <h3>Why this is per-connection and CustomNPC+'s {@code PacketHandler} is global</h3>
 *
 * <p>Their registry maps a packet type to a handler and can be a singleton, because a packet carries
 * everything needed to act on it. A router cannot: it holds <b>pending requests correlated to one peer</b>,
 * a stream-id space, credit, and timeouts. Two players sharing a router would collide on ids the first
 * time both had a call in flight. So the <em>registry</em> is global — see {@link Protocols} — and what
 * it registers are contributors that are invoked once per connection, against one of these.</p>
 *
 * <h3>{@link #tick()} does all three steps</h3>
 *
 * <p>Pump the wire, drain what arrived, expire what timed out. Deliberately one call: a subsystem that
 * had to remember to pump its transport separately would receive nothing, silently, which is the exact
 * failure shape this codebase keeps paying for elsewhere.</p>
 *
 * @param <T> the encoded representation, matching the transport's {@code DynamicOps}
 */
public final class ProtocolConnection<T> {

    private final MessageRouter<T> router;
    private final DynamicOps<T> ops;
    private final Runnable pump;
    @Nullable
    private final Object peer;

    /** Arrives on the transport's thread, drained on ours. See {@link UITransport}. */
    private final Deque<T> mailbox = new ArrayDeque<>();

    private long callTimeoutMillis = 10_000L;

    ProtocolConnection(UITransport<T> transport, DynamicOps<T> ops, Runnable pump, @Nullable Object peer) {
        this.ops = ops;
        this.pump = pump;
        this.peer = peer;
        this.router = new MessageRouter<>(envelope -> transport.send(EnvelopeCodec.encode(ops, envelope)));
        transport.setReceiver(raw -> {
            synchronized (mailbox) {
                mailbox.add(raw);
            }
        });
    }

    /**
     * The platform's handle for who is on the other end — an {@code EntityPlayerMP} on 1.7.10, a
     * {@code ServerPlayer} on 1.20.x, and {@code null} on a client, where there is only ever one peer.
     *
     * <p>Opaque here for the same reason it is opaque on {@code CgNetworkChannel}: {@code core} cannot
     * name a Minecraft type, and a subsystem that needs one casts at its own loader.</p>
     */
    @Nullable
    public Object peer() {
        return peer;
    }

    public DynamicOps<T> ops() {
        return ops;
    }

    /** The router itself, for anything the conveniences below do not cover. */
    public MessageRouter<T> router() {
        return router;
    }

    /** How long this connection's calls wait before their error handler is told. */
    public ProtocolConnection<T> setCallTimeoutMillis(long millis) {
        this.callTimeoutMillis = millis;
        return this;
    }

    // ── Registering ─────────────────────────────────────────────────────────

    /**
     * Serves a method. The handler must answer exactly once — the router enforces it.
     *
     * <p>Shaped as {@link Call.Handler} rather than {@link MessageRouter.RequestHandler} so that
     * {@code WorkspaceRpc.Registrar} and {@code ServerUiSession::onCall} remain interchangeable: a
     * subsystem is written once and installs onto either.</p>
     */
    public ProtocolConnection<T> onRequest(String method, Call.Handler<T> handler) {
        router.onRequest(method, (payload, respond) ->
                handler.invoke(read(payload), new Call.Responder<T>() {
                    @Override
                    public void ok(@Nullable StateMap<T> value) {
                        respond.ok(value == null ? null : value.encode());
                    }

                    @Override
                    public void fail(String error) {
                        respond.fail(error);
                    }
                }));
        return this;
    }

    /** Listens for a notification. Nothing is sent back — that is what makes it one. */
    public ProtocolConnection<T> onNotify(String method, Consumer<StateMap<T>> handler) {
        router.onNotify(method, payload -> handler.accept(read(payload)));
        return this;
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /** Asks the peer and expects an answer. */
    public void call(String method, @Nullable StateMap<T> args,
                     @Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError) {
        router.request(method, args == null ? null : args.encode(),
                value -> {
                    if (onResult != null) onResult.accept(read(value));
                },
                onError,
                System.currentTimeMillis() + callTimeoutMillis);
    }

    /** Tells the peer. Nothing comes back, and nothing may be waited on. */
    public void notify(String method, @Nullable StateMap<T> payload) {
        router.notify(method, payload == null ? null : payload.encode());
    }

    // ── Running ─────────────────────────────────────────────────────────────

    /**
     * Pump, drain, expire. <b>Call once per tick, on the thread that owns whatever the handlers touch.</b>
     *
     * @return how many messages were dispatched
     */
    public int tick() {
        pump.run();

        List<T> batch;
        synchronized (mailbox) {
            if (mailbox.isEmpty()) {
                router.tickTimeouts(System.currentTimeMillis());
                return 0;
            }
            batch = new ArrayList<>(mailbox);
            mailbox.clear();
        }

        int dispatched = 0;
        for (T raw : batch) {
            Envelope envelope;
            try {
                envelope = EnvelopeCodec.decode(ops, raw);
            } catch (RuntimeException malformed) {
                // Per message, so one unreadable frame cannot take the rest of the batch with it.
                CrystalGuiCore.LOGGER.warn("Dropping an undecodable message: {}", malformed.getMessage());
                continue;
            }
            router.accept(envelope);
            dispatched++;
        }
        router.tickTimeouts(System.currentTimeMillis());
        return dispatched;
    }

    /** Fails everything outstanding. Call when the peer goes away, or every caller waits out its timeout. */
    public void close(String reason) {
        router.failAllPending(reason);
    }

    private StateMap<T> read(@Nullable T payload) {
        return payload == null ? new StateMap<>(ops) : new StateMap<>(ops, payload);
    }
}
