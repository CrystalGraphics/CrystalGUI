package com.crystalgui.desktop.host;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Dialog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The router's frozen answer is the live hit test's: {@link HitRegions#takes} against
 * {@link ScreenOverlay#overlayHitTest} at every point of a surface holding what decides it -- overlapping windows,
 * rounded corners, a rotated window, something promoted, the taskbar over the windows, and a modal.
 */
public class HitRegionsTest extends UiDocumentTestBase {

    @Before
    public void stillTheWindows() {
        Desktop.setAnimationsEnabled(false);
    }

    @After
    public void restoreAnimations() {
        Desktop.setAnimationsEnabled(true);
    }

    private Desktop desktop;

    private Desktop desktop() {
        if (desktop == null) {
            withDefaultStyles();
            desktop = Desktop.of(document);
        }
        return desktop;
    }

    private WindowFrame open(String title, float left, float top, float width, float height) {
        WindowFrame frame = desktop().addWindow(new WindowFrame(title));
        frame.moveTo(left, top);
        frame.resizeTo(width, height);
        return frame;
    }

    private void settle() {
        for (int pass = 0; pass < 3; pass++) frame();
    }

    /** Every point the two answers disagree at, over the whole surface; none expected. */
    private void assertAgreeEverywhere(String state) {
        ScreenOverlay overlay = desktop().screenOverlay();
        HitRegions regions = HitRegions.capture(document);
        int disagreements = 0;
        int ours = 0;
        String first = null;
        for (int y = 0; y < 1200; y += 3) {
            for (int x = 0; x < 1600; x += 3) {
                boolean live = overlay.overlayHitTest(x, y) != null;
                if (live) ours++;
                if (regions.takes(x, y) != live) {
                    if (first == null) first = x + "," + y + " live " + live;
                    disagreements++;
                }
            }
        }
        assertTrue(state + ": nothing on the surface is the desktop's, so the comparison proves nothing", ours > 0);
        assertEquals(state + ": the regions disagree with the live test, first at " + first, 0, disagreements);
    }

    @Test
    public void overlappingWindowsAndThePromotedAgree() {
        open("Back", 40, 40, 260, 180);
        open("Front", 160, 110, 240, 170);
        UIElement promoted = new UIElement().layout(l -> l.width(60).height(40));
        desktop().append(promoted);
        document.promote(promoted);
        settle();
        assertAgreeEverywhere("two windows and a promoted box");
    }

    @Test
    public void aRotatedWindowAgrees() {
        WindowFrame frame = open("Turned", 120, 90, 220, 150);
        settle();
        frame.box().setTransform(Transform.rotate(0.2f));
        settle();
        assertAgreeEverywhere("a rotated window");
    }

    @Test
    public void aModalInAWindowAgrees() {
        open("Behind", 20, 20, 200, 160);
        WindowFrame frame = open("Blocked", 100, 80, 260, 180);
        Dialog dialog = new Dialog("Owned");
        frame.attachOwned(dialog);
        dialog.showModal();
        settle();
        assertAgreeEverywhere("an owned modal");
    }
}
