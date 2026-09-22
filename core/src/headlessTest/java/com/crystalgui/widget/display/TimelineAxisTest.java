package com.crystalgui.widget.display;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The axis is arithmetic, so it is asserted rather than looked at.
 *
 * <p>Headless deliberately: every failure here is a click landing on the wrong zone, which is the one
 * defect a profiler cannot survive and the one a screenshot cannot show.</p>
 */
public class TimelineAxisTest {

    private static TimelineAxis axis(long from, long to, float pixels) {
        TimelineAxis axis = new TimelineAxis();
        axis.setExtent(from, to);
        axis.setPixels(pixels);
        axis.showAll();
        return axis;
    }

    @Test
    // a fresh axis shows the whole extent
    public void showsAll() {
        TimelineAxis axis = axis(1_000L, 2_000_000L, 500f);
        assertEquals(1_000L, axis.from());
        assertEquals(2_000_000L, axis.to());
        assertTrue(axis.isShowingAll());
    }

    @Test
    // a pixel maps back to the time it was drawn from
    public void roundTrips() {
        TimelineAxis axis = axis(0L, 10_000_000L, 800f);
        for (long at = 0L; at < 10_000_000L; at += 137_119L) {
            long back = axis.timeAt(axis.xOf(at));
            // One pixel of slack: the mapping is float one way and long the other.
            assertTrue("round trip lost " + (back - at) + "ns at " + at, Math.abs(back - at) <= (long) axis.nanosPerPixel() + 1);
        }
    }

    @Test
    // the ends of the window land on the ends of the track
    public void spansTheTrack() {
        TimelineAxis axis = axis(0L, 1_000_000L, 200f);
        assertEquals(0f, axis.xOf(0L), 0.01f);
        assertEquals(200f, axis.xOf(1_000_000L), 0.01f);
        assertEquals(100f, axis.widthOf(500_000L), 0.01f);
    }

    @Test
    // a pan that hits the end stops rather than shrinking the window
    public void clampingSlides() {
        TimelineAxis axis = axis(0L, 1_000_000L, 100f);
        axis.show(400_000L, 600_000L);
        long span = axis.spanNanos();

        axis.panPixels(1_000f);                       // far past the end
        assertEquals("clamping truncated the window instead of sliding it", span, axis.spanNanos());
        assertEquals(1_000_000L, axis.to());
        assertEquals(1_000_000L - span, axis.from());

        axis.panPixels(-10_000f);                     // far past the start
        assertEquals(span, axis.spanNanos());
        assertEquals(0L, axis.from());
    }

    @Test
    // zoom keeps whatever is under the pointer under the pointer
    public void zoomAnchorsOnThePointer() {
        TimelineAxis axis = axis(0L, 1_000_000L, 100f);
        for (float at : new float[]{0f, 25f, 50f, 99f}) {
            axis.showAll();
            long anchored = axis.timeAt(at);
            axis.zoomAt(at, 4d);
            long still = axis.timeAt(at);
            assertTrue("anchor at x=" + at + " moved by " + (still - anchored) + "ns", Math.abs(still - anchored) <= (long) axis.nanosPerPixel() + 1);
        }
    }

    @Test
    // zooming out past the extent shows the extent and no more
    public void zoomOutClamps() {
        TimelineAxis axis = axis(0L, 1_000_000L, 100f);
        axis.show(400_000L, 500_000L);
        for (int i = 0; i < 40; i++) axis.zoomAt(50f, 1d / 1.25d);
        assertEquals(0L, axis.from());
        assertEquals(1_000_000L, axis.to());
        assertTrue(axis.isShowingAll());
    }

    @Test
    // zooming in stops at a microsecond
    public void zoomInClamps() {
        TimelineAxis axis = axis(0L, 1_000_000L, 100f);
        for (int i = 0; i < 200; i++) axis.zoomAt(50f, 4d);
        assertEquals(TimelineAxis.MIN_SPAN_NANOS, axis.spanNanos());
        assertFalse(axis.isShowingAll());
    }

    @Test
    // a narrower extent pulls the window in with it
    public void narrowingTheExtent() {
        TimelineAxis axis = axis(0L, 1_000_000L, 100f);
        axis.setExtent(0L, 10_000L);
        assertTrue("the window outlived the data it was over", axis.to() <= 10_000L);
        assertTrue(axis.from() >= 0L);
    }

    @Test
    // an empty extent still divides
    public void degenerateExtent() {
        TimelineAxis axis = new TimelineAxis();
        axis.setExtent(500L, 500L);
        axis.setPixels(0f);
        axis.showAll();
        assertTrue(axis.spanNanos() >= TimelineAxis.MIN_SPAN_NANOS);
        assertTrue(axis.pixels() >= 1f);
        assertEquals(500L, axis.timeAt(0f));
    }

    @Test
    // a change announces once, and a no-op announces nothing
    public void announces() {
        TimelineAxis axis = axis(0L, 1_000_000L, 100f);
        int[] count = {0};
        axis.onChanged(() -> count[0]++);

        axis.show(100_000L, 200_000L);
        assertEquals(1, count[0]);
        axis.show(100_000L, 200_000L);
        assertEquals("an unchanged window announced anyway", 1, count[0]);
        axis.setPixels(100f);
        assertEquals("an unchanged width announced anyway", 1, count[0]);
        axis.setPixels(200f);
        assertEquals(2, count[0]);
    }
}
