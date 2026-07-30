package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@code mouseenter}/{@code mouseleave} fire on <b>every</b> element entered or left, not only on the
 * exact element under the pointer.
 *
 * <p>The distinction is easy to misread, and getting it wrong is nearly invisible in a toy tree.
 * These two events do not <em>bubble</em> — but the DOM still dispatches a separate one to each
 * element in the entered/left chain. Firing only on the precise hit target means <b>any container
 * with children never hears about the pointer at all</b>: hovering a row's own text label left the
 * row with no event, so only the bare gaps between its children worked. It surfaced as a tooltip
 * that appeared when you hovered the space between two rows but not when you hovered a row.</p>
 *
 * <p>The {@code :hover} pseudo-class already walked this same chain, so before this the two
 * disagreed about what "hovered" meant — CSS said one thing, listeners another.</p>
 */
public class HoverChainTest extends UiTestBase {

    private UIWindow window;
    private UIElement root, outer, inner;
    private final List<String> log = new ArrayList<>();

    /** root > outer(100x100) > inner(40x40), each recording its own enter/leave. */
    private void buildNestedTree() {
        root = new UIElement().layout(l -> l.width(400).height(400));
        outer = new UIElement().layout(l -> l.width(100).height(100));
        inner = new UIElement().layout(l -> l.width(40).height(40));
        outer.addChild(inner);
        root.addChild(outer);

        record("outer", outer);
        record("inner", inner);

        window = new UIWindow(Ui.of(root));
        window.init(800, 800); // uiScale 2
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    private void record(String name, UIElement element) {
        element.onMouseEnter.attachListener((el, e) -> log.add("enter:" + name), false, false);
        element.onMouseLeave.attachListener((el, e) -> log.add("leave:" + name), false, false);
    }

    /** Physical coords; uiScale is 2. Drives a real move through the accumulate-then-dispatch path. */
    private void moveTo(float logicalX, float logicalY) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(logicalX * 2f), Math.round(logicalY * 2f),
                0, 0, -1, false, 0f, -1L)); // button -1 / millis -1 == a pure move
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /**
     * The bug, exactly. The pointer is over {@code inner}, which is inside {@code outer} — so
     * {@code outer} has been entered too and must be told.
     */
    @Test
    public void enteringAChildAlsoEntersItsAncestors() {
        buildNestedTree();

        moveTo(10f, 10f); // inside inner, which is at the top-left of outer

        assertTrue("the container must receive enter when the pointer is over its child; log=" + log,
                log.contains("enter:outer"));
        assertTrue("the child itself must receive enter too; log=" + log,
                log.contains("enter:inner"));
    }

    /** Outermost first on the way in — an ancestor learns the pointer arrived before its child does,
     * matching the DOM's entry order. */
    @Test
    public void ancestorsAreEnteredBeforeTheirChildren() {
        buildNestedTree();

        moveTo(10f, 10f);

        assertTrue("outer must be entered before inner; log=" + log,
                log.indexOf("enter:outer") < log.indexOf("enter:inner"));
    }

    /** Moving within the same ancestor must not re-fire that ancestor — only the changed part of the
     * chain gets events, which is the whole reason the common ancestor is computed. */
    @Test
    public void movingWithinTheSameAncestorDoesNotReEnterIt() {
        buildNestedTree();

        moveTo(10f, 10f);   // over inner (and outer)
        log.clear();
        moveTo(70f, 70f);   // still inside outer, but off inner

        assertEquals("leaving the child must not leave and re-enter the container it stayed inside; log=" + log,
                List.of("leave:inner"), log);
    }

    /** Innermost first on the way out — the DOM's exit order, and the mirror of entry. */
    @Test
    public void leavingFiresInnermostFirst() {
        buildNestedTree();

        moveTo(10f, 10f);
        log.clear();
        moveTo(300f, 300f); // clean off both

        assertEquals("both must be left, innermost first; log=" + log,
                List.of("leave:inner", "leave:outer"), log);
    }

    /** The pseudo-class and the listeners must agree — they walk the same chain now. */
    @Test
    public void theHoverPseudoClassAgreesWithTheEvents() {
        buildNestedTree();

        moveTo(10f, 10f);

        assertTrue("outer should match :hover while the pointer is over its child", outer.isHovered());
        assertTrue(inner.isHovered());

        moveTo(300f, 300f);

        assertFalse(outer.isHovered());
        assertFalse(inner.isHovered());
    }
}
