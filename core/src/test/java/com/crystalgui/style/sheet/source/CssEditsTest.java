package com.crystalgui.style.sheet.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextBuffer;

/**
 * <b>L3.10 — the five text edits, against the bytes they name.</b>
 *
 * <p>A sheet round-tripped through the parser comes back reformatted with its comments gone, so an
 * inspector that "just wrote the file" would silently reformat a file somebody else maintains. Every edit
 * here touches the range it was measured at and nothing else.</p>
 */
public class CssEditsTest {

    private static final String SHEET =
            "/* The panel's own chrome. Do not restyle from a theme. */\n"
            + ".panel {\n"
            + "    color: #FFFFFF;\n"
            + "    padding: 4;\n"
            + "}\n"
            + "\n"
            + ".panel .title {\n"
            + "    font-size: 12;\n"
            + "}\n";

    private static String applied(String source, ChangeSet edit) {
        TextBuffer buffer = new TextBuffer(source);
        buffer.edit(edit);
        return buffer.toString();
    }

    private static CssSourceModel.Rule ruleWith(CssSourceModel model, String selector) {
        for (CssSourceModel.Rule rule : model.rules()) {
            for (CssSourceModel.Selector each : rule.selectors()) {
                if (each.text().equals(selector)) return rule;
            }
        }
        throw new AssertionError("no rule for " + selector);
    }

    private static CssSourceModel.Declaration declaration(CssSourceModel.Rule rule, String property) {
        for (CssSourceModel.Declaration each : rule.declarations()) {
            if (each.property().equals(property)) return each;
        }
        throw new AssertionError("no declaration " + property);
    }

    /**
     * <b>Edit a value; the comment above the rule is untouched.</b>
     *
     * <p>The acceptance for this step, and the whole reason positions are kept: the comment is not inside
     * any range the edit names, so nothing can take it.</p>
     */
    @Test
    public void replacingAValueLeavesEverythingElseAlone() {
        CssSourceModel model = CssSourceModel.parse(SHEET);
        CssSourceModel.Declaration colour = declaration(ruleWith(model, ".panel"), "color");

        String after = applied(SHEET, CssEdits.replaceValue(model, colour, "#101010"));

        assertTrue("the comment survives",
                after.contains("/* The panel's own chrome. Do not restyle from a theme. */"));
        assertTrue("the value changed", after.contains("color: #101010;"));
        assertFalse("and the old one is gone", after.contains("#FFFFFF"));
        assertTrue("the rule after it is untouched", after.contains(".panel .title {"));
        assertEquals("nothing else moved", SHEET.length() - "#FFFFFF".length() + "#101010".length(),
                after.length());
    }

    @Test
    public void aDeclarationIsAddedInsideTheRule() {
        CssSourceModel model = CssSourceModel.parse(SHEET);
        CssSourceModel.Rule panel = ruleWith(model, ".panel");

        String after = applied(SHEET, CssEdits.insertDeclaration(model, panel, "opacity", "0.5"));

        assertTrue(after.contains("opacity: 0.5;"));
        CssSourceModel reparsed = CssSourceModel.parse(after);
        assertEquals("it landed in the right rule", 3,
                ruleWith(reparsed, ".panel").declarations().size());
        assertTrue("and the cascade reads it", StyleSheet.parse(after).getRules().size() >= 2);
    }

    @Test
    public void aDeclarationIsRemovedWithoutLeavingABlankLine() {
        CssSourceModel model = CssSourceModel.parse(SHEET);
        CssSourceModel.Rule panel = ruleWith(model, ".panel");

        String after = applied(SHEET, CssEdits.deleteDeclaration(model, declaration(panel, "padding")));

        assertFalse(after.contains("padding"));
        assertTrue("the rule still has its other declaration", after.contains("color: #FFFFFF;"));
        assertFalse("and no hole was left where it was", after.contains("\n\n}"));
    }

    @Test
    public void aRuleIsAppendedAtTheEndWhereItWins() {
        CssSourceModel model = CssSourceModel.parse(SHEET);

        String after = applied(SHEET, CssEdits.insertRule(model, ".panel", "color: #000000;"));
        CssSourceModel reparsed = CssSourceModel.parse(after);

        assertEquals("one more rule", model.rules().size() + 1, reparsed.rules().size());
        CssSourceModel.Rule last = reparsed.rules().get(reparsed.rules().size() - 1);
        assertEquals(".panel", last.selectors().get(0).text());
        assertTrue("appended last, which is what makes it win",
                last.range().start() > model.rules().get(model.rules().size() - 1).range().start());
    }

    /**
     * <b>Deleting a rule leaves the comment above it.</b>
     *
     * <p>A rule's range starts at its first selector, so a note above it is not inside the range. That is
     * deliberate: the comment may describe the section rather than the rule, and deleting somebody's note
     * because it sat above the thing you deleted is not recoverable from the diff.</p>
     */
    @Test
    public void deletingARuleDoesNotTakeTheCommentAboveIt() {
        CssSourceModel model = CssSourceModel.parse(SHEET);

        String after = applied(SHEET, CssEdits.deleteRule(model, ruleWith(model, ".panel")));

        assertFalse("the rule is gone", after.contains("padding: 4;"));
        assertTrue("the comment is not", after.contains("Do not restyle from a theme"));
        assertTrue("and the sibling rule survives", after.contains("font-size: 12;"));
    }

    /** Every edit leaves text the cascade can still read. */
    @Test
    public void everyEditLeavesAParseableSheet() {
        CssSourceModel model = CssSourceModel.parse(SHEET);
        CssSourceModel.Rule panel = ruleWith(model, ".panel");

        for (ChangeSet edit : new ChangeSet[]{
                CssEdits.replaceValue(model, declaration(panel, "color"), "#123456"),
                CssEdits.insertDeclaration(model, panel, "opacity", "0.5"),
                CssEdits.deleteDeclaration(model, declaration(panel, "padding")),
                CssEdits.insertRule(model, ".added", "color: #000000;"),
                CssEdits.deleteRule(model, panel)}) {
            String after = applied(SHEET, edit);
            assertFalse("still parses to rules", StyleSheet.parse(after).getRules().isEmpty());
        }
    }
}
