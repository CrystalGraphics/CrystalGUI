package com.crystalgui.app.uibuilder.style;

import com.crystalgui.serialization.style.InlineStyleCodec;

import com.crystalgui.serialization.JsonOps;

import com.crystalgui.app.uibuilder.inspect.NodeFields;

import com.google.gson.JsonElement;

import com.crystalgui.style.sheet.source.CssSourceModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>L5 S.7–S.16 — the composite values a lab edits, and the rules it makes.</b>
 *
 * <p>What is pinned here is the model under the labs: the layers a composite is made of and the three ways a
 * rule comes into being. The gizmos themselves are geometry and are judged on screen.</p>
 */
public class StyleLabsTest {

    private static final String SHEET = """
            /* keep me */
            .card {
                opacity: 0.5;
            }
            """;

    private UIDocument window;
    private UIElement node;
    private TextBuffer sheet;
    private SheetDocuments sheets;

    @Before
    public void openASheet() {
        window = new UIDocument();
        node = new UIElement();
        node.addClass("card");
        window.append(node);
        sheet = new TextBuffer(SHEET);
        sheets = SheetFixture.install(window, sheet, "menu.css");
        frame();
    }

    private void frame() {
        for (int i = 0; i < 4; i++) window.frame(1f / 60f, 400, 300);
    }

    private StyleTarget rule() {
        for (StyleTarget target : StyleTargets.of(node, sheets).targets()) {
            if (!target.isInline()) return target;
        }
        return null;
    }

    // ── S.8 / S.9 / S.11 / S.12: what a composite is made of ────────────────

    @Test
    public void aCompositeIsItsLayersAndTheyRoundTrip() {
        List<String> shadows = CssValues.layers("0 1px 2px #000000, 0 0 8px rgba(0, 0, 0, 0.5)");
        assertEquals("a comma inside rgba() divides nothing", 2, shadows.size());
        assertEquals("0 0 8px rgba(0, 0, 0, 0.5)", shadows.get(1));
        assertEquals("0 1px 2px #000000, 0 0 8px rgba(0, 0, 0, 0.5)", CssValues.join(shadows));

        List<String> ops = CssValues.functions("translate(10px, 0px) rotate(14deg) scale(2, 2)");
        assertEquals(3, ops.size());
        assertEquals("rotate", CssValues.functionName(ops.get(1)));
        assertEquals("14deg", CssValues.arguments(ops.get(1)));
        assertEquals("order is the value, so it is kept", "translate(10px, 0px) rotate(14deg) scale(2, 2)",
                CssValues.joinFunctions(ops));

        assertEquals("a length reads as its number", 12f, CssValues.number("12px", 0f), 1e-6);
        assertEquals("and a turn as its own", 0.25f, CssValues.number("0.25turn", 0f), 1e-6);
        assertEquals("written back without a trailing zero", "12", CssValues.write(12.0));

        // A HEX COLOR IS NOT A NUMBER: readable() scanned its digits as one, so every color with a zero
        // component was shown mangled -- #00000000 as #0, and a plain green as #0FF0.
        assertEquals("#00000000", CssValues.readable("#00000000"));
        assertEquals("#00FF00", CssValues.readable("#00FF00"));
        assertEquals("an opaque alpha is dropped", "#478B18 0px 0px 12px", CssValues.readable("#478B18FF 0.0px 0px 12.0px"));
        assertEquals("a pixel to a tenth", "#CF0600 1px -14px 24.5px", CssValues.readable("#CF0600FF 1px -14px 24.49px"));
        assertEquals("a number inside a word is part of the word", "url(img2.png)", CssValues.readable("url(img2.png)"));
    }

    /** Reordering is an edit, because the order decides what the value does. */
    @Test
    public void movingALayerChangesTheValue() {
        Property<String> css = Property.of("translate(10px, 0px) scale(2, 2)");
        Property<Integer> selected = Property.of(1);
        LayerStack stack = new LayerStack("ops", StylePropertyRegistry.TRANSFORM, selected);
        stack.bind(css.map(CssValues::functions, CssValues::joinFunctions));

        stack.move(1, -1);   // what the row's up arrow does

        assertEquals("scale(2, 2) translate(10px, 0px)", css.get());
        assertEquals("and the moved row stays the selected one", 0, (int) selected.get());
    }

    /**
     * <b>What a row SHOWS is not what it writes.</b>
     *
     * <p>The canvas's own rotate gesture stores radians, and a color is an int — both of which the panel
     * showed raw: {@code rotate(-0.0022845864rad)} and {@code -1535686}.</p>
     */
    @Test
    public void aValueIsShownAsSomethingAPersonReads() {
        assertEquals("rotate(-0.13deg)", CssValues.readable("rotate(-0.0022845864rad)"));
        assertEquals("26px", CssValues.readable("26.0px"));
        assertEquals("translate(4px, 12px)", CssValues.readable("translate(4.0px, 12.0px)"));
        assertEquals("a color is left alone", "#FF8800", CssValues.readable("#FF8800"));

        // AND A COLOR IS WRITTEN AS A COLOR: the default writer answered the signed int it is stored as.
        assertEquals("#E9A016", StylePropertyRegistry.COLOR.write(0xFFE9A016));
        assertEquals("alpha last, as CSS spells it", "#E9A01680", StylePropertyRegistry.COLOR.write(0x80E9A016));
        assertEquals("and it reads back as what it was", Integer.valueOf(0x80E9A016),
                ColorValue.parseColor("#E9A01680"));
    }

    /**
     * <b>A shadow is read wherever its color sits.</b>
     *
     * <p>CSS writes {@code 0 1px 2px #000}; this engine's own writer answers {@code #000 0px 1px 2px}. A lab
     * that reads by position takes the color for an offset — and then shows different numbers from the
     * value it had just written itself.</p>
     */
    @Test
    public void aShadowReadsWithItsColorAtEitherEnd() {
        assertEquals("#AF9B00FF 0px 0px 12.919px", StylePropertyRegistry.TEXT_SHADOW.write(
                StylePropertyRegistry.TEXT_SHADOW.valueParser.parse("0px 0px 12.919px #AF9B00").compute()));
    }

    /**
     * <b>A property writes the CSS keyword, not the Java constant.</b>
     *
     * <p>{@code EnumProperty} lowercases, but {@code font-weight} takes a custom parser and so is a plain
     * {@code StyleProperty} with the default {@code String.valueOf} writer — it answered {@code BOLD}. The
     * parser reads that back, so nothing ever failed; what it produced was a row reading BOLD and a
     * dropdown of lowercase keywords that matched none of them, so choosing bold never showed as chosen.</p>
     */
    @Test
    public void aWeightWritesTheKeywordAndNotTheConstant() {
        assertEquals("bold", StylePropertyRegistry.FONT_WEIGHT.write(
                StylePropertyRegistry.FONT_WEIGHT.valueParser.parse("bold").compute()));
        assertEquals("normal", StylePropertyRegistry.FONT_WEIGHT.write(
                StylePropertyRegistry.FONT_WEIGHT.valueParser.parse("normal").compute()));
        // The engine has two faces, so a numeric weight resolves to one of them and says which.
        assertEquals("bold", StylePropertyRegistry.FONT_WEIGHT.write(
                StylePropertyRegistry.FONT_WEIGHT.valueParser.parse("700").compute()));
    }

    /**
     * <b>A shadow's leading zero is an offset, not a transparent color.</b>
     *
     * <p>{@code ColorValue.parseColor} takes a decimal ARGB literal as well as a CSS color, so it reads
     * {@code 0} as transparent black - which is right for a color property and wrong for any grammar
     * where a bare number is a length. The lab read the X offset of {@code 0 1px 2px #000000} as its
     * color: every shadow it added was invisible, and the picker opened at zero alpha, so choosing a
     * color moved the value and nothing on screen.</p>
     */
    @Test
    public void aShadowsLeadingZeroIsAnOffsetAndNotAColor() {
        assertEquals("the color is the color", 0xFF000000, ShadowLab.colorOf(ShadowLab.DEFAULT));
        assertEquals("wherever it sits", 0xFFAF9B00, ShadowLab.colorOf("#AF9B00 0px 0px 12px"));
        assertEquals("and alpha survives", 0x80FF0000, ShadowLab.colorOf("0 0 4px #FF000080"));

        assertNull("CSS has no integer color", ColorValue.parseCssColor("0"));
        assertEquals("which is exactly what the other parser would have answered",
                Integer.valueOf(0), ColorValue.parseColor("0"));
    }

    /**
     * <b>A shadow bigger than its chip is drawn to scale.</b>
     *
     * <p>A swatch is 28x16; a shadow offset -14px with a 16px blur reaches 30px, so at its own scale what
     * lands in the box is a corner of a blur. Scaling the offsets and the blur together keeps the
     * direction, the softness and the color, which is all a picture that size is being asked.</p>
     */
    /** A shadow's spread and {@code inset} survive an edit, and a plain shadow keeps Level 3's spelling. */
    @Test
    public void aShadowKeepsItsSpreadAndInset() {
        ShadowLab.Shadow full = ShadowLab.Shadow.parse("#FF0000 1px 2px 3px 4px inset");
        assertEquals(4f, full.spread(), 1e-6);
        assertTrue(full.inset());
        assertEquals("1px 2px 3px 4px #FF0000 inset", full.withBlur(3).toString());
        assertEquals("no spread written where there is none", "0px 1px 2px #000000",
                ShadowLab.Shadow.parse("0 1px 2px #000000").toString());
    }

    /** A gradient stop is written to a tenth of a percent, whatever a drag landed on. */
    @Test
    public void aGradientStopIsWrittenToATenth() {
        Gradient ramp = Gradient.parse("linear-gradient(90deg, #FF0000, #0000FF)");
        assertEquals("linear-gradient(90deg, #FF0000 17.6%, #0000FF)",
                ramp.withStop(0, ramp.stops().get(0).withPosition(0.17596f)).toString());
    }

    @Test
    public void aShadowTooBigForItsChipIsDrawnToScale() {
        assertEquals("one that already fits is left alone", "0px 1px 2px #000000",
                ShadowLab.fittedLayer("0 1px 2px #000000"));

        // EVERY layer, and the stack kept: handed the whole comma list as one shadow, a five-layer stack
        // drew as its first color alone -- which is what the inspector's own chip was showing.
        assertEquals("0px 1px 2px #FF0000, 0px 1px 2px #00FF00",
                ShadowLab.fitted("0 1px 2px #FF0000, 0 1px 2px #00FF00"));

        String fitted = ShadowLab.fittedLayer("0 -14px 16px #FF0000");
        List<String> terms = CssValues.terms(fitted);
        float y = CssValues.number(terms, 1, 0f);
        float blur = CssValues.number(terms, 2, 0f);

        assertEquals("the color is kept", 0xFFFF0000, ShadowLab.colorOf(fitted));
        assertTrue("the direction is kept", y < 0f);
        assertTrue("it now fits", Math.abs(y) + blur <= 7.01f);
        assertEquals("and the proportions with it", 16f / 14f, blur / Math.abs(y), 1e-3);
    }

    /**
     * <b>A drag authors two decimals, not seven.</b>
     *
     * <p>A slider hands back wherever the pointer landed, and written straight out that is
     * {@code font-size: 71.771236px} in somebody's file. Not whole numbers: a half-pixel stroke and a
     * 1.5px blur are values a lab exists to find, and a step of 1 cannot reach them.</p>
     */
    @Test
    public void aDraggedValueIsWrittenAtTwoDecimals() {
        assertEquals(71.77, CssValues.dragged(71.771236), 1e-9);
        assertEquals("a half pixel survives, which a step of 1 would not", 0.5, CssValues.dragged(0.5), 1e-9);
        assertEquals(-3.27, CssValues.dragged(-3.2666), 1e-9);
        assertEquals("and a whole number stays whole", "12", CssValues.write(CssValues.dragged(12.0)));
    }

    /**
     * <b>A stroke written in em comes back as the pixels it was set to.</b>
     *
     * <p>This engine spells a font-relative stroke width as a percentage -- {@code TextStrokeStyle}
     * resolves the property against the font size, so {@code 15%} is 0.15em -- which means switching the
     * unit converts rather than re-authors. Rounding that conversion to two places lost the drag's own
     * precision: 2.9px came back as 2.899, and the field and the measured caption disagreed about one
     * value. Three places lands it back on the slider's tenth.</p>
     */
    @Test
    public void aStrokeInEmComesBackAsThePixelsItWasSetTo() {
        float size = 38.1f;
        float px = 2.9f;

        String percent = TypographyLab.spell(px, size, true);
        float back = (float) (CssValues.number(percent, 0f) / 100d * size);

        assertEquals("px stays px", "2.9px", TypographyLab.spell(px, size, false));
        assertTrue("em is written as the percentage this engine resolves against font size",
                percent.endsWith("%"));
        assertEquals("and with no size to be relative to, it cannot be em at all",
                "2.9px", TypographyLab.spell(px, 0f, true));
        // A ZERO WIDTH STILL REMEMBERS ITS UNIT, so dialling a stroke down to nothing and back up does
        // not come back in pixels. It is the same reason the color is no longer cleared at zero.
        assertEquals("0%", TypographyLab.spell(0f, size, true));
        assertEquals("0px", TypographyLab.spell(0f, size, false));

        // AS THE CAPTION PRINTS IT, which is where the disagreement showed: two places of percentage
        // recovers 2.899px and the caption said so while the field still said 2.9.
        assertEquals("the trip through a percentage keeps the pixels", "2.9px", CssValues.px(back));
    }

    // ── S.15: how a rule comes into being ───────────────────────────────────

    @Test
    public void aNewRuleIsNamedBeforeItIsWritten() {
        assertEquals("the class it already has", ".card", RuleActions.selectorFor(node));

        assertNotNull(RuleActions.newRule(sheets.byId("menu.css"), ".card-body"));
        assertTrue("the rule is in the file", sheet.toString().contains(".card-body"));
        assertTrue("and the file still opens with its comment", sheet.toString().startsWith("/* keep me */"));

        // AN EMPTY RULE IS STILL A TARGET, though the cascade never saw it: otherwise the rule you just
        // asked for is the one thing you cannot write into.
        UIElement body = new UIElement();
        body.addClass("card-body");
        window.append(body);
        frame();
        StyleTarget empty = null;
        for (StyleTarget target : StyleTargets.of(body, sheets).targets()) {
            if (".card-body".equals(target.label())) empty = target;
        }
        assertNotNull("the new rule is offered as a target", empty);
        assertTrue(empty.isEditable());

        StyleFields fields = StyleFields.on(null, empty, body);
        fields.add("opacity", "0.4");
        frame();
        assertTrue("and writing into it works", sheet.toString().contains("opacity: 0.4"));
        assertEquals("so the element takes it", Float.valueOf(0.4f),
                body.getStyle().getComputed(StylePropertyRegistry.OPACITY));
    }

    @Test
    public void promotingMovesTheValueOutOfTheElementAndIntoTheRule() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        inline.value("opacity").set("0.2");
        frame();

        assertEquals(1, RuleActions.promote(sheets.byId("menu.css"), null, node, ".card"));
        frame();
        assertTrue("the rule holds it now", sheet.toString().contains("opacity: 0.2"));
        assertTrue("and the element does not", StyleFields.on(null, StyleTarget.inline(), node).declared().isEmpty());
        assertEquals("so the screen shows the sheet's value", Float.valueOf(0.2f),
                node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
    }

    @Test
    public void extractingAClassMovesEverythingAndNamesTheElement() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        inline.value("opacity").set("0.3");
        inline.value("color").set("#FFFFFF");
        frame();

        assertEquals(2, RuleActions.extractClass(sheets.byId("menu.css"), null, node, "chip"));
        frame();
        assertTrue("the rule was written", sheet.toString().contains(".chip"));
        assertTrue("with the values", sheet.toString().contains("opacity: 0.3"));
        assertTrue("and the element wears the class", node.hasClass("chip"));
        assertTrue("with nothing left inline", StyleFields.on(null, StyleTarget.inline(), node).declared().isEmpty());

        sheet.history().undo();
        assertFalse("and it was ONE step in the sheet's history", sheet.toString().contains(".chip"));
    }

    // ── S.16: the row's edits are undoable where they were made ─────────────

    @Test
    public void undoingARuleEditIsTheSheetsOwnHistory() {
        StyleFields fields = StyleFields.on(null, rule(), node);
        fields.value("opacity").set("0.9");
        assertTrue(sheet.toString().contains("opacity: 0.9"));

        sheet.history().undo();
        assertTrue("the sheet's history is where that edit lives", sheet.toString().contains("opacity: 0.5"));
        assertFalse(sheet.toString().contains("opacity: 0.9"));
    }

    @Test
    public void aLayerStackEditIsAStepInTheSheetsHistory() {
        StyleFields fields = StyleFields.on(null, rule(), node);
        Property<String> css = fields.value("text-shadow");
        css.set("0px 1px 2px #000000");
        LayerStack stack = new LayerStack("t", StylePropertyRegistry.TEXT_SHADOW, Property.of(0));
        window.append(stack);
        stack.bind(css.map(CssValues::layers, CssValues::join));
        frame();
        stack.add("0px 0px 4px #FF0000");
        frame();
        assertTrue(sheet.toString().contains("#FF0000"));
        sheet.history().undo();
        frame();
        assertFalse("the add was its own step", sheet.toString().contains("#FF0000"));
        assertTrue(sheet.toString().contains("text-shadow"));
    }

    @Test
    public void aSwitchedOffLayerIsKeptAsACommentTheSheetIgnores() {
        List<String> stack = CssValues.layerStack("#000000 0px 1px 2px /* , #FF0000 0px 0px 4px */, #0000FF 1px 1px 1px");
        assertEquals(List.of("#000000 0px 1px 2px", "/* #FF0000 0px 0px 4px */", "#0000FF 1px 1px 1px"), stack);
        assertEquals("round trip", stack, CssValues.layerStack(CssValues.joinLayerStack(stack)));

        List<String> firstOff = List.of("/* a */", "b", "/* c */");
        assertEquals("the live list is what is left", List.of("b"), CssValues.layers(CssValues.joinLayerStack(firstOff)));
        assertEquals(firstOff, CssValues.layerStack(CssValues.joinLayerStack(firstOff)));

        assertEquals("none /* a, b */", CssValues.joinLayerStack(List.of("/* a */", "/* b */")));
        assertEquals(List.of("/* a */", "/* b */"), CssValues.layerStack("none /* a, b */"));

        List<String> ops = List.of("translate(4px, 2px)", "/* rotate(14deg) */", "scale(2, 2)");
        assertEquals(ops, CssValues.functionStack(CssValues.joinFunctionStack(ops)));
    }

    @Test
    public void aCommentTouchingAValueIsPartOfIt() {
        CssSourceModel model = CssSourceModel.parse(".a {\n    text-shadow: a, b /* , c */;\n    /* color: red; */\n}\n");
        assertEquals("a, b /* , c */", model.rules().get(0).declarations().get(0).value());
    }

    /** Inline, a switched-off declaration stays where it stands, as a commented value the file carries. */
    @Test
    public void anInlineDeclarationSwitchesOffWhereItStands() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        inline.value("opacity").set("0.2");
        frame();

        assertTrue(inline.setEnabled("opacity", false));
        frame();
        assertEquals("the rule's value shows again", Float.valueOf(0.5f),
                node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
        JsonElement saved = NodeFields.inlineStyleOf(node);
        assertEquals("/* 0.2 */", saved.getAsJsonObject().get("opacity").getAsString());
        StyleFields.Declared hidden = inline.declared("opacity");
        assertTrue("the row is still there, switched off", hidden != null && hidden.disabled());

        UIElement reopened = new UIElement();
        InlineStyleCodec.replaceInto(JsonOps.INSTANCE, saved, reopened);
        assertEquals("and survives a save", saved, NodeFields.inlineStyleOf(reopened));

        assertTrue(inline.setEnabled("opacity", true));
        frame();
        assertEquals(Float.valueOf(0.2f), node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
    }

    @Test
    public void anInlineValueKeepsItsSwitchedOffLayer() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        String written = "#000000 0px 1px 2px /* , #FF0000 0px 0px 4px */";
        inline.value("text-shadow").set(written);
        frame();
        assertEquals(written, inline.value("text-shadow").get());
        assertEquals(written, NodeFields.inlineStyleOf(node).getAsJsonObject().get("text-shadow").getAsString());
        assertNotNull("the live layer applies", node.getStyle().getComputed(StylePropertyRegistry.TEXT_SHADOW));
    }

    /** The stroke is two longhands inline, switched off together and shown as the one row it is. */
    @Test
    public void anInlineStrokeSwitchesOffAsOneRow() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        inline.value("text-stroke").set("2px #FF0000");
        frame();

        assertTrue(inline.setEnabled("text-stroke", false));
        frame();
        JsonElement saved = NodeFields.inlineStyleOf(node);
        assertTrue("the width kept where it stands, commented",
                CssValues.isOff(saved.getAsJsonObject().get("text-stroke-width").getAsString()));
        assertTrue("and the color", CssValues.isOff(saved.getAsJsonObject().get("text-stroke-color").getAsString()));
        StyleFields.Declared hidden = inline.declared("text-stroke");
        assertTrue("one row, switched off", hidden != null && hidden.disabled());

        assertTrue(inline.setEnabled("text-stroke", true));
        frame();
        StyleFields.Declared shown = inline.declared("text-stroke");
        assertTrue("and back on", shown != null && !shown.disabled());
    }
}
