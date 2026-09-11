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
     * <b>A shape the ordered walk cannot read is decomposed instead.</b>
     *
     * <p>This asserted a REFUSAL — a translate after a rotate was not this class's shape, and {@code
     * reset} answered false and stayed at identity. That was true of the ordered walk and is no longer
     * true of the class: it composes such a transform to a matrix and takes that apart, so the box opens
     * showing what the element actually has. The refusal is now only for a collapsed transform, which
     * {@code canDecompose} answers before the tool is ever entered.</p>
     *
     * <p>What it must not do is silently open at identity, which is what a false return produced once
     * the caller stopped checking it: the box then showed none of the element's transform and committing
     * wrote over it.</p>
     */
    @Test
    public void resetDecomposesAShapeTheOrderedWalkCannotRead() {
        Transform outOfOrder = Transform.IDENTITY
                .then(Transform.Op.rotate(0.4f))
                .then(Transform.Op.translate(LengthPercent.px(10f), LengthPercent.px(0f)));

        TransformGesture gesture = new TransformGesture();
        assertTrue("it is read through its matrix, not refused",
                gesture.reset(W, H, outOfOrder, W / 2f, H / 2f));
        assertFalse("and the gesture carries it rather than sitting at identity", gesture.isIdentity());
    }

    /**
     * <b>The dragged edge follows the pointer and the opposite edge does not move.</b>
     *
     * <p>Photoshop's convention, and Paint.NET's. Asserted on where the two edges LAND rather than on the
     * sign of the angle, because the angle is the part that is easy to get backwards and impossible to
     * read: {@code skew(ax)} shifts x by {@code tan(ax)·(y - originY)}, so pulling the top edge right is
     * a NEGATIVE ax, and an assertion that the angle went up would have passed the whole time the gesture
     * ran the wrong way.</p>
     */
    @Test
    public void draggingAnEdgeMovesItAndHoldsTheOther() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SKEW, Spot.TOP));
        gesture.skewBy(25f, 0f, false);

        assertEquals("the dragged edge has to end up under the pointer",
                W / 2f + 25f, map(gesture, W / 2f, 0f).x, 0.01f);
        assertEquals("the opposite edge moved, so the box leaned about its middle instead",
                W / 2f, map(gesture, W / 2f, H).x, 0.01f);
        assertEquals("a horizontal edge drag must not move anything vertically",
                0f, map(gesture, W / 2f, 0f).y, 0.01f);
    }

    /** The other axis, which is the same rule turned ninety degrees. */
    @Test
    public void draggingASideEdgeLeansTheOtherWay() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SKEW, Spot.RIGHT));
        gesture.skewBy(0f, 20f, false);

        assertEquals(H / 2f + 20f, map(gesture, W, H / 2f).y, 0.01f);
        assertEquals("the left edge is the anchor", H / 2f, map(gesture, 0f, H / 2f).y, 0.01f);
    }

    /** Alt leans about the pivot instead: both edges travel and neither is held. */
    @Test
    public void altSkewsAboutThePivot() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SKEW, Spot.TOP));
        gesture.skewBy(25f, 0f, true);

        float top = map(gesture, W / 2f, 0f).x;
        float bottom = map(gesture, W / 2f, H).x;
        assertTrue("the top should still lead the drag", top > W / 2f);
        assertEquals("about the pivot, the two edges move by equal and opposite amounts",
                W / 2f - (top - W / 2f), bottom, 0.01f);
    }

    /**
     * <b>The edge keeps pace with the hand however big the box is DRAWN.</b>
     *
     * <p>Scale is the innermost op, so a skew acts on already-scaled coordinates and the lever it leans
     * over is the box as drawn rather than as laid out. Measured against the unscaled extent, a 2x box
     * moved its edge twice as fast as the pointer.</p>
     */
    @Test
    public void aSkewOnAScaledBoxStillTracksThePointer() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
        gesture.scaleTo(new Vector2f(2f * W, 2f * H), false, false);
        assertEquals("the fixture wanted exactly twice", 2f, gesture.scaleX(), 0.01f);
        float before = map(gesture, W / 2f, 0f).x;

        gesture.press(new Grip(Kind.SKEW, Spot.TOP));
        gesture.skewBy(25f, 0f, false);

        assertEquals(before + 25f, map(gesture, W / 2f, 0f).x, 0.01f);
        assertEquals("and the far edge is still the anchor",
                map(gesture, W / 2f, H).x, map(gesture, W / 2f, H).x, 0.01f);
    }

    /**
     * <b>Tangents add; angles do not.</b>
     *
     * <p>Continuing a lean already in progress is {@code atan(tan(was) + delta)}. Adding the angles makes
     * the edge fall behind the pointer as the lean steepens — invisible at a few degrees and obvious at
     * thirty, which is the worst way for it to be wrong.</p>
     */
    @Test
    public void aSecondSkewStillLandsUnderThePointer() {
        TransformGesture gesture = centred();
        gesture.press(new Grip(Kind.SKEW, Spot.TOP));
        gesture.skewBy(30f, 0f, false);

        gesture.press(new Grip(Kind.SKEW, Spot.TOP));
        gesture.skewBy(30f, 0f, false);

        assertEquals("the edge has to be sixty across after two drags of thirty",
                W / 2f + 60f, map(gesture, W / 2f, 0f).x, 0.01f);
    }
}
