package com.crystalgui.app.uibuilder.style;

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
 * <p>What is pinned here is the model under the labs: the layers a composite is made of, the preview that
 * shows a drag without writing the file, and the three ways a rule comes into being. The gizmos themselves
 * are geometry and are judged on screen.</p>
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

        // A HEX COLOUR IS NOT A NUMBER: readable() scanned its digits as one, so every colour with a zero
        // component was shown mangled -- #00000000 as #0, and a plain green as #0FF0.
        assertEquals("#00000000", CssValues.readable("#00000000"));
        assertEquals("#00FF00", CssValues.readable("#00FF00"));
        assertEquals("#478B18FF 0px 0px 12px", CssValues.readable("#478B18FF 0.0px 0px 12.0px"));
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
     * <p>The canvas's own rotate gesture stores radians, and a colour is an int — both of which the panel
     * showed raw: {@code rotate(-0.0022845864rad)} and {@code -1535686}.</p>
     */
    @Test
    public void aValueIsShownAsSomethingAPersonReads() {
        assertEquals("rotate(-0.13deg)", CssValues.readable("rotate(-0.0022845864rad)"));
        assertEquals("26px", CssValues.readable("26.0px"));
        assertEquals("translate(4px, 12px)", CssValues.readable("translate(4.0px, 12.0px)"));
        assertEquals("a colour is left alone", "#FF8800", CssValues.readable("#FF8800"));

        // AND A COLOUR IS WRITTEN AS A COLOUR: the default writer answered the signed int it is stored as.
        assertEquals("#E9A016", StylePropertyRegistry.COLOR.write(0xFFE9A016));
        assertEquals("alpha last, as CSS spells it", "#E9A01680", StylePropertyRegistry.COLOR.write(0x80E9A016));
        assertEquals("and it reads back as what it was", Integer.valueOf(0x80E9A016),
                ColorValue.parseColor("#E9A01680"));
    }

    /**
     * <b>A shadow is read wherever its colour sits.</b>
     *
     * <p>CSS writes {@code 0 1px 2px #000}; this engine's own writer answers {@code #000 0px 1px 2px}. A lab
     * that reads by position takes the colour for an offset — and then shows different numbers from the
     * value it had just written itself.</p>
     */
    @Test
    public void aShadowReadsWithItsColourAtEitherEnd() {
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
     * <b>A shadow's leading zero is an offset, not a transparent colour.</b>
     *
     * <p>{@code ColorValue.parseColor} takes a decimal ARGB literal as well as a CSS colour, so it reads
     * {@code 0} as transparent black - which is right for a colour property and wrong for any grammar
     * where a bare number is a length. The lab read the X offset of {@code 0 1px 2px #000000} as its
     * colour: every shadow it added was invisible, and the picker opened at zero alpha, so choosing a
     * colour moved the value and nothing on screen.</p>
     */
    @Test
    public void aShadowsLeadingZeroIsAnOffsetAndNotAColour() {
        assertEquals("the colour is the colour", 0xFF000000, ShadowLab.colourOf(ShadowLab.DEFAULT));
        assertEquals("wherever it sits", 0xFFAF9B00, ShadowLab.colourOf("#AF9B00 0px 0px 12px"));
        assertEquals("and alpha survives", 0x80FF0000, ShadowLab.colourOf("0 0 4px #FF000080"));

        assertNull("CSS has no integer colour", ColorValue.parseCssColor("0"));
        assertEquals("which is exactly what the other parser would have answered",
                Integer.valueOf(0), ColorValue.parseColor("0"));
    }

    /**
     * <b>A shadow bigger than its chip is drawn to scale.</b>
     *
     * <p>A swatch is 28x16; a shadow offset -14px with a 16px blur reaches 30px, so at its own scale what
     * lands in the box is a corner of a blur. Scaling the offsets and the blur together keeps the
     * direction, the softness and the colour, which is all a picture that size is being asked.</p>
     */
    @Test
    public void aShadowTooBigForItsChipIsDrawnToScale() {
        assertEquals("one that already fits is left alone", "0px 1px 2px #000000",
                ShadowLab.fittedLayer("0 1px 2px #000000"));

        // EVERY layer, and the stack kept: handed the whole comma list as one shadow, a five-layer stack
        // drew as its first colour alone -- which is what the inspector's own chip was showing.
        assertEquals("0px 1px 2px #FF0000, 0px 1px 2px #00FF00",
                ShadowLab.fitted("0 1px 2px #FF0000, 0 1px 2px #00FF00"));

        String fitted = ShadowLab.fittedLayer("0 -14px 16px #FF0000");
        List<String> terms = CssValues.terms(fitted);
        float y = CssValues.number(terms, 1, 0f);
        float blur = CssValues.number(terms, 2, 0f);

        assertEquals("the colour is kept", 0xFFFF0000, ShadowLab.colourOf(fitted));
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
        // not come back in pixels. It is the same reason the colour is no longer cleared at zero.
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

        assertTrue(RuleActions.newRule(sheet, ".card-body"));
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

        assertTrue(RuleActions.promote(sheet, null, node, StylePropertyRegistry.OPACITY, ".card"));
        frame();
        assertTrue("the rule holds it now", sheet.toString().contains("opacity: 0.2"));
        assertTrue("and the element does not",
                StyleTargets.of(node, sheets).chosen("").declarations().isEmpty());
        assertEquals("so the screen shows the sheet's value", Float.valueOf(0.2f),
                node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
    }

    @Test
    public void extractingAClassMovesEverythingAndNamesTheElement() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        inline.value("opacity").set("0.3");
        inline.value("color").set("#FFFFFF");
        frame();

        assertEquals(2, RuleActions.extractClass(sheet, null, node, "chip"));
        frame();
        assertTrue("the rule was written", sheet.toString().contains(".chip"));
        assertTrue("with the values", sheet.toString().contains("opacity: 0.3"));
        assertTrue("and the element wears the class", node.hasClass("chip"));
        assertTrue("with nothing left inline",
                StyleTargets.of(node, sheets).chosen("").declarations().isEmpty());
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
}
