package com.crystalgui.serialization;

import com.google.gson.JsonElement;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CodecsTest {

    @Test
    public void stringRoundTripsThroughJsonOps() {
        JsonElement encoded = Codecs.STRING.encode(JsonOps.INSTANCE, "hello");
        assertEquals("hello", Codecs.STRING.decode(JsonOps.INSTANCE, encoded));
    }

    @Test
    public void intRoundTripsThroughJsonOps() {
        JsonElement encoded = Codecs.INT.encode(JsonOps.INSTANCE, 42);
        assertEquals((Integer) 42, Codecs.INT.decode(JsonOps.INSTANCE, encoded));
    }

    @Test
    public void listOfRoundTripsThroughJsonOps() {
        Codec<List<String>> listCodec = Codecs.listOf(Codecs.STRING);
        List<String> original = Arrays.asList("a", "b", "c");
        JsonElement encoded = listCodec.encode(JsonOps.INSTANCE, original);
        assertEquals(original, listCodec.decode(JsonOps.INSTANCE, encoded));
    }

    @Test
    public void wrongTypeReadThrowsCodecException() {
        JsonElement stringValue = JsonOps.INSTANCE.createString("not a number");
        try {
            Codecs.INT.decode(JsonOps.INSTANCE, stringValue);
            fail("expected CodecException reading a string as a number");
        } catch (CodecException expected) {
            // expected
        }
    }
}
