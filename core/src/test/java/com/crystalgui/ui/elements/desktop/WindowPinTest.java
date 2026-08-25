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

    // ── the presentation ────────────────────────────────────────────────────────────────────────

    /**
     * <b>The decision, which is the thing that stopped the close flicker.</b>
     *
     * <p>Before M16 the paint path was chosen by which hook fired, and each hook tested a Minecraft
     * condition for itself — so on the frame the desktop closed, both concluded it was the other's turn
     * and the pinned window was painted by nobody. Moving the decision here is what makes that
     * impossible, and this is the test of it: one input, one answer, no caller consulted.</p>
     *
     * <p>Asserted rather than the painting, because painting needs a GL context no test source set has.
     * What can be reached is every arm of the mapping, and the mapping is where the bug was.</p>
     */
    @Test
    public void thePresentationIsDecidedInOnePlace() {
        // Our own screen wins outright -- it shows the whole compositor whatever else is true.
        assertEquals(DesktopPresentation.DESKTOP, window.presentation(true, true));

        // Nothing pinned: there is nothing to put over a game, whoever's screen is up.
        assertEquals(DesktopPresentation.NONE, window.presentation(false, false));
        assertEquals(DesktopPresentation.NONE, window.presentation(false, true));

        pinned.setPinned(true);
        settle();

        // A CURSOR EXISTS EXACTLY WHEN A SCREEN IS UP, and that -- not whose screen it is -- is what
        // decides whether a pinned window can be interacted with.
        assertEquals(DesktopPresentation.HUD, window.presentation(false, false));
        assertEquals(DesktopPresentation.OVERLAY, window.presentation(false, true));
    }

    /**
     * <b>Only the interactive arms dispatch input or paint the top layer.</b>
     *
     * <p>Three properties vary across the arms and nothing else does. Pinning them here is what lets the
     * paint method be one method: a new situation becomes a new arm rather than a new path, and the
     * things a path used to get to decide for itself are read off the value instead.</p>
     */
    @Test
    public void eachArmAgreesAboutInputAndTheTopLayer() {
        assertTrue(DesktopPresentation.DESKTOP.isInteractive());
        assertTrue(DesktopPresentation.OVERLAY.isInteractive());
        // The one that matters: a grabbed cursor's position is stale, so running the hover pipeline
        // against it would fire boundary events at a screen nobody is looking at.
        assertFalse(DesktopPresentation.HUD.isInteractive());
        assertFalse(DesktopPresentation.NONE.isInteractive());

        // A menu or a tooltip cannot be summoned by a grabbed cursor, so the HUD has nothing on the top
        // layer that belongs on it -- and painting it would draw whatever the desktop left there.
        assertFalse(DesktopPresentation.HUD.paintsTopLayer());
        assertTrue(DesktopPresentation.OVERLAY.paintsTopLayer());

        // Only our own screen shows the taskbar and the desktop's own chrome.
        assertTrue(DesktopPresentation.DESKTOP.paintsWholeDesktop());
        assertFalse(DesktopPresentation.OVERLAY.paintsWholeDesktop());
        assertFalse(DesktopPresentation.HUD.paintsWholeDesktop());
    }

    /**
     * <b>A window brought to the front while off-desktop pins itself.</b>
     *
     * <p>The window switcher is what found this: the registry keeps HIDDEN windows, so cycling could
     * show one the HUD had put away. It then painted — the overlay draws the whole window layer — while
     * every click fell straight through onto the game, because input accepted only PINNED frames. Two
     * definitions of "what is on the overlay" that agreed until something could become visible without
     * being pinned.</p>
     */
    @Test
    public void aWindowShownWhileOffDesktopPinsItself() {
        pinned.setPinned(true);
        window.enterHudMode();
        settle();
        assertEquals("precondition: the HUD put the unpinned window away",
                WindowState.HIDDEN, plain.state());

        // What Ctrl+Tab does: bring a hidden window back while the desktop is not on screen.
        plain.show(true);
        settle();

        assertEquals(WindowState.VISIBLE, plain.state());
        assertTrue("shown over the game means pinned, or it paints and cannot be clicked",
                plain.isPinned());
        assertTrue("and it belongs to the band like anything else pinned",
                plain.stackOrder() >= Desktop.PINNED_BAND);
    }

    /**
     * <b>...and restoring the desktop does NOT pin everything it put away.</b>
     *
     * <p>The counter-assertion, and it is the one that makes the rule above safe: {@code exitHudMode}
     * shows every window it hid, so a naive "pin on show" would pin the entire desktop the first time
     * anybody closed and reopened it. It is avoided by ordering — {@code hudMode} is cleared before the
     * restores — which is invisible at the call site and would be silently undone by a refactor that
     * moved one line.</p>
     */
    @Test
    public void leavingTheHudPinsNothing() {
        pinned.setPinned(true);
        window.enterHudMode();
        settle();
        window.exitHudMode();
        settle();

        assertEquals("the restored window must come back", WindowState.VISIBLE, plain.state());
        assertFalse("restoring is not the same request as bringing to the front over a game",
                plain.isPinned());
    }

    /**
     * <b>{@code NONE} paints nothing, and that guard is reached before any GL is touched.</b>
     *
     * <p>Observable on a test JVM precisely because asking for the paint context would throw: there is no
     * GL here, so a guard that let this through would fail loudly. Without it, a host whose overlay hook
     * fires before anything is pinned would build the paint context on a frame nobody asked to draw.</p>
     */
    @Test
    public void nothingToShowPaintsNothing() {
        window.paint(DesktopPresentation.NONE, 800, 600);
    }
}
