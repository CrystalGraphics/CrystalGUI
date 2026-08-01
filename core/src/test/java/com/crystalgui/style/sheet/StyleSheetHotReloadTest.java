package com.crystalgui.style.sheet;

import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>Reloading a stylesheet must reach windows that already registered it.</b>
 *
 * <p>The hot-reload path is {@code StyleSheetRegistry.reloadAll()} followed by
 * {@code StyleEngine.invalidateAllMatches()}. These pin the two things that make it work rather than
 * merely appear to: the sheet keeps its identity, and a rule <em>removed</em> from the file stops
 * applying — which a naive re-match would get wrong by adding new candidates on top of the old ones.</p>
 */
public class StyleSheetHotReloadTest extends UiTestBase {

    private UIWindow window;
    private UIElement box;

    private StyleSheet install(String css) {
        StyleSheet sheet = StyleSheet.parse(css);
        box = new UIElement();
        box.addClass("panel");
        UIElement root = new UIElement();
        root.addChild(box);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(sheet);
        window.init(400, 300);
        settle();
        return sheet;
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private void reload(StyleSheet sheet, String css) {
        sheet.replaceRules(StyleSheet.parse(css).getRules());
        window.getStyleEngine().invalidateAllMatches();
        settle();
    }

    private int opacityPercent() {
        return Math.round(box.getStyle().getGeneralGroup().opacity() * 100f);
    }

    /** A changed declaration reaches an element that was already styled. */
    @Test
    public void aReloadRestylesAnAlreadyRegisteredSheet() {
        StyleSheet sheet = install(".panel { opacity: 0.25; }");
        assertEquals(25, opacityPercent());

        reload(sheet, ".panel { opacity: 0.75; }");
        assertEquals("the new value must win", 75, opacityPercent());
    }

    /**
     * <b>And a rule DELETED from the file must stop applying.</b>
     *
     * <p>The failure mode this guards is silent: re-matching that only adds candidates leaves the old
     * winner in place, so a property you removed from the CSS keeps its value and the reload looks like it
     * works for edits but not for deletions. {@code StyleEngine.rematch} avoids it by remembering what it
     * applied per element and replacing that whole set atomically.</p>
     */
    @Test
    public void aReloadDropsARuleThatWasDeleted() {
        StyleSheet sheet = install(".panel { opacity: 0.25; }");
        assertEquals(25, opacityPercent());

        reload(sheet, ".other { opacity: 0.25; }");
        assertEquals("with no rule left to match, opacity returns to its initial value",
                Math.round(StylePropertyRegistry.OPACITY.initialValue * 100f), opacityPercent());
    }

    /** A newly added rule matches too, which needs the selector INDICES rebuilt, not just the rule list. */
    @Test
    public void aReloadPicksUpANewlyAddedRule() {
        StyleSheet sheet = install(".other { opacity: 0.25; }");

        reload(sheet, ".panel { opacity: 0.5; }");
        assertEquals("a rule added by the reload must match through the rebuilt index",
                50, opacityPercent());
    }

    /**
     * <b>The sheet instance survives, which is what lets an existing registration see the change.</b>
     *
     * <p>{@code StyleEngine} holds sheets in a list and {@code StyleSheet.DEFAULT} is a
     * {@code static final}, so a reload that produced a new object would update nothing already on screen
     * and could not replace {@code DEFAULT} at all.</p>
     */
    @Test
    public void aReloadKeepsTheSheetIdentity() {
        StyleSheet sheet = install(".panel { opacity: 0.25; }");
        StyleSheet registered = window.getStyleEngine().getSheets().get(0);
        assertSame("the engine holds the sheet we installed", sheet, registered);

        StyleSheet reparsed = StyleSheet.parse(".panel { opacity: 0.75; }");
        assertNotSame("parse always builds a new object -- that is what replaceRules exists to avoid",
                sheet, reparsed);

        sheet.replaceRules(reparsed.getRules());
        window.getStyleEngine().invalidateAllMatches();
        settle();

        assertSame("still the same instance after a reload",
                sheet, window.getStyleEngine().getSheets().get(0));
        assertEquals(75, opacityPercent());
    }

    /**
     * <b>{@code StyleSheet.DEFAULT} is NOT the registry's cached instance — it is a copy at
     * {@code USER_AGENT} origin.</b>
     *
     * <p>This is the trap {@code reloadAll()} has to work around, and it is written down because the
     * opposite reads as obviously true: {@code loadUserAgentSheet} does fetch the sheet from the registry.
     * It then rebuilds it at a different origin, because the cache holds sheets at the ordinary author
     * origin and the user-agent sheet must sit below every theme.</p>
     *
     * <p>The first version of this test asserted the identity and failed — which is the only reason
     * {@code reloadAll()} refills {@code DEFAULT} explicitly instead of silently ignoring every edit to
     * {@code default.css}.</p>
     */
    @Test
    public void theUserAgentSheetIsACopyAtUserAgentOrigin() {
        assertNotSame("DEFAULT is rebuilt at a different origin, not shared with the cache",
                StyleSheet.DEFAULT, StyleSheetRegistry.of(StyleSheetRegistry.DEFAULT_SHEET));
        assertEquals(StyleOrigin.USER_AGENT, StyleSheet.DEFAULT.getOrigin());
        assertEquals(StyleOrigin.STYLESHEET,
                StyleSheetRegistry.of(StyleSheetRegistry.DEFAULT_SHEET).getOrigin());
    }

    /**
     * <b>And {@code reloadAll()} must therefore reach it anyway.</b>
     *
     * <p>Emptied first to stand in for a stale sheet, since a test cannot edit {@code default.css} on
     * disk. If the reload did not refill {@code DEFAULT}, it would still be empty afterwards — which in
     * the harness is every widget laying out at zero size after the first Ctrl+R.</p>
     */
    @Test
    public void reloadAllRefillsTheUserAgentSheetToo() {
        StyleSheet userAgent = StyleSheet.DEFAULT;
        assertFalse("default.css must have loaded for this test to mean anything",
                userAgent.getRules().isEmpty());

        userAgent.replaceRules(java.util.List.of());
        assertTrue(userAgent.getRules().isEmpty());

        StyleSheetRegistry.reloadAll();

        assertSame("identity survives", userAgent, StyleSheet.DEFAULT);
        assertFalse("reloadAll must refill the user-agent sheet", userAgent.getRules().isEmpty());
        assertEquals("and leave it at USER_AGENT origin", StyleOrigin.USER_AGENT, userAgent.getOrigin());
    }
}
