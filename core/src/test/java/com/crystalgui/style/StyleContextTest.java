package com.crystalgui.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.theme.UiThemeManager;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** A theme is a published generation: shared by every document, adopted at each one's next frame. */
public class StyleContextTest {

    private static final StyleSheet APP = StyleSheet.parse("a { color: var(--ink, #111111); }", Map.of());
    private static final StyleSheet PLAIN = StyleSheet.parse("b { color: #222222; }", Map.of());

    @After
    public void resetTheme() {
        UiThemeManager.getInstance().resetForTesting();
    }

    private static void publish(String ink) {
        StyleContext.PUBLISHED.update(old -> new StyleContext(Map.of("--ink", ink), old.resolve(StyleContext.THEME),
                old.resolve(StyleContext.SCHEME), null, null, false));
    }

    private static StyleEngine engine() {
        StyleEngine engine = new StyleEngine(List::of);
        engine.addStylesheet(StyleSheet.DEFAULT);
        engine.addStylesheet(StyleContext.THEME);
        engine.addStylesheet(APP);
        engine.addStylesheet(PLAIN);
        return engine;
    }

    @Test
    public void twoDocumentsOfOneGenerationShareTheResolvedSheets() {
        publish("#abcdef");
        StyleEngine first = engine();
        StyleEngine second = engine();

        assertSame("one rule index per generation, not per document",
                first.effectiveSheets().get(2), second.effectiveSheets().get(2));
        assertNotSame("a sheet naming var() is bound to the theme, never the text it was parsed from",
                APP, first.effectiveSheets().get(2));
        assertSame("a sheet naming no var() builds nothing", PLAIN, first.effectiveSheets().get(3));
    }

    @Test
    public void aDocumentAdoptsANewGenerationAtItsNextFrame() {
        publish("#abcdef");
        StyleEngine engine = engine();
        StyleSheet before = engine.effectiveSheets().get(2);

        publish("#fedcba");
        assertSame("nothing moves until the document's own frame", before, engine.effectiveSheets().get(2));

        engine.calculateStyle(0f);
        StyleSheet after = engine.effectiveSheets().get(2);
        assertNotSame(before, after);
        assertEquals(1, after.getRules().size());
        // The base sheet is untouched: its text is the same unthemed rules it always was.
        assertEquals(1, APP.getRules().size());
    }

    @Test
    public void theUserAgentSheetIsNeverRewrittenByATheme() {
        int rules = StyleSheet.DEFAULT.getRules().size();
        publish("#abcdef");
        StyleEngine engine = engine();
        engine.calculateStyle(0f);

        assertEquals(rules, StyleSheet.DEFAULT.getRules().size());
        assertNotSame("the document reads the generation's own copy", StyleSheet.DEFAULT, engine.effectiveSheets().get(0));
    }
}
