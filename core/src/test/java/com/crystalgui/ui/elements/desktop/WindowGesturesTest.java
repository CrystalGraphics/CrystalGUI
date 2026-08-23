package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.testsupport.TestPlatformService;
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

    // ── Alt-drag ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Holding the move modifier and pressing the window's CONTENT starts a move.</b>
     *
     * <p>The gesture shipped doing nothing, and the reason is worth keeping: {@code beginMove} carried a
     * guard refusing any press that was not on the title bar. It was right where it was written — a
     * synthesized Space/Enter press carries the cursor's position, which may be nowhere near the bar, and
     * honouring one teleports the window — and it silently disabled Alt-drag the moment that arrived,
     * because Alt-drag presses the content <em>by definition</em>. Nothing failed. The guard now lives on
     * the caption listener, which is the path it is a statement about.</p>
     *
     * <p>Driven through {@code consumeMouseEvent} at a point inside the content, with the modifier
     * reported by a stub: the listener is in the CAPTURE phase and reads the live modifier state, and a
     * fixture that dispatched straight at an element would skip both.</p>
     */
    @Test
    public void altDraggingTheContentStartsAMove() {
        UIElement inside = new UIElement().layout(l -> l.width(120).height(60));
        frame.content().addChild(inside);
        settle();

        withModifier(CgModifiers.ALT, () -> pressAt(inside));

        assertTrue("Alt-dragging a window's content started no move",
                window.getInputHandler().getDragController().isDragging());
    }

    /**
     * <b>The modifier is a setting, and changing it changes the gesture.</b>
     *
     * <p>The plan asks for this chord to be keymap-resolved and it cannot be — a {@code KeyStroke} is a
     * key plus modifiers, so there is no way to spell a modifier-only binding. {@code moveModifier} is
     * the substance of that requirement instead: one place, changeable at runtime. <b>That claim needs a
     * test or it is only a comment</b> — a setter nothing exercises is indistinguishable from a
     * hardcoded constant with a public mutator in front of it.</p>
     */
    @Test
    public void theMoveModifierIsRebindable() {
        UIElement inside = new UIElement().layout(l -> l.width(120).height(60));
        frame.content().addChild(inside);
        settle();
        window.desktop().setMoveModifier(CgModifiers.CTRL);

        withModifier(CgModifiers.ALT, () -> pressAt(inside));
        assertFalse("the old modifier still dragged after it was changed",
                window.getInputHandler().getDragController().isDragging());

        withModifier(CgModifiers.CTRL, () -> pressAt(inside));
        assertTrue("the new modifier does not drag", window.getInputHandler().getDragController().isDragging());
    }

    /**
     * <b>...and without the modifier the same press does nothing to the window.</b>
     *
     * <p>The counter-assertion that gives the one above meaning: a listener that ignored the modifier
     * would make every press anywhere in any window start a drag, which is a far worse bug than the
     * gesture not working.</p>
     */
    @Test
    public void pressingTheContentWithoutTheModifierStartsNoMove() {
        UIElement inside = new UIElement().layout(l -> l.width(120).height(60));
        frame.content().addChild(inside);
        settle();

        pressAt(inside);

        assertFalse("a plain press inside a window started a window move",
                window.getInputHandler().getDragController().isDragging());
    }

    /**
     * Runs {@code body} with {@code mask} reported as held.
     *
     * <p>Through the test platform's own input slot rather than a whole replacement service: the modifier
     * is <b>polled</b>, not carried on the event — which is what lets the gesture survive a modifier
     * pressed after the mouse went down, and what makes it untestable without a stub.</p>
     */
    private void withModifier(int mask, Runnable body) {
        TestPlatformService.get().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return mask; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return ""; }
            @Override public void setClipboard(String text) { }
        });
        try {
            body.run();
        } finally {
            TestPlatformService.get().input(TestPlatformService.STUB_INPUT);
        }
    }

    private void pressAt(UIElement target) {
        var box = target.getRuntimeCache();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f),
                0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 0L));
        settle();
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
