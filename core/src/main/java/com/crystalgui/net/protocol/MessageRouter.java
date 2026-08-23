package com.crystalgui.net.protocol;

import com.crystalgui.core.CrystalGuiCore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Method name in, handler out — the thing that replaces every {@code instanceof} chain.
 *
 * <p>Registration lives next to the code that owns the method. The workspace registers
 * {@code workspace/*}, a session registers {@code ui/*}, and a script runtime in {@code language/}
 * registers {@code script/*} without {@code core} ever learning it exists. <b>Nothing enumerates the
 * set</b>, which is the property that makes adding a message one edit instead of four.</p>
 *
 * <pre>{@code
 * router.onRequest("workspace/read", (payload, respond) -> respond.ok(readFile(payload)));
 * router.onNotify ("ui/event",       payload -> dispatchToWidget(payload));
 *
 * router.request("workspace/read", path, onOk, onError);
 * router.notify ("ui/stateDelta", delta);
 * }</pre>
 *
 * <h3>Unknown methods are answered, not dropped</h3>
 *
 * <p>An unknown REQUEST gets {@link ProtocolErrors#METHOD_NOT_FOUND}; an unknown NOTIFICATION is logged
 * <em>once per method</em> and discarded. Both are deliberate and they differ because the two shapes
 * differ: somebody is waiting on the first and nobody on the second. Logging once rather than per message
 * matters because the common cause is a peer one version ahead sending something at frame rate.</p>
 *
 * <h3>Threading — the same contract as everything else here</h3>
 *
 * <p>Every method on this class runs on the thread that owns the tree. {@link #accept} is called from a
 * session's pump, never from the network thread — {@code FrameMultiplexer} already owns that hop, and
 * {@code Property}/{@code SignalBase} are single-threaded by documented contract. A handler that wants to
 * work off-thread responds later through its {@link Responder}, which is why replying is a callback and
 * not a return value.</p>
 */
public final class MessageRouter<T> {

    /** Handles one request. Reply through {@code respond}, which may be invoked later. */
    public interface RequestHandler<T> {
        void handle(@Nullable T payload, Responder<T> respond);
    }

    /** Handles one notification. There is nothing to reply with; that is what makes it a notification. */
    public interface NotificationHandler<T> {
        void handle(@Nullable T payload);
    }

    /** Answers a request. Exactly one of these, exactly once — later calls are ignored and logged. */
    public interface Responder<T> {
        void ok(@Nullable T payload);

        void fail(String error);
    }

    private final Consumer<Envelope> outbound;

    private final Map<String, RequestHandler<T>> requestHandlers = new LinkedHashMap<>();
    private final Map<String, NotificationHandler<T>> notificationHandlers = new LinkedHashMap<>();

    /** Requests we have sent and not yet had answered. */
    private final Map<Integer, Pending<T>> pending = new LinkedHashMap<>();

    /** Requests we are serving, so a {@link Envelope.Cancel} can find them. */
    private final Set<Integer> serving = new LinkedHashSet<>();

    /** Methods already complained about, so a chatty peer costs one line rather than thousands. */
    private final Set<String> warnedUnknown = new LinkedHashSet<>();

    private int nextRequestId = 1;

    public MessageRouter(Consumer<Envelope> outbound) {
        this.outbound = outbound;
    }

    // ── Registration ────────────────────────────────────────────────────────

    public MessageRouter<T> onRequest(String method, RequestHandler<T> handler) {
        // Refused rather than replaced: two registrations for one method is a wiring mistake, and the
        // symptom of silently keeping the last one is that whichever subsystem initialised second wins.
        if (requestHandlers.putIfAbsent(method, handler) != null) {
            throw new IllegalStateException("a request handler for '" + method + "' is already registered");
        }
        return this;
    }

    public MessageRouter<T> onNotify(String method, NotificationHandler<T> handler) {
        if (notificationHandlers.putIfAbsent(method, handler) != null) {
            throw new IllegalStateException("a notification handler for '" + method + "' is already registered");
        }
        return this;
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /**
     * Asks, and routes the answer to one of the two callbacks.
     *
     * @param deadlineMillis when to give up, or {@code 0} to wait indefinitely
     * @return the request id, for {@link #cancel}
     */
    public int request(String method, @Nullable T payload,
                       @Nullable Consumer<T> onOk, @Nullable Consumer<String> onError,
                       long deadlineMillis) {
        int id = nextRequestId++;
        // Wraps rather than overflowing negative: NO_ID is -1, and a negative id would collide with it.
        if (nextRequestId < 0) nextRequestId = 1;
        pending.put(id, new Pending<>(method, onOk, onError, deadlineMillis));
        outbound.accept(new Envelope.Request<>(id, method, payload));
        return id;
    }

    public int request(String method, @Nullable T payload,
                       @Nullable Consumer<T> onOk, @Nullable Consumer<String> onError) {
        return request(method, payload, onOk, onError, 0L);
    }

    public void notify(String method, @Nullable T payload) {
        outbound.accept(new Envelope.Notification<>(method, payload));
    }

    /**
     * Withdraws a request.
     *
     * <p>The local callbacks are dropped immediately and the peer is told. A late answer that crosses the
     * cancel is discarded by {@link #accept}, because its id is no longer pending — so a handler cannot
     * be re-entered by a reply it has already given up on.</p>
     */
    public void cancel(int id) {
        Pending<T> gone = pending.remove(id);
        if (gone == null) return;
        if (gone.onError != null) gone.onError.accept(ProtocolErrors.CANCELLED);
        outbound.accept(new Envelope.Cancel(id));
    }

    // ── Receiving ───────────────────────────────────────────────────────────

    /** <b>On the thread that owns the tree.</b> @see MessageRouter */
    @SuppressWarnings("unchecked")
    public void accept(Envelope envelope) {
        if (envelope instanceof Envelope.Request<?> request) {
            acceptRequest((Envelope.Request<T>) request);
        } else if (envelope instanceof Envelope.Response<?> response) {
            acceptResponse((Envelope.Response<T>) response);
        } else if (envelope instanceof Envelope.Notification<?> notification) {
            acceptNotification((Envelope.Notification<T>) notification);
        } else if (envelope instanceof Envelope.Cancel cancel) {
            // Best-effort by design: a handler already past the point of no return still answers, and the
            // sender has stopped listening. Removing it here is what stops a late reply being sent.
            serving.remove(cancel.id());
        }
    }

    private void acceptRequest(Envelope.Request<T> request) {
        RequestHandler<T> handler = requestHandlers.get(request.method());
        if (handler == null) {
            warnUnknown("request", request.method());
            outbound.accept(Envelope.Response.<T>failed(request.id(),
                    ProtocolErrors.METHOD_NOT_FOUND + ": " + request.method()));
            return;
        }
        int id = request.id();
        serving.add(id);
        Responder<T> responder = new OnceResponder<>(this, id, request.method());
        try {
            handler.handle(request.payload(), responder);
        } catch (RuntimeException failed) {
            // A throwing handler must still produce an answer, or the caller waits for its timeout on
            // what is really an immediate, local failure.
            CrystalGuiCore.LOGGER.warn("Handler for '{}' threw", request.method(), failed);
            responder.fail(ProtocolErrors.HANDLER_FAILED + ": " + failed);
        }
    }

    private void acceptResponse(Envelope.Response<T> response) {
        Pending<T> waiting = pending.remove(response.id());
        if (waiting == null) {
            // Timed out, cancelled, or a peer answering twice. Not an error worth raising -- all three
            // are ordinary races -- but worth one line, because a flood of them means a broken peer.
            CrystalGuiCore.LOGGER.debug("Response {} matched no pending request", response.id());
            return;
        }
        if (response.ok()) {
            if (waiting.onOk != null) waiting.onOk.accept(response.payload());
        } else if (waiting.onError != null) {
            waiting.onError.accept(response.error());
        }
    }

    private void acceptNotification(Envelope.Notification<T> notification) {
        NotificationHandler<T> handler = notificationHandlers.get(notification.method());
        if (handler == null) {
            warnUnknown("notification", notification.method());
            return;
        }
        try {
            handler.handle(notification.payload());
        } catch (RuntimeException failed) {
            // Nobody to tell. Swallowed rather than propagated so one bad notification cannot take down
            // the pump that is also delivering everything else.
            CrystalGuiCore.LOGGER.warn("Handler for notification '{}' threw", notification.method(), failed);
        }
    }

    private void warnUnknown(String shape, String method) {
        if (warnedUnknown.add(method)) {
            CrystalGuiCore.LOGGER.warn("No {} handler for '{}' — is the peer a different version?",
                    shape, method);
        }
    }

    // ── Timeouts ────────────────────────────────────────────────────────────

    /**
     * Fails anything past its deadline. Call from the session's pump; a router with no deadlines is free.
     *
     * @return how many requests were given up on
     */
    public int tickTimeouts(long nowMillis) {
        if (pending.isEmpty()) return 0;
        List<Integer> expired = null;
        for (Map.Entry<Integer, Pending<T>> entry : pending.entrySet()) {
            long deadline = entry.getValue().deadlineMillis;
            if (deadline > 0 && nowMillis >= deadline) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(entry.getKey());
            }
        }
        if (expired == null) return 0;
        for (int id : expired) {
            Pending<T> gone = pending.remove(id);
            if (gone != null && gone.onError != null) gone.onError.accept(ProtocolErrors.TIMEOUT);
        }
        return expired.size();
    }

    /** Fails every outstanding request. For a connection that has gone away. */
    public void failAllPending(String error) {
        List<Pending<T>> all = new ArrayList<>(pending.values());
        pending.clear();
        serving.clear();
        for (Pending<T> gone : all) {
            if (gone.onError != null) gone.onError.accept(error);
        }
    }

    public int pendingRequests() {
        return pending.size();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void answer(int id, String method, boolean ok, @Nullable T payload, String error) {
        // Silently dropped if the request was cancelled: the peer stopped listening, and sending anyway
        // would be a response with no pending id on the far side -- noise at best.
        if (!serving.remove(id)) return;
        outbound.accept(ok
                ? Envelope.Response.ok(id, payload)
                : Envelope.Response.<T>failed(id, error));
    }

    /**
     * Enforces "exactly once".
     *
     * <p>A handler that answers twice is a bug that otherwise produces a second response matching no
     * pending request on the far side — which surfaces as a confusing debug line about an unmatched id,
     * a long way from the handler that caused it. Caught here, where the name of the method is known.</p>
     */
    private static final class OnceResponder<T> implements Responder<T> {
        private final MessageRouter<T> router;
        private final int id;
        private final String method;
        private boolean answered;

        OnceResponder(MessageRouter<T> router, int id, String method) {
            this.router = router;
            this.id = id;
            this.method = method;
        }

        @Override
        public void ok(@Nullable T payload) {
            if (claim()) router.answer(id, method, true, payload, "");
        }

        @Override
        public void fail(String error) {
            if (claim()) router.answer(id, method, false, null, error);
        }

        private boolean claim() {
            if (answered) {
                CrystalGuiCore.LOGGER.warn("Handler for '{}' answered more than once; ignoring", method);
                return false;
            }
            answered = true;
            return true;
        }
    }

    private record Pending<T>(String method,
                              @Nullable Consumer<T> onOk,
                              @Nullable Consumer<String> onError,
                              long deadlineMillis) {
    }
}
