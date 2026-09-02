package com.crystalgui.widget.scroll;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import static org.junit.Assert.*;
import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * Scrolling as an <b>element capability</b>, with no widget involved.
 *
 * <p>This is the web model: {@code overflow: auto} on any element makes it a scroll container, its
 * children are ordinary direct children, and the offset is {@code scrollTop}/{@code scrollLeft} —
 * state on the element, applied through the transform chain. There is no viewport wrapper and no
 * content wrapper, which is the whole point (LDLib's equivalent needs both, plus 11 more nodes).</p>
 *
 * <p>Every test here uses a plain {@link UINode}. If any of them needed {@code ScrollerView}, the
 * capability wouldn't actually be in the engine.</p>
 */
public class ScrollCapabilityTest extends UiDocumentTestBase {

    private static final float VIEWPORT = 100f;
    private static final float ROW_H = 40f;
    private static final int ROWS = 5;               // 200px of content in a 100px box

    // ── Which overflow values scroll ────────────────────────────────────────

    @Test
    public void overflowValuesEstablishScrollContainersPerCss() {
        assertFalse(withOverflow(Overflow.VISIBLE).isScrollContainer());
        assertFalse("clip must NOT be scrollable — that's what distinguishes it from hidden",
                withOverflow(Overflow.CLIP).isScrollContainer());
        assertTrue("in CSS overflow:hidden IS a scroll container, it just shows no bars",
                withOverflow(Overflow.HIDDEN).isScrollContainer());
        assertTrue(withOverflow(Overflow.SCROLL).isScrollContainer());
        assertTrue(withOverflow(Overflow.AUTO).isScrollContainer());
    }

    @Test
    public void onlyVisibleSkipsClipping() {
        assertFalse(Overflow.VISIBLE.clips());
        for (Overflow o : new Overflow[]{Overflow.CLIP, Overflow.HIDDEN, Overflow.SCROLL, Overflow.AUTO}) {
            assertTrue(o + " should clip", o.clips());
        }
    }

    /**
     * A bare element is <b>programmatic-only</b>: {@code scrollTop} works, the wheel does nothing,
     * whatever its {@code overflow}. Wheel handling is a widget's job ({@code ScrollerView} opts in),
     * so a stray clipped element can never silently swallow scroll input.
     *
     * <p>{@code allowsUserScrolling()} still reports CSS's own narrower rule — {@code hidden} is a
     * scroll container yet CSS Overflow 3 forbids the UA from giving it a scrolling mechanism — and
     * remains the right predicate for anything that <em>does</em> offer user scrolling.</p>
     */
    @Test
    public void overflowValuesReportCssUserScrollability() {
        assertTrue("hidden must remain programmatically scrollable", Overflow.HIDDEN.isScrollContainer());
        assertFalse("hidden must never be user-scrollable", Overflow.HIDDEN.allowsUserScrolling());

        assertTrue(Overflow.SCROLL.allowsUserScrolling());
        assertTrue(Overflow.AUTO.allowsUserScrolling());
        assertFalse(Overflow.CLIP.allowsUserScrolling());
        assertFalse(Overflow.VISIBLE.allowsUserScrolling());
    }

    /** Programmatic scrolling works on hidden, which is the half of it people don't expect. */
    @Test
    public void hiddenIsProgrammaticallyScrollable() {
        UINode hidden = scrollingList(Overflow.HIDDEN);
        assertTrue(hidden.isScrollContainer());
        assertFalse(hidden.allowsUserScrolling());
        hidden.scrollTo(hidden.scrollLeft(), 60f);
        assertEquals("programmatic scrolling must still work on hidden", 60f, hidden.scrollTop(), 0.5f);
    }

    /** No bare element responds to the wheel — not even {@code overflow: auto}. */
    @Test
    public void bareElementsIgnoreTheWheel() {
        UINode scroller = scrollingList(Overflow.AUTO);
        UIDocument window = scroller.document();
        var c = scroller.box();
        int x = Math.round((c.x() + 10f) * 2f), y = Math.round((c.y() + 20f) * 2f);

        window.input().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
        window.input().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 3f, -1L));
        frame();
        frame();

        assertEquals("a bare element must not be wheel-scrollable", 0f, scroller.scrollTop(), 0.5f);
    }

    @Test
    public void scrollbarVisibilityFollowsCss() {
        assertFalse(Overflow.HIDDEN.showsScrollbar(true));
        assertTrue(Overflow.SCROLL.showsScrollbar(false));   // always
        assertFalse(Overflow.AUTO.showsScrollbar(false));    // only when needed
        assertTrue(Overflow.AUTO.showsScrollbar(true));
    }

    // ── The capability itself ───────────────────────────────────────────────

    @Test
    public void contentBoundsAreMeasuredFromChildren() {
        UINode scroller = scrollingList(Overflow.AUTO);
        assertEquals(ROWS * ROW_H, scroller.box().scrollHeight(), 0.5f);
        assertEquals(VIEWPORT, scroller.box().clientHeight(), 0.5f);
        assertEquals(ROWS * ROW_H - VIEWPORT, scroller.box().maxScrollTop(), 0.5f);
    }

    /** The container's own box must not grow to fit its content — that's what overflow buys. */
    @Test
    public void scrollingDoesNotResizeTheContainer() {
        UINode scroller = scrollingList(Overflow.AUTO);
        float before = scroller.box().height();
        scroller.scrollTo(scroller.scrollLeft(), 60f);
        assertEquals(before, scroller.box().height(), 0.5f);
        assertEquals(VIEWPORT, before, 0.5f);
    }

    @Test
    public void scrollIsClampedToTheContent() {
        UINode scroller = scrollingList(Overflow.AUTO);
        scroller.scrollTo(scroller.scrollLeft(), 9999f);
        assertEquals(ROWS * ROW_H - VIEWPORT, scroller.scrollTop(), 0.5f);
        scroller.scrollTo(scroller.scrollLeft(), -50f);
        assertEquals(0f, scroller.scrollTop(), 0.5f);
    }

    /** Assigning scrollTop to an unscrollable element does nothing, as in the DOM. */
    @Test
    public void nonScrollContainersIgnoreScrolling() {
        UINode plain = scrollingList(Overflow.VISIBLE);
        plain.scrollTo(plain.scrollLeft(), 50f);
        assertEquals(0f, plain.scrollTop(), 0.5f);

        UINode clipped = scrollingList(Overflow.CLIP);
        clipped.scrollTo(clipped.scrollLeft(), 50f);
        assertEquals("overflow:clip must not scroll", 0f, clipped.scrollTop(), 0.5f);
    }

    @Test
    public void clampScrollPullsBackWhenContentShrinks() {
        UINode scroller = scrollingList(Overflow.AUTO);
        scroller.scrollTo(scroller.scrollLeft(), 100f);
        assertEquals(100f, scroller.scrollTop(), 0.5f);

        scroller.removeAll();
        scroller.append(new UINode().layout(l -> l.width(80).height(ROW_H)));
        layOut(scroller);
        scroller.box().clampScroll();

        assertEquals("content now fits, so scroll must return to 0", 0f, scroller.scrollTop(), 0.5f);
    }

    // ── The load-bearing claim: input follows the scroll ─────────────────────

    /**
     * The design rests on this. The offset lives in the transform chain, and
     * {@code UIDocument.elementHitTest} inverts that same matrix — so hit-testing must follow the
     * scroll with no second code path <em>and without a paint having happened</em>. If this fails,
     * scrolling would need its own input plumbing and the whole approach is wrong.
     */
    @Test
    public void hitTestingFollowsTheScroll() {
        UINode scroller = scrollingList(Overflow.AUTO);
        UIDocument window = scroller.document();
        UINode firstRow = scroller.children().get(0);
        UINode fourthRow = scroller.children().get(3);

        // SURFACE pixels. `worldX()` already has the root transform baked in, so the offset into
        // the box is the only part that scales -- and this fixture runs at uiScale 1, where the old
        // UIWindow defaulted to 2. The ported version read `box().x()`, which is PARENT-RELATIVE
        // here and absolute on the old engine, and then doubled it.
        int probeX = Math.round(scroller.box().worldX() + 10f);
        int probeY = Math.round(scroller.box().worldY() + 20f);

        assertSame("unscrolled, the first row should be under the probe",
                firstRow, hit(probeX, probeY));

        // Row 3 starts at 120px; scrolling by 120 brings it to the top of the viewport.
        scroller.scrollTo(scroller.scrollLeft(), 120f);

        assertSame("after scrolling, the fourth row should be under the same probe",
                fourthRow, hit(probeX, probeY));
    }

    /** Content scrolled out of the viewport must not be clickable, even though it still exists. */
    @Test
    public void contentScrolledOutOfViewIsNotHittable() {
        UINode scroller = scrollingList(Overflow.AUTO);
        UIDocument window = scroller.document();
        UINode firstRow = scroller.children().get(0);

        scroller.scrollTo(scroller.scrollLeft(), 150f); // pushes rows 0-2 above the viewport

        for (int y = 0; y < VIEWPORT; y += 5) {
            int px = Math.round(scroller.box().worldX() + 10f);
            int py = Math.round(scroller.box().worldY() + y);
            assertNotSame("row scrolled out of view is still hittable at y=" + y,
                    firstRow, hit(px, py));
        }
    }

    /** Scroll-exempt children (a scroll container's own scrollbars) must stay put. */
    @Test
    public void scrollExemptChildrenDoNotMove() {
        UINode scroller = scrollingList(Overflow.AUTO);
        UINode bar = new UINode().layout(l -> l.width(10).height(VIEWPORT));
        bar.setScrollExempt(true);
        scroller.append(bar);
        layOut(scroller);

        float before = bar.box().y();
        scroller.scrollTo(scroller.scrollLeft(), 120f);
        assertEquals("a scroll-exempt child scrolled away with the content",
                before, bar.box().y(), 0.5f);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * <b>A scroll-exempt child is not content to scroll to.</b>
     *
     * <p>The second half of what {@code setScrollExempt} promises — {@code getScrollWidth} and
     * {@code getScrollHeight} both already exclude exempt children, and {@code scrollIntoView} was the one
     * place that did not. An exempt child does not move with the content, so it is already where it will
     * be drawn; "revealing" it is meaningless, and what it actually reveals is its <em>layout</em>
     * position, which for an overlay pinned with {@code top: 0} is the top of the document.</p>
     *
     * <p>Found from a stack trace, not from here: the editor's find bar is pinned that way, so focusing
     * its input scrolled a file from line 429 to line 1 — through {@code Popover.hide} restoring focus
     * when the hover documentation dismissed, and {@code requestFocus} revealing even an element that
     * already has focus. Any other pinned overlay would have done the same.</p>
     *
     * <p>Both halves asserted, because the exemption is the whole content of the test: with an ordinary
     * child at the same place the reveal must still happen, or this would pass just as well against a
     * {@code scrollIntoView} that had been broken outright.</p>
     */
    @Test
    public void scrollIntoViewSkipsAnAncestorAnExemptChildDoesNotMoveWith() {
        for (boolean exempt : new boolean[]{true, false}) {
            UINode scroller = scrollingList(Overflow.SCROLL);
            UINode pinned = new UINode().layout(l -> l.positionType(
                    dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE).top(0f).left(0f).width(80).height(20));
            pinned.setScrollExempt(exempt);
            scroller.append(pinned);
            layOut(scroller);

            scroller.box().setScroll(0f, 100f);
            assertEquals(100f, scroller.scrollTop(), 0.5f);

            pinned.box().scrollIntoView();

            if (exempt) {
                assertEquals("an exempt overlay pinned at top:0 dragged the content back to it",
                        100f, scroller.scrollTop(), 0.5f);
            } else {
                assertEquals("an ordinary child at top:0 must still be revealed",
                        0f, scroller.scrollTop(), 0.5f);
            }
        }
    }

    private UINode withOverflow(Overflow mode) {
        UINode e = new UINode();
        StyleGroup.defaultPipeline(e.getStyle().getGeneralGroup(), g -> g.overflow(mode));
        return e;
    }

    /** A 100px-tall box with 200px of rows in it — a plain element, no widget. */
    private UINode scrollingList(Overflow mode) {
        UINode scroller = withOverflow(mode);
        scroller.layout(l -> l.width(80).height(VIEWPORT).flexDirection(FlexDirection.COLUMN));
        for (int i = 0; i < ROWS; i++) {
            scroller.append(new UINode().layout(l -> l.width(80).height(ROW_H)));
        }
        layOut(scroller);
        return scroller;
    }

    private void layOut(UINode scroller) {
        if (scroller.document() == null) {
            UINode root = new UINode().layout(l -> l.width(400).height(300));
            root.append(scroller);
            document.append(root);
        }
        frame();
    }
}
