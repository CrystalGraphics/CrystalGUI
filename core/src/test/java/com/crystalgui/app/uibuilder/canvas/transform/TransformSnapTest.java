package com.crystalgui.app.uibuilder.canvas.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.joml.Vector2f;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.widget.surface.snap.SnapAxis;
import com.crystalgui.widget.surface.snap.SnapIndicator;
import com.crystalgui.widget.surface.snap.SnapScene;

/**
 * <b>Free Transform snaps what is DRAWN</b>, and a handle only while the box is square to the page.
 */
public class TransformSnapTest {

    private static final float TOLERANCE = 6f;

    private static TransformGesture box(float width, float height) {
        TransformGesture gesture = new TransformGesture();
        gesture.reset(width, height, Transform.IDENTITY, width / 2f, height / 2f);
        return gesture;
    }

    private static SnapScene only(SnapScene.Rect rect) {
        return new SnapScene(List.of(rect), List.of(rect), null);
    }

    private static Vector2f handle(TransformGesture g, Spot spot) {
        Vector2f corner = g.corner(spot);
        return g.apply(corner.x, corner.y);
    }

    /** A box drawn at twice its size lines up by its DRAWN edge, not the layout one under it. */
    @Test
    public void aMoveSnapsTheDrawnEdge() {
        TransformGesture g = box(100f, 50f);
        g.setScale(2f, 2f);

        // Laid out at (100, 100) and drawn from 50 to 250 across.
        TransformSnap.Result r = TransformSnap.move(g, 100f, 100f, false, false, TOLERANCE, 1f,
                only(new SnapScene.Rect(254f, 300f, 40f, 40f)));

        assertEquals(4f, r.dx(), 0.01f);
    }

    /**
     * <b>A turned box snaps by its bounds and marks its corner.</b> The line through a diamond's leftmost
     * point passes that point — not the empty corners of the square around it.
     */
    @Test
    public void aTurnedBoxMarksItsCornerNotItsBounds() {
        TransformGesture g = box(100f, 100f);
        g.setRotation((float) Math.toRadians(45));
        float reach = 100f / (float) Math.sqrt(2);

        // Laid out at (100, 100): a diamond about (150, 150), its leftmost point `reach` from the middle.
        TransformSnap.Result r = TransformSnap.move(g, 100f, 100f, false, false, TOLERANCE, 1f,
                only(new SnapScene.Rect(80f, 0f, 40f, 40f)));

        assertEquals(80f - (150f - reach), r.dx(), 0.01f);
        float[] marked = lineAt(r, 80f).crosses();
        assertTrue("the diamond's point is on the line", contains(marked, 150f));
        assertFalse("the bounds' empty corner is not", contains(marked, 150f - reach));
    }

    /** A handle lands its drawn edge while the box is square — at a quarter turn, on the other axis. */
    @Test
    public void aHandleSnapsWhileSquareToThePage() {
        TransformGesture g = box(100f, 50f);
        g.setScale(0.97f, 1f);
        Vector2f h = handle(g, Spot.RIGHT);
        // An edge 1.5 beyond the handle, and a middle 2 off it the other way that must be left alone.
        TransformSnap.Result upright = TransformSnap.scale(g, Spot.RIGHT, false, false, 0f, 0f, TOLERANCE, 1f,
                only(new SnapScene.Rect(h.x + 1.5f, h.y - 8f, 10f, 20f)));
        assertEquals(1.5f, upright.dx(), 0.01f);
        assertEquals(0f, upright.dy(), 0.01f);

        g.setRotation((float) Math.toRadians(90));
        h = handle(g, Spot.RIGHT);
        TransformSnap.Result turned = TransformSnap.scale(g, Spot.RIGHT, false, false, 0f, 0f, TOLERANCE, 1f,
                only(new SnapScene.Rect(h.x - 8f, h.y + 1.5f, 20f, 10f)));
        assertEquals("a quarter turn sends the right handle vertically", 1.5f, turned.dy(), 0.01f);
        assertEquals(0f, turned.dx(), 0.01f);
    }

    /** Tilted, the dragged edge is no line an axis can land on, so nothing snaps: tldraw's rule. */
    @Test
    public void aTiltedHandleDoesNotSnap() {
        TransformGesture g = box(100f, 50f);
        g.setRotation((float) Math.toRadians(30));
        Vector2f h = handle(g, Spot.RIGHT);

        TransformSnap.Result r = TransformSnap.scale(g, Spot.RIGHT, false, false, 0f, 0f, TOLERANCE, 1f,
                only(new SnapScene.Rect(h.x + 1.5f, h.y + 1.5f, 10f, 10f)));

        assertEquals(0f, r.dx(), 0f);
        assertEquals(0f, r.dy(), 0f);
        assertTrue(r.indicators().isEmpty());
    }

    /**
     * <b>Ratio held, the nearer snap leads</b> and the other axis follows along the handle's diagonal —
     * tldraw's rule. Landing both would change the shape.
     */
    @Test
    public void aLockedCornerFollowsTheNearerSnap() {
        TransformGesture g = box(100f, 50f);
        g.setScale(0.97f, 0.97f);
        Vector2f h = handle(g, Spot.BOTTOM_RIGHT);
        // An edge 1 to the right and one 3 below: the right one is nearer.
        SnapScene scene = only(new SnapScene.Rect(h.x + 1f, h.y + 3f, 10f, 10f));

        TransformSnap.Result locked = TransformSnap.scale(g, Spot.BOTTOM_RIGHT, true, false, 0f, 0f,
                TOLERANCE, 1f, scene);
        assertEquals(1f, locked.dx(), 0.01f);
        assertEquals("half, because the box is twice as wide as it is tall", 0.5f, locked.dy(), 0.01f);

        TransformSnap.Result free = TransformSnap.scale(g, Spot.BOTTOM_RIGHT, false, false, 0f, 0f,
                TOLERANCE, 1f, scene);
        assertEquals("unlocked, each axis takes its own", 3f, free.dy(), 0.01f);
    }

    /**
     * <b>Back on its outline, every corner is marked</b> — along an axis the handle drags, both edges, not
     * only the dragged one. The held corner used to go unmarked while the box sat exactly on its layout box.
     */
    @Test
    public void backOnItsOutlineEveryCornerIsMarked() {
        TransformGesture g = box(100f, 50f);
        SnapScene outline = only(new SnapScene.Rect(0f, 0f, 100f, 50f));

        List<SnapIndicator> corner = TransformSnap.settled(g, Spot.BOTTOM_RIGHT, false, 0f, 0f, outline);
        assertTrue("the held left edge", contains(lineAt(corner, SnapAxis.HORIZONTAL, 0f).crosses(), 0f));
        assertTrue("the held top edge", contains(lineAt(corner, SnapAxis.VERTICAL, 0f).crosses(), 0f));
        lineAt(corner, SnapAxis.HORIZONTAL, 100f);
        lineAt(corner, SnapAxis.VERTICAL, 50f);

        List<SnapIndicator> side = TransformSnap.settled(g, Spot.RIGHT, false, 0f, 0f, outline);
        assertTrue("a side handle drags one axis, so only that axis is marked", side.stream()
                .filter(i -> i instanceof SnapIndicator.Points)
                .allMatch(i -> ((SnapIndicator.Points) i).axis() == SnapAxis.HORIZONTAL));
    }

    private static SnapIndicator.Points lineAt(List<SnapIndicator> indicators, SnapAxis axis, float at) {
        for (SnapIndicator indicator : indicators) {
            if (indicator instanceof SnapIndicator.Points points && points.axis() == axis
                    && Math.abs(points.at() - at) < 0.01f) {
                return points;
            }
        }
        throw new AssertionError("no " + axis + " guide at " + at + " in " + indicators);
    }

    private static SnapIndicator.Points lineAt(TransformSnap.Result r, float at) {
        for (SnapIndicator indicator : r.indicators()) {
            if (indicator instanceof SnapIndicator.Points points && Math.abs(points.at() - at) < 0.01f) {
                return points;
            }
        }
        throw new AssertionError("no guide at " + at + " in " + r.indicators());
    }

    private static boolean contains(float[] values, float wanted) {
        for (float value : values) {
            if (Math.abs(value - wanted) < 0.01f) return true;
        }
        return false;
    }
}
