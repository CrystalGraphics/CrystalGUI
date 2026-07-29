package com.crystalgui.ui;

import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link Tooltip} — the first real consumer of the top layer, and the thing that proves it works.
 *
 * <p>Sizes are set explicitly here rather than coming from {@code default.css}, so these tests pin
 * <em>placement logic</em> and don't turn red the moment someone re-tunes the user-agent sheet's
 * tooltip padding.</p>
 */
public class TooltipTest extends UiTestBase {

    private static final float ROOT_W = 800f, ROOT_H = 600f;
    private static final float TIP_W = 60f, TIP_H = 20f;

    private UIElement root;
    private UIWindow window;

    private UIElement newRoot() {
        root = new UIElement().layout(l -> l.width(ROOT_W).height(ROOT_H));
        return root;
    }

    private void attach() {
        window = new UIWindow(Ui.of(root));
        window.init(Math.round(ROOT_W * 2), Math.round(ROOT_H * 2)); // uiScale 2
        settle();
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    /** An anchor at a known place, plus a sized tooltip already parented to it. */
    private Tooltip tooltipOn(UIElement anchor) {
        Tooltip tip = new Tooltip("hello");
        tip.layout(l -> l.width(TIP_W).height(TIP_H));
        anchor.addChild(tip);
        return tip;
    }

    private float x(UIElement e) { return e.getRuntimeCache().getX() - root.getRuntimeCache().getX(); }
    private float y(UIElement e) { return e.getRuntimeCache().getY() - root.getRuntimeCache().getY(); }

    // ── Not disturbing the tree it lives in ─────────────────────────────────

    /**
     * A tooltip is an internal child of its anchor so the cascade reaches it — which means a
     * <em>closed</em> one would otherwise pad every element that had a tooltip. Closed popovers are
     * {@code display: none} on the web for the same reason.
     */
    @Test
    public void aHiddenTooltipDoesNotAffectItsAnchorsLayout() {
        UIElement anchor = new UIElement().layout(l -> l.width(100));
        anchor.addChild(new UIElement().layout(l -> l.width(100).height(30)));
        newRoot().addChild(anchor);
        attach();
        float heightWithout = anchor.getRuntimeCache().getHeight();

        tooltipOn(anchor);
        settle();

        assertEquals("a closed tooltip must not take up space in its anchor",
                heightWithout, anchor.getRuntimeCache().getHeight(), 0.001f);
    }

    @Test
    public void attachInstallsAnInternalChildNotAPublicOne() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(anchor);
        attach();

        Tooltip tip = Tooltip.attach(anchor, "explain");

        // Internal children DO live in `children` — what makes them internal is the flag, which is
        // what public traversal and UIDescriptionCodec filter on.
        assertTrue("a tooltip must be an internal child, not public content", tip.isInternalUI());
        assertSame(anchor, tip.getParent());
    }

    @Test
    public void detachRemovesItFromTheAnchor() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(anchor);
        attach();

        Tooltip tip = Tooltip.attach(anchor, "explain");
        tip.showFor(anchor);
        tip.detach();

        assertNull(tip.getParent());
        assertFalse(tip.isShown());
        assertTrue("detaching must also take it out of the top layer", window.getTopLayer().isEmpty());
    }

    /**
     * Ownership regression. When this wiring lived on {@code UIElement.setTooltip}, a
     * set/clear/set cycle attached a <em>second</em> pair of hover listeners each time. Creating the
     * tooltip and its listeners together in one call makes that unrepresentable.
     */
    @Test
    public void attachingWiresExactlyOnePairOfListeners() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20).marginTop(50));
        newRoot().addChild(anchor);
        attach();

        Tooltip tip = Tooltip.attach(anchor, "explain");
        tip.showFor(anchor);
        tip.showFor(anchor);
        settle();

        assertEquals(1, window.getTopLayer().elements().size());
    }

    /** Hovering the tooltip itself must not count as leaving the anchor, or it flickers forever. */
    @Test
    public void aTooltipNeverEatsThePointer() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        assertFalse("a tooltip must be transparent to hit-testing", tip.isHitTest());
    }

    // ── Placement ───────────────────────────────────────────────────────────

    @Test
    public void showingPlacesItBelowTheAnchor() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(40).marginLeft(50).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();

        assertTrue(tip.isShown());
        assertTrue(tip.isInTopLayer());
        assertEquals("left-aligned with the anchor", x(anchor), x(tip), 0.5f);
        assertEquals("directly below the anchor's bottom edge",
                y(anchor) + anchor.getRuntimeCache().getHeight(), y(tip), 0.5f);
    }

    /** Near the bottom there is no room below, so it flips above — the useful subset of the web's
     * {@code position-try-fallbacks}. */
    @Test
    public void itFlipsAboveWhenThereIsNoRoomBelow() {
        UIElement spacer = new UIElement().layout(l -> l.width(100).height(ROOT_H - 30f));
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(spacer);
        root.addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();

        assertEquals("with no room below, it must sit above the anchor",
                y(anchor) - TIP_H, y(tip), 0.5f);
    }

    @Test
    public void itClampsInsteadOfOverflowingTheRightEdge() {
        // Narrow, and hard against the right edge, so a left-aligned tooltip genuinely overflows:
        // anchor spans 770..790, so the tooltip would want 770..830 against a 800-wide root.
        UIElement anchor = new UIElement()
                .layout(l -> l.width(20).height(20).marginLeft(ROOT_W - 30f).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();

        assertTrue("must not overflow the right edge", x(tip) + TIP_W <= ROOT_W + 0.5f);
        assertEquals("clamped flush to the right edge", ROOT_W - TIP_W, x(tip), 0.5f);
    }

    // ── The reason the top layer exists ─────────────────────────────────────

    /**
     * <b>The whole feature.</b> An anchor deep inside an {@code overflow: hidden} scroller gets a
     * tooltip that lands outside the scroller's box — and is hittable there, which is the half that
     * used to be impossible even with a paint-order hack.
     */
    @Test
    public void aTooltipEscapesAClippingAncestor() {
        // The scroller is SHORTER than its row on purpose: the row's bottom edge — and therefore the
        // tooltip hanging off it — falls outside the clip box. That is the case that used to be
        // impossible to render.
        UIElement scroller = new UIElement()
                .layout(l -> l.width(200).height(30).marginLeft(40).marginTop(40))
                .generalStyle(g -> g.overflow(Overflow.HIDDEN));
        newRoot().addChild(scroller);
        UIElement row = new UIElement().layout(l -> l.width(200).height(40));
        scroller.addChild(row);
        attach();
        Tooltip tip = tooltipOn(row);

        tip.showFor(row);
        settle();

        float scrollerBottom = y(scroller) + scroller.getRuntimeCache().getHeight();
        assertTrue("the tooltip should land below the scroller's clipped box, at y=" + y(tip)
                        + " vs bottom=" + scrollerBottom,
                y(tip) + TIP_H > scrollerBottom);

        // And it must be reachable by the pointer out there. This cannot work by reordering the
        // main walk: elementHitTest refuses to recurse into a clipping ancestor's children when the
        // pointer is outside it, which is exactly where the tooltip now is.
        int probeX = Math.round((root.getRuntimeCache().getX() + x(tip) + 2f) * 2f);
        int probeY = Math.round((root.getRuntimeCache().getY() + y(tip) + TIP_H - 2f) * 2f);
        assertNotSame("the clip must no longer swallow the tooltip's own area",
                scroller, window.getHoveredElement(probeX, probeY));
    }

    /** Placement is recomputed per frame, so a scrolling anchor drags its tooltip along. */
    @Test
    public void placementFollowsAScrollingAnchor() {
        UIElement scroller = new UIElement()
                .layout(l -> l.width(200).height(100).marginLeft(40).marginTop(40))
                .generalStyle(g -> g.overflow(Overflow.AUTO));
        newRoot().addChild(scroller);
        for (int i = 0; i < 5; i++) scroller.addChild(new UIElement().layout(l -> l.width(200).height(40)));
        UIElement row = scroller.getChildren().get(2);
        attach();
        Tooltip tip = tooltipOn(row);

        tip.showFor(row);
        settle();
        float before = y(tip);

        scroller.setScrollTop(60f);
        // updateWithoutPainting(), not settle(), and no manual reposition() — because scrolling
        // changes no Taffy layout at all (the offset lives in the transform chain), so the
        // onLayoutChanged hook never fires for a pure scroll. Following a scrolling anchor is
        // carried entirely by the per-frame placement ticker, and updateWithoutPainting is the
        // documented headless driver that runs style -> tickers -> layout in the production order.
        // Calling reposition() by hand here would still pass if the ticker were deleted.
        window.updateWithoutPainting();

        assertEquals("the tooltip must follow its anchor up as the container scrolls",
                before - 60f, y(tip), 0.5f);
    }

    // ── Hiding ──────────────────────────────────────────────────────────────

    @Test
    public void hidingDemotesAndTakesItOutOfLayoutAgain() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(40).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        float heightWhenClosed = anchor.getRuntimeCache().getHeight();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();
        tip.hide();
        settle();

        assertFalse(tip.isShown());
        assertFalse(tip.isInTopLayer());
        assertTrue(window.getTopLayer().isEmpty());
        assertEquals("hiding must put it back out of flow",
                heightWhenClosed, anchor.getRuntimeCache().getHeight(), 0.001f);
    }

    @Test
    public void showingIsIdempotent() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        tip.showFor(anchor);
        settle();

        assertEquals("re-showing must not stack duplicates in the top layer",
                1, window.getTopLayer().elements().size());
    }
}
