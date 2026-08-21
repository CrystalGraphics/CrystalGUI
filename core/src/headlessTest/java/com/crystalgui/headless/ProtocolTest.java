package com.crystalgui.headless;

import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.MessageRouter;
import com.crystalgui.net.protocol.ProtocolErrors;
import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.PlainOps;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The envelope and the router — headless, which is where the server contract lives.
 */
public class ProtocolTest {

    private static Object payload(String tag) {
        Map<Object, Object> value = new LinkedHashMap<>();
        value.put("tag", tag);
        return value;
    }

    private static Envelope roundTrip(Envelope envelope) {
        Object encoded = EnvelopeCodec.encode(PlainOps.INSTANCE, envelope);
        return EnvelopeCodec.decode(PlainOps.INSTANCE, encoded);
    }

    // ── The envelope ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    public void allFourKindsRoundTrip() {
        Envelope.Request<Object> request =
                (Envelope.Request<Object>) roundTrip(new Envelope.Request<>(7, "workspace/read", payload("a")));
        assertEquals(7, request.id());
        assertEquals("workspace/read", request.method());
        assertEquals(payload("a"), request.payload());

        Envelope.Response<Object> ok =
                (Envelope.Response<Object>) roundTrip(Envelope.Response.ok(7, payload("b")));
        assertEquals(7, ok.id());
        assertTrue(ok.ok());
        assertEquals(payload("b"), ok.payload());

        Envelope.Response<Object> failed =
                (Envelope.Response<Object>) roundTrip(Envelope.Response.failed(7, "nope"));
        assertEquals(false, failed.ok());
        assertEquals("nope", failed.error());
        assertNull(failed.payload());

        Envelope.Notification<Object> notification =
                (Envelope.Notification<Object>) roundTrip(new Envelope.Notification<>("ui/stateDelta", payload("c")));
        assertEquals("ui/stateDelta", notification.method());
        assertEquals(payload("c"), notification.payload());

        assertEquals(9, ((Envelope.Cancel) roundTrip(new Envelope.Cancel(9))).id());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aPayloadIsOptionalAndSurvivesAsNull() {
        // The envelope must carry "no payload" distinctly from "empty payload": a notification with no
        // arguments is ordinary, and inventing an empty map for it would make handlers unwrap nothing.
        Envelope.Notification<Object> bare =
                (Envelope.Notification<Object>) roundTrip(new Envelope.Notification<>("ui/closeWindow", null));
        assertNull(bare.payload());
        assertEquals("ui/closeWindow", bare.method());
    }

    @Test(expected = CodecException.class)
    public void anUnknownEnvelopeKindIsRefused() {
        Map<Object, Object> hostile = new LinkedHashMap<>();
        hostile.put("k", "z");
        EnvelopeCodec.decode(PlainOps.INSTANCE, hostile);
    }

    /**
     * The payload is carried, never inspected — which is what lets a subsystem change its own wire format
     * without touching the envelope codec.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void thePayloadIsNotInterpreted() {
        Map<Object, Object> weird = new LinkedHashMap<>();
        weird.put("k", "this key would collide with the envelope's own");
        weird.put("i", "so would this, and it is a String where the envelope uses an int");
        Envelope.Notification<Object> out =
                (Envelope.Notification<Object>) roundTrip(new Envelope.Notification<>("x/y", weird));
        assertEquals(weird, out.payload());
    }

    // ── The router ──────────────────────────────────────────────────────────

    /** Two routers wired to each other, which is what a connection is once the transport is below it. */
    private static final class Pair {
        final MessageRouter<Object> a;
        final MessageRouter<Object> b;

        Pair() {
            MessageRouter<Object>[] slot = new MessageRouter[2];
            slot[0] = new MessageRouter<>(envelope -> slot[1].accept(roundTrip(envelope)));
            slot[1] = new MessageRouter<>(envelope -> slot[0].accept(roundTrip(envelope)));
            a = slot[0];
            b = slot[1];
        }
    }

    @Test
    public void aRequestReachesItsHandlerAndTheAnswerComesBack() {
        Pair pair = new Pair();
        pair.b.onRequest("workspace/read", (in, respond) -> respond.ok(payload("contents")));

        List<Object> got = new ArrayList<>();
        pair.a.request("workspace/read", payload("path"), got::add, error -> fail("failed: " + error));

        assertEquals(1, got.size());
        assertEquals(payload("contents"), got.get(0));
        assertEquals("nothing left outstanding", 0, pair.a.pendingRequests());
    }

    @Test
    public void aNotificationReachesItsHandlerAndAnswersNothing() {
        Pair pair = new Pair();
        List<Object> got = new ArrayList<>();
        pair.b.onNotify("ui/event", got::add);

        pair.a.notify("ui/event", payload("click"));

        assertEquals(1, got.size());
        assertEquals(payload("click"), got.get(0));
        assertEquals("a notification creates no pending state", 0, pair.a.pendingRequests());
    }

    /**
     * The reason request and notification are structurally different.
     *
     * <p>Someone is waiting on a request, so an unknown method must still produce an answer. Under the
     * old {@code instanceof} chain it fell off the end and the caller waited for a timeout.</p>
     */
    @Test
    public void anUnknownRequestIsAnsweredRatherThanDropped() {
        Pair pair = new Pair();
        List<String> errors = new ArrayList<>();
        pair.a.request("nobody/handles", null, ok -> fail("should not succeed"), errors::add);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith(ProtocolErrors.METHOD_NOT_FOUND));
        assertTrue("names the method", errors.get(0).contains("nobody/handles"));
    }

    @Test
    public void anUnknownNotificationIsDroppedWithoutAnswering() {
        Pair pair = new Pair();
        List<Object> outbound = new ArrayList<>();
        MessageRouter<Object> lonely = new MessageRouter<>(outbound::add);
        lonely.accept(new Envelope.Notification<>("nobody/handles", null));
        assertEquals("nothing may be sent in reply to a notification", 0, outbound.size());
    }

    @Test
    public void aThrowingHandlerStillAnswers() {
        Pair pair = new Pair();
        pair.b.onRequest("boom", (in, respond) -> {
            throw new IllegalStateException("handler exploded");
        });

        List<String> errors = new ArrayList<>();
        pair.a.request("boom", null, ok -> fail("should not succeed"), errors::add);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith(ProtocolErrors.HANDLER_FAILED));
    }

    @Test
    public void aHandlerMayAnswerLater() {
        // The whole reason replying is a callback rather than a return value: a workspace read or a
        // script compile answers off the frame it was asked on.
        Pair pair = new Pair();
        List<MessageRouter.Responder<Object>> deferred = new ArrayList<>();
        pair.b.onRequest("slow", (in, respond) -> deferred.add(respond));

        List<Object> got = new ArrayList<>();
        pair.a.request("slow", null, got::add, error -> fail(error));

        assertEquals("must not have answered yet", 0, got.size());
        assertEquals(1, pair.a.pendingRequests());

        deferred.get(0).ok(payload("eventually"));
        assertEquals(1, got.size());
        assertEquals(payload("eventually"), got.get(0));
    }

    @Test
    public void answeringTwiceIsIgnoredRatherThanSendingTwoResponses() {
        Pair pair = new Pair();
        pair.b.onRequest("twice", (in, respond) -> {
            respond.ok(payload("first"));
            respond.ok(payload("second"));
            respond.fail("and an error for good measure");
        });

        List<Object> got = new ArrayList<>();
        pair.a.request("twice", null, got::add, error -> fail(error));

        assertEquals("exactly one answer reaches the caller", 1, got.size());
        assertEquals(payload("first"), got.get(0));
    }

    @Test
    public void aDuplicateRegistrationIsRefused() {
        // Silently keeping the last one means whichever subsystem initialises second wins, which is a
        // wiring bug that presents as a feature intermittently not working.
        MessageRouter<Object> router = new MessageRouter<>(envelope -> { });
        router.onRequest("a/b", (in, respond) -> respond.ok(null));
        try {
            router.onRequest("a/b", (in, respond) -> respond.ok(null));
            fail("expected a refusal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("a/b"));
        }
    }

    @Test
    public void requestsAndNotificationsHaveSeparateNamespaces() {
        // Same name, different shape: nothing about "ui/x" as a request implies anything about "ui/x" as
        // a notification, and refusing that would be an arbitrary restriction.
        MessageRouter<Object> router = new MessageRouter<>(envelope -> { });
        router.onRequest("ui/x", (in, respond) -> respond.ok(null));
        router.onNotify("ui/x", in -> { });
    }

    // ── Correlation, cancellation, timeouts ─────────────────────────────────

    @Test
    public void concurrentRequestsCorrelateIndependently() {
        Pair pair = new Pair();
        List<MessageRouter.Responder<Object>> deferred = new ArrayList<>();
        pair.b.onRequest("slow", (in, respond) -> deferred.add(respond));

        List<Object> first = new ArrayList<>();
        List<Object> second = new ArrayList<>();
        pair.a.request("slow", payload("1"), first::add, error -> fail(error));
        pair.a.request("slow", payload("2"), second::add, error -> fail(error));

        assertEquals(2, pair.a.pendingRequests());
        // Answered out of order, which is the case an id exists for.
        deferred.get(1).ok(payload("second"));
        assertEquals(0, first.size());
        assertEquals(1, second.size());
        assertEquals(payload("second"), second.get(0));

        deferred.get(0).ok(payload("first"));
        assertEquals(payload("first"), first.get(0));
    }

    @Test
    public void cancellingDropsTheCallbacksAndALateAnswerIsIgnored() {
        Pair pair = new Pair();
        List<MessageRouter.Responder<Object>> deferred = new ArrayList<>();
        pair.b.onRequest("slow", (in, respond) -> deferred.add(respond));

        List<String> errors = new ArrayList<>();
        int id = pair.a.request("slow", null, ok -> fail("cancelled request must not succeed"), errors::add);

        pair.a.cancel(id);
        assertEquals(1, errors.size());
        assertEquals(ProtocolErrors.CANCELLED, errors.get(0));
        assertEquals(0, pair.a.pendingRequests());

        // The handler was already running and answers anyway. Nothing may reach the caller.
        deferred.get(0).ok(payload("too late"));
        assertEquals("no second callback", 1, errors.size());
    }

    @Test
    public void aRequestPastItsDeadlineFails() {
        Pair pair = new Pair();
        pair.b.onRequest("slow", (in, respond) -> { /* never answers */ });

        List<String> errors = new ArrayList<>();
        pair.a.request("slow", null, ok -> fail("should not succeed"), errors::add, 1_000L);

        assertEquals("not yet due", 0, pair.a.tickTimeouts(999L));
        assertEquals(1, pair.a.tickTimeouts(1_000L));
        assertEquals(1, errors.size());
        assertEquals(ProtocolErrors.TIMEOUT, errors.get(0));
        assertEquals(0, pair.a.pendingRequests());
    }

    @Test
    public void aRequestWithNoDeadlineIsNeverTimedOut() {
        Pair pair = new Pair();
        pair.b.onRequest("slow", (in, respond) -> { });
        pair.a.request("slow", null, ok -> { }, error -> fail(error));
        assertEquals(0, pair.a.tickTimeouts(Long.MAX_VALUE));
        assertEquals(1, pair.a.pendingRequests());
    }

    @Test
    public void aLostConnectionFailsEverythingOutstanding() {
        Pair pair = new Pair();
        pair.b.onRequest("slow", (in, respond) -> { });
        List<String> errors = new ArrayList<>();
        pair.a.request("slow", null, ok -> fail("no"), errors::add);
        pair.a.request("slow", null, ok -> fail("no"), errors::add);

        pair.a.failAllPending("gone");
        assertEquals(2, errors.size());
        assertEquals(0, pair.a.pendingRequests());
    }

    @Test
    public void bothDirectionsWorkOnOneRouterPair() {
        Pair pair = new Pair();
        pair.a.onRequest("client/ping", (in, respond) -> respond.ok(payload("pong")));
        pair.b.onRequest("server/ping", (in, respond) -> respond.ok(payload("pong")));

        List<Object> fromServer = new ArrayList<>();
        List<Object> fromClient = new ArrayList<>();
        pair.a.request("server/ping", null, fromServer::add, error -> fail(error));
        pair.b.request("client/ping", null, fromClient::add, error -> fail(error));

        assertNotNull(fromServer.get(0));
        assertNotNull(fromClient.get(0));
    }
}
