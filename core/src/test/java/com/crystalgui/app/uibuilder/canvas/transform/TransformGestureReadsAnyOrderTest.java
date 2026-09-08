package com.crystalgui.app.uibuilder.canvas.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.joml.Matrix4f;
import org.junit.Test;

import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.style.property.visual.transform.TransformValue;

/**
 * <b>Free Transform opens on a transform whatever order it was written in.</b>
 *
 * <p>The gesture's five fields describe {@code translate → rotate → skew → scale} composed in that
 * order, and it used to read an existing transform by walking the ops and refusing anything out of that
 * sequence. Refusing meant opening at IDENTITY — the box showed none of the element's rotation or
 * scale, and committing wrote the gesture over the property, replacing what was there.</p>
 *
 * <p>A transform is an ordered list, so the order genuinely matters — {@code scale(2) rotate(1rad)} is
 * not {@code rotate(1rad) scale(2)}. Composing to a matrix first makes it irrelevant: each of those is
 * a different matrix, and each decomposes into the fields that reproduce it. That is what these assert
 * — not the numbers, which are one of several valid decompositions, but that the box <em>shows the same
 * transform the element has</em>.</p>
 */
public class TransformGestureReadsAnyOrderTest {

    private static final float W = 180f;
    private static final float H = 120f;
    private static final float OX = 90f;
    private static final float OY = 60f;

    /** Opening the box on {@code css} and closing it again leaves the element looking identical. */
    private static void reproduces(String css) {
        Transform original = new TransformValue(css).compute();
        assertNotNull(css + " does not parse", original);

        TransformGesture gesture = new TransformGesture();
        assertTrue(css + " could not be read", gesture.reset(W, H, original, OX, OY));

        Matrix4f was = original.applyTo(new Matrix4f(), 0f, 0f, W, H, OX, OY);
        Matrix4f now = gesture.toTransform().applyTo(new Matrix4f(), 0f, 0f, W, H, OX, OY);

        for (int i = 0; i < 16; i++) {
            assertEquals(css + " came back as a different matrix at [" + i + "], rebuilt as '"
                            + gesture.toTransform() + "'",
                    was.get(i / 4, i % 4), now.get(i / 4, i % 4), 0.001f);
        }
    }

    /** The order the gesture itself writes — read straight off the ops, no matrix involved. */
    @Test
    public void theCanonicalOrderIsReadExactly() {
        reproduces("translate(4px, 2px) rotate(0.15rad) skew(0.05rad, 0rad) scale(1.1, 1.1)");

        TransformGesture gesture = new TransformGesture();
        gesture.reset(W, H, new TransformValue("rotate(0.15rad) scale(1.1, 1.2)").compute(), OX, OY);
        assertEquals("rotate(0.15rad) scale(1.1, 1.2)", gesture.toTransform().toString());
    }

    /** And every other order, which used to open at identity. */
    @Test
    public void anyOtherOrderIsReadThroughItsMatrix() {
        reproduces("translate(4px, 2px) rotate(0.15rad) scale(1.1, 1.1) skew(0.05rad, 0rad)");
        reproduces("scale(2, 2) rotate(0.4rad)");
        reproduces("rotate(0.4rad) scale(2, 2)");
        reproduces("skew(0.2rad, 0rad) translate(10px, 5px)");
        reproduces("scale(1.5, 0.5) translate(8px, 3px) rotate(-0.3rad)");
    }

    /** Including a kind repeated either side of another, which no ordered walk can describe. */
    @Test
    public void aRepeatedKindIsReadToo() {
        reproduces("translate(4px, 0px) rotate(0.2rad) translate(0px, 6px)");
        reproduces("scale(2, 2) rotate(0.3rad) scale(0.5, 0.5)");
    }

    /** A mirrored transform keeps its flip. */
    @Test
    public void aNegativeScaleSurvives() {
        reproduces("scale(-1, 1)");
        reproduces("rotate(0.3rad) scale(-1, -1)");
    }

    /**
     * A zero scale is the one that cannot be opened on.
     *
     * <p>It collapses the box to a line or a point, and no rotation or shear survives in the matrix to
     * be read back — so the command refuses rather than the tool opening on nothing.</p>
     */
    @Test
    public void aCollapsedTransformIsRefused() {
        assertFalse(TransformGesture.canDecompose(new TransformValue("scale(0, 1)").compute()));
        assertFalse(TransformGesture.canDecompose(new TransformValue("scale(1, 0)").compute()));

        assertTrue(TransformGesture.canDecompose(null));
        assertTrue(TransformGesture.canDecompose(Transform.IDENTITY));
        assertTrue(TransformGesture.canDecompose(
                new TransformValue("scale(2, 2) rotate(0.4rad)").compute()));
    }
}
