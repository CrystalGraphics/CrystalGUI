package com.crystalgui.desktop.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.scroll.ScrollerView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What a document looks like when NOBODY sized or placed it — which is every networked document, since the
 * host mounting one knows nothing about what is inside.
 *
 * <p>Two things were wrong, and they hid each other. The frame's content slot uses the fill idiom
 * ({@code height: 0; flex-grow: 1}), which is right for a frame that has a size and contributes
 * exactly nothing to one that does not — so an unsized document was caption + 0, clamped to
 * {@code min-height: 48px}: a strip with a scrollbar. And {@code placeByCascade} stepped from (0,0),
 * so even a correctly sized document opened in the corner, which is also where an UNPLACED document is
 * drawn — the two were indistinguishable on screen.</p>
 *
 * <p>The first was invisible for as long as an old desktop record supplied the size: the machine
 * document opened "healthy" once per session and collapsed on its second open, after its close had
 * dropped the record. The reopen case is therefore asserted by name.</p>
 */
public class WindowInitialPlacementTest extends UiDocumentTestBase {

    /**
     * Animations OFF for the fixture. Several tests below turn them back on for the thing they are
     * about and restore this in a finally; without a @Before the class relied on that restore having
     * run, i.e. on another test having gone first. A window's state change is DEFERRED while a
     * timeline plays, so the assertions here read VISIBLE for a window that has been closed.
     */
    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }


    @After
    public void animationsBackOn() {
        Desktop.setAnimationsEnabled(true);
    }

    @Before
    public void setUpDesktop() {
        // Animations OFF, stated rather than inherited. Every assertion in this fixture reads a
        // geometry or a state straight after a gesture, and a running timeline defers both -- `hide()`
        // detaches and `close()` destroys only once the flight ends, so the assertion reads the state
        // BEFORE the gesture took effect and the numbers it does get are mid-flight fractions.
        Desktop.setAnimationsEnabled(false);
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    private WindowFrame open(float contentWidth, float contentHeight) {
        WindowFrame frame = new WindowFrame("W");
        frame.setContent(new UIElement().layout(l -> l.width(contentWidth).height(contentHeight)));
        return Desktop.of(document).addWindow(frame);
    }

    private UIElement workArea() {
        return Desktop.of(document).windowLayer();
    }

    // ── Size ────────────────────────────────────────────────────────────────

    @Test
    public void aWindowNobodySizedTakesItsContentsNaturalSize() {
        WindowFrame frame = open(300, 400);
        settle();

        float caption = frame.captionHeight();
        assertTrue("a caption has to have been measured", caption > 0f);
        assertEquals("the content slot is as tall as what is in it",
                400f, frame.content().box().height(), 0.5f);
        assertTrue("and the frame is at least caption + content, never the 48px floor",
                frame.box().height() >= 400f + caption - 0.5f);
        assertTrue("wide enough for its content", frame.box().width() >= 300f - 0.5f);
    }

    @Test
    public void aWindowSomebodySizedStillClipsItsContent() {
        WindowFrame frame = open(300, 400);
        frame.resizeTo(300, 200);
        settle();

        // The other half of the change, and the one that must not move: a sized frame is the size it
        // was given, and its content slot compresses to fit rather than pushing the frame open.
        assertEquals(200f, frame.box().height(), 0.5f);
        assertTrue("the slot shrank to what was left under the caption",
                frame.content().box().height() < 400f);
    }

    @Test
    public void aContentSizedWindowIsBoundedByTheWorkArea() {
        WindowFrame frame = open(300, 4000);
        settle();

        assertTrue("taller than the desktop is not a size a document can be",
                frame.box().height() <= workArea().box().height() + 0.5f);
        assertTrue("so the content scrolls inside it instead",
                frame.content().box().height() < 4000f);
    }

    // ── Scroll ──────────────────────────────────────────────────────────────

    private float barHeight(WindowFrame frame) {
        // ...and a bar that is `display: none` has NO BOX AT ALL here, which is the same answer and
        // the reason this cannot dereference one. The comment below was right about the question;
        // the old engine just always had a box to ask.
        return heightOf(((ScrollerView) frame.content()).verticalScroller());
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

        assertTrue("the slot can scroll", frame.content().box().maxScrollTop() > 0f);
        assertTrue("and says so", barHeight(frame) > 0f);
    }

    /**
     * The reported shape: a content-sized document whose content GROWS after it opened -- the machine
     * panel's engine section unfolding -- runs into the work-area cap and must scroll from there.
     */
    @Test
    public void contentGrowingPastTheWorkAreaScrollsInsideTheWindow() {
        UIElement body = new UIElement().layout(l -> l.width(300).height(400));
        WindowFrame frame = new WindowFrame("W");
        frame.setContent(body);
        Desktop.of(document).addWindow(frame);
        settle();
        assertEquals("fits at first", 0f, barHeight(frame), 0.01f);

        body.layout(l -> l.height(4000));
        settle();

        assertTrue("the document stopped at the work area",
                frame.box().height() <= workArea().box().height() + 0.5f);
        assertTrue("and the content scrolls inside it", frame.content().box().maxScrollTop() > 0f);
        assertTrue("with a bar to grab", barHeight(frame) > 0f);
    }

    // ── Place ───────────────────────────────────────────────────────────────

    @Test
    public void aWindowNobodyPlacedOpensCentred() {
        WindowFrame frame = open(300, 400);
        settle();

        assertTrue(frame.isPlaced());
        float areaWidth = workArea().box().width();
        float areaHeight = workArea().box().height();
        assertTrue("the work area has to be measured for centring to mean anything", areaWidth > 0f);
        assertEquals((areaWidth - frame.box().width()) / 2f, frame.left(), 0.5f);
        assertEquals((areaHeight - frame.box().height()) / 2f, frame.top(), 0.5f);
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
        float height = first.box().height();

        first.destroy();
        settle();

        WindowFrame second = open(300, 400);
        settle();
        assertEquals("the same size as the first — nothing about a reopen is smaller",
                height, second.box().height(), 0.5f);
        assertEquals("and in the same place: alone on the desktop, there is nothing to cascade from",
                left, second.left(), 0.5f);
        assertEquals(top, second.top(), 0.5f);
    }
}
