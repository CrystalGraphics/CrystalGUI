package com.crystalgui.ui.elements.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.ScrollerView;
import org.junit.Before;
import org.junit.Test;

/**
 * What a window looks like when NOBODY sized or placed it — which is every networked window, since the
 * host mounting one knows nothing about what is inside.
 *
 * <p>Two things were wrong, and they hid each other. The frame's content slot uses the fill idiom
 * ({@code height: 0; flex-grow: 1}), which is right for a frame that has a size and contributes
 * exactly nothing to one that does not — so an unsized window was caption + 0, clamped to
 * {@code min-height: 48px}: a strip with a scrollbar. And {@code placeByCascade} stepped from (0,0),
 * so even a correctly sized window opened in the corner, which is also where an UNPLACED window is
 * drawn — the two were indistinguishable on screen.</p>
 *
 * <p>The first was invisible for as long as an old desktop record supplied the size: the machine
 * window opened "healthy" once per session and collapsed on its second open, after its close had
 * dropped the record. The reopen case is therefore asserted by name.</p>
 */
public class WindowInitialPlacementTest extends UiTestBase {

    private UIWindow window;

    @Before
    public void setUpDesktop() {
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private WindowFrame open(float contentWidth, float contentHeight) {
        WindowFrame frame = new WindowFrame("W");
        frame.setContent(new UIElement().layout(l -> l.width(contentWidth).height(contentHeight)));
        return window.openWindow(frame);
    }

    private UIElement workArea() {
        return window.desktop().windowLayer();
    }

    // ── Size ────────────────────────────────────────────────────────────────

    @Test
    public void aWindowNobodySizedTakesItsContentsNaturalSize() {
        WindowFrame frame = open(300, 400);
        settle();

        float caption = frame.captionHeight();
        assertTrue("a caption has to have been measured", caption > 0f);
        assertEquals("the content slot is as tall as what is in it",
                400f, frame.content().getRuntimeCache().getHeight(), 0.5f);
        assertTrue("and the frame is at least caption + content, never the 48px floor",
                frame.getRuntimeCache().getHeight() >= 400f + caption - 0.5f);
        assertTrue("wide enough for its content", frame.getRuntimeCache().getWidth() >= 300f - 0.5f);
    }

    @Test
    public void aWindowSomebodySizedStillClipsItsContent() {
        WindowFrame frame = open(300, 400);
        frame.resizeTo(300, 200);
        settle();

        // The other half of the change, and the one that must not move: a sized frame is the size it
        // was given, and its content slot compresses to fit rather than pushing the frame open.
        assertEquals(200f, frame.getRuntimeCache().getHeight(), 0.5f);
        assertTrue("the slot shrank to what was left under the caption",
                frame.content().getRuntimeCache().getHeight() < 400f);
    }

    @Test
    public void aContentSizedWindowIsBoundedByTheWorkArea() {
        WindowFrame frame = open(300, 4000);
        settle();

        assertTrue("taller than the desktop is not a size a window can be",
                frame.getRuntimeCache().getHeight() <= workArea().getRuntimeCache().getHeight() + 0.5f);
        assertTrue("so the content scrolls inside it instead",
                frame.content().getRuntimeCache().getHeight() < 4000f);
    }

    // ── Scroll ──────────────────────────────────────────────────────────────

    private static float barHeight(WindowFrame frame) {
        // A bar that is display: none measures 0 -- the question every state can answer.
        return ((ScrollerView) frame.content()).verticalScroller().getRuntimeCache().getHeight();
    }

    @Test
    public void aWindowThatFitsItsContentShowsNoBar() {
        WindowFrame frame = open(300, 400);
        settle();
        assertEquals("nothing to scroll, nothing drawn", 0f, barHeight(frame), 0.01f);
    }

    @Test
    public void aWindowSmallerThanItsContentScrollsItWithABar() {
        WindowFrame frame = open(300, 400);
        frame.resizeTo(300, 200);
        settle();

        assertTrue("the slot can scroll", frame.content().getMaxScrollTop() > 0f);
        assertTrue("and says so", barHeight(frame) > 0f);
    }

    /**
     * The reported shape: a content-sized window whose content GROWS after it opened -- the machine
     * panel's engine section unfolding -- runs into the work-area cap and must scroll from there.
     */
    @Test
    public void contentGrowingPastTheWorkAreaScrollsInsideTheWindow() {
        UIElement body = new UIElement().layout(l -> l.width(300).height(400));
        WindowFrame frame = new WindowFrame("W");
        frame.setContent(body);
        window.openWindow(frame);
        settle();
        assertEquals("fits at first", 0f, barHeight(frame), 0.01f);

        body.layout(l -> l.height(4000));
        settle();

        assertTrue("the window stopped at the work area",
                frame.getRuntimeCache().getHeight() <= workArea().getRuntimeCache().getHeight() + 0.5f);
        assertTrue("and the content scrolls inside it", frame.content().getMaxScrollTop() > 0f);
        assertTrue("with a bar to grab", barHeight(frame) > 0f);
    }

    // ── Place ───────────────────────────────────────────────────────────────

    @Test
    public void aWindowNobodyPlacedOpensCentred() {
        WindowFrame frame = open(300, 400);
        settle();

        assertTrue(frame.isPlaced());
        float areaWidth = workArea().getRuntimeCache().getWidth();
        float areaHeight = workArea().getRuntimeCache().getHeight();
        assertTrue("the work area has to be measured for centring to mean anything", areaWidth > 0f);
        assertEquals((areaWidth - frame.getRuntimeCache().getWidth()) / 2f, frame.left(), 0.5f);
        assertEquals((areaHeight - frame.getRuntimeCache().getHeight()) / 2f, frame.top(), 0.5f);
    }

    @Test
    public void aSecondWindowCascadesFromTheCentre() {
        WindowFrame first = open(300, 400);
        WindowFrame second = open(300, 400);
        settle();

        float step = first.captionHeight();
        assertEquals("one caption along, so the two are not stacked exactly",
                first.left() + step, second.left(), 0.5f);
        assertEquals(first.top() + step, second.top(), 0.5f);
    }

    /** The reported shape: open, close, open again — and the second must be the first over again. */
    @Test
    public void aWindowReopenedAfterTheLastOneClosedIsCentredAndFullSized() {
        WindowFrame first = open(300, 400);
        settle();
        float left = first.left();
        float top = first.top();
        float height = first.getRuntimeCache().getHeight();

        first.destroy();
        settle();

        WindowFrame second = open(300, 400);
        settle();
        assertEquals("the same size as the first — nothing about a reopen is smaller",
                height, second.getRuntimeCache().getHeight(), 0.5f);
        assertEquals("and in the same place: alone on the desktop, there is nothing to cascade from",
                left, second.left(), 0.5f);
        assertEquals(top, second.top(), 0.5f);
    }
}
