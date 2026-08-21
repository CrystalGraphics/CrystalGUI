package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * CrystalOS W1 — the compositor's ownership and its geometry rules ({@code plan_windowing.md}).
 *
 * <p>Deliberately not a pixel test. What is pinned here is the handful of things that are invisible
 * when broken: who owns the desktop, whether an unused one can swallow input, whether the frame list
 * the work-area callback walks stays in step with the tree, and the two clamp rules that decide
 * whether a window can be dragged somewhere it cannot be dragged back from.</p>
 */
public class DesktopWindowTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;

    /** Logical 400x300 — {@code init} takes real pixels and the default {@code uiScale} is 2. */
    private void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        settle();
    }

    /** Twice: placement happens in a layout callback and writes style, so the pass that measures a
     * frame is not the pass that positions it. That is the settling {@code UIText} already documents. */
    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
    }

    private float captionOf(WindowFrame frame) {
        return frame.titleBar().getRuntimeCache().getHeight();
    }

    private float areaWidth() {
        return window.desktop().windowLayer().getRuntimeCache().getWidth();
    }

    private float areaHeight() {
        return window.desktop().windowLayer().getRuntimeCache().getHeight();
    }

    // ── Ownership ───────────────────────────────────────────────────────────

    /**
     * <b>Nobody constructs a desktop.</b> The compositor is engine infrastructure the window hands out,
     * like the overlay layer beside it — an application that had to assemble one would be an
     * application that assembled it slightly differently.
     */
    @Test
    public void everyWindowOwnsADesktopAndHandsOutTheSameOne() {
        build();
        Desktop desktop = window.desktop();
        assertNotNull("a UIWindow must always have a desktop", desktop);
        assertSame("desktop() must not build a second one", desktop, window.desktop());
        assertSame("the desktop belongs to the window's root", root, desktop.getParent());

        WindowFrame frame = window.openWindow(new WindowFrame("One"));
        assertEquals(1, desktop.windows().size());
        assertSame(frame, desktop.windows().get(0));
    }

    /**
     * <b>An unused desktop must take up no space at all.</b> It is an overlay over the application's
     * own root, so a full-size empty one would hit-test across the whole window and eat every click
     * that landed on background — a UI that had never opened a window would simply stop responding,
     * with nothing about the symptom pointing at a compositor.
     */
    @Test
    public void anEmptyDesktopClaimsNoSurfaceAndGivesItBackWhenTheLastWindowGoes() {
        build();
        Desktop desktop = window.desktop();
        settle();
        assertFalse("no windows, so the compositor is not the surface", desktop.isLive());
        assertEquals(0f, desktop.getRuntimeCache().getWidth(), 0.01f);
        assertEquals(0f, desktop.getRuntimeCache().getHeight(), 0.01f);

        WindowFrame frame = window.openWindow(new WindowFrame("One"));
        settle();
        assertTrue(desktop.isLive());
        assertEquals("a live desktop fills its root", 400f, desktop.getRuntimeCache().getWidth(), 0.01f);
        assertEquals(300f, desktop.getRuntimeCache().getHeight(), 0.01f);

        frame.requestClose();
        settle();
        assertFalse(desktop.isLive());
        assertEquals("closing the last window returns the surface", 0f,
                desktop.getRuntimeCache().getWidth(), 0.01f);
    }

    /**
     * The work-area callback walks a maintained list rather than filtering the layer's children, so
     * the list has to stay in step with the tree through <b>every</b> removal path — including a frame
     * removing itself, which is the whole reason frames are public children of an internal layer.
     *
     * <p>Asserted on {@code visibleWindows()} — the layer's list — rather than {@code windows()}, which
     * since W3 means every <em>live</em> window and deliberately keeps hidden ones. A bare detach is a
     * hide, so the frame is still a window and is simply no longer on the desktop.</p>
     */
    @Test
    public void theFrameListTracksTheTreeThroughSelfRemoval() {
        build();
        Desktop desktop = window.desktop();
        WindowFrame first = window.openWindow(new WindowFrame("One"));
        WindowFrame second = window.openWindow(new WindowFrame("Two"));
        assertEquals(2, desktop.visibleWindows().size());

        first.removeSelf();
        assertEquals("a frame that removed itself is off the desktop", 1, desktop.visibleWindows().size());
        assertSame(second, desktop.visibleWindows().get(0));
        assertEquals("but it is retained, which is what W3 made detaching mean", 2, desktop.windows().size());
    }

    /** The layer holds windows and nothing else, which is what makes the list provably its children
     * rather than a cache that could drift from them. */
    @Test
    public void theWindowLayerRefusesAnythingThatIsNotAWindow() {
        build();
        try {
            window.desktop().windowLayer().addChild(new UIElement());
            fail("the window layer must refuse a non-window");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("WindowFrame"));
        }
    }

    // ── The clamp ───────────────────────────────────────────────────────────

    /**
     * <b>A caption can never leave the top of the work area</b> — the one clamp every window manager
     * enforces, because the title bar is what the next drag has to grab. Both directions: a window
     * cannot be pushed above the desktop, and cannot be dropped below its bottom edge.
     */
    @Test
    public void theCaptionStaysReachable() {
        build();
        WindowFrame frame = window.openWindow(new WindowFrame("One")).resizeTo(200, 120);
        settle();

        frame.moveTo(40, -500);
        settle();
        assertEquals("a window cannot be dragged above the desktop", 0f, frame.top(), 0.01f);

        frame.moveTo(40, 10_000);
        settle();
        assertTrue("the caption must stay on screen: top=" + frame.top(),
                frame.top() <= areaHeight() - captionOf(frame) + 0.01f);
    }

    /**
     * <b>And a window may hang off the side, which is the half a panel's clamp gets wrong.</b> Clamping
     * a window fully inside its desktop — what {@code Dialog} and {@code CanvasOverlayMove} do, rightly,
     * for a panel over a canvas — means a window wider than the desktop can never be dragged far enough
     * to reach its own right-hand side.
     */
    @Test
    public void aWindowMayHangOffTheSideButNeverDisappearEntirely() {
        build();
        WindowFrame frame = window.openWindow(new WindowFrame("One")).resizeTo(200, 120);
        settle();

        frame.moveTo(10_000, 40);
        settle();
        float width = frame.getRuntimeCache().getWidth();
        assertTrue("it should genuinely hang off the right edge: left=" + frame.left(),
                frame.left() + width > areaWidth() + 0.01f);
        assertTrue("but a grabbable sliver stays on screen: left=" + frame.left(),
                frame.left() <= areaWidth() - captionOf(frame) + 0.01f);

        frame.moveTo(-10_000, 40);
        settle();
        assertTrue("the same on the left: left=" + frame.left(),
                frame.left() + width >= captionOf(frame) - 0.01f);
    }

    /**
     * <b>A shrinking desktop pulls a window in; giving the room back puts it where the user left it.</b>
     *
     * <p>This is the reason position is kept as two fields — the intent and the placement. Clamping the
     * stored value instead, which is what a single field forces, quietly rewrites what the user asked
     * for, and the window never comes back.</p>
     */
    @Test
    public void aWindowPushedInByAShrinkingDesktopReturnsWhenTheRoomDoes() {
        build();
        WindowFrame frame = window.openWindow(new WindowFrame("One")).resizeTo(120, 100);
        settle();
        frame.moveTo(260, 40);
        settle();
        assertEquals(260f, frame.left(), 0.01f);

        root.layout(l -> l.width(200));
        settle();
        assertTrue("the shrinking work area must pull it in: left=" + frame.left(),
                frame.left() < 260f);

        root.layout(l -> l.width(400));
        settle();
        assertEquals("and the room coming back restores what the user asked for",
                260f, frame.left(), 0.01f);
    }

    /**
     * A window nobody positioned is cascaded from the last one — Win32's {@code CW_USEDEFAULT}, one
     * caption height along each time. The step is measured from the title bar rather than written as a
     * constant, so a theme that changes the caption's height moves the cascade with it.
     */
    @Test
    public void aWindowNobodyPlacedIsCascaded() {
        build();
        WindowFrame first = window.openWindow(new WindowFrame("One")).resizeTo(120, 100);
        WindowFrame second = window.openWindow(new WindowFrame("Two")).resizeTo(120, 100);
        settle();

        assertTrue("the cascade must have placed both", first.isPlaced() && second.isPlaced());
        float step = captionOf(first);
        assertTrue("a caption has to have been measured for the step to mean anything", step > 0f);
        assertEquals(0f, first.left(), 0.01f);
        assertEquals(step, second.left(), 0.01f);
        assertEquals(step, second.top(), 0.01f);
    }
}
