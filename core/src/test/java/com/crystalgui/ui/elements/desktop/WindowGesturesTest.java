package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Fullscreen and drag-to-edge snap — CrystalOS <b>W13b</b>.
 *
 * <p>The snap arithmetic is pinned separately in {@link SnapZonesTest}, which needs no desktop at all.
 * What is here is the half that needs one: that fullscreen is maximise plus a hidden strip and comes
 * back to the state it left, and that a snap goes through the same {@code maximize()} the button does
 * rather than writing a rect of its own.</p>
 */
public class WindowGesturesTest extends UiTestBase {

    private UIWindow window;
    private WindowFrame frame;

    @Before
    public void setUpDesktop() {
        CommandRegistry.global().resetForTesting();
        WindowCommands.resetForTesting();

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        frame = window.openWindow(new WindowFrame("Editor"));
        frame.resizeTo(300, 200).moveTo(40, 40);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private boolean barVisible() {
        return window.desktop().taskbar().getRuntimeCache().getHeight() > 0f;
    }

    // ── Fullscreen ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Fullscreen is maximise plus a hidden strip — and it needs no third geometry.</b>
     *
     * <p>A frame is placed against the window layer and the layer's box <em>is</em> the work area, so
     * hiding the taskbar re-flows the layer to full height and a maximised window follows it. That is
     * Windows' model exactly: maximise respects the taskbar, fullscreen covers it.</p>
     */
    @Test
    public void fullscreenMaximisesAndHidesTheStrip() {
        assertTrue("the fixture started with no taskbar", barVisible());

        frame.enterFullscreen();
        settle();

        assertTrue(frame.isFullscreen());
        assertTrue("fullscreen did not maximise the window", frame.isMaximized());
        assertFalse("fullscreen left the taskbar on screen", barVisible());
    }

    /**
     * <b>Leaving fullscreen returns to the state it was entered from — restored stays restored.</b>
     *
     * <p>A browser does exactly this, and getting it wrong only shows up the second time somebody uses
     * it: F11 from a restored window that came back maximised would have quietly resized their
     * window.</p>
     */
    @Test
    public void leavingFullscreenFromARestoredWindowRestoresIt() {
        assertFalse(frame.isMaximized());

        frame.toggleFullscreen();
        settle();
        frame.toggleFullscreen();
        settle();

        assertFalse("a restored window came back maximised", frame.isMaximized());
        assertTrue("the taskbar did not come back", barVisible());
    }

    /** <b>...and maximised stays maximised.</b> The other half, which a single test cannot cover. */
    @Test
    public void leavingFullscreenFromAMaximisedWindowKeepsItMaximised() {
        frame.maximize();
        settle();

        frame.toggleFullscreen();
        settle();
        frame.toggleFullscreen();
        settle();

        assertTrue("a maximised window came back restored", frame.isMaximized());
        assertTrue("the taskbar did not come back", barVisible());
    }

    /**
     * <b>The strip stays hidden while ANY window is fullscreen.</b>
     *
     * <p>Asked of the whole set rather than tracked as one window: two can be fullscreen at once, and a
     * field holding "the fullscreen window" would need every exit to know whether it was the one being
     * remembered. The registry can simply be asked.</p>
     */
    @Test
    public void theStripStaysHiddenWhileASecondWindowIsStillFullscreen() {
        WindowFrame other = window.openWindow(new WindowFrame("Other"));
        other.resizeTo(300, 200).moveTo(360, 40);
        settle();

        frame.enterFullscreen();
        other.enterFullscreen();
        settle();
        frame.exitFullscreen();
        settle();

        assertFalse("the strip came back while another window was still fullscreen", barVisible());

        other.exitFullscreen();
        settle();
        assertTrue("the strip never came back", barVisible());
    }

    /** A tool window is offered neither Maximize nor Full Screen, for the same reason. */
    @Test
    public void aToolWindowIsNotOfferedFullscreen() {
        frame.setToolWindow(true);
        settle();

        Command fullscreen = CommandRegistry.global().get(WindowCommands.FULLSCREEN);
        assertNotNull(fullscreen);
        assertFalse("a tool window was offered Full Screen",
                fullscreen.isEnabled(CommandContext.of(frame)));
    }

    /** F11 is the chord, and unlike Alt+Space it is not the host's. */
    @Test
    public void fullscreenIsOnF11() {
        Command fullscreen = CommandRegistry.global().get(WindowCommands.FULLSCREEN);
        assertNotNull(fullscreen);
        assertTrue("Full Screen lost its chord", fullscreen.bindings().contains("F11"));
    }

    // ── Snap ────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The snap preview takes no box at all until a snap is being previewed.</b>
     *
     * <p>A full-size element over the work area is this codebase's most-repeated failure — it swallows
     * every click that misses a window and nothing on screen says why. What makes one safe is that it is
     * {@code display: none} for every frame it is not drawn, which is also what keeps it out of the
     * layer-FBO path.</p>
     */
    @Test
    public void theSnapPreviewIsAbsentUntilItIsNeeded() {
        assertEquals("the snap preview is taking up space before any drag", 0f,
                previewBox().getWidth(), 0.01f);

        window.desktop().showSnapPreview(SnapZones.Zone.LEFT);
        settle();
        assertTrue("the preview did not appear", previewBox().getWidth() > 0f);

        window.desktop().hideSnapPreview();
        settle();
        assertEquals("the preview stayed on screen", 0f, previewBox().getWidth(), 0.01f);
    }

    /** <b>A LEFT snap covers the left half of the work area, and stops at the middle.</b> */
    @Test
    public void theSnapPreviewCoversTheHalfItNames() {
        window.desktop().showSnapPreview(SnapZones.Zone.LEFT);
        settle();

        var area = window.desktop().windowLayer().getRuntimeCache();
        var preview = previewBox();
        assertEquals("a left snap is not half the work area",
                Math.floor(area.getWidth() / 2f), preview.getWidth(), 1f);
        assertEquals("a left snap is not full height", area.getHeight(), preview.getHeight(), 1f);
    }

    private UIElement.RuntimeCache previewBox() {
        UIElement found = window.desktop().windowLayer()
                .querySelector("." + Desktop.SNAP_PREVIEW_CLASS);
        // Never built is the same observable as never shown, which is what the assertion means.
        return found == null ? new UIElement().getRuntimeCache() : found.getRuntimeCache();
    }
}
