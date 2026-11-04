package com.crystalgui.style;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.ClipPath;
import com.crystalgui.style.property.visual.border.LengthPercent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** {@code clip-path: inset()}: its four spellings, and easing between two insets as a transition does. */
public class ClipPathTest {

    private static final LengthPercent ZERO = LengthPercent.px(0f);

    @Test
    public void insetTakesOneToFourValuesInMarginsOrder() {
        assertEquals(ClipPath.inset(LengthPercent.px(4f), LengthPercent.px(4f), LengthPercent.px(4f), LengthPercent.px(4f)),
                ClipPath.parse("inset(4px)"));
        assertEquals(ClipPath.inset(ZERO, LengthPercent.percent(0.1f), ZERO, LengthPercent.percent(0.1f)),
                ClipPath.parse("inset(0 10%)"));
        assertEquals(ClipPath.inset(ZERO, ZERO, LengthPercent.percent(0.5f), ZERO), ClipPath.parse("inset(0 0 50%)"));
        assertEquals(ClipPath.inset(LengthPercent.px(1f), LengthPercent.px(2f), LengthPercent.px(3f), LengthPercent.px(4f)),
                ClipPath.parse("inset(1px 2px 3px 4px)"));
        assertEquals(ClipPath.inset(ZERO, ZERO, LengthPercent.percent(0.5f), ZERO, LengthPercent.px(8f)),
                ClipPath.parse("inset(0 0 50% 0 round 8px)"));
        assertNull("round with no radius was taken", ClipPath.parse("inset(0 round)"));
        assertSame(ClipPath.NONE, ClipPath.parse("none"));
        assertNull("a shape it does not draw was taken", ClipPath.parse("circle(50%)"));
        assertNull("five values were taken", ClipPath.parse("inset(1 2 3 4 5)"));
    }

    @Test
    public void easesFromNothingClippedToHalfCutAway() {
        ClipPath open = ClipPath.parse("inset(0 0 0 0)");
        ClipPath shut = ClipPath.parse("inset(0 0 50% 0)");
        ClipPath half = StylePropertyRegistry.CLIP_PATH.getInterpolator().interpolate(shut, open, 0.5f);

        assertTrue("a zero against a percentage did not ease: " + half, half.bottom().percent);
        assertEquals(0.25f, half.bottom().value, 1e-6f);
        assertEquals(0f, half.top().value, 0f);
    }
}
