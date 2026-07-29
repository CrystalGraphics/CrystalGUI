package com.crystalgui.style.property.visual.transform;

import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.UITransform;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * The {@code transform} function-list parser, tested directly — no window, no stylesheet, no GL, in the
 * same spirit as {@code TextureValueRepeatTest}.
 *
 * <p>Two things matter most here and neither is about a single function: that the ops come back in the
 * order they were written, and that anything unrecognised rejects the <b>whole</b> value rather than
 * quietly dropping one function out of a chain.</p>
 */
public class TransformValueTest {

    private static final float EPS = 1e-5f;

    private static List<UITransform.Op> ops(String css) {
        UITransform t = new TransformValue(css).compute();
        assertNotNull("expected '" + css + "' to parse", t);
        return t.ops();
    }

    @Test
    public void noneIsTheIdentity() {
        assertEquals(UITransform.IDENTITY, new TransformValue("none").compute());
        assertTrue(new TransformValue("NONE").compute().isIdentity());
    }

    @Test
    public void translateTakesLengthsAndPercentages() {
        List<UITransform.Op> got = ops("translate(10px, 50%)");
        assertEquals(1, got.size());
        assertEquals(UITransform.Kind.TRANSLATE, got.get(0).kind());
        assertEquals(LengthPercent.px(10f), got.get(0).lx());
        assertEquals(LengthPercent.percent(0.5f), got.get(0).ly());
    }

    /** CSS: a one-argument translate leaves Y at zero. */
    @Test
    public void translateDefaultsItsSecondArgumentToZero() {
        assertEquals(LengthPercent.ZERO, ops("translate(10px)").get(0).ly());
    }

    /** The axis variants collapse into the two-argument form — see UITransform's divergence note. */
    @Test
    public void axisVariantsCollapseToTheTwoArgumentForm() {
        UITransform.Op x = ops("translateX(5px)").get(0);
        assertEquals(LengthPercent.px(5f), x.lx());
        assertEquals(LengthPercent.ZERO, x.ly());

        UITransform.Op y = ops("translateY(5px)").get(0);
        assertEquals(LengthPercent.ZERO, y.lx());
        assertEquals(LengthPercent.px(5f), y.ly());

        assertEquals(1f, ops("scaleX(3)").get(0).fy(), EPS);
        assertEquals(1f, ops("scaleY(3)").get(0).fx(), EPS);
    }

    /** Unlike translate, a one-argument scale applies to BOTH axes. That asymmetry is CSS's. */
    @Test
    public void oneArgumentScaleAppliesToBothAxes() {
        UITransform.Op op = ops("scale(2)").get(0);
        assertEquals(2f, op.fx(), EPS);
        assertEquals(2f, op.fy(), EPS);
    }

    @Test
    public void rotateAndSkewCarryRadians() {
        assertEquals((float) Math.PI / 4f, ops("rotate(45deg)").get(0).fx(), EPS);

        UITransform.Op skew = ops("skew(45deg, 10deg)").get(0);
        assertEquals((float) Math.PI / 4f, skew.fx(), EPS);
        assertEquals((float) Math.toRadians(10), skew.fy(), EPS);
        assertEquals("skew's second argument defaults to zero", 0f, ops("skew(45deg)").get(0).fy(), EPS);
    }

    /** The whole reason the value is a list: order survives parsing. */
    @Test
    public void functionsKeepTheirWrittenOrder() {
        List<UITransform.Op> got = ops("translate(10px, 5px) scale(2) rotate(45deg)");
        assertEquals(3, got.size());
        assertEquals(UITransform.Kind.TRANSLATE, got.get(0).kind());
        assertEquals(UITransform.Kind.SCALE, got.get(1).kind());
        assertEquals(UITransform.Kind.ROTATE, got.get(2).kind());

        List<UITransform.Op> reversed = ops("rotate(45deg) scale(2) translate(10px, 5px)");
        assertEquals(UITransform.Kind.ROTATE, reversed.get(0).kind());
        assertEquals(UITransform.Kind.TRANSLATE, reversed.get(2).kind());
    }

    /** Spaces inside a call must not split it into two tokens. */
    @Test
    public void argumentSpacingDoesNotSplitAFunction() {
        assertEquals(1, ops("translate( 10px ,  5px )").size());
    }

    /**
     * All-or-nothing. Dropping the bad function and keeping the rest would silently apply a transform
     * the author never wrote, which is worse than applying none.
     */
    @Test
    public void oneBadFunctionRejectsTheWholeValue() {
        assertNull("unknown function", new TransformValue("scale(2) wobble(3)").compute());
        assertNull("matrix() is not supported", new TransformValue("matrix(1,0,0,1,0,0)").compute());
        assertNull("angle without a unit", new TransformValue("rotate(45)").compute());
        assertNull("wrong argument count", new TransformValue("rotate(45deg, 10deg)").compute());
        assertNull("not a function at all", new TransformValue("2px").compute());
        assertNull("empty arguments", new TransformValue("scale()").compute());
    }

    /** A malformed value computes to null, and StyleValue swallows and logs rather than throwing. */
    @Test
    public void malformedValuesDoNotThrow() {
        assertNull(new TransformValue("").compute());
        assertNull(new TransformValue("translate(nope)").compute());
    }
}
