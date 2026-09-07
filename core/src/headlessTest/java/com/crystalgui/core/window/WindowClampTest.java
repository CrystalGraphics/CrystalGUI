package com.crystalgui.core.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The one rule a {@code WindowFrame} and a {@code Dialog} share, and the reason it is shared: it is
 * stated in caption heights rather than in "inside", and the arithmetic has edges neither caller
 * reaches on its own.
 */
public class WindowClampTest {

    private static final float AREA = 800f;
    private static final float CAPTION = 16f;
    private static final float WIDTH = 120f;

    /** Overhang is allowed at both ends, and it is a caption that stays — not the whole window. */
    @Test
    public void aCaptionStaysAtEitherEdge() {
        assertEquals("all but a caption may leave to the left",
                CAPTION - WIDTH, WindowClamp.left(-9999f, WIDTH, AREA, CAPTION), 0.01f);
        assertEquals("and to the right",
                AREA - CAPTION, WindowClamp.left(9999f, WIDTH, AREA, CAPTION), 0.01f);
    }

    /** Inside the range nothing is touched — the clamp is a limit, not a placement. */
    @Test
    public void aPositionWellInsideIsLeftAlone() {
        assertEquals(240f, WindowClamp.left(240f, WIDTH, AREA, CAPTION), 0.01f);
        assertEquals(90f, WindowClamp.top(90f, 600f, CAPTION, false), 0.01f);
    }

    /**
     * The top is the asymmetry: a caption off the top cannot be grabbed to bring it back, so it is
     * allowed only while a drag is live and only by one caption.
     */
    @Test
    public void theCaptionRisesAboveTheTopOnlyWhileMoving() {
        assertEquals("at rest it stops at the edge",
                0f, WindowClamp.top(-9999f, 600f, CAPTION, false), 0.01f);
        assertEquals("moving, exactly one caption of headroom",
                -CAPTION, WindowClamp.top(-9999f, 600f, CAPTION, true), 0.01f);
    }

    /**
     * <b>A window narrower than its own caption</b> — where the lower bound exceeds the upper one, and
     * a naive {@code max(lo, min(value, hi))} would pin it to {@code lo} and push the title bar off the
     * right-hand edge. The upper bound wins, so what stays on screen is the caption rather than the body.
     */
    @Test
    public void aWindowNarrowerThanItsCaptionKeepsItsTitleBar() {
        float narrow = 8f;
        float placed = WindowClamp.left(9999f, narrow, AREA, CAPTION);

        assertEquals(AREA - CAPTION, placed, 0.01f);
        assertTrue("it must not be pushed past the edge to satisfy the lower bound", placed < AREA);
    }
}
