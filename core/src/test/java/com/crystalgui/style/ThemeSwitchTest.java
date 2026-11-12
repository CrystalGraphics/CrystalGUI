package com.crystalgui.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.core.async.UiSequence;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.style.theme.UiThemeManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** A theme switch asked for by a document is built once, off every document, and adopted by each at its own frame. */
public class ThemeSwitchTest {

    private static final StyleSheet APP = StyleSheet.parse("a { color: var(--accent, #123456); }", Map.of());

    private final UiThemeManager themes = UiThemeManager.getInstance();

    @Before
    public void registered() {
        ThemeRegistry.registerBuiltins();
        themes.resetForTesting();
    }

    @After
    public void reset() {
        themes.resetForTesting();
    }

    private static StyleEngine document() {
        StyleEngine engine = new StyleEngine(List::of);
        UiThemeManager.getInstance().installInto(engine);
        engine.addStylesheet(APP);
        return engine;
    }

    @Test
    public void aSwitchFromADocumentIsBuiltOnceAndEveryDocumentAdoptsTheSameSheets() throws Exception {
        List<UiTheme> all = ThemeRegistry.themes();
        assertTrue("two themes to switch between", all.size() >= 2);
        StyleEngine first = document();
        StyleEngine second = document();
        StyleEngine third = document();
        long before = StyleContext.number();

        UiSequence asker = UiSequence.create("theme-asker");
        CountDownLatch asked = new CountDownLatch(1);
        asker.execute(() -> {
            themes.setTheme(all.get(0).id());
            asked.countDown();
        });
        assertTrue(asked.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (StyleContext.number() == before && System.nanoTime() < deadline) Thread.sleep(5);

        assertEquals("one generation for one switch", before + 1, StyleContext.number());
        assertEquals(all.get(0).id(), StyleContext.current().themeId());
        for (StyleEngine engine : List.of(first, second, third)) engine.calculateStyle(0f);
        StyleSheet shared = first.effectiveSheets().get(3);
        assertNotNull(shared);
        assertNotSame("the app sheet is bound to the theme", APP, shared);
        assertSame(shared, second.effectiveSheets().get(3));
        assertSame(shared, third.effectiveSheets().get(3));
        asker.close();
    }

    @Test
    public void aSwitchFromAnyOtherThreadIsBuiltBeforeItReturns() {
        List<UiTheme> all = ThemeRegistry.themes();
        long before = StyleContext.number();
        assertTrue(themes.setTheme(all.get(0).id()));
        assertEquals(all.get(0).id(), themes.activeThemeId());
        assertEquals(before + 1, StyleContext.number());
        assertTrue("an unknown theme is refused and the generation stands",
                !themes.setTheme("no-such-theme") && StyleContext.number() == before + 1);
    }
}
