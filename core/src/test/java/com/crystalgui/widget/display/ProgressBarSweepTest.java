package com.crystalgui.widget.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com.crystalgui.style.ComputedStyle;
import com.crystalgui.testsupport.UiDocumentTestBase;

/** The indeterminate sweep moves its stripe without touching the cascade, and survives the bar leaving the tree. */
public class ProgressBarSweepTest extends UiDocumentTestBase {

    @Test
    public void theStripeMovesWithoutRestyling() {
        ProgressBar bar = new ProgressBar();
        bar.layout(l -> l.width(100).height(4));
        document.append(bar);
        step();

        ComputedStyle style = bar.fill().computedStyle();
        float was = stripeX(bar);
        step();

        assertNotEquals("the stripe did not move", was, stripeX(bar), 0.001f);
        assertSame("a sweep frame restyled the stripe", style, bar.fill().computedStyle());
    }

    @Test
    public void aBarAddedBackStillSweeps() {
        ProgressBar bar = new ProgressBar();
        bar.layout(l -> l.width(100).height(4));
        document.append(bar);
        step();
        document.remove(bar);
        step();
        document.append(bar);
        step();

        float was = stripeX(bar);
        step();
        assertNotEquals("the sweep died when the bar left the tree", was, stripeX(bar), 0.001f);
    }

    /** Moved within one frame, the old hook is still live: a second would sweep at twice the rate. */
    @Test
    public void aBarMovedWithinAFrameKeepsOneHook() {
        ProgressBar bar = new ProgressBar();
        bar.layout(l -> l.width(100).height(4));
        document.append(bar);
        step();
        int hooks = document.animation().afterLayoutCount();

        document.remove(bar);
        document.append(bar);
        step();
        assertEquals(hooks, document.animation().afterLayoutCount());
    }

    private void step() {
        document.frame(0.05f, W, H);
    }

    private static float stripeX(ProgressBar bar) {
        return bar.fill().box().localToWorld().m30();
    }
}
