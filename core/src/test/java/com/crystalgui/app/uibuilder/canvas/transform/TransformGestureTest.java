package com.crystalgui.app.uibuilder.canvas.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Grip;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Kind;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;

/**
 * <b>L4.5a — the arithmetic under Free Transform.</b>
 *
 * <p>Every one of these is a property a hand cannot check by dragging: a handle that trails the pointer by
 * a few pixels, a pivot that slides the box as it is placed, an aspect lock that jumps. They are asserted
 * on the numbers because on screen each of them reads as an unsteady hand rather than as a defect —
 * which is exactly how the resize handles' own aspect lock survived two wrong versions.</p>
 */
public class TransformGestureTest {

    /** A box big enough that a handle landing in the wrong place is unmistakable. */
    private static final float W = 100f;
    private static final float H = 50f;

    private TransformGesture centred() {
        TransformGesture gesture = new TransformGesture();
        gesture.reset(W, H, Transform.IDENTITY, W / 2f, H / 2f);
        return gesture;
    }

    private static Vector2f map(TransformGesture gesture, float x, float y) {
        Matrix4f m = gesture.matrix();
        Vector3f out = m.transformPosition(new Vector3f(x, y, 0f));
        return new Vector2f(out.x, out.y);
    }

    /**
     * <b>The dragged handle lands on the pointer and the opposite corner does not move.</b>
     *
     * <p>Both halves, because they fail separately: the scale can be right while the anchor drifts, which
     * is a box that grows from its centre when it should grow from its corner.</p>
     */
    @Test
    public void aCornerScaleHoldsTheOppositeCorner() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        gesture.scaleTo(new Vector2f(150f, 75f), false, false);

        Vector2f dragged = map(gesture, W, H);
        assertEquals("the handle has to end up under the pointer", 150f, dragged.x, 0.01f);
        assertEquals(75f, dragged.y, 0.01f);

        Vector2f held = map(gesture, 0f, 0f);
        assertEquals("the anchor moved, so the box grew from the wrong place", 0f, held.x, 0.01f);
        assertEquals(0f, held.y, 0.01f);
    }

    /** Alt scales about the pivot instead, so the pivot is what stays put. */
    @Test
    public void altScalesAboutThePivot() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        gesture.scaleTo(new Vector2f(150f, 75f), false, true);

        Vector2f pivot = map(gesture, W / 2f, H / 2f);
        assertEquals(W / 2f, pivot.x, 0.01f);
        assertEquals(H / 2f, pivot.y, 0.01f);
    }

    /**
     * <b>The aspect lock is continuous.</b>
     *
     * <p>Taking the larger of the two scale factors is discontinuous wherever they cross, and the symptom
     * is a box that changes size in a burst from the smallest movement — reported twice against the resize
     * handles before it was projected instead of compared. Walked in small steps: no step may produce a
     * response wildly larger than its neighbours.</p>
     */
    @Test
    public void theAspectLockIsContinuous() {
        float previous = Float.NaN;
        float worst = 0f;
        for (int i = 0; i <= 200; i++) {
            float x = W + (i - 100) * 0.5f;
            TransformGesture gesture = centred();
            gesture.press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
            gesture.scaleTo(new Vector2f(x, H), true, false);
            float scale = gesture.scaleX();
            if (!Float.isNaN(previous)) worst = Math.max(worst, Math.abs(scale - previous));
            previous = scale;
        }
        assertTrue("a 0.5px step moved the scale by " + worst + " -- that is a burst", worst < 0.02f);
    }

    /** Shift snaps the angle, and to the step Photoshop uses. */
    @Test
    public void shiftSnapsRotationTo15Degrees() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.ROTATE, Spot.TOP_RIGHT));
        gesture.rotateBy(0.31f, true);

        float degrees = (float) Math.toDegrees(gesture.rotation());
        assertEquals("not on a 15 degree step", 0f, degrees % 15f, 0.01f);
    }

    /**
     * <b>Placing the pivot does not move the box.</b>
     *
     * <p>The same matrix about a different point is a different matrix, so writing the origin alone slides
     * everything the transform touches. Asserted under a rotation, since with none there is nothing to
     * slide and the test would pass against no compensation at all.</p>
     */
    @Test
    public void movingThePivotLeavesTheBoxWhereItIs() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.ROTATE, Spot.TOP_RIGHT));
        gesture.rotateBy(0.4f, false);
        Vector2f before = map(gesture, 0f, 0f);

        gesture.pivotTo(new Vector2f(10f, 40f), false);

        Vector2f after = map(gesture, 0f, 0f);
        assertEquals("the box slid when only the pivot was placed", before.x, after.x, 0.01f);
        assertEquals(before.y, after.y, 0.01f);
        assertEquals(10f, gesture.originX(), 0.01f);
        assertEquals(40f, gesture.originY(), 0.01f);
    }

    /** The pivot lands on an anchor point when it is dropped near one. */
    @Test
    public void thePivotSnapsToTheNineAnchors() {
        TransformGesture gesture = centred();
        gesture.pivotTo(new Vector2f(W - 2f, 2f), true);

        assertEquals(W, gesture.originX(), 0.01f);
        assertEquals(0f, gesture.originY(), 0.01f);
    }

    /** What the gesture writes, it can read back — otherwise reopening it starts from a lie. */
    @Test
    public void resetReadsBackWhatItWrites() {
        TransformGesture written = centred();
        written.press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        written.scaleTo(new Vector2f(140f, 68f), false, false);
        written.press(new Grip(Kind.ROTATE, Spot.TOP_RIGHT));
        written.rotateBy(0.27f, false);
        Transform transform = written.toTransform();

        TransformGesture reopened = new TransformGesture();
        assertTrue("this is the shape the gesture itself writes",
                reopened.reset(W, H, transform, W / 2f, H / 2f));

        assertEquals(written.scaleX(), reopened.scaleX(), 0.001f);
        assertEquals(written.scaleY(), reopened.scaleY(), 0.001f);
        assertEquals(written.rotation(), reopened.rotation(), 0.001f);
        assertEquals(written.translateX(), reopened.translateX(), 0.001f);
        assertEquals(written.translateY(), reopened.translateY(), 0.001f);
    }

    /**
     * <b>A transform it cannot decompose is refused, not guessed at.</b>
     *
     * <p>Seven numbers cannot describe an arbitrary op list — a translate after a rotate is a different
     * matrix from one before it — so reading one back as if they could would silently move the element on
     * the first drag. It says so instead, and the caller decides.</p>
     */
    @Test
    public void resetRefusesAShapeItCannotDecompose() {
        Transform outOfOrder = Transform.IDENTITY
                .then(Transform.Op.rotate(0.4f))
                .then(Transform.Op.translate(LengthPercent.px(10f), LengthPercent.px(0f)));

        TransformGesture gesture = new TransformGesture();
        assertFalse("a translate after a rotate is not this class's shape",
                gesture.reset(W, H, outOfOrder, W / 2f, H / 2f));
        assertTrue("a refused transform must leave the gesture at identity", gesture.isIdentity());
    }

    /**
     * <b>Shift keeps a move on one axis, chosen from the whole gesture.</b>
     *
     * <p>From the whole gesture and not the last frame: on a slow diagonal the per-frame delta crosses
     * back and forth over the diagonal, and the box flickers between the two axes instead of committing
     * to one.</p>
     */
    @Test
    public void shiftConstrainsAMoveToOneAxis() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.MOVE, null));

        gesture.moveBy(50f, 8f, true);
        assertEquals(50f, gesture.translateX(), 0.01f);
        assertEquals("the minor axis has to be dropped, not merely reduced",
                0f, gesture.translateY(), 0.01f);

        gesture.moveBy(8f, 50f, true);
        assertEquals(0f, gesture.translateX(), 0.01f);
        assertEquals(50f, gesture.translateY(), 0.01f);

        gesture.moveBy(8f, 50f, false);
        assertEquals("without Shift both axes move", 8f, gesture.translateX(), 0.01f);
        assertEquals(50f, gesture.translateY(), 0.01f);
    }

    /** A skew leans the box without moving the edge it was dragged from. */
    @Test
    public void skewingAnEdgeLeansTheBox() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SKEW, Spot.TOP));
        gesture.skewBy(25f, 0f);

        assertTrue("dragging the top edge right should lean the box", gesture.skewXRadians() > 0f);
        assertEquals("a horizontal edge drag must not skew the other axis",
                0f, gesture.skewYRadians(), 0.0001f);
    }
}
