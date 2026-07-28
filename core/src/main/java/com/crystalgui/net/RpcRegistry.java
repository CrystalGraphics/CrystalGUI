package com.crystalgui.net;

import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Named, typed calls in either direction, with optional replies.
 *
 * <h3>Timeouts are not optional</h3>
 * <p>A call awaiting a reply parks its callback in a table. If the reply is lost — a dropped packet,
 * a peer that closed mid-call — that entry lives forever, and so does everything it captured.
 * LDLib2 has exactly this hole. {@link #sweepTimeouts} closes it by failing the call instead, so a
 * caller always hears something.</p>
 *
 * <h3>Unknown methods answer, rather than going quiet</h3>
 * <p>A call for a method the peer doesn't have returns an error result. Dropping it silently would
 * be indistinguishable from a slow reply, and the caller would wait out its whole timeout for
 * something that was never going to arrive.</p>
 */
public final class RpcRegistry<T> {

    /** Handles one call. Reply through {@code respond}, which may be invoked later. */
    public interface Handler<T> {
        void invoke(StateMap<T> args, Responder<T> respond);
    }

    /** Answers a call. Exactly one of these should be called, once. */
    public interface Responder<T> {
        void ok(@Nullable StateMap<T> value);

        void fail(String error);
    }

    private record Pending<T>(@Nullable Consumer<StateMap<T>> onResult,
                              @Nullable Consumer<String> onError,
                              long deadlineMillis) {
    }

    private final DynamicOps<T> ops;
    private final Map<String, Handler<T>> handlers = new LinkedHashMap<>();
    private final Map<Integer, Pending<T>> pending = new LinkedHashMap<>();

    private int nextCallId = 1;   // 0 means "no reply wanted"
    private long timeoutMillis = 10_000L;

    public RpcRegistry(DynamicOps<T> ops) {
        this.ops = ops;
    }

    public RpcRegistry<T> register(String method, Handler<T> handler) {
        if (handlers.putIfAbsent(method, handler) != null) {
            throw new IllegalArgumentException("RPC method already registered: " + method);
        }
        return this;
    }

    public RpcRegistry<T> setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Allocates a call id and parks the callbacks. {@code 0} when no reply was asked for. */
    int beginCall(@Nullable Consumer<StateMap<T>> onResult, @Nullable Consumer<String> onError, long nowMillis) {
        if (onResult == null && onError == null) return 0;
        int callId = nextCallId++;
        pending.put(callId, new Pending<>(onResult, onError, nowMillis + timeoutMillis));
        return callId;
    }

    /** Runs the named handler and answers through {@code respond}. */
    void dispatch(String method, @Nullable T args, Responder<T> respond) {
        Handler<T> handler = handlers.get(method);
        if (handler == null) {
            respond.fail("unknown method '" + method + "'");
            return;
        }
        StateMap<T> decoded = args == null ? new StateMap<>(ops) : new StateMap<>(ops, args);
        try {
            handler.invoke(decoded, respond);
        } catch (RuntimeException e) {
            respond.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Completes a parked call. Unknown ids are ignored — a duplicate or late reply is not fatal. */
    void complete(int callId, boolean ok, @Nullable T value, String error) {
        Pending<T> parked = pending.remove(callId);
        if (parked == null) return;
        if (ok) {
            if (parked.onResult() != null) {
                parked.onResult().accept(value == null ? new StateMap<>(ops) : new StateMap<>(ops, value));
            }
        } else if (parked.onError() != null) {
            parked.onError().accept(error);
        }
    }

    /** Fails every call past its deadline. Called once per tick. */
    void sweepTimeouts(long nowMillis) {
        List<Integer> expired = new ArrayList<>();
        for (var entry : pending.entrySet()) {
            if (nowMillis >= entry.getValue().deadlineMillis()) expired.add(entry.getKey());
        }
        for (int callId : expired) {
            Pending<T> parked = pending.remove(callId);
            if (parked != null && parked.onError() != null) parked.onError().accept("timeout");
        }
    }
}
