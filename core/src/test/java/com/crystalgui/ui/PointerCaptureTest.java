package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * <b>Pointer capture</b> — Pointer Events Level 3, and the primitive pointer-based dragging is built
 * on. This engine deliberately does not implement HTML5 drag-and-drop; the web itself moved to
 * pointer events, so this is what "match the web" actually means here.
 *
 * <p>The spec sentence everything below follows from: "the capturing target will substitute the
 * normal hit testing result <b>as if the pointer is always over the capturing target</b>, and they
 * MUST always be targeted at this element until capture is released."</p>
 *
 * <p>The second rule is the one that was broken before this existed: "when an element receives the
 * pointer capture all the following events for that pointer are considered to be <b>inside the
 * boundary of the capturing element</b>". Dragging a slider used to run the ordinary per-frame hover
 * diff, so {@code :hover} flickered on and {@code mouseenter}/{@code mouseleave} fired on every
 * element the cursor crossed. Fixing the hover chain to reach ancestors made it worse, not better.</p>
 */
public class PointerCaptureTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root, source, other;
    private final List<String> log = new ArrayList<>();

    /** Two disjoint 100x100 boxes: `source` at 0,0 and `other` at 200,0. */
    @Before
    public void buildTree() {
        log.clear();
        root = new UIElement().layout(l -> l.width(400).height(400));
        source = new UIElement().layout(l -> l.width(100).height(100));
        other = new UIElement().layout(l -> l.width(100).height(100).marginLeft(100));
        root.layout(l -> l.flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        root.addChild(source);
        root.addChild(other);

        record("source", source);
        record("other", other);

        window = new UIWindow(Ui.of(root));
        window.init(800, 800); // uiScale 2
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        input = window.getInputHandler();

        // Present one frame before any input. consumeMouseEvent drops everything until
        // firstFrameOver is set — hover has nothing to be relative to before a frame exists — so
        // without this the very first press is silently discarded.
        input.beginFrame();
        input.endFrame();
    }

    private void record(String name, UIElement element) {
        element.onMouseEnter.attachListener((el, e) -> log.add("enter:" + name), false, false);
        element.onMouseLeave.attachListener((el, e) -> log.add("leave:" + name), false, false);
    }

    private void move(float logicalX, float logicalY) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(logicalX * 2f), Math.round(logicalY * 2f), 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
    }

    private void press(float logicalX, float logicalY) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(logicalX * 2f), Math.round(logicalY * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    private void release(float logicalX, float logicalY) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(logicalX * 2f), Math.round(logicalY * 2f), 0, 0, 0, false, 0f, 2L));
        input.beginFrame();
        input.endFrame();
    }

    // ── Hit-test substitution ───────────────────────────────────────────────

    /**
     * Events go to the capture target, not to whatever is physically underneath. The raw
     * {@link UIWindow#getHoveredElement} is deliberately left alone — capture is an <em>input</em>
     * concept, so the substitution belongs in the handler rather than in the tree's geometry.
     */
    @Test
    public void captureSubstitutesTheHitTestResult() {
        List<String> moves = new ArrayList<>();
        source.onMouseMove.attachListener((el, e) -> moves.add("source"), false, false);
        other.onMouseMove.attachListener((el, e) -> moves.add("other"), false, false);

        press(50f, 50f);            // over source, button down
        input.setPointerCapture(source);
        moves.clear();

        move(250f, 50f);            // physically over `other`

        assertSame(source, input.getPointerCaptureTarget());
        assertTrue("the move must be delivered to the capture target; log=" + moves,
                moves.contains("source"));
        assertFalse("it must not be delivered to the element under the cursor; log=" + moves,
                moves.contains("other"));
        assertSame("the window's own geometric hit test is unchanged — only the handler substitutes",
                other, window.getHoveredElement(Math.round(250f * 2f), Math.round(50f * 2f)));
    }

    /** <b>The bug this exists for.</b> No boundary events may reach anything else during capture. */
    @Test
    public void noBoundaryEventsLeakToOtherElementsWhileCaptured() {
        press(50f, 50f);
        input.setPointerCapture(source);
        log.clear();

        move(250f, 50f);   // straight across `other`
        move(50f, 50f);    // and back

        assertTrue("nothing should have entered or left anything mid-capture; log=" + log,
                log.isEmpty());
    }

    /** {@code :hover} must stay pinned to the captured element, not follow the cursor. */
    @Test
    public void theHoverPseudoClassStaysOnTheCapturedElement() {
        press(50f, 50f);
        input.setPointerCapture(source);

        move(250f, 50f);

        assertTrue("the captured element keeps :hover", source.isHovered());
        assertFalse("the element actually under the cursor must not take :hover", other.isHovered());
    }

    // ── Release ─────────────────────────────────────────────────────────────

    @Test
    public void releasingRestoresNormalHitTesting() {
        press(50f, 50f);
        input.setPointerCapture(source);
        move(250f, 50f);

        input.releasePointerCapture();
        log.clear();
        move(250f, 50f);

        assertFalse(input.hasPointerCapture());
        assertTrue("after release the real target under the cursor takes over; log=" + log,
                log.contains("enter:other"));
    }

    /** The spec releases capture implicitly once the buttons go up — after delivering the up event,
     * which is what lets a drag end anywhere on screen. */
    @Test
    public void releaseHappensImplicitlyOnButtonUp() {
        press(50f, 50f);
        input.setPointerCapture(source);
        assertTrue(input.hasPointerCapture());

        release(250f, 50f); // released far away, over a different element

        assertFalse("button-up must implicitly end capture", input.hasPointerCapture());
    }

    /** "only when the pointer is in its active buttons state … otherwise fails silently" — and
     * capture taken with no button held could never be ended by a release, wedging input. */
    @Test
    public void captureWithNoButtonDownFailsSilently() {
        move(50f, 50f);

        input.setPointerCapture(source);

        assertFalse(input.hasPointerCapture());
    }

    /** A captured element that leaves the tree must not keep swallowing every pointer event —
     * that reads as totally dead input, with no error to explain it. */
    @Test
    public void captureIsDroppedIfTheTargetLeavesTheTree() {
        press(50f, 50f);
        input.setPointerCapture(source);

        source.removeSelf();
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        move(250f, 50f);

        assertFalse("a detached capture target must not hold input hostage", input.hasPointerCapture());
    }
}
