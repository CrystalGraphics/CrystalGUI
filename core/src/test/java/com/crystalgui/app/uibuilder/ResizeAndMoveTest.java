package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.MoveOutOfFlow;
import com.crystalgui.app.uibuilder.canvas.Snap;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>L4.5 — what a resize and a move are allowed to write.</b>
 *
 * <p>The gestures themselves are driven by {@code Drag}, which needs a live pointer; what is asserted
 * here is the arithmetic underneath them — which alignment a proposed position takes, and which inset a
 * move is allowed to write. Those are the parts that are wrong silently: a snap that never fires looks
 * like a designer with an unsteady hand, and writing {@code left} on a right-anchored node moves it
 * again the next time the parent resizes.</p>
 */
public class ResizeAndMoveTest extends UiDocumentTestBase {

    private UIElement parent;
    private UIElement moving;
    private UIElement fixed;

    @Before
    public void layOutTwoBoxes() {
        parent = new UIElement().layout(l -> l.width(400).height(300));
        moving = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(10f).top(10f).width(50f).height(20f));
        fixed = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(100f).top(80f).width(60f).height(40f));
        parent.append(moving, fixed);
        document.append(parent);
        document.update(W, H);
    }

    /** <b>Only a positioned node is movable.</b> An in-flow one is placed by its parent. */
    @Test
    public void anInFlowNodeIsNotSomethingADragMayPosition() {
        UIElement inFlow = new UIElement().layout(l -> l.width(30).height(10));
        parent.append(inFlow);
        document.update(W, H);

        assertTrue(MoveOutOfFlow.isMovable(moving));
        assertFalse("dragging this means reorder, which is a different gesture",
                MoveOutOfFlow.isMovable(inFlow));
    }

    /** A proposed position near a sibling's left edge takes it, and says which line it took. */
    @Test
    public void aPositionNearASiblingEdgeSnapsToIt() {
        Snap.Result snapped = Snap.of(moving, 97f, 200f, 6f);

        assertEquals("did not snap to the sibling's left edge", 100f, snapped.x(), 0.01f);
        assertEquals("the other axis had nothing to snap to and must not move",
                200f, snapped.y(), 0.01f);
        assertFalse("a snap that draws no guide cannot be explained", snapped.guides().isEmpty());
    }

    /** Out of range, nothing moves — which is what makes the snap usable rather than magnetic. */
    @Test
    public void aPositionOutOfRangeIsLeftAlone() {
        Snap.Result snapped = Snap.of(moving, 80f, 200f, 6f);

        assertEquals(80f, snapped.x(), 0.01f);
        assertTrue(snapped.guides().isEmpty());
    }

    /** Centres count too: aligning to the middle of a sibling is the same gesture. */
    @Test
    public void aCentreCountsAsAnAlignment() {
        // The sibling's centre is at x = 130; a 50-wide box centred there starts at 105.
        Snap.Result snapped = Snap.of(moving, 107f, 5f, 6f);

        assertEquals(105f, snapped.x(), 0.01f);
    }

    /** The parent's own content box is an alignment, and beats a sibling at the same distance. */
    @Test
    public void theParentsEdgeIsAnAlignment() {
        Snap.Result snapped = Snap.of(moving, 3f, 200f, 6f);

        assertEquals("flush with the container is what somebody aiming at zero meant",
                0f, snapped.x(), 0.01f);
    }

    /**
     * <b>A right-anchored node keeps being right-anchored.</b>
     *
     * <p>Writing {@code left} instead moves it now and moves it again the moment the parent resizes,
     * which is how a designed panel drifts.</p>
     */
    @Test
    public void aRightAnchoredNodeIsMovedByItsOwnInset() {
        assertFalse("a node stating left is moved by left", MoveOutOfFlow.anchorsRight(moving));

        UIElement pinned = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .right(20f).bottom(30f).width(40f).height(20f));
        parent.append(pinned);
        document.update(W, H);

        assertTrue("stating right and not left means right is the anchor",
                MoveOutOfFlow.anchorsRight(pinned));
        assertTrue(MoveOutOfFlow.anchorsBottom(pinned));
    }
}
