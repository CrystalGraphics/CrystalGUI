package com.crystalgui.style.sheet;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import org.junit.Test;

import static org.junit.Assert.*;

public class StyleSheetTest {

    @Test
    public void parsesSimpleRule() {
        var sheet = StyleSheet.parse(".button { z-index: 10; opacity: 0.5; }");
        assertEquals(1, sheet.getRules().size());
        var rule = sheet.getRules().get(0);
        assertEquals(2, rule.declarations().size());
    }

    @Test
    public void commaSeparatedSelectorsProduceSeparateRulesSharingDeclarations() {
        var sheet = StyleSheet.parse(".a, .b, #special { z-index: 5; }");
        assertEquals(3, sheet.getRules().size());
        for (var rule : sheet.getRules()) {
            assertEquals(1, rule.declarations().size());
            assertEquals(0, rule.sourceOrder());
        }
    }

    @Test
    public void commentsAreStripped() {
        var sheet = StyleSheet.parse("// a line comment\n.button { /* inline block comment */ z-index: 5; }");
        assertEquals(1, sheet.getRules().size());
        assertEquals(1, sheet.getRules().get(0).declarations().size());
    }

    @Test
    public void unknownPropertyIsSkippedNotFatal() {
        var sheet = StyleSheet.parse(".button { totally-unknown-property: 1; z-index: 5; }");
        assertEquals(1, sheet.getRules().size());
        assertEquals(1, sheet.getRules().get(0).declarations().size());
        assertEquals(StylePropertyRegistry.Z_INDEX, sheet.getRules().get(0).declarations().get(0).property());
    }

    @Test
    public void importantSuffixMarksOnlyThatDeclaration() {
        var sheet = StyleSheet.parse(".button { z-index: 5 !important; opacity: 0.5; }");
        var decls = sheet.getRules().get(0).declarations();
        assertTrue(decls.get(0).important());
        assertFalse(decls.get(1).important());
        assertEquals((Integer) 5, decls.get(0).value().compute());
    }

    @Test
    public void bucketIndexReturnsCandidatesAcrossAllElementClasses() {
        var sheet = StyleSheet.parse(".foo { z-index: 1; } .bar { z-index: 2; } #id-only { z-index: 3; } * { z-index: 4; }");

        UIElement el = new UIElement();
        el.addClass("foo");
        el.addClass("bar");

        var candidates = sheet.candidatesFor(el);
        // Should include the .foo rule, the .bar rule, and the universal rule — not the #id-only rule.
        assertEquals(3, candidates.size());
    }

    @Test
    public void sourceOrderIncrementsPerRuleBlockNotPerSelector() {
        var sheet = StyleSheet.parse(".a { z-index: 1; } .b, .c { z-index: 2; } .d { z-index: 3; }");
        var rules = sheet.getRules();
        assertEquals(4, rules.size()); // .a, .b, .c, .d
        assertEquals(0, rules.get(0).sourceOrder()); // .a
        assertEquals(1, rules.get(1).sourceOrder()); // .b
        assertEquals(1, rules.get(2).sourceOrder()); // .c (shares block with .b)
        assertEquals(2, rules.get(3).sourceOrder()); // .d
    }

    @Test
    public void marginShorthandExpandsIntoFourRealLonghands() {
        var sheet = StyleSheet.parse(".a { margin: 10px 5px; }");
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(4, decls.size());
        for (var decl : decls) {
            assertTrue("declaration should target a real longhand, not a shorthand",
                    decl.property() == LayoutProperties.MARGIN_LEFT
                            || decl.property() == LayoutProperties.MARGIN_TOP
                            || decl.property() == LayoutProperties.MARGIN_RIGHT
                            || decl.property() == LayoutProperties.MARGIN_BOTTOM);
        }
        assertEquals(LengthPercentageAuto.length(10), findValue(decls, LayoutProperties.MARGIN_TOP));
        assertEquals(LengthPercentageAuto.length(10), findValue(decls, LayoutProperties.MARGIN_BOTTOM));
        assertEquals(LengthPercentageAuto.length(5), findValue(decls, LayoutProperties.MARGIN_LEFT));
        assertEquals(LengthPercentageAuto.length(5), findValue(decls, LayoutProperties.MARGIN_RIGHT));
    }

    @Test
    public void marginAllHorizontalVerticalAliasesExpandCorrectly() {
        var all = StyleSheet.parse(".a { margin-all: 3px; }").getRules().get(0).declarations();
        assertEquals(4, all.size());

        var horizontal = StyleSheet.parse(".a { margin-horizontal: 7px; }").getRules().get(0).declarations();
        assertEquals(2, horizontal.size());
        assertEquals(LengthPercentageAuto.length(7), findValue(horizontal, LayoutProperties.MARGIN_LEFT));
        assertEquals(LengthPercentageAuto.length(7), findValue(horizontal, LayoutProperties.MARGIN_RIGHT));

        var vertical = StyleSheet.parse(".a { margin-vertical: 9px; }").getRules().get(0).declarations();
        assertEquals(2, vertical.size());
        assertEquals(LengthPercentageAuto.length(9), findValue(vertical, LayoutProperties.MARGIN_TOP));
        assertEquals(LengthPercentageAuto.length(9), findValue(vertical, LayoutProperties.MARGIN_BOTTOM));
    }

    @Test
    public void borderWidthShorthandExpandsIntoRealLonghands() {
        var decls = StyleSheet.parse(".a { border-width: 2px; }").getRules().get(0).declarations();
        assertEquals(4, decls.size());
        assertEquals(LengthPercentageAuto.length(2), findValue(decls, LayoutProperties.BORDER_LEFT));
        assertEquals(LengthPercentageAuto.length(2), findValue(decls, LayoutProperties.BORDER_TOP));
        assertEquals(LengthPercentageAuto.length(2), findValue(decls, LayoutProperties.BORDER_RIGHT));
        assertEquals(LengthPercentageAuto.length(2), findValue(decls, LayoutProperties.BORDER_BOTTOM));
    }

    @Test
    public void shorthandExpansionPreservesImportantFlag() {
        var decls = StyleSheet.parse(".a { margin: 10px !important; }").getRules().get(0).declarations();
        assertEquals(4, decls.size());
        for (var decl : decls) {
            assertTrue(decl.important());
        }
    }

    private static Object findValue(java.util.List<StyleRule.Declaration> decls, com.crystalgui.style.property.StyleProperty<?> property) {
        for (var decl : decls) {
            if (decl.property() == property) return decl.value().compute();
        }
        throw new AssertionError("No declaration found for " + property);
    }
}
