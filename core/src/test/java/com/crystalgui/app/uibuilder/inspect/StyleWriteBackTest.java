package com.crystalgui.app.uibuilder.inspect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>L3.11 — a tweak made permanent.</b>
 *
 * <p>{@code LiveEdits} changes the running screen and nothing else. This is the other half: the edit
 * lands in the sheet as text, at the position the rule actually occupies, leaving everything around it
 * exactly as written.</p>
 */
public class StyleWriteBackTest extends UiDocumentTestBase {

    /** Shaped like the real thing the acceptance names — a glow with a percentage somebody wants moved. */
    private static final String SHEET =
            "/* The taskbar's accent glow. Tuned against the dark theme. */\n"
            + ".taskbar-glow {\n"
            + "    opacity: 0.18;\n"
            + "}\n"
            + "\n"
            + ".taskbar {\n"
            + "    color: #FFFFFF;\n"
            + "}\n";

    private static int ruleOrderOf(CssSourceModel model, String selector) {
        for (CssSourceModel.Rule rule : model.rules()) {
            for (CssSourceModel.Selector each : rule.selectors()) {
                if (each.text().equals(selector)) return rule.sourceOrder();
            }
        }
        throw new AssertionError("no rule for " + selector);
    }

    /**
     * <b>A declaration edited in the pane lands as a text edit in the sheet.</b>
     *
     * <p>The acceptance, in the shape the plan names it: move the glow's value and see it in the file,
     * with the note above it untouched.</p>
     */
    @Test
    public void editingAValueWritesItIntoTheSheet() {
        TextBuffer sheet = new TextBuffer(SHEET);
        int glow = ruleOrderOf(CssSourceModel.parse(SHEET), ".taskbar-glow");

        assertTrue(StyleWriteBack.writeValue(sheet, glow, "opacity", "0.42"));

        String after = sheet.toString();
        assertTrue("the new value is in the file", after.contains("opacity: 0.42;"));
        assertFalse("and the old one is gone", after.contains("0.18"));
        assertTrue("the note above it survives", after.contains("Tuned against the dark theme"));
        assertTrue("and the rule after it", after.contains(".taskbar {"));
    }

    /** A property the rule does not state yet is added to it rather than refused. */
    @Test
    public void aPropertyTheRuleLacksIsAdded() {
        TextBuffer sheet = new TextBuffer(SHEET);
        int glow = ruleOrderOf(CssSourceModel.parse(SHEET), ".taskbar-glow");

        assertTrue(StyleWriteBack.writeValue(sheet, glow, "color", "#101010"));

        CssSourceModel after = CssSourceModel.parse(sheet.toString());
        assertEquals("the rule gained one", 2,
                after.ruleAt(glow).declarations().size());
        assertTrue(sheet.toString().contains("color: #101010;"));
    }

    /** A rule number no sheet carries writes nothing — what a stale pane asks after the file moved. */
    @Test
    public void anUnknownRuleWritesNothing() {
        TextBuffer sheet = new TextBuffer(SHEET);
        assertFalse(StyleWriteBack.writeValue(sheet, 99, "opacity", "0.42"));
        assertEquals("the file is untouched", SHEET, sheet.toString());
    }

    /**
     * <b>Promote to rule turns an inline tweak into a rule</b>, and takes the inline value away.
     *
     * <p>Leaving both would hide the moment the rule stopped matching: the screen would still look right,
     * from the wrong source.</p>
     */
    @Test
    public void promotingAnInlineTweakWritesARuleAndDropsTheInlineValue() {
        UIElement element = new UIElement().layout(l -> l.width(40).height(20));
        document.append(element);
        document.update(W, H);

        LiveEdits.setInline(element, StylePropertyRegistry.COLOR, "#FF0000");
        document.update(W, H);
        assertTrue(LiveEdits.hasInline(element, StylePropertyRegistry.COLOR));

        TextBuffer sheet = new TextBuffer(SHEET);
        assertTrue(StyleWriteBack.promoteToRule(
                sheet, element, StylePropertyRegistry.COLOR, ".promoted"));

        String after = sheet.toString();
        assertTrue("a rule was written", after.contains(".promoted"));
        assertTrue("carrying the value that was inline", after.contains("color:"));
        assertFalse("and the element no longer carries it inline",
                LiveEdits.hasInline(element, StylePropertyRegistry.COLOR));
        assertFalse("the sheet still parses", StyleSheet.parse(after).getRules().isEmpty());
    }

    /** Nothing inline is nothing to promote. */
    @Test
    public void promotingWithNoInlineValueWritesNothing() {
        UIElement element = new UIElement().layout(l -> l.width(40).height(20));
        document.append(element);
        document.update(W, H);

        TextBuffer sheet = new TextBuffer(SHEET);
        assertFalse(StyleWriteBack.promoteToRule(
                sheet, element, StylePropertyRegistry.COLOR, ".promoted"));
        assertEquals(SHEET, sheet.toString());
    }

    /**
     * <b>One buffer, so the text tab and the pane are the same document.</b>
     *
     * <p>Which is what makes "undo there undoes here" true — there is nothing to keep in step.</p>
     */
    @Test
    public void theEditIsOnTheBufferATextEditorWouldShow() {
        TextBuffer sheet = new TextBuffer(SHEET);
        int glow = ruleOrderOf(CssSourceModel.parse(SHEET), ".taskbar-glow");

        StyleWriteBack.writeValue(sheet, glow, "opacity", "0.42");
        assertTrue(sheet.toString().contains("0.42"));

        sheet.undo();
        assertEquals("undone on the same buffer", SHEET, sheet.toString());
    }
}
