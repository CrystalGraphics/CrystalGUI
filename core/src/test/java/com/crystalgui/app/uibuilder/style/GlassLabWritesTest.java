package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/** The glass lab writes only where the glass differs from the engine's, in the grammar's order. */
public class GlassLabWritesTest {

    private static Map<String, String> parts(String... functions) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String function : functions) out.put(CssValues.functionName(function), function);
        return out;
    }

    @Test
    public void aFunctionAtTheEngineDefaultIsLeftOut() {
        assertEquals("blur(24px)", GlassLab.written(parts("blur(24px)", "saturate(1.35)", "ior(1.5)")));
    }

    @Test
    public void functionsAreWrittenInTheGrammarsOrder() {
        assertEquals("blur(4px) ior(2)", GlassLab.written(parts("ior(2)", "blur(4px)")));
    }

    @Test
    public void allDefaultGlassIsItsBlur() {
        assertEquals("blur(12px)", GlassLab.written(parts("blur(12px)", "bezel(8px)")));
    }

    @Test
    public void anUnknownFunctionIsKeptAsWritten() {
        assertEquals("blur(4px) warp(3)", GlassLab.written(parts("warp(3)", "blur(4px)")));
    }
}
