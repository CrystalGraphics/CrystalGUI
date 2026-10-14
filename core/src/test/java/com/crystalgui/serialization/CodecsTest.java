package com.crystalgui.serialization;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgJsonOps;
import com.google.gson.JsonElement;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CodecsTest {

    @Test
    public void stringRoundTripsThroughJsonOps() {
        JsonElement encoded = CgCodecs.STRING.encode(CgJsonOps.INSTANCE, "hello");
        assertEquals("hello", CgCodecs.STRING.decode(CgJsonOps.INSTANCE, encoded));
    }

    @Test
    public void intRoundTripsThroughJsonOps() {
        JsonElement encoded = CgCodecs.INT.encode(CgJsonOps.INSTANCE, 42);
        assertEquals((Integer) 42, CgCodecs.INT.decode(CgJsonOps.INSTANCE, encoded));
    }

    @Test
    public void listOfRoundTripsThroughJsonOps() {
        CgCodec<List<String>> listCodec = CgCodecs.listOf(CgCodecs.STRING);
        List<String> original = Arrays.asList("a", "b", "c");
        JsonElement encoded = listCodec.encode(CgJsonOps.INSTANCE, original);
        assertEquals(original, listCodec.decode(CgJsonOps.INSTANCE, encoded));
    }

    @Test
    public void wrongTypeReadThrowsCodecException() {
        JsonElement stringValue = CgJsonOps.INSTANCE.createString("not a number");
        try {
            CgCodecs.INT.decode(CgJsonOps.INSTANCE, stringValue);
            fail("expected CgCodecException reading a string as a number");
        } catch (CgCodecException expected) {
            // expected
        }
    }
}
