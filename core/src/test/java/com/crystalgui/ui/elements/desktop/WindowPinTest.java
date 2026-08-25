package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.Ui;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pin and the HUD — CrystalOS <b>W14</b>.
 *
 * <p>What is worth testing here is the <b>spine</b>, and the three things below are it: that the band
 * actually orders, that the freeze contract still keys on hidden rather than on the screen closing, and
 * that the paint-only entry is paint-only. Everything else about a HUD is visual and belongs to the
 * harness's game mode, which is why that exists.</p>
 */
public class WindowPinTest extends UiTestBase {

    private UIWindow window;
    private WindowFrame plain;
    private WindowFrame pinned;

    @Before
    public void setUpDesktop() {
        CommandRegistry.global().resetForTesting();
        WindowCommands.resetForTesting();

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        plain = window.openWindow(new WindowFrame("Plain"));
        plain.resizeTo(300, 200).moveTo(40, 40);
        pinned = window.openWindow(new WindowFrame("Pinned"));
        pinned.resizeTo(300, 200).moveTo(120, 120);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    // ── the band ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A pinned window outranks an unpinned one however recently the unpinned one was raised.</b>
     *
     * <p>That is the whole claim always-on-top makes, and it is what separates a band from an ordinary
     * raise: raising is monotonic, so without the offset the most recent click always wins. The
     * assertion therefore raises the UNPINNED window last — the case a bare counter gets wrong.</p>
     */
    @Test
    public void aPinnedWindowStaysAboveOneRaisedAfterIt() {
        pinned.setPinned(true);
        window.desktop().raise(plain);
        settle();

        assertTrue("the unpinned window was raised last and must still sort below the pinned one",
                pinned.stackOrder() > plain.stackOrder());
        assertTrue("a pinned frame belongs to the band, not merely to a high counter value",
                pinned.stackOrder() >= Desktop.PINNED_BAND);
        assertTrue("an unpinned frame must stay out of the band",
                plain.stackOrder() < Desktop.PINNED_BAND);
    }

    /** Unpinning returns the window to the ordinary band rather than leaving it stranded above. */
    @Test
    public void unpinningLeavesTheBand() {
        pinned.setPinned(true);
        settle();
        assertTrue(pinned.stackOrder() >= Desktop.PINNED_BAND);

        pinned.setPinned(false);
        settle();
        assertFalse("still carrying the state class after unpinning",
                pinned.hasClass(WindowFrame.PINNED_CLASS));
        assertTrue("unpinning must drop the band offset, not just the class",
                pinned.stackOrder() < Desktop.PINNED_BAND);
    }

    // ── the HUD ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Visible stays live; everything else freezes exactly as before.</b>
     *
     * <p>The freeze contract keys on <em>hidden</em>, not on the screen being closed — so entering the
     * HUD hides every unpinned window (detaching it, which is what stops its tickers, its selectors and
     * its layout) and leaves pinned ones attached and running. Asserted on the PARENT rather than on the
     * state, because detachment is the mechanism the whole contract rests on: a window that reported
     * HIDDEN while still in the tree is the exact defect {@code removeChildInternal} exists to prevent.
     * </p>
     */
    @Test
    public void enteringTheHudHidesTheUnpinnedAndKeepsThePinnedLive() {
        pinned.setPinned(true);
        settle();

        window.enterHudMode();
        settle();

        assertTrue("hud mode should be on", window.isHudMode());
        assertEquals("an unpinned window must be HIDDEN on the HUD",
                WindowState.HIDDEN, plain.state());
        assertNull("hidden is DETACHED, or nothing about the freeze holds", plain.getParent());

        assertEquals("a pinned window must stay VISIBLE", WindowState.VISIBLE, pinned.state());
        assertNotNull("a pinned window must stay in the tree, or it cannot lay out or paint",
                pinned.getParent());
        assertTrue("a pinned frame is restyled while it is on the HUD",
                pinned.hasClass(WindowFrame.HUD_CLASS));
    }

    /**
     * <b>Leaving the HUD restores exactly what entering it put away — and nothing else.</b>
     *
     * <p>The set has to be remembered rather than inferred, and this is the case that proves it: a
     * window the user had already minimised before the screen closed must still be minimised when it
     * comes back. Inferring "show everything unpinned" on the way out would resurrect it.</p>
     */
    @Test
    public void leavingTheHudDoesNotResurrectAnAlreadyMinimisedWindow() {
        WindowFrame minimised = window.openWindow(new WindowFrame("Minimised"));
        settle();
        minimised.hide();
        settle();
        assertEquals(WindowState.HIDDEN, minimised.state());

        pinned.setPinned(true);
        window.enterHudMode();
        settle();
        window.exitHudMode();
        settle();

        assertEquals("the window the HUD hid must come back",
                WindowState.VISIBLE, plain.state());
        assertEquals("a window minimised BEFORE the HUD must stay minimised after it",
                WindowState.HIDDEN, minimised.state());
        assertFalse("the HUD restyle must come off", pinned.hasClass(WindowFrame.HUD_CLASS));
    }

    /**
     * <b>The paint-only entry paints nothing when the HUD is not up.</b>
     *
     * <p>Which is as much of that entry as a unit test can reach, and the reason is worth stating rather
     * than working around: {@code paintHudFrame} asks {@code CgUiPaintContext.getInstance()}, which
     * builds framebuffers — so the moment it paints at all it needs a GL context, and no test source set
     * has one. What the guard pins is the half that is pure control flow, and it is the half that would
     * bite: without it a host whose overlay hook fires before anything is pinned would build the paint
     * context on a frame nobody asked to draw. On a test JVM that is observable precisely because asking
     * would throw.</p>
     *
     * <p><b>The no-input claim is covered by the harness's game mode</b> ({@code cgui-desktop}, F6),
     * not here. It is not a visual property and it would be worth a unit test — but every way of
     * observing it has to run the paint path first. What is structural is that {@code paintHudFrame}
     * never touches {@code inputHandler} at all; what is observable needs a surface.</p>
     */
    @Test
    public void theHudEntryPaintsNothingWhenTheHudIsNotUp() {
        assertFalse("precondition: this test never enters the HUD", window.isHudMode());
        window.paintHudFrame(800, 600);
    }
}
