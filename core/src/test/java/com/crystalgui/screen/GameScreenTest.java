package com.crystalgui.screen;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.app.screens.ScreenExample;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A game screen of ours over the game: its own document filling the window, one at a time, closed by Escape or its own
 * keys, dropped with its hooks run, and an error panel instead of a screen that faults. The host's part (the engine's
 * screen, the Escape it hands back) is {@code CgScreens}', so these call the owner's callbacks as the host does.
 */
public class GameScreenTest {

    @Before
    public void inline() {
        System.setProperty("crystalgui.ui.async", "false");
        CgUiScreen.install();
        CgUiScreen.shown();
        CgUiScreen.hidden();
    }

    @After
    public void tearDown() {
        CgUiScreen.dispose();
        System.clearProperty("crystalgui.ui.async");
    }

    private static GameScreen up(ScreenKind kind) {
        GameScreen screen = new GameScreen(kind);
        screen.owner().shown();
        return screen;
    }

    /** The kind's builder fills a root that is the window's size, and the mode is the screen's until it is hidden. */
    @Test
    public void aScreenFillsTheWindowAndTheModeFollowsIt() {
        AtomicInteger closed = new AtomicInteger();
        UIElement[] filled = new UIElement[1];
        GameScreen screen = up(ScreenKind.of("test:settings", context -> {
            filled[0] = new UIElement();
            context.root().append(filled[0]);
            context.onClosed(closed::incrementAndGet);
        }));

        assertEquals(CgUiScreen.Mode.GAME_SCREEN, CgUiScreen.mode());
        assertEquals("test:settings", CgUiScreen.screen().id());
        assertNotNull(filled[0]);
        screen.viewport().document().update(800f, 600f);
        UIElement root = screen.viewport().document().children().get(0);
        assertEquals(800f, root.box().width(), 0.5f);
        assertEquals(600f, root.box().height(), 0.5f);

        screen.owner().hidden();

        assertEquals("the kind's close hook ran on the way out", 1, closed.get());
        assertNull(CgUiScreen.screen());
        assertFalse(CgUiScreen.mode() == CgUiScreen.Mode.GAME_SCREEN);
    }

    /** The kind says whether the game stops under it, and the host reads it from the owner. */
    @Test
    public void theKindDecidesWhetherTheGamePauses() {
        ScreenKind kind = ScreenKind.of("test:pause", context -> { });
        GameScreen running = up(kind);
        assertFalse(running.owner().pausesGame());
        assertTrue("it dims, so it covers the HUD", running.owner().hidesHud());
        running.owner().hidden();

        GameScreen paused = up(kind.pausesGame(true).background(ScreenKind.Background.NONE));
        assertTrue(paused.owner().pausesGame());
        assertFalse(paused.owner().hidesHud());
        paused.owner().hidden();
    }

    /** Its keys close it while nothing is typed; a content that closes itself asks the same way, from any thread. */
    @Test
    public void closingKeysAndTheContextAskForAClose() {
        ScreenContext[] made = new ScreenContext[1];
        GameScreen screen = up(ScreenKind.of("test:keys", context -> made[0] = context).closedBy(CgKeyCodes.KEY_O));

        assertTrue(screen.kind().closesOn(CgKeyCodes.KEY_O));
        assertFalse(screen.kind().closesOn(CgKeyCodes.KEY_P));
        assertFalse(screen.takeCloseRequest());
        made[0].close();
        assertTrue(screen.takeCloseRequest());
        assertFalse("asked once, taken once", screen.takeCloseRequest());
        screen.owner().hidden();
    }

    /** One at a time: the second takes the first's place, and the first is dropped with its hooks run. */
    @Test
    public void aSecondScreenReplacesTheFirst() {
        AtomicInteger closed = new AtomicInteger();
        GameScreen first = up(ScreenKind.of("test:first", context -> context.onClosed(closed::incrementAndGet)));
        GameScreen second = up(ScreenKind.of("test:second", context -> { }));
        // The host hides the one it replaces as the new one's init runs.
        first.owner().hidden();

        assertEquals(1, closed.get());
        assertSame("the replaced one's hide must not take the new one with it", second.kind(), CgUiScreen.screen());
        assertEquals(CgUiScreen.Mode.GAME_SCREEN, CgUiScreen.mode());
        second.owner().hidden();
    }

    /** A builder that throws leaves an error panel and the game running; the panel's Close asks to close. */
    @Test
    public void aScreenThatFaultsShowsOurErrorPanel() {
        GameScreen screen = up(ScreenKind.of("test:broken", context -> {
            context.root().append(new UIElement());
            throw new IllegalStateException("no inventory");
        }));

        assertNotNull(screen.fault());
        UIElement root = screen.viewport().document().children().get(0);
        assertEquals("the half-built content is gone, the panel is all there is", 1, root.childCount());
        assertTrue(root.children().get(0).children().get(0).hasClass(GameScreen.FAULT_CLASS));
        screen.viewport().document().update(800f, 600f);
        assertEquals("the screen carries on", CgUiScreen.Mode.GAME_SCREEN, CgUiScreen.mode());
        screen.owner().hidden();
    }

    /** What pins on the desktop stays pinned under the screen: it is the desktop's arm that draws over it. */
    @Test
    public void aPinnedWindowIsStillThereBehindAScreen() {
        CgUiScreen.desktop().addWindow(new WindowFrame("Pinned")).setPinned(true);
        GameScreen screen = up(ScreenKind.of("test:over", context -> { }));

        assertEquals(CgUiScreen.Mode.GAME_SCREEN, CgUiScreen.mode());
        assertTrue(CgUiScreen.desktop().windowManager().hasPinnedWindows());
        screen.owner().hidden();
    }

    /** The worked example builds a panel and its Close button asks the screen to close. */
    @Test
    public void theExampleScreenBuildsAndItsCloseButtonCloses() {
        GameScreen screen = up(ScreenExample.SCREEN);

        assertNull(screen.fault());
        UIElement root = screen.viewport().document().children().get(0);
        UIElement panel = root.children().get(0);
        Button close = (Button) panel.children().get(2);
        close.onPressed.emit();
        assertTrue(screen.takeCloseRequest());
        screen.owner().hidden();
    }
}
