package com.crystalgui.style.sheet.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;

/**
 * <b>L3.3 — a sheet's text, and where every part of it is.</b>
 *
 * <p>The cascade strips comments before it parses, which shifts every offset after the first one, so
 * nothing could say which line a rule came from. These assert the positions are exact against the sheets
 * actually shipped, not against a sample chosen to be easy.</p>
 */
public class CssSourceModelTest {

    private static String sourceOf(String part) {
        int colon = part.indexOf(':');
        String namespace = part.substring(0, colon);
        String path = part.substring(colon + 1);
        return CgIO.loadSource(namespace + ":ui/styles/" + path + ".css");
    }

    /**
     * <b>Every range slices its own text back, out of every shipped user-agent part.</b>
     *
     * <p>The whole point of the model: a range that is off by the length of one stripped comment reads
     * as a rule pointing at its neighbour, which looks like a mapping bug in whatever asked.</p>
     */
    @Test
    public void everyPositionRoundTripsAgainstEveryShippedPart() {
        int rules = 0;
        for (String part : StyleSheetRegistry.DEFAULT_SHEET_PARTS) {
            String source = sourceOf(part);
            assertNotNull("the shipped part loads: " + part, source);
            CssSourceModel model = CssSourceModel.parse(source);
            assertFalse("a shipped part has rules: " + part, model.rules().isEmpty());

            for (CssSourceModel.Rule rule : model.rules()) {
                assertEquals(part + ": the rule's own text",
                        source.substring(rule.range().start(), rule.range().end()),
                        model.textOf(rule.range()));

                for (CssSourceModel.Selector selector : rule.selectors()) {
                    assertEquals(part + ": selector text is where it says",
                            selector.text(), model.textOf(selector.range()));
                    assertFalse("a selector range is trimmed", selector.text().isBlank());
                }
                for (CssSourceModel.Declaration declaration : rule.declarations()) {
                    assertEquals(part + ": property is where it says",
                            declaration.property(), model.textOf(declaration.propertyRange()));
                    assertEquals(part + ": value is where it says",
                            declaration.value(), model.textOf(declaration.valueRange()));
                }
                rules++;
            }
        }
        assertTrue("the shipped sheets really were read: " + rules, rules > 500);
    }

    /**
     * <b>A rule's number is the one the cascade gave it.</b>
     *
     * <p>Which is how a matched {@code StyleRule} is traced back to its text — the number is all the two
     * halves share.</p>
     */
    @Test
    public void sourceOrderLinesUpWithWhatTheCascadeParsed() {
        String css = "a { color: red }\n"
                + "/* skipped: no declarations */\n"
                + "b { }\n"
                + "c, d { color: blue }\n";

        CssSourceModel model = CssSourceModel.parse(css);
        StyleSheet parsed = StyleSheet.parse(css);

        assertEquals("three rules as written", 3, model.rules().size());
        assertEquals("the empty one is not numbered", -1, model.rules().get(1).sourceOrder());
        assertEquals(0, model.rules().get(0).sourceOrder());
        assertEquals("and the one after it takes the next number, not a skipped one",
                1, model.rules().get(2).sourceOrder());

        assertEquals("two selectors, one rule", 2, model.rules().get(2).selectors().size());
        for (StyleRule rule : parsed.getRules()) {
            assertNotNull("every parsed rule traces back to text",
                    model.ruleAt(rule.sourceOrder()));
        }
    }

    /** Comments are kept, and keep their place. */
    @Test
    public void aCommentIsPreservedWhereItWasWritten() {
        String css = "/* the header */\na { color: red } /* trailing */\n";
        CssSourceModel model = CssSourceModel.parse(css);

        List<CssSourceModel.Comment> comments = model.comments();
        assertEquals(2, comments.size());
        assertEquals("/* the header */", model.textOf(comments.get(0).range()));
        assertEquals("/* trailing */", model.textOf(comments.get(1).range()));
        assertEquals("and the rule after one is still placed exactly",
                "a { color: red }", model.textOf(model.rules().get(0).range()).trim());
    }

    /**
     * <b>A comment cannot open a rule.</b>
     *
     * <p>Masking rather than stripping is what makes this true AND keeps the offsets: braces and colons
     * inside a comment are read as whitespace, and everything after it is still where it was.</p>
     */
    @Test
    public void bracesInsideACommentAreNotStructure() {
        String css = "/* a { color: red } */\nb { color: blue }\n";
        CssSourceModel model = CssSourceModel.parse(css);

        assertEquals("one real rule", 1, model.rules().size());
        assertEquals("b", model.rules().get(0).selectors().get(0).text());
        assertEquals("color", model.rules().get(0).declarations().get(0).property());
        assertEquals("blue", model.rules().get(0).declarations().get(0).value());
    }

    @Test
    public void importantIsRead() {
        CssSourceModel model = CssSourceModel.parse("a { color: red !important; }");
        CssSourceModel.Declaration declaration = model.rules().get(0).declarations().get(0);

        assertTrue(declaration.important());
        assertEquals("red !important", model.textOf(declaration.valueRange()));
    }
}
