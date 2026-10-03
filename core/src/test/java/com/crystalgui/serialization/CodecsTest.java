package com.crystalgui.serialization;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.google.gson.JsonElement;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CodecsTest {

    @Test
    public void stringRoundTripsThroughJsonOps() {
        JsonElement encoded = CgCodecs.STRING.encode(JsonOps.INSTANCE, "hello");
        assertEquals("hello", CgCodecs.STRING.decode(JsonOps.INSTANCE, encoded));
    }

    @Test
    public void intRoundTripsThroughJsonOps() {
        JsonElement encoded = CgCodecs.INT.encode(JsonOps.INSTANCE, 42);
        assertEquals((Integer) 42, CgCodecs.INT.decode(JsonOps.INSTANCE, encoded));
    }

    @Test
    public void listOfRoundTripsThroughJsonOps() {
        CgCodec<List<String>> listCodec = CgCodecs.listOf(CgCodecs.STRING);
        List<String> original = Arrays.asList("a", "b", "c");
        JsonElement encoded = listCodec.encode(JsonOps.INSTANCE, original);
        assertEquals(original, listCodec.decode(JsonOps.INSTANCE, encoded));
    }

    @Test
    public void wrongTypeReadThrowsCodecException() {
        JsonElement stringValue = JsonOps.INSTANCE.createString("not a number");
        try {
            CgCodecs.INT.decode(JsonOps.INSTANCE, stringValue);
            fail("expected CgCodecException reading a string as a number");
        } catch (CgCodecException expected) {
            // expected
        }
    }
}
