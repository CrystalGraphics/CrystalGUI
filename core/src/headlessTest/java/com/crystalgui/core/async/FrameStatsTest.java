package com.crystalgui.core.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * The arithmetic behind the readout, on synthetic frames — the only way to assert on a distribution,
 * since a real one is whatever the machine running the suite happened to do.
 */
public class FrameStatsTest {

    private FrameStats stats;

    /** Frames are fed at explicit times, so every number below is exact rather than approximate. */
    private long clock;

    @Before
    public void collecting() {
        stats = FrameStats.get();
        stats.hold();
        stats.setWindowSeconds(3f).setBudgetMs(16.7f);
        clock = 1_000_000_000L;
    }

    @After
    public void released() {
        stats.release();
    }

    /** One frame lasting {@code wallMs}, of which {@code cpuMs} was work. */
    private void frame(double wallMs, double cpuMs) {
        clock += (long) (wallMs * 1_000_000d);
        stats.frameBegan(clock);
        stats.frameEnded(clock + (long) (cpuMs * 1_000_000d));
    }

    private void frames(int howMany, double wallMs) {
        for (int i = 0; i < howMany; i++) frame(wallMs, wallMs / 2d);
    }

    /**
     * A frame's wall time is the interval to the NEXT one, so the last frame fed is always still open —
     * this closes it. @see FrameStats#frameBegan
     */
    private void settle(double wallMs, double cpuMs) {
        frame(wallMs, cpuMs);
    }

    /** As {@link #settle(double, double)}, where the closing frame's own cost does not matter. */
    private void settle() {
        settle(8d, 4d);
    }

    @Test
    public void aSingleFrameHasNoRateToReport() {
        frame(10d, 5d);
        // THE FIRST FRAME IS AN INTERVAL SHORT. Charging it the gap since the collector was switched on
        // would put one enormous sample into every window it appears in.
        assertEquals(0, stats.sampleCount());
        assertTrue(stats.lines().get(0).contains("warming up"));
    }

    @Test
    public void theRateIsTheWindowsFramesOverItsSpan() {
        frames(200, 10d);
        settle(10d, 5d);
        assertEquals(100f, stats.fps(), 1f);
        assertEquals(10f, stats.lastFrameMs(), 0.01f);
        assertEquals(5f, stats.lastCpuMs(), 0.01f);
    }

    @Test
    public void theWindowForgetsWhatFellOutOfIt() {
        frames(100, 10d);          // a second of 100fps
        frames(300, 10d);          // ...and three more, so the first second is out of a 3s window
        settle();
        assertEquals(300d, stats.sampleCount(), 2d);
    }

    @Test
    public void oneSpikeMovesTheWorstAndTheLowsAndNotTheAverage() {
        frames(299, 8d);
        frame(200d, 190d);
        settle();
        // The average barely moves -- which is the whole reason a readout states more than an average.
        assertTrue("fps was " + stats.fps(), stats.fps() > 100f);
        assertEquals(200f, stats.worstMs(), 0.5f);
        assertEquals(8f, stats.bestMs(), 0.5f);
        assertEquals(8f, stats.percentileMs(0.5), 0.5f);
        assertTrue("1% low was " + stats.lowOnePercentFps(), stats.lowOnePercentFps() < 60f);
        // THE TWO VERDICTS DISAGREE, which is the point of having both: the rate is fine and one frame
        // was not, and a readout that coloured itself by the average alone would be green through it.
        // AND BOTH STAY GREEN, which is the whole of the threshold question: one outlier in three
        // hundred frames is what a healthy run looks like, and a verdict it turned red would be red for
        // the entire life of the readout. The spike is shown; it is not the judgement.
        assertEquals(FrameStats.Health.GOOD, stats.rateHealth());
        assertEquals(FrameStats.Health.GOOD, stats.spreadHealth());
        assertEquals(FrameStats.Health.GOOD, stats.missHealth());
    }

    @Test
    public void sustainedMissesAreWhatTurnsTheReadoutRed() {
        frames(150, 40d);
        settle();
        assertEquals(FrameStats.Health.BAD, stats.rateHealth());
        assertEquals(FrameStats.Health.BAD, stats.spreadHealth());
        assertEquals(FrameStats.Health.BAD, stats.missHealth());
    }

    @Test
    public void aHandfulOfMissesIsAmberRatherThanRed() {
        frames(280, 8d);
        frames(15, 20d);
        settle();
        // ~5% of the window, which is worth noticing and is not a broken frame rate.
        assertEquals(FrameStats.Health.WARN, stats.missHealth());
    }

    @Test
    public void aMissedFrameIsOneOverTheStatedBudget() {
        frames(100, 8d);
        frames(10, 20d);
        settle();
        assertEquals(10, stats.framesOverBudget());
        stats.setBudgetMs(33.3f);
        assertEquals("nothing misses a budget twice as generous", 0, stats.framesOverBudget());
        assertEquals(FrameStats.Health.GOOD, stats.missHealth());
    }

    /** What a frame's phase map looks like, in the order FrameProfile fills it. */
    private void phases(long styleUs, long layoutUs, long paintUs) {
        java.util.Map<String, Long> timed = new java.util.LinkedHashMap<>();
        timed.put("style", styleUs * 1_000L);
        timed.put("layout", layoutUs * 1_000L);
        timed.put("paint", paintUs * 1_000L);
        java.util.Map<String, Integer> counted = new java.util.LinkedHashMap<>();
        counted.put("drawcalls", 143);
        stats.describeFrame(timed, counted);
    }

    @Test
    public void theSummaryKeepsThePhasesOnOneLine() {
        frames(10, 8d);
        settle();
        phases(6000, 3000, 1000);
        List<FrameStats.Row> rows = stats.rows(FrameStats.Detail.SUMMARY);
        // Three verdicts, the bars, one phase line, one counts line.
        assertEquals(6, rows.size());
        assertTrue(rows.get(4).text(), rows.get(4).text().startsWith("style 6.00ms"));
        assertEquals("drawcalls=143", rows.get(5).text());
    }

    @Test
    public void fullDetailGivesEachPhaseItsOwnRowAndItsShare() {
        frames(10, 8d);
        settle();
        phases(6000, 3000, 1000);
        List<FrameStats.Row> rows = stats.rows(FrameStats.Detail.FULL);
        assertEquals(9, rows.size());
        assertTrue(rows.get(4).text(), rows.get(4).text().contains("phases 10.0ms"));
        // THE SHARE IS OF THE PHASES' OWN TOTAL, so the column adds up to 100 rather than to whatever
        // fraction of the frame happened to be marked. @see FrameStats#addPhaseRows
        assertTrue(rows.get(5).text(), rows.get(5).text().contains("style") && rows.get(5).text().endsWith("60%"));
        assertTrue(rows.get(6).text(), rows.get(6).text().endsWith("30%"));
        assertTrue(rows.get(7).text(), rows.get(7).text().endsWith("10%"));
        // A breakdown is never a verdict: nothing here knows what a phase SHOULD cost.
        for (FrameStats.Row row : rows.subList(3, rows.size())) {
            assertEquals(row.text(), FrameStats.Health.NONE, row.health());
        }
    }

    @Test
    public void aFrameWithNoPhasesSaysSoRatherThanShowingNothing() {
        frames(10, 8d);
        settle();
        // A host that frames without painting records no phases -- the row says that, rather than being
        // absent, which would read as "no time was spent anywhere".
        assertTrue(stats.phasesByCost().isEmpty());
        assertTrue(stats.lines().stream().anyMatch(line -> line.contains("none recorded")));
    }

    @Test
    public void theBarsAreJudgedAgainstTheBudgetAndNotAgainstEachOther() {
        frames(60, 8d);
        settle();
        FrameStats.Spark spark = stats.spark(20);
        assertEquals(20, spark.bars().length());
        assertEquals(20, spark.health().size());
        // A FLAWLESS RUN IS GREEN THROUGHOUT however tall its bars are drawn -- height is a share of the
        // window's own worst frame, and at a steady rate that worst frame is a good one.
        for (FrameStats.Health each : spark.health()) assertEquals(FrameStats.Health.GOOD, each);
    }

    @Test
    public void aSpikeColoursItsOwnColumnAndNoOther() {
        frames(150, 8d);
        frame(200d, 190d);
        frames(150, 8d);
        settle();
        FrameStats.Spark spark = stats.spark(30);
        long bad = spark.health().stream().filter(each -> each == FrameStats.Health.BAD).count();
        assertEquals("one spike is one red column", 1, bad);
    }

    @Test
    public void theBreakdownShownIsTheSlowestFramesAndNotTheLastOnes() {
        frames(10, 8d);
        settle(60d, 55d);
        phases(50000, 3000, 1000);
        settle(8d, 4d);
        phases(100, 50, 10);

        // The last frame's breakdown is the cheap one, which is what a live one-line hint should show...
        assertTrue(stats.phasesByCost().get(0).getValue() < 1_000_000L);
        // ...and what the expanded readout shows is the spike's. A readout refreshes ten times a second
        // and the frame worth reading is never the one still on screen by the time an eye reaches it.
        assertEquals("style", stats.peakPhasesByCost().get(0).getKey());
        assertEquals(55f, stats.peakCpuMs(), 0.5f);
    }

    @Test
    public void aPeakOlderThanTheWindowIsForgotten() {
        settle(60d, 55d);
        phases(50000, 3000, 1000);
        frames(400, 10d);       // four seconds, so the spike is well outside a three-second window
        settle(8d, 4d);
        phases(100, 50, 10);
        // OR THE BREAKDOWN WOULD BE STARTUP'S FOREVER: the first frames of any scene carry every lazy
        // allocation and every shader's first compile, and nothing afterwards would ever beat them.
        assertEquals(4f, stats.peakCpuMs(), 0.5f);
    }

    @Test
    public void releasingTheLastHolderStopsCollection() {
        assertTrue(FrameStats.isCollecting());
        stats.release();
        assertFalse(FrameStats.isCollecting());
        stats.hold();   // the @After release is the matching one
    }
}
