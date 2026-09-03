package com.crystalgui.desktop;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.desktop.window.WindowFrame;
import org.junit.Before;
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
 * whether a document can be dragged somewhere it cannot be dragged back from.</p>
 */
public class DesktopWindowTest extends UiDocumentTestBase {

    /**
     * Animations OFF, said out loud rather than inherited. This fixture asserts a window's STATE
     * straight after a gesture, and an animation defers exactly that -- `hide()` detaches and
     * `close()` destroys only once the flight ends, so the assertion reads VISIBLE for a window that
     * has been asked to go. It used to pass by picking up a flag some other class had left off.
     */
    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }

    private UIElement root;

    /** Logical 400x300 — {@code init} takes real pixels and the default {@code uiScale} is 2. */
    private void build() {
        // THE SURFACE, not a wrapper. The compositor belongs to the document, so it fills the
        // viewport -- sizing a node inside the document says nothing about how big the desktop is.
        viewport(400f, 300f);
        root = new UIElement().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
    }

    /** Twice: placement happens in a layout callback and writes style, so the pass that measures a
     * frame is not the pass that positions it. That is the settling {@code UIText} already documents. */
    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
        frame();
        }
    }

    private float captionOf(WindowFrame frame) {
        return frame.titleBar().box().height();
    }

    private float areaWidth() {
        return Desktop.of(document).windowLayer().box().width();
    }

    private float areaHeight() {
        return Desktop.of(document).windowLayer().box().height();
    }

    // ── Ownership ───────────────────────────────────────────────────────────

    /**
     * <b>Nobody constructs a desktop.</b> The compositor is engine infrastructure the document hands out,
     * like the overlay layer beside it — an application that had to assemble one would be an
     * application that assembled it slightly differently.
     */
    @Test
    public void everyWindowOwnsADesktopAndHandsOutTheSameOne() {
        build();
        Desktop desktop = Desktop.of(document);
        assertNotNull("a UIDocument must always have a desktop", desktop);
        assertSame("desktop() must not build a second one", desktop, Desktop.of(document));
        // THE DOCUMENT, not a node in it. The engine may not name a compositor, so the compositor
        // names the document -- `Desktop.of(document)` -- and attaches itself there. Under the old
        // engine it was an internal child of whatever root a `UIWindow` had been given, which is what
        // this used to assert.
        assertSame("the desktop belongs to the document itself", document, desktop.parent());

        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("One"));
        assertEquals(1, desktop.windows().size());
        assertSame(frame, desktop.windows().get(0));
    }

    /**
     * <b>An unused desktop must take up no space at all.</b> It is an overlay over the application's
     * own root, so a full-size empty one would hit-test across the whole document and eat every click
     * that landed on background — a UI that had never opened a document would simply stop responding,
     * with nothing about the symptom pointing at a compositor.
     */
    @Test
    public void anEmptyDesktopClaimsNoSurfaceAndGivesItBackWhenTheLastWindowGoes() {
        build();
        Desktop desktop = Desktop.of(document);
        settle();
        assertFalse("no windows, so the compositor is not the surface", desktop.isLive());
        assertEquals(0f, desktop.box().width(), 0.01f);
        assertEquals(0f, desktop.box().height(), 0.01f);

        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("One"));
        settle();
        assertTrue(desktop.isLive());
        assertEquals("a live desktop fills its root", 400f, desktop.box().width(), 0.01f);
        assertEquals(300f, desktop.box().height(), 0.01f);

        frame.requestClose();
        settle();
        assertFalse(desktop.isLive());
        assertEquals("closing the last document returns the surface", 0f,
                desktop.box().width(), 0.01f);
    }

    /**
     * The work-area callback walks a maintained list rather than filtering the layer's children, so
     * the list has to stay in step with the tree through <b>every</b> removal path — including a frame
     * removing itself, which is the whole reason frames are public children of an internal layer.
     *
     * <p>Asserted on {@code visibleWindows()} — the layer's list — rather than {@code windows()}, which
     * since W3 means every <em>live</em> document and deliberately keeps hidden ones. A bare detach is a
     * hide, so the frame is still a document and is simply no longer on the desktop.</p>
     */
    @Test
    public void theFrameListTracksTheTreeThroughSelfRemoval() {
        build();
        Desktop desktop = Desktop.of(document);
        WindowFrame first = Desktop.of(document).addWindow(new WindowFrame("One"));
        WindowFrame second = Desktop.of(document).addWindow(new WindowFrame("Two"));
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
            Desktop.of(document).windowLayer().append(new UIElement());
            fail("the document layer must refuse a non-document");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("WindowFrame"));
        }
    }

    // ── The clamp ───────────────────────────────────────────────────────────

    /**
     * <b>A caption can never leave the top of the work area</b> — the one clamp every document manager
     * enforces, because the title bar is what the next drag has to grab. Both directions: a document
     * cannot be pushed above the desktop, and cannot be dropped below its bottom edge.
     */
    @Test
    public void theCaptionStaysReachable() {
        build();
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("One")).resizeTo(200, 120);
        settle();

        frame.moveTo(40, -500);
        settle();
        assertEquals("a document cannot be dragged above the desktop", 0f, frame.top(), 0.01f);

        frame.moveTo(40, 10_000);
        settle();
        assertTrue("the caption must stay on screen: top=" + frame.top(),
                frame.top() <= areaHeight() - captionOf(frame) + 0.01f);
    }

    /**
     * <b>And a document may hang off the side, which is the half a panel's clamp gets wrong.</b> Clamping
     * a document fully inside its desktop — what {@code Dialog} and {@code CanvasOverlayMove} do, rightly,
     * for a panel over a canvas — means a document wider than the desktop can never be dragged far enough
     * to reach its own right-hand side.
     */
    @Test
    public void aWindowMayHangOffTheSideButNeverDisappearEntirely() {
        build();
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("One")).resizeTo(200, 120);
        settle();

        frame.moveTo(10_000, 40);
        settle();
        float width = frame.box().width();
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
     * <b>A shrinking desktop pulls a document in; giving the room back puts it where the user left it.</b>
     *
     * <p>This is the reason position is kept as two fields — the intent and the placement. Clamping the
     * stored value instead, which is what a single field forces, quietly rewrites what the user asked
     * for, and the document never comes back.</p>
     */
    @Test
    public void aWindowPushedInByAShrinkingDesktopReturnsWhenTheRoomDoes() {
        build();
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("One")).resizeTo(120, 100);
        settle();
        frame.moveTo(260, 40);
        settle();
        assertEquals(260f, frame.left(), 0.01f);

        // THE SURFACE SHRINKS, not a node inside it -- the work area is the viewport's, so resizing
        // a wrapper leaves the compositor exactly as wide as it was and the window is never pushed.
        viewport(200f, 300f);
        settle();
        assertTrue("the shrinking work area must pull it in: left=" + frame.left(),
                frame.left() < 260f);

        viewport(400f, 300f);
        settle();
        assertEquals("and the room coming back restores what the user asked for",
                260f, frame.left(), 0.01f);
    }

    /**
     * A document nobody positioned opens centred, and the next one is cascaded from it — one caption
     * height along each time. Centred because every GuiContainer in Minecraft is, and because the
     * top-left corner is where an UNPLACED document is drawn, so a placed one there is indistinguishable
     * from the bug. The step is measured from the title bar rather than written as a constant, so a
     * theme that changes the caption's height moves the cascade with it.
     */
    @Test
    public void aWindowNobodyPlacedIsCentredAndTheNextCascadedFromIt() {
        build();
        WindowFrame first = Desktop.of(document).addWindow(new WindowFrame("One")).resizeTo(120, 100);
        WindowFrame second = Desktop.of(document).addWindow(new WindowFrame("Two")).resizeTo(120, 100);
        settle();

        assertTrue("the cascade must have placed both", first.isPlaced() && second.isPlaced());
        float step = captionOf(first);
        assertTrue("a caption has to have been measured for the step to mean anything", step > 0f);
        UIElement area = Desktop.of(document).windowLayer();
        assertEquals((area.box().width() - 120f) / 2f, first.left(), 0.01f);
        assertEquals((area.box().height() - 100f) / 2f, first.top(), 0.01f);
        assertEquals(first.left() + step, second.left(), 0.01f);
        assertEquals(first.top() + step, second.top(), 0.01f);
    }
}
