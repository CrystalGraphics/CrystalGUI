package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A restored window flies out of its taskbar button; it does not unfold from its own centre.
 *
 * <p>{@code playRestore} falls back to {@code playOpen} when it cannot find anything to fly from, and it
 * could not: {@code show(true)} reattaches the frame and starts the animation in the same breath, so the
 * freshly-registered Taffy node has not been laid out and the frame measures 0x0 for exactly one frame.
 * {@code toward} refuses a zero-sized self, so the fallback did too, so {@code towardTaskbar} answered
 * null and the last resort played the ENTRY animation — a window that had flown INTO the taskbar came
 * back unfolding from the middle of the screen.</p>
 *
 * <p><b>The minimise cannot see this and never could</b>, because a window is fully laid out on the way
 * out. That asymmetry is what made it read as the restore being styled differently rather than as a
 * measurement that was not available yet.</p>
 */
public class WindowRestoreFlightTest extends UiTestBase {

    private UIWindow window;

    @Before
    public void setUp() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        window.updateWithoutPainting();
    }

    @After
    public void tearDown() {
        Desktop.setAnimationsEnabled(false);
    }

    @Test
    public void aRestoreDoesNotReplayTheEntryAnimation() {
        // Settled with animations OFF, or the entry timeline is still running when the gesture under
        // test starts and there is no telling which one is being reported. The standing fixture rule.
        Desktop.setAnimationsEnabled(false);
        WindowFrame frame = window.openWindow(new WindowFrame("Restore"));
        frame.resizeTo(200, 140);
        window.updateWithoutPainting();
        window.updateWithoutPainting();

        // THE POSITIVE CONTROL. Without it a fix that broke both animations equally would pass: two
        // nulls, or two identical values, satisfy the inequality below just as well as a real fix.
        Desktop.setAnimationsEnabled(true);
        WindowFrame fresh = window.openWindow(new WindowFrame("Fresh"));
        fresh.resizeTo(200, 140);
        UITransform entryStart = fresh.animationStart();
        assertNotNull("the entry animation is not running -- the control is worthless", entryStart);

        Desktop.setAnimationsEnabled(false);
        frame.minimize();
        window.updateWithoutPainting();

        Desktop.setAnimationsEnabled(true);
        frame.show(true);
        UITransform restoreStart = frame.animationStart();

        assertNotNull("nothing is playing -- the window came back with no animation at all", restoreStart);
        assertNotEquals(
                "a restore started from the ENTRY transform: towardTaskbar could not measure the frame "
                        + "(reattached this frame, not yet laid out) and playRestore fell back to playOpen",
                entryStart, restoreStart);
    }
}
