package com.crystalgui.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.display.FrameStatsOverlay;

/**
 * The frame readout reaches a surface <b>through the desktop's commands</b>, which is the whole of why
 * it lives in {@code core/} rather than in a harness scene.
 *
 * <p>It shipped attachable only from {@code CgUiDesktopScene}, and a harness scene is the one place a
 * frame is already understood. The frames worth measuring are an editor's and a Minecraft client's, and
 * neither of those could reach it.</p>
 */
public class DesktopFrameStatsTest extends UiDocumentTestBase {

    @Test
    public void f7PutsTheReadoutOnAnySurfaceWithADesktop() {
        Desktop.of(document);
        assertNull("something attached a readout before the key was pressed",
                FrameStatsOverlay.of(document));

        assertTrue("F7 reached nothing", keyPress(CgKeyCodes.KEY_F7));
        FrameStatsOverlay hud = FrameStatsOverlay.of(document);
        assertNotNull("F7 did not attach the readout", hud);
        assertTrue(hud.isShowing());

        // AND THE SAME KEY PUTS IT AWAY. Hidden rather than removed, so the next press does not start
        // the window over -- what stops it costing anything is the released hold, not the missing node.
        keyPress(CgKeyCodes.KEY_F7);
        assertFalse("F7 did not hide it again", hud.isShowing());
        assertNotNull("hiding it took the node out of the tree", FrameStatsOverlay.of(document));
        hud.removeSelf();
    }

    @Test
    public void f8AsksForTheBreakdownAndShowsItWhileDoingSo() {
        Desktop.of(document);

        assertTrue("F8 reached nothing", keyPress(CgKeyCodes.KEY_F8));
        FrameStatsOverlay hud = FrameStatsOverlay.of(document);
        assertNotNull("F8 did not attach the readout", hud);
        // NEVER A HIDDEN EXPANSION: the key is pressed by somebody who wants to see the breakdown, and
        // silently changing the shape of an invisible panel reads as the key doing nothing.
        assertTrue("F8 expanded a readout nobody can see", hud.isShowing());
        assertTrue(hud.isDetailed());

        keyPress(CgKeyCodes.KEY_F8);
        assertTrue("F8 hid the readout when it should only have collapsed it", hud.isShowing());
        assertFalse(hud.isDetailed());
        hud.removeSelf();
    }
}
