package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.widget.display.CounterTrack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The window's state, on a synthetic ring.
 *
 * <p>Headless because the model names no element: every band of the window is a function of this
 * object, so a defect here is a defect in every band at once, and none of them has to be built to
 * find it.</p>
 */
public class ProfilerModelTest {

    private static final CgTraceChannel CHANNEL = CgTrace.channel("profiler-model-test");

    private long clock = 1_000_000L;

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgTrace.setEnabled(CHANNEL, true);
    }

    @After
    public void tearDown() {
        CgTrace.resetForTesting();
    }

    /** One frame, {@code millis} long, with two zones in it — SIBLINGS, since nothing is open. */
    private void frame(double millis) {
        CgTrace.frameBegin(clock);
        long begin = clock;
        CgTrace.zoneDone(CHANNEL, "root", begin, begin + (long) (millis * 800_000d));
        CgTrace.zoneDone(CHANNEL, "child", begin + 100_000L, begin + 400_000L);
        clock += (long) (millis * 1_000_000d);
        CgTrace.frameEnd(clock);
    }

    /** A frame commits at the NEXT frameBegin, so a run needs one more than it asserts on. */
    private void close() {
        CgTrace.frameBegin(clock);
    }

    @Test
    public void liveFollowsTheNewestFrame() {
        frame(5d);
        frame(40d);
        frame(6d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        assertTrue("a fresh window is not live", model.isFollowing());
        assertEquals("live did not select the newest frame", model.frameCount() - 1, model.selectedIndex());

        frame(7d);
        close();
        model.refresh();
        assertEquals("live stopped following when a frame arrived",
                model.frameCount() - 1, model.selectedIndex());
    }

    @Test
    public void worstFrameSelectsTheSlowestAndPauses() {
        frame(5d);
        frame(40d);
        frame(6d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        model.selectWorst();

        CgFrameRecord selected = model.selectedFrame();
        assertNotNull("nothing selected on a ring with frames in it", selected);
        assertEquals("Worst frame did not land on the worst frame", 40d, selected.wallMillis(), 1d);
        assertFalse("jumping to a frame left the window following the newest one", model.isFollowing());
    }

    /** "Find next": a repeat steps down the slowest frames rather than landing on the same one again. */
    @Test
    public void worstFrameAgainStepsToTheNextSlowest() {
        frame(5d);
        frame(40d);
        frame(6d);
        frame(25d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        model.selectWorst();
        assertEquals(40d, model.selectedFrame().wallMillis(), 1d);
        model.selectWorst();
        assertEquals("a second press did not step to the next slowest", 25d, model.selectedFrame().wallMillis(), 1d);

        // Any other selection resets it: after a click elsewhere, "worst" means the worst again.
        model.selectFrame(0);
        model.selectWorst();
        assertEquals("a press after a click stepped on instead of restarting",
                40d, model.selectedFrame().wallMillis(), 1d);
    }

    @Test
    public void steppingIsClampedAtBothEnds() {
        frame(5d);
        frame(5d);
        frame(5d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        model.selectFrame(0);

        model.stepFrame(-1);
        assertEquals("stepping off the start wrapped or went negative", 0, model.selectedIndex());
        model.stepFrame(99);
        assertEquals("stepping off the end ran past the ring",
                model.frameCount() - 1, model.selectedIndex());
    }

    @Test
    public void aRefreshKeepsTheSameFrameSelectedRatherThanTheSameIndex() {
        frame(5d);
        frame(40d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        // PAUSED: holding one frame still is what a paused window is for, and a live one follows the newest.
        model.setFollowing(false);
        model.selectFrame(0);
        long was = model.selectedFrame().index();

        // Three more frames arrive; the old one is now three places further back.
        frame(5d);
        frame(5d);
        frame(5d);
        close();
        model.refresh();

        assertEquals("the selection drifted through the run on its own",
                was, model.selectedFrame().index());
    }

    @Test
    public void aRangeAggregatesOverEveryFrameInIt() {
        frame(5d);
        frame(5d);
        frame(5d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();

        model.selectFrame(0);
        int one = model.zonesOfSelection().size();
        assertEquals("one frame is one selection", 1, model.selectionFrameCount());

        model.selectRange(0, 2);
        assertTrue(model.hasRange());
        assertEquals(3, model.selectionFrameCount());
        assertEquals("a three-frame range did not carry three frames' zones",
                one * 3, model.zonesOfSelection().size());

        List<CgTraceAggregate.Stat> stats = model.statsOfSelection();
        CgTraceAggregate.Stat root = stats.stream()
                .filter(stat -> stat.name().equals("root")).findFirst().orElse(null);
        assertNotNull("the range lost a zone that is in every frame of it", root);
        assertEquals("instances were merged rather than counted", 3, root.count());
    }

    @Test
    public void selectingAFrameClearsTheRangeAndTheZone() {
        frame(5d);
        frame(5d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        model.selectRange(0, 1);
        model.selectZone("root");

        model.selectFrame(1);
        assertFalse("a frame click left the range selected", model.hasRange());
        assertNull("a frame click left a zone scoped from the previous selection",
                model.selectedZone());
    }

    @Test
    public void everySelectionChangeAnnouncesExactlyOnce() {
        frame(5d);
        frame(5d);
        frame(5d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        model.selectFrame(0);

        int[] count = {0};
        model.onChanged.connect(() -> count[0]++);

        model.selectFrame(1);
        assertEquals(1, count[0]);
        model.selectFrame(1);
        assertEquals("selecting what is already selected announced anyway", 1, count[0]);
        model.selectZone("root");
        assertEquals(2, count[0]);
        model.selectZone("root");
        assertEquals("scoping to the same zone announced anyway", 2, count[0]);
    }

    @Test
    public void freezingStopsCaptureAndThawingRestoresExactlyWhatWasOn() {
        frame(5d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        List<String> before = CgTrace.enabledNames();
        assertTrue("the test's own channel was not recording", before.contains(CHANNEL.name()));

        model.setFrozen(true);
        assertTrue(model.isFrozen());
        assertFalse("freezing left a channel recording", CgTrace.isRecording());

        model.setFrozen(false);
        assertFalse(model.isFrozen());
        assertEquals("thawing restored a different set than was frozen",
                before, CgTrace.enabledNames());
    }

    @Test
    public void anEmptyRingSelectsNothingRatherThanFrameZero() {
        ProfilerModel model = new ProfilerModel();
        model.refresh();

        assertEquals(0, model.frameCount());
        assertNull(model.selectedFrame());
        assertEquals(0, model.selectionFrameCount());
        assertTrue(model.zonesOfSelection().isEmpty());
        assertTrue(model.statsOfSelection().isEmpty());
        // And stepping on nothing is not an exception: the window binds arrows before it has data.
        model.stepFrame(1);
        assertNull(model.selectedFrame());
    }

    /**
     * On the REAL clock, because nesting is what is being asserted.
     *
     * <p>Depth is the recording thread's live stack, so a zone handed its own times through
     * {@code zoneDone} is recorded at whatever depth is open — two of them in a row are siblings, not
     * a parent and a child. Only a genuinely open zone produces a depth, which is why every other test
     * here can use a synthetic clock and this one cannot.</p>
     */
    @Test
    public void theTreeOfOneFrameNestsItsChild() {
        CgTrace.frameBegin();
        try (CgTrace.Zone ignoredRoot = CgTrace.zone(CHANNEL, "nest:root")) {
            try (CgTrace.Zone ignoredChild = CgTrace.zone(CHANNEL, "nest:child")) {
                // the body is the measurement
            }
        }
        CgTrace.frameEnd();
        CgTrace.frameBegin();

        ProfilerModel model = new ProfilerModel();
        model.refresh();

        List<CgTraceAggregate.Node> roots = model.treeOfSelection();
        assertEquals("the frame did not come back as one root", 1, roots.size());
        assertEquals("nest:root", roots.get(0).name());
        assertEquals("the child was not nested under the zone containing it",
                1, roots.get(0).children().size());
        assertEquals("nest:child", roots.get(0).children().get(0).name());
    }

    /**
     * V3's gate: a hundred-frame range aggregates inside one frame's budget.
     *
     * <p>A window that janks while re-aggregating is a window whose own cost lands in the ring it is
     * showing — the failure this whole design is written against. Asserted against 16ms with a warm
     * run first, because the first pass through {@code CgTraceAggregate} is JIT rather than work.</p>
     */
    @Test
    public void aHundredFrameRangeAggregatesInsideAFrame() {
        for (int i = 0; i < 120; i++) frame(5d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        model.selectRange(0, 99);
        assertEquals(100, model.selectionFrameCount());

        for (int warm = 0; warm < 20; warm++) {
            model.statsOfSelection();
            model.treeOfSelection();
        }

        long began = System.nanoTime();
        List<CgTraceAggregate.Stat> stats = model.statsOfSelection();
        List<CgTraceAggregate.Node> tree = model.treeOfSelection();
        double millis = (System.nanoTime() - began) / 1_000_000d;

        assertFalse("the range aggregated to nothing", stats.isEmpty());
        assertFalse("the range produced no tree", tree.isEmpty());
        assertTrue("a 100-frame range took " + String.format("%.2f", millis)
                + "ms to aggregate — the window would jank on a range selection", millis < 16d);
    }

    // ── V4: channels, and the boundary a mask change leaves ─────────────────────────────────

    @Test
    public void everyRegisteredChannelIsOffered() {
        ProfilerModel model = new ProfilerModel();
        assertTrue("the test's own channel was not offered",
                model.channelNames().contains(CHANNEL.name()));
        assertTrue("the engine's own channel was not offered",
                model.channelNames().contains(CgTrace.TRACE.name()));
    }

    @Test
    public void theEnabledSetRoundTripsThroughTheControlsProperty() {
        CgTraceChannel other = CgTrace.channel("profiler-model-test.other");
        ProfilerModel model = new ProfilerModel();

        model.setEnabledChannels(Set.of(CHANNEL.name(), other.name()));
        Set<String> back = model.enabledChannels();
        assertTrue("a channel written was not read back", back.contains(CHANNEL.name()));
        assertTrue("a channel written was not read back", back.contains(other.name()));

        model.setEnabledChannels(Set.of(CHANNEL.name()));
        assertFalse("clearing a channel left it recording",
                model.enabledChannels().contains(other.name()));
    }

    @Test
    public void turningAChannelOffStopsItsZonesReachingTheWindow() {
        CgTraceChannel noisy = CgTrace.channel("profiler-model-test.noisy");
        CgTrace.setEnabled(noisy, true);

        CgTrace.frameBegin(clock);
        CgTrace.zoneDone(CHANNEL, "mine", clock, clock + 500_000L);
        CgTrace.zoneDone(noisy, "theirs", clock, clock + 500_000L);
        clock += 5_000_000L;
        CgTrace.frameEnd(clock);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        model.selectFrame(model.frameCount() - 1);
        assertTrue("the noisy channel's zone never reached the window at all",
                named(model, "theirs"));

        model.setEnabledChannels(Set.of(CHANNEL.name()));
        CgTrace.frameBegin(clock);
        CgTrace.zoneDone(CHANNEL, "mine", clock, clock + 500_000L);
        CgTrace.zoneDone(noisy, "theirs", clock, clock + 500_000L);
        clock += 5_000_000L;
        CgTrace.frameEnd(clock);
        close();
        model.refresh();
        model.selectFrame(model.frameCount() - 1);

        // THE LATEST FRAME ONLY. Disabling a channel stops it RECORDING; it does not erase what it
        // already wrote, and the frames still in the ring are history rather than a leak.
        assertTrue("the enabled channel stopped recording too", named(model, "mine"));
        assertFalse("a disabled channel kept recording into the window",
                named(model, "theirs"));
    }

    /** Whether the CURRENTLY selected frame holds a zone by this name. */
    private static boolean named(ProfilerModel model, String zone) {
        for (CgTraceSnapshot.ZoneView view : model.zonesOfSelection()) {
            if (view.name().equals(zone)) return true;
        }
        return false;
    }

    /**
     * On the REAL clock, and that is the point of the method under test.
     *
     * <p>The engine stamps a mask marker with {@code System.nanoTime()} and a frame carries whatever
     * clock the caller passed, so a synthetic-clock frame and a real-clock marker are not on one
     * timeline at all — every frame looks older than every marker, and the boundary comes out at the
     * end of the ring. In production both are the same clock; in a test they are only the same clock
     * if the test says so, which is why this one does.</p>
     */
    @Test
    public void aMaskChangeMarksWhereTheFramesStopBeingComparable() {
        realFrame();
        realFrame();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        int before = model.frameCount();
        assertTrue("no frames were captured at all", before > 0);

        CgTrace.setEnabled(CgTrace.channel("profiler-model-test.late"), true);
        realFrame();
        realFrame();
        model.refresh();

        int boundary = model.comparableFrom();
        assertTrue("the boundary landed before frames that preceded the change, at " + boundary,
                boundary >= before);
        assertTrue("the boundary ran past the end of the ring", boundary <= model.frameCount());
        assertTrue("the boundary swallowed the frames recorded after the change",
                boundary < model.frameCount());
    }

    /** One frame on the engine's own clock — the only way a marker and a frame can be compared. */
    private void realFrame() {
        CgTrace.frameBegin();
        CgTrace.zoneDone(CHANNEL, "real", System.nanoTime(), System.nanoTime());
        CgTrace.frameEnd();
        CgTrace.frameBegin();
    }

    // ── V5: counters ────────────────────────────────────────────────────────────────────────

    @Test
    public void aCounterBecomesOneValuePerFrame() {
        CgTrace.frameBegin(clock);
        CgTrace.counter(CHANNEL, "drawcalls", 31L);
        clock += 5_000_000L;
        CgTrace.frameEnd(clock);

        CgTrace.frameBegin(clock);
        CgTrace.counter(CHANNEL, "drawcalls", 480L);
        clock += 5_000_000L;
        CgTrace.frameEnd(clock);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();

        long[] series = model.counterSeries().get("drawcalls");
        assertNotNull("the counter did not reach the window", series);
        assertEquals("the series is not one entry per frame", model.frameCount(), series.length);
        assertEquals(31L, series[0]);
        assertEquals(480L, series[1]);
    }

    @Test
    public void aFrameThatRecordedNoCounterIsAbsentRatherThanZero() {
        CgTrace.frameBegin(clock);
        CgTrace.counter(CHANNEL, "layers", 17L);
        clock += 5_000_000L;
        CgTrace.frameEnd(clock);

        frame(5d);                    // this one writes no counter
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();

        long[] series = model.counterSeries().get("layers");
        assertNotNull(series);
        assertEquals(17L, series[0]);
        assertEquals("a frame with no counter reads as a measured zero",
                CounterTrack.ABSENT, series[1]);
    }

    @Test
    public void theLastWriteInAFrameWins() {
        CgTrace.frameBegin(clock);
        CgTrace.counter(CHANNEL, "layers", 3L);
        CgTrace.counter(CHANNEL, "layers", 17L);
        clock += 5_000_000L;
        CgTrace.frameEnd(clock);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        assertEquals("a counter written twice did not settle on its final value",
                17L, model.counterSeries().get("layers")[0]);
    }

    @Test
    public void anEmptyRingHasNoCounters() {
        ProfilerModel model = new ProfilerModel();
        model.refresh();
        assertTrue(model.counterSeries().isEmpty());
    }

    @Test
    public void snapshotIsHeldRatherThanReReadPerQuery() {
        frame(5d);
        close();

        ProfilerModel model = new ProfilerModel();
        model.refresh();
        CgTraceSnapshot held = model.snapshot();

        frame(5d);
        close();

        assertTrue("the model read the live ring instead of its snapshot",
                model.snapshot() == held);
        assertEquals("the frame list moved under a held snapshot",
                held.frames().size(), model.frameCount());
    }
}
