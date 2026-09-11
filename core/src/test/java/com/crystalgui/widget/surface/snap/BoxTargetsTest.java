package com.crystalgui.widget.surface.snap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.snap.SnapSolver.AxisSnap;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>What a laid-out box can snap to, and what is drawn for it</b> — each case one of the things the
 * first cut got wrong on the scratch document.
 */
public class BoxTargetsTest extends UiDocumentTestBase {

    private static final float TOLERANCE = 6f;

    private static UIElement box(float left, float top, float width, float height) {
        return new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(left).top(top).width(width).height(height));
    }

    /** A 400x300 parent holding {@code children}, laid out. */
    private void layOut(UIElement... children) {
        UIElement parent = new UIElement().layout(l -> l.width(400).height(300));
        parent.append(children);
        document.append(parent);
        document.update(W, H);
    }

    private static AxisSnap moveX(UIElement moving, float wanted) {
        float y = moving.box().y();
        return SnapSolver.move(SnapAxis.HORIZONTAL, wanted, moving.box().width(),
                y, y + moving.box().height(), TOLERANCE, 1f, BoxTargets.sceneFor(moving));
    }

    /**
     * <b>Three things share the line the box lands on, and one guide runs through all of them</b>, with
     * the moving box's own corners on it. The first cut drew only the tie-break's winner, which on the
     * scratch document was an element hidden under another.
     */
    @Test
    public void everyPointOnTheLineIsOnOneGuide() {
        UIElement moving = box(300f, 40f, 50f, 20f);
        layOut(moving, box(0f, 0f, 50f, 20f), box(70f, 0f, 50f, 20f), box(140f, 0f, 50f, 20f));

        AxisSnap y = SnapSolver.move(SnapAxis.VERTICAL, 2f, 20f, 300f, 350f,
                TOLERANCE, 1f, BoxTargets.sceneFor(moving));

        assertEquals(0f, y.value(), 0.01f);
        float[] marked = lineAt(y, 0f).crosses();
        for (float x : new float[] {0f, 50f, 70f, 120f, 140f, 190f, 300f, 350f}) {
            assertTrue("nothing marked at x=" + x, contains(marked, x));
        }
    }

    /**
     * <b>A sibling's child is a target</b> — {@code #save} inside {@code #header}, reached from
     * {@code #every-style} beside it, and measured in the parent's space rather than the header's.
     */
    @Test
    public void aSiblingsChildIsATarget() {
        UIElement header = box(20f, 30f, 200f, 40f);
        header.append(box(140f, 5f, 40f, 20f));
        UIElement moving = box(300f, 200f, 50f, 20f);
        layOut(header, moving);

        AxisSnap x = moveX(moving, 159f);

        assertEquals("the child's left edge is at 20 + 140", 160f, x.value(), 0.01f);
        assertTrue("its corner is at 30 + 5", contains(lineAt(x, 160f).crosses(), 35f));
    }

    /** <b>The fourth box in a row lands the row's gap past the third</b>, and both gaps are drawn. */
    @Test
    public void aRowsGapIsContinued() {
        UIElement moving = box(300f, 0f, 50f, 20f);
        layOut(moving, box(0f, 0f, 50f, 20f), box(70f, 0f, 50f, 20f), box(140f, 0f, 50f, 20f));

        AxisSnap x = moveX(moving, 208f);

        assertEquals(210f, x.value(), 0.01f);
        assertTrue("the gap it made", hasBar(x, 190f, 210f));
        assertTrue("the gap it matched", hasBar(x, 120f, 140f));
    }

    /** <b>A box that fits is centred in a gap</b>, drawn as the two halves it leaves. */
    @Test
    public void aBoxThatFitsIsCentredInAGap() {
        UIElement moving = box(300f, 100f, 50f, 20f);
        layOut(moving, box(0f, 100f, 50f, 20f), box(200f, 100f, 50f, 20f));

        AxisSnap x = moveX(moving, 102f);

        assertEquals("50..200 less 50, halved", 100f, x.value(), 0.01f);
        assertTrue(hasBar(x, 50f, 100f));
        assertTrue(hasBar(x, 150f, 200f));
    }

    /**
     * <b>Boxes in different rows leave no gap between them.</b> The first cut paired any two siblings,
     * so an element in another row lent spacing to edges it had nothing to do with.
     */
    @Test
    public void boxesInDifferentRowsLeaveNoGap() {
        UIElement moving = box(300f, 100f, 50f, 20f);
        layOut(moving, box(0f, 0f, 50f, 20f), box(120f, 100f, 50f, 20f));

        // Were 50..120 a gap, 170 + 70 = 240 would continue it.
        assertFalse(moveX(moving, 238f).taken());
    }

    /** A resize snaps its dragged edge alone, marking both of that edge's ends on the line. */
    @Test
    public void aDraggedEdgeLandsOnAPoint() {
        UIElement moving = box(300f, 0f, 50f, 20f);
        layOut(moving, box(0f, 0f, 50f, 20f), box(70f, 0f, 50f, 20f));

        AxisSnap edge = SnapSolver.edge(SnapAxis.HORIZONTAL, 68f, 0f, 20f,
                TOLERANCE, 1f, BoxTargets.around(moving));

        assertEquals(70f, edge.value(), 0.01f);
        float[] marked = lineAt(edge, 70f).crosses();
        assertTrue(contains(marked, 0f) && contains(marked, 20f));
    }

    /**
     * <b>The page's edges are targets from any depth</b> — the artboard, seen from a node inside a panel,
     * measured back into that panel's space.
     */
    @Test
    public void thePagesEdgesAreTargets() {
        UIElement page = new UIElement().layout(l -> l.width(400).height(300));
        UIElement panel = box(50f, 40f, 200f, 100f);
        UIElement moving = box(10f, 10f, 20f, 20f);
        panel.append(moving);
        page.append(panel);
        document.append(page);
        document.update(W, H);

        // The page's left edge is 50 to the left of the panel.
        AxisSnap x = SnapSolver.move(SnapAxis.HORIZONTAL, -48f, 20f, 10f, 30f, TOLERANCE, 1f,
                BoxTargets.sceneFor(moving, page));
        assertEquals(-50f, x.value(), 0.01f);
        assertFalse("and without the page nothing is there",
                SnapSolver.move(SnapAxis.HORIZONTAL, -48f, 20f, 10f, 30f, TOLERANCE, 1f,
                        BoxTargets.sceneFor(moving)).taken());
    }

    /**
     * <b>A target is snapped where it is DRAWN</b>, not where its layout box sits. A committed transform
     * moves the one and not the other, and a guide on the layout box stood that far from what it touched.
     */
    @Test
    public void aTransformedTargetIsSnappedWhereItIsDrawn() {
        UIElement moving = box(300f, 100f, 50f, 20f);
        UIElement shifted = box(100f, 0f, 50f, 20f);
        StyleGroup.inlinePipeline(shifted.getStyle().getGeneralGroup(),
                g -> g.transform(Transform.translate(10f, 0f)));
        layOut(moving, shifted);

        assertEquals("laid out from 100, drawn from 110", 110f, moveX(moving, 108f).value(), 0.01f);
    }

    /**
     * <b>A guide outlines the boxes its points belong to</b>, so a mark on a corner nothing else draws —
     * {@code #hint}'s, sitting on another box's edge — says whose it is.
     */
    @Test
    public void aGuideOutlinesTheBoxesItsPointsBelongTo() {
        UIElement moving = box(300f, 40f, 50f, 20f);
        layOut(moving, box(0f, 0f, 50f, 20f));

        AxisSnap y = SnapSolver.move(SnapAxis.VERTICAL, 2f, 20f, 300f, 350f,
                TOLERANCE, 1f, BoxTargets.sceneFor(moving));

        assertTrue("no outline of the box the line passes", y.indicators().stream().anyMatch(i ->
                i instanceof SnapIndicator.Owner owner && contains(owner.xs(), 50f) && contains(owner.ys(), 20f)));
    }

    private static SnapIndicator.Points lineAt(AxisSnap snap, float at) {
        for (SnapIndicator indicator : snap.indicators()) {
            if (indicator instanceof SnapIndicator.Points points && Math.abs(points.at() - at) < 0.01f) {
                return points;
            }
        }
        throw new AssertionError("no guide at " + at + " in " + snap.indicators());
    }

    private static boolean hasBar(AxisSnap snap, float from, float to) {
        for (SnapIndicator indicator : snap.indicators()) {
            if (indicator instanceof SnapIndicator.Gap gap
                    && Math.abs(gap.from() - from) < 0.01f && Math.abs(gap.to() - to) < 0.01f) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(float[] values, float wanted) {
        for (float value : values) {
            if (Math.abs(value - wanted) < 0.01f) return true;
        }
        return false;
    }
}
