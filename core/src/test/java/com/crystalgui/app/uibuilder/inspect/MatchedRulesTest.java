package com.crystalgui.app.uibuilder.inspect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>L3.7 — the cascade for one element, honestly.</b>
 *
 * <p>Every candidate the engine kept, grouped by the rule that wrote it, with the loser marked. A pane
 * that re-decided the ordering itself would eventually disagree with what is on screen and be believed,
 * so this asks {@code computeCandidateSlot} who won rather than working it out again.</p>
 */
public class MatchedRulesTest extends UiDocumentTestBase {

    /** What the shipped user-agent sheet sets on everything -- {@code * { font-size: 10 }}. */
    private static final float UA_FONT_SIZE = 10f;

    private static final float THEME_FONT_SIZE = 20f;

    /**
     * The real two-sheet stack: {@code StyleSheet.DEFAULT} under a theme.
     *
     * <p>The UA sheet is the shipped one rather than a stand-in, because it is the only sheet that
     * carries {@code USER_AGENT} origin -- and that origin is precisely what the pane has to show as
     * beaten.</p>
     */
    private UIElement labelUnderTwoSheets() {
        UIElement label = new UIElement().layout(l -> l.width(40).height(20));
        label.addClass("label");
        document.append(label);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.styleEngine().addStylesheet(StyleSheet.parse(".label { font-size: 20 }"));
        document.update(W, H);
        return label;
    }

    /**
     * <b>The UA rule is struck through under the theme rule.</b>
     *
     * <p>Both matched; the theme won. What the pane draws struck through is the one that lost, and the
     * fact it matched at all is why it is shown rather than hidden.</p>
     */
    @Test
    public void aUaRuleIsMarkedLostUnderAThemeRule() {
        UIElement label = labelUnderTwoSheets();
        assertEquals("the theme won on screen", THEME_FONT_SIZE,
                label.getStyle().getComputed(StylePropertyRegistry.FONT_SIZE), 0.01f);

        MatchedRules.Declaration ua = sizeFrom(label, UA_FONT_SIZE);
        MatchedRules.Declaration theme = sizeFrom(label, THEME_FONT_SIZE);

        assertNotNull("the user-agent declaration is still listed", ua);
        assertNotNull("and so is the theme's", theme);
        assertFalse("the one that lost is struck through", ua.won());
        assertTrue("and the one on screen is not", theme.won());
    }

    /** Weakest first, so the winner reads at the end of the history that produced it. */
    @Test
    public void rulesAreListedWeakestFirst() {
        UIElement label = labelUnderTwoSheets();
        List<MatchedRules.Rule> rules = MatchedRules.of(label);

        int uaAt = indexOfSize(rules, UA_FONT_SIZE);
        int themeAt = indexOfSize(rules, THEME_FONT_SIZE);
        assertTrue("both are there", uaAt >= 0 && themeAt >= 0);
        assertTrue("the user-agent rule comes before the theme's", uaAt < themeAt);
    }

    /** A rule carries the numbers that trace it back to its text. @see CssSourceModel */
    @Test
    public void aRuleFromASheetCarriesItsSheetAndRuleNumber() {
        UIElement label = labelUnderTwoSheets();

        for (MatchedRules.Rule rule : MatchedRules.of(label)) {
            if (rule.origin() != StyleOrigin.STYLESHEET && rule.origin() != StyleOrigin.USER_AGENT) {
                continue;
            }
            assertTrue("a sheet rule knows which sheet: " + rule, rule.sheetIndex() >= 0);
            assertTrue("and which rule in it: " + rule, rule.ruleOrder() >= 0);
        }
    }

    /** An element the cascade never visited answers nothing rather than throwing. */
    @Test
    public void anElementWithNoCandidatesIsEmpty() {
        assertTrue(MatchedRules.of(new UIElement()).isEmpty());
    }

    private static MatchedRules.Declaration sizeFrom(UIElement element, float size) {
        for (MatchedRules.Rule rule : MatchedRules.of(element)) {
            for (MatchedRules.Declaration declaration : rule.declarations()) {
                if (isSize(declaration, size)) return declaration;
            }
        }
        return null;
    }

    private static int indexOfSize(List<MatchedRules.Rule> rules, float size) {
        for (int i = 0; i < rules.size(); i++) {
            for (MatchedRules.Declaration declaration : rules.get(i).declarations()) {
                if (isSize(declaration, size)) return i;
            }
        }
        return -1;
    }

    private static boolean isSize(MatchedRules.Declaration declaration, float size) {
        return declaration.property() == StylePropertyRegistry.FONT_SIZE
                && declaration.value() instanceof Number number
                && Math.abs(number.floatValue() - size) < 0.01f;
    }
}
