package com.crystalgui.core.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** One view's frames: read as its own statistics, apart from the compositor's. */
public class FrameLogTest {

    private static final long MS = 1_000_000L;

    /** Frames starting 10 ms apart, each costing 4 ms. */
    private static FrameLog steady(int frames) {
        FrameLog log = new FrameLog();
        for (int i = 0; i < frames; i++) log.committed(i * 10 * MS, 4 * MS);
        return log;
    }

    @Test
    public void aFrameIsWrittenWhenTheNextOneBegins() {
        FrameLog log = steady(3);

        assertEquals("the third has no successor yet", 2, log.frames().size());
        assertEquals(10 * MS, log.frames().get(0).wallNanos());
        assertEquals(4 * MS, log.frames().get(0).cpuNanos());
    }

    @Test
    public void statisticsOverALogAreItsOwn() {
        FrameStats stats = new FrameStats(steady(40));

        assertEquals(100f, stats.fps(), 0.5f);
        assertEquals(4f, stats.lastCpuMs(), 0.01f);
        assertTrue("a log records no phases", stats.peakPhasesByCost().isEmpty());
    }

    @Test
    public void holdingAViewsStatsSwitchesNoTraceChannelOn() {
        FrameStats stats = new FrameStats(steady(3));
        boolean before = FrameStats.isCollecting();

        stats.hold();

        assertEquals(before, FrameStats.isCollecting());
        stats.release();
    }

    @Test
    public void theRingKeepsItsCapacity() {
        FrameLog log = steady(FrameLog.CAPACITY + 50);

        assertEquals(FrameLog.CAPACITY, log.frames().size());
    }
}
