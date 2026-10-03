package com.crystalgui.headless;

import com.crystalgraphics.net.protocol.CgEnvelope;
import com.crystalgraphics.net.protocol.CgEnvelopeCodec;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgui.serialization.JsonOps;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The envelope through a second representation, {@link JsonOps}, so nothing about it depends on
 * {@code CgPlainOps}. The rest of the envelope and the router are CrystalGraphics' {@code CgProtocolTest}.
 */
public class EnvelopeThroughJsonTest {

    @Test
    public void everyKindRoundTripsThroughAnyOps() {
        assertKindsSurvive(CgPlainOps.INSTANCE);
        assertKindsSurvive(JsonOps.INSTANCE);
    }

    /**
     * Note the payload is built THROUGH the ops under test, not handed in.
     *
     * <p>A {@code CgPlainOps} map cannot ride a JSON envelope — {@code JsonOps} is
     * {@code CgDynamicOps<JsonElement>}, so a {@code LinkedHashMap} payload is not a value it can carry.
     * Writing the test the other way compiles only by erasure and then fails at the first nesting, which
     * is exactly the mistake a real second transport would make.</p>
     */
    @SuppressWarnings("unchecked")
    private <T> void assertKindsSurvive(CgDynamicOps<T> ops) {
        CgStateMap<T> map = new CgStateMap<>(ops);
        map.putString("tag", "a");
        T payload = map.encode();

        CgEnvelope.Request<T> request = (CgEnvelope.Request<T>) reencode(ops,
                new CgEnvelope.Request<>(7, "workspace/read", payload));
        assertEquals(7, request.id());
        assertEquals("workspace/read", request.method());
        assertEquals(payload, request.payload());

        CgEnvelope.Response<T> ok = (CgEnvelope.Response<T>) reencode(ops, CgEnvelope.Response.ok(7, payload));
        assertTrue(ok.ok());
        assertEquals(payload, ok.payload());

        CgEnvelope.Response<T> failed = (CgEnvelope.Response<T>) reencode(ops, CgEnvelope.Response.failed(7, "nope"));
        assertFalse(failed.ok());
        assertEquals("nope", failed.error());
        assertNull(failed.payload());

        CgEnvelope.Notification<T> notification = (CgEnvelope.Notification<T>) reencode(ops,
                new CgEnvelope.Notification<>("ui/stateDelta", payload));
        assertEquals("ui/stateDelta", notification.method());
        assertEquals(payload, notification.payload());

        assertEquals(9, ((CgEnvelope.Cancel) reencode(ops, new CgEnvelope.Cancel(9))).id());
    }

    private static <T> CgEnvelope reencode(CgDynamicOps<T> ops, CgEnvelope envelope) {
        return CgEnvelopeCodec.decode(ops, CgEnvelopeCodec.encode(ops, envelope));
    }
}
