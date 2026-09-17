package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/** A long value reads in the lines a person would break it at: layers, functions, or one call's arguments. */
public class CssValueLinesTest {

    private static List<CssValues.Line> lines(String value) {
        return CssValues.lines(value);
    }

    @Test
    public void aCommaListIsALayerALine() {
        assertEquals(List.of(new CssValues.Line("#000 0px 1px 2px,", false), new CssValues.Line("#F00 0px 0px 4px", false)),
                lines("#000 0px 1px 2px, #F00 0px 0px 4px"));
    }

    @Test
    public void aRunOfFunctionsIsAFunctionALine() {
        assertEquals(List.of(new CssValues.Line("translate(0.3px, -0.3px)", false), new CssValues.Line("scale(1.001, 0.993)", false)),
                lines("translate(0.3px, -0.3px) scale(1.001, 0.993)"));
    }

    @Test
    public void aLongCallOpensOntoAnArgumentALine() {
        assertEquals(List.of(
                new CssValues.Line("linear-gradient(", false),
                new CssValues.Line("270deg,", true),
                new CssValues.Line("#F5005B,", true),
                new CssValues.Line("#C86AFF 13.8%,", true),
                new CssValues.Line("#FF7368 46.6%)", true)),
                lines("linear-gradient(270deg, #F5005B, #C86AFF 13.8%, #FF7368 46.6%)"));
    }

    @Test
    public void aShortValueStaysOnOneLine() {
        assertEquals(1, lines("linear-gradient(90deg, #F00, #00F)").size());
        assertEquals(1, lines("#00000080 0px 1px 2px").size());
    }
}
