package com.crystalgui.ui;

import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.Overflow;
import dev.vfyjxf.taffy.style.TaffyPosition;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The <b>top layer</b> — CSS Position 4 §top-layer, the machinery behind {@code <dialog>} and the
 * Popover API.
 *
 * <p>The feature exists for one situation, and it is the situation every test here is built around:
 * something anchored to an element inside an {@code overflow: hidden} container has to draw
 * <em>outside</em> that container. Before this, nothing could — {@code drawSubtree} paints
 * depth-first under every ancestor's scissor, so a tooltip was clipped by whatever was scrolling it.</p>
 *
 * <p>Promotion keeps the element's DOM parent, because the cascade must not change. Four positional
 * relationships diverge instead, and they are separate code paths that can silently disagree:
 * the Taffy parent, {@code RuntimeCache.getX()/getY()}, {@code localToWorld}, and the paint/hit-test
 * entry points. Each has a test below; a fix to one that misses another shows up as "it draws in the
 * right place but you can't click it", or the reverse.</p>
 */
public class TopLayerTest extends UiTestBase {

    private static final float SCROLLER_H = 100f;
    private static final float ROW_H = 40f;

    // ── Fixture ─────────────────────────────────────────────────────────────

    /** A clipping scroller with rows, plus a detached-from-flow "tooltip" parented to the first row. */
    private static final class Fixture {
        UIWindow window;
        UIElement root, scroller, firstRow, tooltip;
    }

    private static Fixture build() {
        Fixture f = new Fixture();
        f.root = new UIElement().layout(l -> l.width(800).height(600));

        f.scroller = new UIElement()
                .layout(l -> l.width(200).height(SCROLLER_H).marginLeft(50).marginTop(50))
                .generalStyle(g -> g.overflow(Overflow.HIDDEN));
        f.root.addChild(f.scroller);

        for (int i = 0; i < 5; i++) {
            f.scroller.addChild(new UIElement().layout(l -> l.width(200).height(ROW_H)));
        }
        f.firstRow = f.scroller.getChildren().get(0);

        // Parented to a row deep inside the clip — the whole point.
        f.tooltip = new UIElement().layout(l -> l.width(60).height(20));
        f.firstRow.addChild(f.tooltip);

        f.window = new UIWindow(Ui.of(f.root));
        f.window.init(1600, 1200); // uiScale 2 -> 800x600 logical
        settle(f.window);
        return f;
    }

    private static void settle(UIWindow window) {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    /** Physical pointer coords for a logical point (uiScale is 2). */
    private static int px(float logical) {
        return Math.round(logical * 2f);
    }

    // ── Bookkeeping ─────────────────────────────────────────────────────────

    @Test
    public void promotionIsReflectedOnBothTheElementAndTheWindow() {
        Fixture f = build();
        assertFalse(f.tooltip.isInTopLayer());
        assertTrue(f.window.getTopLayer().isEmpty());

        f.tooltip.addToTopLayer();

        assertTrue(f.tooltip.isInTopLayer());
        assertEquals(java.util.List.of(f.tooltip), f.window.getTopLayer().elements());
    }

    /**
     * The spec's add algorithm removes an already-present element before appending, so re-adding is
     * how you raise. That makes "bring this popup to the front" one idempotent call rather than a
     * remove/add dance the caller has to get right.
     */
    @Test
    public void reAddingRaisesToTheTopInsteadOfDuplicating() {
        Fixture f = build();
        UIElement other = new UIElement().layout(l -> l.width(10).height(10));
        f.root.addChild(other);
        settle(f.window);

        f.tooltip.addToTopLayer();
        other.addToTopLayer();
        assertEquals(java.util.List.of(f.tooltip, other), f.window.getTopLayer().elements());

        f.tooltip.addToTopLayer(); // raise

        assertEquals("re-adding must move it to the top, not duplicate it",
                java.util.List.of(other, f.tooltip), f.window.getTopLayer().elements());
    }

    @Test
    public void theRootCannotBePromoted() {
        Fixture f = build();
        assertThrows(IllegalArgumentException.class, () -> f.window.getTopLayer().add(f.root));
    }

    @Test
    public void promotingADetachedElementFails() {
        UIElement orphan = new UIElement();
        assertThrows(IllegalStateException.class, orphan::addToTopLayer);
    }

    /** A promoted element that leaves the tree must stop painting and hit-testing. */
    @Test
    public void detachingDemotesAutomatically() {
        Fixture f = build();
        f.tooltip.addToTopLayer();
        assertFalse(f.window.getTopLayer().isEmpty());

        f.tooltip.removeSelf();

        assertTrue("a detached element must not linger in the top layer",
                f.window.getTopLayer().isEmpty());
        assertFalse(f.tooltip.isInTopLayer());
    }

    // ── Divergence 1: the Taffy parent / containing block ───────────────────

    /**
     * Promotion forces {@code position: absolute} at IMPORTANT origin. Without it the promoted node
     * becomes an ordinary in-flow child of the root and shoves the real content around — the element
     * would escape its clip and wreck the layout it left behind.
     */
    @Test
    public void promotionForcesOutOfFlowPositioning() {
        Fixture f = build();
        float scrollerYBefore = f.scroller.getRuntimeCache().getY();

        f.tooltip.addToTopLayer();
        settle(f.window);

        assertEquals(TaffyPosition.ABSOLUTE,
                f.tooltip.getStyle().computeCandidate(LayoutProperties.POSITION));
        assertEquals("promoting must not disturb the layout of the tree it left",
                scrollerYBefore, f.scroller.getRuntimeCache().getY(), 0.001f);
    }

    // ── Divergence 2: getX()/getY() accumulation ────────────────────────────

    /**
     * The double-count bug. {@code RuntimeCache.getX()} adds the <em>DOM parent's</em> absolute
     * origin to a Taffy location — but promotion reparents the Taffy node to the root, so that
     * location is already root-relative. Adding the parent on top sends a tooltip on a nested,
     * scrolled element hundreds of pixels off-screen.
     */
    @Test
    public void positionAccumulatesFromTheRootNotTheDomParent() {
        Fixture f = build();
        f.tooltip.addToTopLayer();
        // Place it via the ordinary absolute-positioning properties, relative to the root box.
        f.tooltip.layout(l -> l.left(400).top(300));
        settle(f.window);

        assertEquals("x must be root-relative, not offset by the scroller's own position",
                400f, f.tooltip.getRuntimeCache().getX(), 0.5f);
        assertEquals(300f, f.tooltip.getRuntimeCache().getY(), 0.5f);
    }

    /** Scrolling the ancestor must not drag a promoted element along — it is out of that flow now. */
    @Test
    public void ancestorScrollDoesNotMoveAPromotedElement() {
        Fixture f = build();
        f.tooltip.addToTopLayer();
        f.tooltip.layout(l -> l.left(400).top(300));
        settle(f.window);
        float yBefore = f.tooltip.getRuntimeCache().getY();

        f.scroller.setScrollTop(80f);
        settle(f.window);

        assertEquals("a promoted element must not scroll with the ancestor it escaped",
                yBefore, f.tooltip.getRuntimeCache().getY(), 0.001f);
    }

    // ── Divergence 3: localToWorld ──────────────────────────────────────────

    /**
     * Separate code path from {@code getX()} — one feeds paint geometry, the other the matrix
     * {@code elementHitTest} inverts. Fixing only one produces the classic "it draws where I expect
     * but clicks land somewhere else".
     */
    @Test
    public void worldMatrixIsSeededFromTheWindowNotTheDomParent() {
        Fixture f = build();
        f.tooltip.addToTopLayer();
        settle(f.window);

        assertEquals("a promoted element's world matrix must equal the window root transform",
                f.window.getRootTransform(),
                f.tooltip.getRuntimeCache().localToWorld.get());
    }

    // ── Divergence 4: hit testing ───────────────────────────────────────────

    /**
     * <b>The feature.</b> A promoted element positioned outside its clipping ancestor must be
     * hittable there.
     *
     * <p>This cannot work by reordering the existing walk: {@code elementHitTest} only recurses into
     * children when the pointer is inside a clipping ancestor's content box, so the tooltip is
     * unreachable from the root walk at exactly the coordinates that matter. It needs its own entry
     * point, which is what {@code getHoveredElement} now does.</p>
     */
    @Test
    public void aPromotedElementIsHittableOutsideItsClippingAncestor() {
        Fixture f = build();
        f.tooltip.addToTopLayer();
        // Well below the scroller, which ends at y=150 logical.
        f.tooltip.layout(l -> l.left(400).top(300));
        settle(f.window);

        UIElement hit = f.window.getHoveredElement(px(410), px(305));

        assertSame("a promoted element must be hittable outside the ancestor that clips it",
                f.tooltip, hit);
    }

    /** Before promotion the same element at the same place is clipped away — proving the fixture
     * actually exercises the clip rather than passing for an unrelated reason. */
    @Test
    public void withoutPromotionTheSamePositionIsNotHittable() {
        Fixture f = build();
        f.tooltip.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(400).top(300));
        settle(f.window);

        assertNotSame("unpromoted, the clip must swallow it — otherwise this test proves nothing",
                f.tooltip, f.window.getHoveredElement(px(410), px(305)));
    }

    /** Later promotions sit on top. The spec makes z-index irrelevant here, so order is the model. */
    @Test
    public void hitTestingPrefersTheMostRecentlyPromoted() {
        Fixture f = build();
        UIElement lower = new UIElement().layout(l -> l.width(100).height(100));
        UIElement upper = new UIElement().layout(l -> l.width(100).height(100));
        f.root.addChild(lower);
        f.root.addChild(upper);
        settle(f.window);

        lower.addToTopLayer();
        upper.addToTopLayer();
        lower.layout(l -> l.left(400).top(300));
        upper.layout(l -> l.left(400).top(300)); // exactly overlapping
        settle(f.window);

        assertSame("the last element in the top layer is rendered on top, so it is hit first",
                upper, f.window.getHoveredElement(px(410), px(310)));
    }

    /**
     * z-index must not reorder the top layer. Per spec it is irrelevant between promoted elements —
     * they stack purely by position in the layer, so a high z-index on the lower one changes nothing.
     */
    @Test
    public void zIndexDoesNotReorderTheTopLayer() {
        Fixture f = build();
        UIElement lower = new UIElement().layout(l -> l.width(100).height(100));
        UIElement upper = new UIElement().layout(l -> l.width(100).height(100));
        f.root.addChild(lower);
        f.root.addChild(upper);
        settle(f.window);

        lower.addToTopLayer();
        upper.addToTopLayer();
        lower.generalStyle(g -> g.zIndex(999));
        lower.layout(l -> l.left(400).top(300));
        upper.layout(l -> l.left(400).top(300));
        settle(f.window);

        assertSame("z-index is irrelevant between top-layer elements; order in the layer wins",
                upper, f.window.getHoveredElement(px(410), px(310)));
    }

    // ── Demotion ────────────────────────────────────────────────────────────

    /** Demotion has to undo all four divergences, not just the flag. */
    @Test
    public void demotionRestoresNormalParenting() {
        Fixture f = build();
        f.tooltip.addToTopLayer();
        f.tooltip.layout(l -> l.left(400).top(300));
        settle(f.window);

        f.tooltip.removeFromTopLayer();
        settle(f.window);

        assertFalse(f.tooltip.isInTopLayer());
        assertTrue(f.window.getTopLayer().isEmpty());
        assertNull("our forced IMPORTANT position must be withdrawn on demotion",
                f.tooltip.getStyle().computeCandidate(LayoutProperties.POSITION));
        assertNotSame("back under the clip, it must stop being hittable out there",
                f.tooltip, f.window.getHoveredElement(px(410), px(305)));
    }

    @Test
    public void demotingSomethingNeverPromotedIsANoOp() {
        Fixture f = build();
        f.tooltip.removeFromTopLayer();
        assertFalse(f.tooltip.isInTopLayer());
    }
}
