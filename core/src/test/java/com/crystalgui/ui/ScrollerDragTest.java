package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.ScrollBehavior;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.elements.Scroller;
import com.crystalgui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Scrollbar dragging and wheel handling, driven through the same entry point real input uses.
 *
 * <p>Value maths and static geometry can both look perfect while a drag never starts — that's how the
 * Slider shipped broken — so these push events through
 * {@link com.crystalgui.ui.input.UIInputHandler#consumeMouseEvent}.</p>
 */
public class ScrollerDragTest {

    private static final float VIEWPORT = 100f;
    private static final float ROW_H = 40f;
    private static final int ROWS = 5;                 // 200px content in a 100px box

    private UIWindow window;
    private ScrollerView view;

    /** Whether the stub adapter reports the left button as physically held — the Scroller polls this
     * to decide when to stop auto-repeating, since a release outside the button never reaches it. */
    private boolean mouseHeld = false;
    /** Whether the stub adapter reports SHIFT as held, for the shift+wheel case. */
    private boolean shiftHeld = false;

    @Before
    public void registerStubAdapter() {
        mouseHeld = false;
        shiftHeld = false;
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return shiftHeld ? com.crystalgui.core.input.keyboard.Modifiers.SHIFT : 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return mouseHeld; }
            @Override public int howManyMouseButtons() { return 3; }
        });
    }

    private ScrollerView setUp(float uiScale) {
        view = new ScrollerView();
        view.layout(l -> l.width(120).height(VIEWPORT).flexDirection(FlexDirection.COLUMN));
        for (int i = 0; i < ROWS; i++) {
            view.addChild(new UIElement().layout(l -> l.width(120).height(ROW_H)));
        }

        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        root.addChild(view);

        window = new UIWindow(Ui.of(root));
        window.setUiScale(uiScale);
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        frame();
        view.refreshScrollers();
        frame();
        return view;
    }

    /** One frame, mirroring what {@code UIWindow.paintFrame} does minus the painting — including the
     * scroll-animation tick, since default.css opts scroll views into {@code scroll-behavior: smooth}
     * and without it a smooth scroll would never advance. */
    private void frame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.tickAnimations(0.016f);
        window.calculateLayout();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** Runs enough frames for a smooth scroll to settle. */
    private void settle() {
        for (int i = 0; i < 120; i++) frame();
    }

    private void mouseTo(int x, int y) {
        window.getInputHandler().consumeMouseEvent(new SystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
    }

    private void press(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(x, y, 0, 0, 0, true, 0f, System.currentTimeMillis()));
    }

    private void release(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(x, y, 0, 0, 0, false, 0f, System.currentTimeMillis()));
    }

    private void wheel(int x, int y, float notches) {
        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(x, y, 0, 0, -1, false, notches, -1L));
    }

    /** Physical centre of the vertical scrollbar's thumb — where a user would grab it. */
    private int[] thumbCentrePhys(float uiScale) {
        var t = view.verticalScroller().thumb().getRuntimeCache();
        return new int[]{
                Math.round((t.getX() + t.getWidth() / 2f) * uiScale),
                Math.round((t.getY() + t.getHeight() / 2f) * uiScale)
        };
    }

    // ── Wheel ───────────────────────────────────────────────────────────────

    /**
     * Direction, which was shipped backwards once already. Wheel deltas reach us already normalised
     * to the top-left-origin convention, so a POSITIVE delta means wheel-down and must increase
     * scrollTop (content moves up, later content appears).
     */
    @Test
    public void wheelDownScrollsContentDown() {
        setUp(2f);
        var c = view.getRuntimeCache();
        int x = Math.round((c.getX() + 20f) * 2f), y = Math.round((c.getY() + 20f) * 2f);

        mouseTo(x, y);
        frame();
        wheel(x, y, 1f);
        // Two frames: mouse input is accumulated and dispatched in endFrame(), which runs AFTER the
        // animation tick within the same frame, so easing only begins on the next one.
        frame();
        frame();

        assertTrue("wheel-down should increase scrollTop, got " + view.getScrollTop(),
                view.getScrollTop() > 0f);
    }

    @Test
    public void wheelUpScrollsBackToTheStart() {
        setUp(2f);
        var c = view.getRuntimeCache();
        int x = Math.round((c.getX() + 20f) * 2f), y = Math.round((c.getY() + 20f) * 2f);

        view.setScrollTop(80f);
        mouseTo(x, y);
        frame();
        wheel(x, y, -1f);
        frame();

        assertTrue("wheel-up should decrease scrollTop", view.getScrollTop() < 80f);
    }

    /** The wheel must not scroll past the content in either direction. */
    @Test
    public void wheelIsClamped() {
        setUp(2f);
        var c = view.getRuntimeCache();
        int x = Math.round((c.getX() + 20f) * 2f), y = Math.round((c.getY() + 20f) * 2f);
        mouseTo(x, y);
        frame();

        for (int i = 0; i < 50; i++) { wheel(x, y, 1f); frame(); }
        settle();
        assertEquals(view.getMaxScrollTop(), view.getScrollTop(), 0.5f);

        for (int i = 0; i < 50; i++) { wheel(x, y, -1f); frame(); }
        settle();
        assertEquals(0f, view.getScrollTop(), 0.5f);
    }

    // ── Smooth scrolling ────────────────────────────────────────────────────

    /** The wheel must ease rather than teleport: one notch sets a target, and the rendered offset is
     * still short of it on the very next frame. */
    @Test
    public void wheelEasesTowardTheTargetInsteadOfJumping() {
        setUp(2f);
        var c = view.getRuntimeCache();
        int x = Math.round((c.getX() + 20f) * 2f), y = Math.round((c.getY() + 20f) * 2f);

        mouseTo(x, y);
        frame();
        wheel(x, y, 1f);
        frame();   // dispatches the wheel, setting the target
        frame();   // first frame that actually eases

        float target = view.getTargetScrollTop();
        assertTrue("nothing was scheduled to scroll", target > 0f);
        assertTrue("scroll jumped straight to the target instead of easing"
                        + " (at " + view.getScrollTop() + " of " + target + ")",
                view.getScrollTop() < target);
        assertTrue("scroll did not move at all", view.getScrollTop() > 0f);

        settle();
        assertEquals("smooth scroll never reached its target", target, view.getScrollTop(), 0.5f);
    }

    /** scroll-behavior: auto must still be an instant jump — the CSS default, and what a consumer
     * gets back by opting out. */
    @Test
    public void autoBehaviourJumpsImmediately() {
        setUp(2f);
        StyleGroup.importantPipeline(view.getStyle().getGeneralGroup(),
                g -> g.scrollBehavior(ScrollBehavior.AUTO));
        frame();

        view.setScrollTop(60f);
        assertEquals("scroll-behavior:auto should not animate", 60f, view.getScrollTop(), 0.5f);
    }

    /** Dragging the thumb must never ease, or the thumb lags behind the cursor. */
    @Test
    public void draggingTheThumbIsNotSmoothed() {
        setUp(2f);
        int[] c = thumbCentrePhys(2f);
        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        float travel = view.verticalScroller().getRuntimeCache().getHeight()
                - view.verticalScroller().thumb().getRuntimeCache().getHeight();
        mouseTo(c[0], c[1] + Math.round(travel * 0.25f * 2f));
        frame();

        assertEquals("thumb drag was animated — it must land instantly",
                view.getTargetScrollTop(), view.getScrollTop(), 0.5f);
    }

    // ── Dragging ────────────────────────────────────────────────────────────

    @Test
    public void pressOnTheThumbStartsADrag() {
        setUp(2f);
        int[] c = thumbCentrePhys(2f);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        var thumb = view.verticalScroller().thumb().getRuntimeCache();
        assertTrue("press on the thumb did not start a drag."
                        + " thumb=(" + thumb.getX() + "," + thumb.getY()
                        + " " + thumb.getWidth() + "x" + thumb.getHeight() + ")"
                        + " pressedPhys=(" + c[0] + "," + c[1] + ")"
                        + " hovered=" + window.getHoveredElement(c[0], c[1]),
                window.getInputHandler().getDragController().isDragging());
    }

    @Test
    public void draggingTheThumbScrollsTheContent() {
        setUp(2f);
        int[] c = thumbCentrePhys(2f);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        // Drag down by a quarter of the bar's travel.
        float travel = view.verticalScroller().getRuntimeCache().getHeight()
                - view.verticalScroller().thumb().getRuntimeCache().getHeight();
        mouseTo(c[0], c[1] + Math.round(travel * 0.25f * 2f));
        frame();

        assertTrue("dragging the thumb did not scroll, scrollTop=" + view.getScrollTop(),
                view.getScrollTop() > 0f);

        release(c[0], c[1]);
        assertFalse(window.getInputHandler().getDragController().isDragging());
    }

    /** The guard that caught the Slider's physical-vs-logical bug. Asserts movement first so it
     * cannot pass vacuously when no drag starts at either scale. */
    @Test
    public void dragResultIsIndependentOfUiScale() {
        float atOne = dragQuarterAtScale(1f);
        float atTwo = dragQuarterAtScale(2f);
        assertTrue("the drag never scrolled, so the comparison below would be vacuous", atOne > 0f);
        assertEquals(atOne, atTwo, 2f);
    }

    private float dragQuarterAtScale(float uiScale) {
        setUp(uiScale);
        int[] c = thumbCentrePhys(uiScale);
        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        float travel = view.verticalScroller().getRuntimeCache().getHeight()
                - view.verticalScroller().thumb().getRuntimeCache().getHeight();
        mouseTo(c[0], c[1] + Math.round(travel * 0.25f * uiScale));
        frame();
        return view.getScrollTop();
    }

    // ── Bar geometry ────────────────────────────────────────────────────────

    /** Thumb length encodes the visible fraction — half the content visible, half-length thumb. */
    @Test
    public void thumbLengthReflectsTheVisibleFraction() {
        setUp(2f);
        Scroller bar = view.verticalScroller();
        float expected = VIEWPORT / (ROWS * ROW_H);
        assertEquals(expected, bar.getVisibleRatio(), 0.02f);
    }

    /** The thumb must stay inside its track at both ends. */
    @Test
    public void thumbStaysInsideTheTrackAtBothExtremes() {
        setUp(2f);
        Scroller bar = view.verticalScroller();

        view.setScrollTop(0f);
        frame();
        assertTrue(bar.thumb().getRuntimeCache().getY() >= bar.getRuntimeCache().getY() - 0.5f);

        view.setScrollTop(view.getMaxScrollTop());
        frame();
        var t = bar.thumb().getRuntimeCache();
        var track = bar.getRuntimeCache();
        assertTrue("thumb overhangs the track at max scroll",
                t.getY() + t.getHeight() <= track.getY() + track.getHeight() + 0.5f);
    }

    // ── Both axes at once ───────────────────────────────────────────────────

    /** A view that overflows on BOTH axes, so both bars show. */
    private ScrollerView setUpBothAxes() {
        setUp(2f);
        for (UIElement child : view.getChildren()) {
            if (!child.isScrollExempt()) child.layout(l -> l.width(400));
        }
        frame();
        view.refreshScrollers();
        frame();
        return view;
    }

    /**
     * With both bars showing they must not run into each other in the bottom-right corner — browsers
     * leave an empty square there. Without it the two bars overlap, and with the step buttons enabled
     * the two tail buttons sit on top of one another.
     */
    @Test
    public void bothBarsLeaveTheCornerClear() {
        setUpBothAxes();
        var v = view.verticalScroller().getRuntimeCache();
        var h = view.horizontalScroller().getRuntimeCache();

        assertTrue("both bars should be visible", v.getHeight() > 0f && h.getWidth() > 0f);

        float vBottom = v.getY() + v.getHeight();
        float hTop = h.getY();
        assertTrue("vertical bar runs into the horizontal one (bottom " + vBottom + " vs " + hTop + ")",
                vBottom <= hTop + 0.5f);

        float hRight = h.getX() + h.getWidth();
        float vLeft = v.getX();
        assertTrue("horizontal bar runs into the vertical one",
                hRight <= vLeft + 0.5f);
    }

    /** With only one axis overflowing there is no corner to reserve, so the bar spans fully. */
    @Test
    public void asingleBarSpansTheFullEdge() {
        setUp(2f);   // vertical overflow only
        var v = view.verticalScroller().getRuntimeCache();
        assertEquals("a lone vertical bar should span the whole height",
                view.getRuntimeCache().getHeight(), v.getHeight(), 1f);
    }

    /** Shift+wheel scrolls horizontally — the universal convention, and the only way to reach a
     * horizontal overflow with a plain vertical wheel. */
    @Test
    public void shiftWheelScrollsHorizontally() {
        setUpBothAxes();
        var c = view.getRuntimeCache();
        int x = Math.round((c.getX() + 20f) * 2f), y = Math.round((c.getY() + 20f) * 2f);

        mouseTo(x, y);
        frame();
        shiftHeld = true;
        wheel(x, y, 1f);
        frame();
        frame();

        assertTrue("shift+wheel should scroll horizontally", view.getTargetScrollLeft() > 0f);
        assertEquals("shift+wheel must not also scroll vertically", 0f, view.getTargetScrollTop(), 0.5f);
    }

    // ── Track clicks ────────────────────────────────────────────────────────

    /**
     * Clicking the groove centres the thumb on the click rather than paging by a screenful, and
     * eases there. Checked by geometry — where the thumb ends up — rather than by the value, so it
     * would catch an off-by-half-a-thumb error that a value assertion would miss.
     */
    @Test
    public void clickingTheTrackCentresTheThumbOnTheClick() {
        setUp(2f);
        Scroller bar = view.verticalScroller();
        var groove = bar.track().getRuntimeCache();

        // Three-quarters down the groove.
        float targetLocalY = groove.getY() + groove.getHeight() * 0.75f;
        int px = Math.round((groove.getX() + groove.getWidth() / 2f) * 2f);
        int py = Math.round(targetLocalY * 2f);

        mouseTo(px, py);
        frame();
        press(px, py);
        frame();

        assertTrue("a track click should ease, not jump",
                view.getScrollTop() < view.getTargetScrollTop());

        settle();
        var thumb = bar.thumb().getRuntimeCache();
        float thumbCentre = thumb.getY() + thumb.getHeight() / 2f;
        assertEquals("the thumb's centre should land on the click",
                targetLocalY, thumbCentre, 3f);
    }

    /** Clicking within half a thumb of the end pins to the extreme rather than overshooting. */
    @Test
    public void clickingTheEndOfTheTrackPinsToTheExtreme() {
        setUp(2f);
        Scroller bar = view.verticalScroller();
        var groove = bar.track().getRuntimeCache();

        int px = Math.round((groove.getX() + groove.getWidth() / 2f) * 2f);
        int py = Math.round((groove.getY() + groove.getHeight() - 1f) * 2f);

        mouseTo(px, py);
        frame();
        press(px, py);
        settle();

        assertEquals(view.getMaxScrollTop(), view.getScrollTop(), 1f);
    }

    // ── Step buttons ────────────────────────────────────────────────────────

    /** Hidden by default (as Ore and every modern desktop UI do), but fully wired — a theme turns
     * them on with CSS alone. */
    @Test
    public void stepButtonsExistAndAreHiddenByDefault() {
        setUp(2f);
        Scroller bar = view.verticalScroller();
        assertNotNull(bar.head());
        assertNotNull(bar.tail());
        assertEquals("head button should be display:none by default",
                0f, bar.head().getRuntimeCache().getHeight(), 0.5f);
        assertEquals(0f, bar.tail().getRuntimeCache().getHeight(), 0.5f);
    }

    /** A click moves the view by about one line, and repeated clicks accumulate — they apply to the
     * scroll TARGET, so a second click mid-animation adds to the first rather than restarting from
     * wherever the ease happened to be. */
    @Test
    public void stepButtonsNudgeByOneLineAndAccumulate() {
        setUp(2f);
        Scroller bar = view.verticalScroller();

        pressTarget(bar.tail());
        float afterOne = view.getTargetScrollTop();
        assertTrue("a step should move the view", afterOne > 0f);
        assertEquals("one click should be about one line", 40f, afterOne, 2f);

        pressTarget(bar.tail());
        assertEquals("a second click should add to the first, not restart",
                afterOne * 2f, view.getTargetScrollTop(), 2f);

        pressTarget(bar.head());
        assertEquals(afterOne, view.getTargetScrollTop(), 2f);
    }

    /**
     * A click steps by roughly one line, not by a slice of the content — so it feels the same on a
     * short list and a huge one, which is what browser arrows do. A fixed fraction would make the
     * jump grow with the content.
     */
    @Test
    public void stepIsOneLineRegardlessOfContentLength() {
        setUp(2f);
        float shortStepPx = view.verticalScroller().getStepFraction() * view.getScrollHeight();

        // Ten times the content.
        for (int i = 0; i < ROWS * 9; i++) {
            view.addChild(new UIElement().layout(l -> l.width(120).height(ROW_H)));
        }
        frame();
        view.refreshScrollers();
        frame();
        float longStepPx = view.verticalScroller().getStepFraction() * view.getScrollHeight();

        assertEquals("a step should cover the same distance regardless of content length",
                shortStepPx, longStepPx, 1f);
    }

    /**
     * Holding an arrow keeps scrolling, the way a browser's does — a short delay, then a steady
     * stream of small steps that reads as continuous motion once eased. A single click must stay a
     * single step, which is what the delay buys.
     */
    @Test
    public void holdingAStepButtonKeepsScrolling() {
        setUp(2f);
        Scroller bar = view.verticalScroller();

        mouseHeld = true;
        pressTarget(bar.tail());
        float afterClick = view.getTargetScrollTop();

        // Inside the repeat delay: still exactly one step.
        for (int i = 0; i < 10; i++) window.tickAnimations(0.016f);   // 0.16s < 0.3s delay
        assertEquals("repeat started before the delay elapsed — a click would not be a single step",
                afterClick, view.getTargetScrollTop(), 0.5f);

        // Past the delay: it keeps going.
        for (int i = 0; i < 40; i++) window.tickAnimations(0.016f);
        float whileHeld = view.getTargetScrollTop();
        assertTrue("holding the button did not keep scrolling", whileHeld > afterClick + 1f);

        // Release: it stops, even though the release happened nowhere near the button.
        mouseHeld = false;
        shiftHeld = false;
        window.tickAnimations(0.016f);
        float atRelease = view.getTargetScrollTop();
        for (int i = 0; i < 40; i++) window.tickAnimations(0.016f);
        assertEquals("repeat kept running after release", atRelease, view.getTargetScrollTop(), 0.5f);
    }

    /** Arrow clicks ease; only dragging lands instantly. */
    @Test
    public void stepButtonsAnimateWhileDraggingDoesNot() {
        setUp(2f);
        Scroller bar = view.verticalScroller();

        pressTarget(bar.tail());
        assertTrue("a step button should schedule a scroll", view.getTargetScrollTop() > 0f);
        assertTrue("a step button should ease rather than jump",
                view.getScrollTop() < view.getTargetScrollTop());

        settle();
        assertEquals(view.getTargetScrollTop(), view.getScrollTop(), 0.5f);
    }

    /** Showing the buttons must shorten the groove, not overlap it — otherwise the thumb's travel
     * would be measured against the whole bar and overshoot. */
    @Test
    public void showingButtonsShrinksTheTrack() {
        setUp(2f);
        Scroller bar = view.verticalScroller();
        float trackBefore = bar.track().getRuntimeCache().getHeight();

        window.getStyleEngine().addStylesheet(StyleSheet.parse(
                "scroller .__head__, scroller .__tail__ { display: flex; height: 12px; }"));
        frame();

        assertTrue("head button did not become visible",
                bar.head().getRuntimeCache().getHeight() > 0f);
        assertTrue("groove should shrink to make room for the buttons",
                bar.track().getRuntimeCache().getHeight() < trackBefore);
    }

    /** Dispatches a press directly at an element, bypassing coordinates — the buttons are hidden by
     * default so there is no on-screen position to click. */
    private void pressTarget(UIElement target) {
        window.getInputHandler().sendInputEvent(target,
                new com.crystalgui.ui.event.MouseEvent.Down(
                        target, new com.crystalgui.core.data.ReadOnlyVec2f(new org.joml.Vector2f()), 0, 1));
    }

    /** Bars hide when everything fits (overflow: auto), which is the whole difference from `scroll`. */
    @Test
    public void barsHideWhenContentFits() {
        setUp(2f);
        view.clearAllChildren();
        view.addChild(new UIElement().layout(l -> l.width(120).height(20)));
        frame();
        view.refreshScrollers();
        frame();

        assertEquals(0f, view.getMaxScrollTop(), 0.5f);
        assertEquals("bar should be display:none when content fits",
                0f, view.verticalScroller().getRuntimeCache().getHeight(), 0.5f);
    }

    // ── Horizontal-only views ───────────────────────────────────────────────

    /** A row of children wider than the view, with nothing to scroll vertically. */
    private ScrollerView horizontalOnly() {
        view = new ScrollerView();
        view.layout(l -> l.width(100).height(40).flexDirection(FlexDirection.ROW));
        for (int i = 0; i < ROWS; i++) {
            view.addChild(new UIElement().layout(l -> l.width(60).height(20)));
        }
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        root.addChild(view);

        window = new UIWindow(Ui.of(root));
        window.setUiScale(2f);
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        frame();
        view.refreshScrollers();
        frame();
        return view;
    }

    /**
     * On a view that can only move sideways, a plain wheel drives that axis.
     *
     * <p>Without this a tab strip or toolbar just eats the wheel and looks broken — you'd have to
     * know to hold shift, on a widget with no vertical axis at all.</p>
     */
    @Test
    public void aPlainWheelScrollsAHorizontalOnlyView() {
        horizontalOnly();
        assertEquals("precondition: nothing to scroll vertically", 0f, view.getMaxScrollTop(), 0.5f);
        assertTrue("precondition: something to scroll horizontally", view.getMaxScrollLeft() > 0f);

        wheel(20, 20, 1f);          // no shift held
        settle();

        assertTrue("a plain wheel must pan a horizontal-only view",
                view.getScrollLeft() > 0f);
    }

    /** ...but a view that CAN scroll vertically still treats the plain wheel as vertical. */
    @Test
    public void aPlainWheelStaysVerticalWhenThereIsAVerticalAxis() {
        setUp(2f);
        wheel(20, 20, 1f);
        settle();

        assertTrue(view.getScrollTop() > 0f);
        assertEquals("the horizontal axis must not move", 0f, view.getScrollLeft(), 0.5f);
    }

    // ── Hiding the bars without disabling scrolling ─────────────────────────

    /**
     * {@code setScrollbarsVisible(false)} hides the chrome but leaves the view scrollable — which
     * {@code overflow: hidden} does not, and which CSS cannot express because the bars' display is
     * written at IMPORTANT origin. TabView's strip relies on it: the bars are absolutely positioned,
     * so on a strip barely taller than one tab the horizontal bar sits on top of the tabs.
     */
    @Test
    public void hiddenScrollbarsStillScroll() {
        setUp(2f);
        view.setScrollbarsVisible(false);
        frame();
        view.refreshScrollers();
        frame();

        assertEquals("the bar must be gone",
                0f, view.verticalScroller().getRuntimeCache().getHeight(), 0.5f);

        wheel(20, 20, 1f);
        settle();

        assertTrue("hiding the bars must not disable scrolling", view.getScrollTop() > 0f);
    }
}
