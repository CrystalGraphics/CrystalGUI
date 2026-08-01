package com.crystalgui.ui;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.tree.UITreeTraversal;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Hit-testing into a popover promoted out of a <b>transformed</b> ancestor.
 *
 * <p>This is the node graph's exact geometry: the canvas plane carries a pan/zoom {@code transform}, a
 * node sits on it, and a field's popover opens from inside that node. Promotion exists precisely to
 * escape the ancestor chain, so the popover paints from the bare root transform — and the question this
 * pins is whether hit-testing agrees with where it painted. If the two disagree the popover renders
 * perfectly and is simply dead to the mouse, which is indistinguishable from "the widget is broken".</p>
 */
public class PromotedPopoverHitTest extends UiTestBase {

    private UIWindow window;
    private UIElement plane;
    private UIElement anchor;
    private Popover popover;
    private UIElement inner;

    private void build(UITransform planeTransform) {
        UIElement root = new UIElement();
        window = new UIWindow(Ui.of(root));
        window.init(400, 400);

        plane = new UIElement();
        plane.layout(l -> l.width(400).height(400));
        plane.setTransform(planeTransform);
        root.addChild(plane);

        anchor = new UIElement();
        anchor.layout(l -> l.width(20).height(10));
        plane.addChild(anchor);

        popover = new Popover();
        popover.layout(l -> l.width(100).height(80));
        inner = new UIElement();
        inner.layout(l -> l.width(100).height(80));
        popover.addChild(inner);
        anchor.addChild(popover);

        window.updateWithoutPainting();
        popover.showFor(anchor, anchor);
        window.updateWithoutPainting();
    }

    /** Baseline: with no ancestor transform the popover is hittable where it sits. */
    @Test
    public void untransformedAncestorIsHittable() {
        build(UITransform.IDENTITY);
        float x = popover.getRuntimeCache().getX() + 5;
        float y = popover.getRuntimeCache().getY() + 5;
        assertSame("a popover with no ancestor transform must be hittable",
                inner, window.getHoveredElement(x, y));
    }

    /**
     * The real case. The popover paints from the root transform, so its painted rect is its layout rect
     * — the ancestor's scale must NOT be applied a second time by the hit test.
     */
    @Test
    public void scaledAncestorDoesNotDisplaceTheHitTest() {
        build(UITransform.scale(2f, 2f));
        float x = popover.getRuntimeCache().getX() + 5;
        float y = popover.getRuntimeCache().getY() + 5;
        assertSame("a promoted popover must hit-test where it paints, not where its scaled ancestor is",
                inner, window.getHoveredElement(x, y));
    }

    /**
     * The picker itself, inside the popover, on a scaled plane — the Color node's exact arrangement.
     *
     * <p>{@code ColorSelector} calls {@code markAsInternal()} on itself and is then added with the
     * ordinary {@code addChild}, which is the one thing this case has that the plain-element cases above
     * do not. If internal marking removed it from hit-testing, the picker would render and be dead.</p>
     */
    @Test
    public void theColorSelectorInsideAPromotedPopoverIsHittable() {
        build(UITransform.scale(2f, 2f));
        com.crystalgui.ui.elements.ColorSelector picker = new com.crystalgui.ui.elements.ColorSelector();
        popover.addChild(picker);
        window.updateWithoutPainting();

        float x = picker.getRuntimeCache().getX() + 4;
        float y = picker.getRuntimeCache().getY() + 4;
        UIElement hit = window.getHoveredElement(x, y);
        assertNotNull("a point inside the picker must hit something", hit);
        assertTrue("the hit must be the picker or one of its descendants, was " + hit,
                java.util.Arrays.asList(UITreeTraversal.pathToRoot(hit)).contains(picker));
    }

    /** Same for a translation — a panned canvas must not drag the popover's hit box with it. */
    @Test
    public void translatedAncestorDoesNotDisplaceTheHitTest() {
        build(UITransform.translate(60f, 40f));
        float x = popover.getRuntimeCache().getX() + 5;
        float y = popover.getRuntimeCache().getY() + 5;
        assertSame("a promoted popover must hit-test where it paints, not where its panned ancestor is",
                inner, window.getHoveredElement(x, y));
    }
}
