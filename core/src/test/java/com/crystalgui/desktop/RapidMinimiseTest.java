package com.crystalgui.desktop;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A window flying to the taskbar takes no pointer, from the press.</b> Two maximised windows share a minimise
 * button's spot: the second click, before the first one's flight has moved it, is for the window underneath. It landed on the flying window's own
 * minimise, which restored it and minimised it again, and the window underneath never heard it.
 */
public class RapidMinimiseTest extends UiDocumentTestBase {

    @After
    public void restore() {
        Desktop.setAnimationsEnabled(false);
    }

    private WindowFrame open(Desktop desktop, String title) {
        WindowFrame frame = new WindowFrame(title);
        desktop.addWindow(frame);
        frame();
        frame();
        return frame;
    }

    /** A click with no movement before it, as a pointer resting on the spot sends. */
    private void click(float x, float y, long at) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event((int) x, (int) y, 0, 0, 0, true, 0f, at));
        frame();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event((int) x, (int) y, 0, 0, 0, false, 0f, at + 60L));
        frame();
    }

    @Test
    public void aSecondMinimiseDuringTheFlightGoesToTheWindowUnderneath() {
        Desktop.setAnimationsEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        WindowFrame back = open(desktop, "Back");
        WindowFrame front = open(desktop, "Front");
        back.maximize();
        front.maximize();
        for (int i = 0; i < 5; i++) frame();
        Desktop.setAnimationsEnabled(true);

        Box button = front.minimizeButton().box();
        float x = button.worldX() + button.width() / 2f, y = button.worldY() + button.height() / 2f;
        Box under = back.minimizeButton().box();
        assertEquals("the two minimise buttons share a spot", x, under.worldX() + under.width() / 2f, 0.5f);

        move(x, y);
        frame();
        click(x, y, 1000L);
        assertTrue("the first click did not minimise the front window", front.isMinimizing());
        // BEFORE THE FLIGHT HAS MOVED it, as a click lands when a frame stalls: the window is still drawn in place
        click(x, y, 1150L);
        assertTrue("the second click went to the flying window, not the one underneath", back.isMinimizing());

        long until = System.nanoTime() + 800_000_000L;
        while (System.nanoTime() < until) frame();
        assertEquals(WindowState.HIDDEN, front.state());
        assertEquals(WindowState.HIDDEN, back.state());
    }
}
