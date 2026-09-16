package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
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
    }

    /** Reordering is an edit, because the order decides what the value does. */
    @Test
    public void movingALayerChangesTheValue() {
        LayerStack stack = new LayerStack(StylePropertyRegistry.TRANSFORM);
        String[] written = {""};
        stack.onChange(layers -> written[0] = CssValues.joinFunctions(layers));
        stack.show(List.of("translate(10px, 0px)", "scale(2, 2)"), 0);

        stack.move(1, -1);   // what the row's up arrow does

        assertEquals("scale(2, 2) translate(10px, 0px)", written[0]);
        assertEquals("and the moved row stays the selected one", 0, stack.selected());
    }

    // ── S.8: a drag shows without writing ───────────────────────────────────

    @Test
    public void aPreviewShowsOnEveryElementTheRuleReachesAndLeavesTheFileAlone() {
        UIElement sibling = new UIElement();
        sibling.addClass("card");
        window.append(sibling);
        frame();

        SheetPreview preview = SheetPreview.of(rule(), node);
        preview.show(StylePropertyRegistry.OPACITY, "0.1");
        frame();

        assertEquals("the node shows the dragged value", Float.valueOf(0.1f),
                node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
        assertEquals("and so does every other element the rule reaches", Float.valueOf(0.1f),
                sibling.getStyle().getComputed(StylePropertyRegistry.OPACITY));
        assertTrue("with nothing written yet", sheet.toString().contains("opacity: 0.5;"));

        preview.cancel();
        frame();
        assertEquals("cancelled, the sheet's own value is back", Float.valueOf(0.5f),
                node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
    }

    @Test
    public void committingAPreviewWritesOnce() {
        StyleFields fields = StyleFields.on(null, rule(), node);
        SheetPreview preview = SheetPreview.of(rule(), node);
        for (int i = 0; i < 30; i++) preview.show(StylePropertyRegistry.OPACITY, "0." + (i % 9 + 1));
        preview.commit(fields, "opacity", "0.25");
        frame();

        assertTrue("the value landed", sheet.toString().contains("opacity: 0.25"));
        assertEquals("a 30-frame drag is one entry in the sheet's history", 1, sheet.history().undoDepth());
        assertEquals("and the canvas reads it from the text", Float.valueOf(0.25f),
                node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
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
