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

    /**
     * <b>A switched-off entry does not decide what the value IS.</b> A layer is kept as a comment inside the value,
     * and the split at a comment happens whether or not a comma is anywhere near it — so a transform with one op
     * switched off read as two layers, and the chip and the readout printed it a comma-separated line at a time,
     * inventing a comma no transform has.
     */
    @Test
    public void aFunctionSwitchedOffDoesNotMakeItACommaList() {
        assertEquals(List.of(
                new CssValues.Line("/* translate(40px, 0px) */", false),
                new CssValues.Line("scale(2.58, 2.58)", false),
                new CssValues.Line("rotate(358deg)", false)),
                lines("/* translate(40px, 0px) */ scale(2.58, 2.58) rotate(358deg)"));
    }

    /** And a comma list still is one: its separator is kept inside the comment, so it is still there to be found. */
    @Test
    public void aLayerSwitchedOffKeepsTheListALayerALine() {
        assertEquals(List.of(
                new CssValues.Line("#000 0px 1px 2px,", false),
                new CssValues.Line("/* #F00 0px 0px 4px */", false)),
                lines("#000 0px 1px 2px /* , #F00 0px 0px 4px */"));
    }

    @Test
    public void aShortValueStaysOnOneLine() {
        assertEquals(1, lines("linear-gradient(90deg, #F00, #00F)").size());
        assertEquals(1, lines("#00000080 0px 1px 2px").size());
    }
}
