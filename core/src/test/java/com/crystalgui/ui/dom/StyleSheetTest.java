package com.crystalgui.ui.dom;

import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import dev.vfyjxf.taffy.style.BoxSizing;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import org.junit.Test;

import static org.junit.Assert.*;

public class StyleSheetTest extends UiDocumentTestBase {

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

    @Test
    public void cssVariableSubstitutesInDeclarationValue() {
        // .vars's own rule has zero real declarations (only --gap, which isn't one) so it's
        // dropped entirely (see parse()'s declarations.isEmpty() -> continue) — .a's rule is the
        // only one that ends up in getRules().
        var sheet = StyleSheet.parse(".vars { --gap: 10px; } .a { margin-top: var(--gap); }");
        assertEquals(1, sheet.getRules().size());
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals(LengthPercentageAuto.length(10), findValue(decls, LayoutProperties.MARGIN_TOP));
    }

    @Test
    public void cssVariableForwardReferenceWorks() {
        // Referenced by a rule declared BEFORE the rule that defines it — real CSS custom
        // properties don't care about declaration order within their scope, and the two-pass
        // parse (collectVariables runs over the whole sheet before any rule is parsed) must not
        // either.
        var sheet = StyleSheet.parse(".a { z-index: var(--my-z); } .vars { --my-z: 7; }");
        assertEquals(1, sheet.getRules().size());
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals((Integer) 7, decls.get(0).value().compute());
    }

    @Test
    public void cssVariableDeclarationItselfIsNotEmittedAsARealDeclaration() {
        var sheet = StyleSheet.parse(".a { --gap: 10px; z-index: 5; }");
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals(StylePropertyRegistry.Z_INDEX, decls.get(0).property());
    }

    @Test
    public void boxSizingPropertyParsesToBorderBox() {
        var sheet = StyleSheet.parse(".a { box-sizing: border-box; }");
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals(LayoutProperties.BOX_SIZING, decls.get(0).property());
        assertEquals(BoxSizing.BORDER_BOX, decls.get(0).value().compute());
    }

    @Test
    public void boxSizingDefaultsToBorderBoxWhenUnset() {
        // Deliberate project default, not real CSS's actual initial value (content-box) — see
        // LayoutProperties.BOX_SIZING / TaffyBridge.DEFAULT_TAFFY_STYLE.
        assertEquals(BoxSizing.BORDER_BOX, LayoutProperties.BOX_SIZING.initialValue);
    }

    @Test
    public void boxSizingCanBeSetToContentBoxExplicitly() {
        var sheet = StyleSheet.parse(".a { box-sizing: content-box; }");
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals(BoxSizing.CONTENT_BOX, decls.get(0).value().compute());
    }

    @Test
    public void fontSizeParsesToFloat() {
        var sheet = StyleSheet.parse(".a { font-size: 20; }");
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals(StylePropertyRegistry.FONT_SIZE, decls.get(0).property());
        assertEquals((Float) 20f, decls.get(0).value().compute());
    }

    @Test
    public void fontFamilySingleParsesToOneElementList() {
        var sheet = StyleSheet.parse(".a { font-family: \"crystalgraphics:fonts/A.ttf\"; }");
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals(java.util.List.of("crystalgraphics:fonts/A.ttf"), decls.get(0).value().compute());
    }

    @Test
    public void fontFamilyFallbackStackParsesToOrderedList() {
        var sheet = StyleSheet.parse(".a { font-family: \"crystalgraphics:fonts/A.ttf\", \"crystalgraphics:fonts/B.ttf\"; }");
        var decls = sheet.getRules().get(0).declarations();
        assertEquals(1, decls.size());
        assertEquals(java.util.List.of("crystalgraphics:fonts/A.ttf", "crystalgraphics:fonts/B.ttf"),
                decls.get(0).value().compute());
    }

    @Test
    public void fontSizeAndFontFamilyDefaultsAreInheritable() {
        assertTrue(StylePropertyRegistry.FONT_SIZE.isInheritable());
        assertTrue(StylePropertyRegistry.FONT_FAMILY.isInheritable());
        assertEquals((Float) 16f, StylePropertyRegistry.FONT_SIZE.initialValue);
        // Must track CgUiPaintContext.DEFAULT_FONT_STACK. The two are a documented pair — an element
        // with no font-family anywhere in its ancestor chain falls back to this initial value, and it
        // has to name a font the paint context has actually loaded or text renders as nothing.
        // They silently diverged once already (the property was reverted in one commit, this
        // assertion updated to a different font two commits later), so if you change one, change both.
        //
        // PROPORTIONAL. The monospace face is applied by ua/editor.css to the editor and to `.__syntax__`
        // rather than being the default for every widget — mono chrome was tried and is not what either
        // reference does. @see FontStackFallbackTest for the preference-list behaviour itself.
        assertEquals(java.util.List.of("crystalgraphics:IBMPlexSans-Regular.ttf"),
                StylePropertyRegistry.FONT_FAMILY.initialValue);
    }

    // ── `outline` shorthand disambiguation ──────────────────────────────────────────────────────
    // `outline` is polymorphic: a drawable slot OR a width/color shorthand, decided by the value's
    // shape. These pin each branch, since a mis-dispatch is silent (you just get the wrong property).

    private static java.util.List<StyleRule.Declaration> outlineDecls(String value) {
        return StyleSheet.parse(".a { outline: " + value + "; }").getRules().get(0).declarations();
    }

    private static boolean declares(java.util.List<StyleRule.Declaration> decls,
                                    com.crystalgui.style.property.StyleProperty<?> property) {
        return decls.stream().anyMatch(d -> d.property() == property);
    }

    @Test
    public void outlineWithFunctionValueResolvesToTheDrawableSlot() {
        var decls = outlineDecls("asset(\"crystalgui:ore\", \"focus-ring\")");
        assertEquals(1, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE));
    }

    @Test
    public void outlineWithWidthAndColorExpandsToBothLonghands() {
        var decls = outlineDecls("2px #4488ff");
        assertEquals(2, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_WIDTH));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_COLOR));
        assertFalse(declares(decls, StylePropertyRegistry.OUTLINE));
    }

    @Test
    public void outlineShorthandIsOrderIndependent() {
        var decls = outlineDecls("#4488ff 2px");
        assertEquals(2, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_WIDTH));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_COLOR));
    }

    @Test
    public void outlineWidthOnlyExpandsToWidth() {
        var decls = outlineDecls("1px");
        assertEquals(1, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_WIDTH));
    }

    /** A bare color means outline-color, never a solid-fill drawable — a solid drawable outline
     * would just cover the element, so it's never what an author meant. */
    @Test
    public void outlineBareColorExpandsToColorNotDrawable() {
        var decls = outlineDecls("#4488ff");
        assertEquals(1, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_COLOR));
        assertFalse(declares(decls, StylePropertyRegistry.OUTLINE));
    }

    /** rgb()/rgba() are colors, not drawable functions, despite having parens. */
    @Test
    public void outlineRgbFunctionIsTreatedAsColorNotDrawable() {
        var decls = outlineDecls("2px rgb(68, 136, 255)");
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_COLOR));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_WIDTH));
        assertFalse(declares(decls, StylePropertyRegistry.OUTLINE));
    }

    @Test
    public void outlineNoneExpandsToZeroWidth() {
        var decls = outlineDecls("none");
        assertEquals(1, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_WIDTH));
    }

    /** {@code outline-offset} is edge shorthand (4 longhands); width/colour are single properties. */
    @Test
    public void outlineOffsetAndWidthAreSeparateProperties() {
        var decls = StyleSheet.parse(".a { outline-offset: 2px; outline-width: 3px; outline-color: #fff; }")
                .getRules().get(0).declarations();
        assertEquals(6, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_OFFSET_TOP));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_OFFSET_RIGHT));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_OFFSET_LEFT));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_WIDTH));
        assertTrue(declares(decls, StylePropertyRegistry.OUTLINE_COLOR));
    }

    /** One value hits all four edges; four values are top/right/bottom/left, clockwise like margin. */
    @Test
    public void outlineOffsetExpandsPerEdge() {
        assertEquals(2f, offsetEdge("2px", StylePropertyRegistry.OUTLINE_OFFSET_LEFT), 0.0001f);

        // The case this exists for: tighten one edge, leave the other three flush.
        assertEquals(-2f, offsetEdge("-2px 0 0 0", StylePropertyRegistry.OUTLINE_OFFSET_TOP), 0.0001f);
        assertEquals(0f, offsetEdge("-2px 0 0 0", StylePropertyRegistry.OUTLINE_OFFSET_RIGHT), 0.0001f);
        assertEquals(0f, offsetEdge("-2px 0 0 0", StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM), 0.0001f);
        assertEquals(0f, offsetEdge("-2px 0 0 0", StylePropertyRegistry.OUTLINE_OFFSET_LEFT), 0.0001f);

        // Two values are vertical/horizontal, so `left` must come from the SECOND token.
        assertEquals(4f, offsetEdge("1px 4px", StylePropertyRegistry.OUTLINE_OFFSET_LEFT), 0.0001f);
        assertEquals(1f, offsetEdge("1px 4px", StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM), 0.0001f);
    }

    private static float offsetEdge(String shorthandValue,
                                    com.crystalgui.style.property.StyleProperty<LengthPercent> edge) {
        var decls = StyleSheet.parse(".a { outline-offset: " + shorthandValue + "; }")
                .getRules().get(0).declarations();
        return decls.stream()
                .filter(d -> d.property() == edge)
                .map(d -> ((LengthPercent) d.value().compute()).value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no declaration for " + edge.name));
    }

    /**
     * The {@code mask} layer gets the same geometry longhands the other paint layers have —
     * {@code -origin}/{@code -fit}/{@code -position} mirroring {@code overlay-*}, plus
     * {@code -offset} mirroring {@code outline-offset}. Since the mask's alpha is what the children
     * layer is multiplied by, re-boxing it is what moves the actual clip region.
     */
    @Test
    public void maskGeometryLonghandsAreSeparateRegisteredProperties() {
        var decls = StyleSheet.parse(".a { mask-origin: padding-box; mask-fit: contain;"
                        + " mask-position: top-left; mask-offset: 4px; }")
                .getRules().get(0).declarations();
        assertEquals(4, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.MASK_ORIGIN));
        assertTrue(declares(decls, StylePropertyRegistry.MASK_FIT));
        assertTrue(declares(decls, StylePropertyRegistry.MASK_POSITION));
        assertTrue(declares(decls, StylePropertyRegistry.MASK_OFFSET));
    }

    /** The longhands must not collide with the {@code mask} drawable shorthand itself. */
    @Test
    public void maskShorthandStillParsesAlongsideItsLonghands() {
        var decls = StyleSheet.parse(".a { mask: #FFFFFF; mask-offset: -2px; }")
                .getRules().get(0).declarations();
        assertEquals(2, decls.size());
        assertTrue(declares(decls, StylePropertyRegistry.MASK));
        assertTrue(declares(decls, StylePropertyRegistry.MASK_OFFSET));
    }

    // ── sprite() tiling args, end-to-end through the stylesheet parser ──────────────────────────

    private static com.crystalgui.render.texture.CgUiSprite parseSpriteValue(String extraArgs) {
        var decls = StyleSheet.parse(".a { background: sprite(\"t.png\", \"0 0 16 16\", \"4 4 4 4\""
                + extraArgs + "); }").getRules().get(0).declarations();
        assertEquals(1, decls.size());
        Object value = decls.get(0).value().compute();
        assertTrue("expected a CgUiSprite, got " + value,
                value instanceof com.crystalgui.render.texture.CgUiSprite);
        return (com.crystalgui.render.texture.CgUiSprite) value;
    }

    @Test
    public void spriteDefaultsToStretchOnBothAxes() {
        var sprite = parseSpriteValue("");
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.STRETCH, sprite.getRepeatX());
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.STRETCH, sprite.getRepeatY());
    }

    /** One keyword sets both axes, matching CSS border-image-repeat's shorthand. */
    @Test
    public void spriteSingleRepeatKeywordAppliesToBothAxes() {
        var sprite = parseSpriteValue(", \"round\"");
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.ROUND, sprite.getRepeatX());
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.ROUND, sprite.getRepeatY());
    }

    @Test
    public void spriteTwoRepeatKeywordsSetAxesIndependently() {
        var sprite = parseSpriteValue(", \"repeat space\"");
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.REPEAT, sprite.getRepeatX());
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.SPACE, sprite.getRepeatY());
    }

    /** Trailing args are type-sniffed, so the size reference and the tiling keyword may appear in
     * either order — the same contract image() already had. */
    @Test
    public void spriteTrailingArgsAreOrderIndependent() {
        var a = parseSpriteValue(", \"64 64\", \"round\"");
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.ROUND, a.getRepeatX());
        var b = parseSpriteValue(", \"round\", \"64 64\"");
        assertEquals(com.crystalgui.render.texture.CgUiRepeat.ROUND, b.getRepeatX());
    }

    @Test
    public void spriteRejectsAnUnknownTrailingArg() {
        var decls = StyleSheet.parse(
                        ".a { background: sprite(\"t.png\", \"0 0 16 16\", \"4 4 4 4\", \"wobble\"); }")
                .getRules().get(0).declarations();
        assertTrue("an unparseable sprite() must not yield a drawable",
                decls.isEmpty() || decls.get(0).value().compute() == null);
    }

    /** Border/slice geometry survives the widened trailing-arg loop. */
    @Test
    public void spriteBorderAndSizeReferenceStillParse() {
        var sprite = parseSpriteValue(", \"64 64\"");
        assertEquals(4f, sprite.getBorderLeft(), 0.001f);
        assertEquals(4f, sprite.getBorderBottom(), 0.001f);
        // 16x16 sprite with a 4px border on each side -> 8x8 centre, the horizontal tile size.
        assertEquals(8f, sprite.centerSourceWidth(), 0.001f);
        assertEquals(8f, sprite.centerSourceHeight(), 0.001f);
    }

    /** borderScale multiplies the tile size, so a 2x scale doubles it (Unity's PPU multiplier). */
    @Test
    public void borderScaleScalesTheCentreTileSize() {
        var sprite = parseSpriteValue("");
        sprite.setBorderScale(2f);
        assertEquals(16f, sprite.centerSourceWidth(), 0.001f);
    }

    private static Object findValue(java.util.List<StyleRule.Declaration> decls, com.crystalgui.style.property.StyleProperty<?> property) {
        for (var decl : decls) {
            if (decl.property() == property) return decl.value().compute();
        }
        throw new AssertionError("No declaration found for " + property);
    }
}
