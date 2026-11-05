package com.crystalgui.desktop;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>Fullscreen grows the window into the taskbar's row as it plays.</b> The maximise aimed at the work area measured
 * on the press, with the bar still in it, so the window stopped at the bar's edge and jumped the rest at the end.
 */
public class FullscreenAnimationTest extends UiDocumentTestBase {

    @After
    public void restore() {
        Desktop.setAnimationsEnabled(false);
    }

    @Test
    public void theWindowGrowsPastTheBarsEdgeBeforeItLands() {
        Desktop.setAnimationsEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        WindowFrame frame = desktop.addWindow(new WindowFrame("Window"));
        frame.resizeTo(200f, 150f);
        for (int i = 0; i < 4; i++) frame();
        float barred = desktop.windowLayer().box().height();
        Desktop.setAnimationsEnabled(true);

        frame.enterFullscreen();
        boolean past = false;
        frame();
        // Every frame the resize plays, however many the machine gives it
        while (frame.animator().isPlaying()) {
            float h = frame.box().height();
            if (h > barred + 1f) past = true;
            frame();
        }
        assertTrue("the window stopped at the bar's edge until the resize ended", past);
        for (int i = 0; i < 3; i++) frame();
        assertEquals("it did not land filling the screen", desktop.windowLayer().box().height(), frame.box().height(), 0.5f);
    }

    /**
     * Out of fullscreen to a restored window, the size eases down from the whole screen: the work-area clamp cut its first
     * frame to the area the taskbar had already taken back.
     */
    @Test
    public void backToRestoredTheFirstFrameIsNotClampedToTheWorkArea() {
        Desktop.setAnimationsEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        WindowFrame frame = desktop.addWindow(new WindowFrame("Window"));
        frame.resizeTo(300f, 200f).moveTo(40f, 40f);
        for (int i = 0; i < 4; i++) frame();
        frame.enterFullscreen();
        for (int i = 0; i < 4; i++) frame();
        Desktop.setAnimationsEnabled(true);

        frame.exitFullscreen();
        frame();
        float area = desktop.windowLayer().box().height();
        assertTrue("the taskbar did not take its row back", area < frame.box().height() + 40f);
        assertTrue("the first frame out was clamped to the work area: " + frame.box().height() + " <= " + area,
                frame.box().height() > area + 1f);
        while (frame.animator().isPlaying()) frame();
        for (int i = 0; i < 3; i++) frame();
        assertEquals("it did not land restored", 200f, frame.box().height(), 0.5f);
    }

    /** Out of fullscreen to maximised, the window eases into the work area the taskbar takes back, never cuts to it. */
    @Test
    public void backToMaximisedTheWindowEasesIntoTheBarsReturn() {
        Desktop.setAnimationsEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        WindowFrame frame = desktop.addWindow(new WindowFrame("Window"));
        frame.maximize();
        for (int i = 0; i < 4; i++) frame();
        float barred = desktop.windowLayer().box().height();
        frame.enterFullscreen();
        for (int i = 0; i < 4; i++) frame();
        float full = frame.box().height();
        assertTrue("fullscreen did not take the bar's row", full > barred + 1f);
        Desktop.setAnimationsEnabled(true);

        frame.exitFullscreen();
        boolean between = false;
        frame();
        while (frame.animator().isPlaying()) {
            float h = frame.box().height();
            if (h < full - 1f && h > barred + 1f) between = true;
            frame();
        }
        assertTrue("the window cut to the work area instead of easing into it", between);
        for (int i = 0; i < 3; i++) frame();
        assertEquals("it did not land in the work area", barred, frame.box().height(), 0.5f);
        assertTrue(frame.isMaximized());
    }
}
