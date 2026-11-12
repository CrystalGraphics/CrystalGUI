package com.crystalgui.screen;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The desktop's screen, inline: what its screen going away does, and the mode it decides. */
public class CgUiScreenTest {

    @Before
    public void inline() {
        System.setProperty("crystalgui.ui.async", "false");
        CgUiScreen.install();
        CgUiScreen.shown();
    }

    @After
    public void tearDown() {
        CgUiScreen.dispose();
        System.clearProperty("crystalgui.ui.async");
    }

    private static CgUiScreen.Mode modeOf(Desktop desktop, boolean anyScreenUp) {
        return CgUiScreen.Mode.of(false, anyScreenUp, desktop.parent() != null, desktop.windowManager().hasPinnedWindows());
    }

    /** Closing the screen with something pinned goes to the HUD, not to a suspend, which would answer NONE. */
    @Test
    public void closingTheScreenWithAPinnedWindowGoesToTheHud() {
        Desktop desktop = CgUiScreen.desktop();
        desktop.addWindow(new WindowFrame("Pinned")).setPinned(true);

        CgUiScreen.hidden();

        assertFalse("still on the tree, or the mode is NONE", desktop.isSuspended());
        assertTrue(desktop.windowManager().isHudMode());
        assertEquals(CgUiScreen.Mode.HUD, modeOf(desktop, false));
        assertEquals("and over somebody else's screen", CgUiScreen.Mode.OVERLAY, modeOf(desktop, true));
    }

    /** Nothing pinned is the ordinary case, and it still detaches. */
    @Test
    public void closingTheScreenWithNothingPinnedStillSuspends() {
        Desktop desktop = CgUiScreen.desktop();
        desktop.addWindow(new WindowFrame("Ordinary"));

        CgUiScreen.hidden();

        assertTrue(desktop.isSuspended());
        assertFalse(desktop.windowManager().isHudMode());
        assertEquals(CgUiScreen.Mode.NONE, modeOf(desktop, false));
    }

    /** Our screen is the desktop whatever it holds; with none up, only pinned windows show. */
    @Test
    public void theModeFollowsWhatIsUpAndWhatIsPinned() {
        assertEquals(CgUiScreen.Mode.DESKTOP, CgUiScreen.Mode.of(true, true, false, false));
        assertEquals(CgUiScreen.Mode.NONE, CgUiScreen.Mode.of(false, true, true, false));
        assertEquals(CgUiScreen.Mode.NONE, CgUiScreen.Mode.of(false, false, false, true));
        assertEquals(CgUiScreen.Mode.HUD, CgUiScreen.Mode.of(false, false, true, true));
        assertEquals(CgUiScreen.Mode.OVERLAY, CgUiScreen.Mode.of(false, true, true, true));
    }
}
