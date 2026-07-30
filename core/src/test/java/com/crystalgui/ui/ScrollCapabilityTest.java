package com.crystalgui.ui;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.testsupport.UiTestBase;
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
 * <p>Every test here uses a plain {@link UIElement}. If any of them needed {@code ScrollerView}, the
 * capability wouldn't actually be in the engine.</p>
 */
public class ScrollCapabilityTest extends UiTestBase {

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
        UIElement hidden = scrollingList(Overflow.HIDDEN);
        assertTrue(hidden.isScrollContainer());
        assertFalse(hidden.allowsUserScrolling());
        hidden.setScrollTop(60f);
        assertEquals("programmatic scrolling must still work on hidden", 60f, hidden.getScrollTop(), 0.5f);
    }

    /** No bare element responds to the wheel — not even {@code overflow: auto}. */
    @Test
    public void bareElementsIgnoreTheWheel() {
        UIElement scroller = scrollingList(Overflow.AUTO);
        UIWindow window = scroller.getAttachedWindow();
        var c = scroller.getRuntimeCache();
        int x = Math.round((c.getX() + 10f) * 2f), y = Math.round((c.getY() + 20f) * 2f);

        window.getInputHandler().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 3f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.tickAnimations(0.016f);

        assertEquals("a bare element must not be wheel-scrollable", 0f, scroller.getScrollTop(), 0.5f);
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
        UIElement scroller = scrollingList(Overflow.AUTO);
        assertEquals(ROWS * ROW_H, scroller.getScrollHeight(), 0.5f);
        assertEquals(VIEWPORT, scroller.getClientHeight(), 0.5f);
        assertEquals(ROWS * ROW_H - VIEWPORT, scroller.getMaxScrollTop(), 0.5f);
    }

    /** The container's own box must not grow to fit its content — that's what overflow buys. */
    @Test
    public void scrollingDoesNotResizeTheContainer() {
        UIElement scroller = scrollingList(Overflow.AUTO);
        float before = scroller.getRuntimeCache().getHeight();
        scroller.setScrollTop(60f);
        assertEquals(before, scroller.getRuntimeCache().getHeight(), 0.5f);
        assertEquals(VIEWPORT, before, 0.5f);
    }

    @Test
    public void scrollIsClampedToTheContent() {
        UIElement scroller = scrollingList(Overflow.AUTO);
        scroller.setScrollTop(9999f);
        assertEquals(ROWS * ROW_H - VIEWPORT, scroller.getScrollTop(), 0.5f);
        scroller.setScrollTop(-50f);
        assertEquals(0f, scroller.getScrollTop(), 0.5f);
    }

    /** Assigning scrollTop to an unscrollable element does nothing, as in the DOM. */
    @Test
    public void nonScrollContainersIgnoreScrolling() {
        UIElement plain = scrollingList(Overflow.VISIBLE);
        plain.setScrollTop(50f);
        assertEquals(0f, plain.getScrollTop(), 0.5f);

        UIElement clipped = scrollingList(Overflow.CLIP);
        clipped.setScrollTop(50f);
        assertEquals("overflow:clip must not scroll", 0f, clipped.getScrollTop(), 0.5f);
    }

    @Test
    public void clampScrollPullsBackWhenContentShrinks() {
        UIElement scroller = scrollingList(Overflow.AUTO);
        scroller.setScrollTop(100f);
        assertEquals(100f, scroller.getScrollTop(), 0.5f);

        scroller.clearAllChildren();
        scroller.addChild(new UIElement().layout(l -> l.width(80).height(ROW_H)));
        layOut(scroller);
        scroller.clampScroll();

        assertEquals("content now fits, so scroll must return to 0", 0f, scroller.getScrollTop(), 0.5f);
    }

    // ── The load-bearing claim: input follows the scroll ─────────────────────

    /**
     * The design rests on this. The offset lives in the transform chain, and
     * {@code UIWindow.elementHitTest} inverts that same matrix — so hit-testing must follow the
     * scroll with no second code path <em>and without a paint having happened</em>. If this fails,
     * scrolling would need its own input plumbing and the whole approach is wrong.
     */
    @Test
    public void hitTestingFollowsTheScroll() {
        UIElement scroller = scrollingList(Overflow.AUTO);
        UIWindow window = scroller.getAttachedWindow();
        UIElement firstRow = scroller.getChildren().get(0);
        UIElement fourthRow = scroller.getChildren().get(3);

        // uiScale 2: physical = logical * 2. Probe the middle of the viewport.
        int probeX = Math.round((scroller.getRuntimeCache().getX() + 10f) * 2f);
        int probeY = Math.round((scroller.getRuntimeCache().getY() + 20f) * 2f);

        assertSame("unscrolled, the first row should be under the probe",
                firstRow, window.getHoveredElement(probeX, probeY));

        // Row 3 starts at 120px; scrolling by 120 brings it to the top of the viewport.
        scroller.setScrollTop(120f);

        assertSame("after scrolling, the fourth row should be under the same probe",
                fourthRow, window.getHoveredElement(probeX, probeY));
    }

    /** Content scrolled out of the viewport must not be clickable, even though it still exists. */
    @Test
    public void contentScrolledOutOfViewIsNotHittable() {
        UIElement scroller = scrollingList(Overflow.AUTO);
        UIWindow window = scroller.getAttachedWindow();
        UIElement firstRow = scroller.getChildren().get(0);

        scroller.setScrollTop(150f); // pushes rows 0-2 above the viewport

        for (int y = 0; y < VIEWPORT; y += 5) {
            int px = Math.round((scroller.getRuntimeCache().getX() + 10f) * 2f);
            int py = Math.round((scroller.getRuntimeCache().getY() + y) * 2f);
            assertNotSame("row scrolled out of view is still hittable at y=" + y,
                    firstRow, window.getHoveredElement(px, py));
        }
    }

    /** Scroll-exempt children (a scroll container's own scrollbars) must stay put. */
    @Test
    public void scrollExemptChildrenDoNotMove() {
        UIElement scroller = scrollingList(Overflow.AUTO);
        UIElement bar = new UIElement().layout(l -> l.width(10).height(VIEWPORT));
        bar.setScrollExempt(true);
        scroller.addChild(bar);
        layOut(scroller);

        float before = bar.getRuntimeCache().getY();
        scroller.setScrollTop(120f);
        assertEquals("a scroll-exempt child scrolled away with the content",
                before, bar.getRuntimeCache().getY(), 0.5f);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UIElement withOverflow(Overflow mode) {
        UIElement e = new UIElement();
        StyleGroup.defaultPipeline(e.getStyle().getGeneralGroup(), g -> g.overflow(mode));
        return e;
    }

    /** A 100px-tall box with 200px of rows in it — a plain element, no widget. */
    private UIElement scrollingList(Overflow mode) {
        UIElement scroller = withOverflow(mode);
        scroller.layout(l -> l.width(80).height(VIEWPORT).flexDirection(FlexDirection.COLUMN));
        for (int i = 0; i < ROWS; i++) {
            scroller.addChild(new UIElement().layout(l -> l.width(80).height(ROW_H)));
        }
        layOut(scroller);
        return scroller;
    }

    private void layOut(UIElement scroller) {
        UIWindow window = scroller.getAttachedWindow();
        if (window == null) {
            UIElement root = new UIElement().layout(l -> l.width(400).height(300));
            root.addChild(scroller);
            window = new UIWindow(Ui.of(root));
            window.init(800, 600);
        }
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }
}
