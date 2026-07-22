package com.crystalgui.style.sheet;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.UIElement;
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
}
