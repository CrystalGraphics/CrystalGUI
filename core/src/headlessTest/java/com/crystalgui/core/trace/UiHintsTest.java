package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceHints;
import com.crystalgraphics.trace.CgTraceSnapshot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Every hint fires on a case built for it, and links somewhere — the V6 gate of the Frame window.
 *
 * <p>A hint that cannot be acted on is noise, so each case also checks where the link leads: a zone
 * that is in the frame, or a counter that was recorded.</p>
 */
public class UiHintsTest {

    private static final CgTraceChannel CHANNEL = CgTrace.channel("ui-hints-test");
    private static final long MS = 1_000_000L;

    private long clock = 1_000_000L;

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgTrace.setEnabled(CHANNEL, true);
        UiHints.install();
    }

    @After
    public void tearDown() {
        CgTrace.resetForTesting();
    }

    /** Records one 10ms frame, running {@code body} inside it, and returns that frame. */
    private CgFrameRecord frame(Runnable body) {
        CgTrace.frameBegin(clock);
        body.run();
        clock += 10 * MS;
        CgTrace.frameEnd(clock);
        CgTrace.frameBegin(clock);
        List<CgFrameRecord> frames = CgTrace.frames();
        return frames.get(frames.size() - 1);
    }

    private List<CgTraceAggregate.Node> tree(CgFrameRecord frame) {
        return CgTraceAggregate.tree(CgTrace.zonesIn(frame));
    }

    private static Map<String, Long> counters(CgFrameRecord frame) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (CgTraceSnapshot.CounterView counter : CgTrace.countersIn(frame)) {
            out.merge(counter.name(), counter.value(), Long::sum);
        }
        return out;
    }

    private CgTraceHints.Hint find(String code, CgFrameRecord frame, List<CgTraceAggregate.Node> tree,
                                   Map<String, Long> counters) {
        for (CgTraceHints.Hint hint : CgTraceHints.forFrame(frame, tree, counters)) {
            if (hint.code().equals(code)) {
                assertNotNull(code + " links nowhere", hint.link());
                return hint;
            }
        }
        fail(code + " did not fire on the case built for it");
        return null;
    }

    private CgTraceHints.Hint find(String code, CgFrameRecord frame) {
        return find(code, frame, tree(frame), counters(frame));
    }

    @Test
    public void layerBound() {
        CgFrameRecord frame = frame(() -> {
            CgTrace.counter(CHANNEL, "layer-clear-kpx", 20_000L);
            CgTrace.counter(CHANNEL, "drawcalls", 31L);
        });
        assertEquals("layer-clear-kpx", find("LAYER-BOUND", frame).linkedCounter());
    }

    @Test
    public void retentionRefused() {
        CgFrameRecord frame = frame(() -> {
            CgTrace.counter(CHANNEL, "layers", 17L);
            CgTrace.counter(CHANNEL, "retain-dynamic", 12L);
        });
        assertEquals("retain-dynamic", find("RETENTION-REFUSED", frame).linkedCounter());
    }

    /** With blame on, the heaviest call site is named — the probe's best output, finally shown. */
    @Test
    public void cascadeChurnNamesTheBlamedSite() {
        CgTrace.setEnabled(UiTrace.BLAME, true);
        CgFrameRecord frame = frame(() -> {
            CgTrace.counter(CHANNEL, "rematched", 2_000L);
            CgTrace.markerAt(UiTrace.BLAME, "invalidated-by", "Tooltip.java:214 x1900", clock + MS);
            CgTrace.markerAt(UiTrace.BLAME, "invalidated-by", "Menu.java:88 x100", clock + MS);
        });
        CgTraceHints.Hint hint = find("CASCADE-CHURN", frame);
        assertTrue(hint.text(), hint.text().contains("Tooltip.java:214"));
        assertEquals("rematched", hint.linkedCounter());
    }

    @Test
    public void collectedNamesThePhaseWearingIt() {
        CgFrameRecord recorded = frame(() -> {
            CgTrace.zoneDone(CHANNEL, "hint:paint", clock + MS, clock + 8 * MS);
            CgTrace.zoneDone(CHANNEL, "hint:layout", clock, clock + MS / 2);
        });
        // A COLLECTION CANNOT BE SCHEDULED, so the record is the recorded one with a pause written in.
        CgFrameRecord collected = new CgFrameRecord(recorded.index(), recorded.beginNanos(), recorded.endNanos(),
                recorded.cpuNanos(), CgFrameRecord.ABSENT, 5L, 1, 0, 0L);
        assertEquals("hint:paint", find("COLLECTED", collected, tree(recorded), counters(recorded)).linkedZone());
    }

    @Test
    public void iconsDirect() {
        CgFrameRecord frame = frame(() -> CgTrace.counter(CHANNEL, "svg-direct", 3L));
        assertEquals("svg-direct", find("ICONS-DIRECT", frame).linkedCounter());
    }

    /** A zone's first run is named; the same zone in a later frame is not first any more. */
    @Test
    public void firstDrawOnlyOnTheFirstRun() {
        CgFrameRecord first = frame(() -> CgTrace.zoneDone(CHANNEL, "hint:atlas", clock + MS, clock + 4 * MS));
        assertEquals("hint:atlas", find("FIRST-DRAW", first).linkedZone());

        CgFrameRecord again = frame(() -> CgTrace.zoneDone(CHANNEL, "hint:atlas", clock + MS, clock + 4 * MS));
        for (CgTraceHints.Hint hint : CgTraceHints.forFrame(again, tree(again), counters(again))) {
            assertTrue("a second run was called a first one", !hint.code().equals("FIRST-DRAW"));
        }
    }

    /** No GPU timer resolves yet (engine T7), so the frame is built with one. */
    @Test
    public void gpuBound() {
        CgFrameRecord frame = new CgFrameRecord(1L, 0L, 20 * MS, 6 * MS, 14 * MS, 0L, 0, 0, 0L);
        // A frame's GPU figure lands with one counter per GPU zone; the hint names the costliest.
        CgTraceHints.Hint hint = find("GPU-BOUND", frame, List.of(),
                Map.of("gpu:ui", 11 * MS, "gpu:world.opaque", 3 * MS, "drawcalls", 900L));
        assertEquals("gpu:ui", hint.linkedCounter());
    }

    @Test
    public void blockedOnWorker() throws InterruptedException {
        CgFrameRecord frame = frame(() -> {
            long begin = clock;
            CgTrace.counter(CHANNEL, "jobs-busy", 2L);
            Thread worker = new Thread(() ->
                    CgTrace.zoneDone(CHANNEL, "hint:parse", begin + MS, begin + 7 * MS), "hint-worker");
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CgTraceHints.Hint hint = find("BLOCKED-ON-WORKER", frame);
        assertEquals("hint:parse", hint.linkedZone());
        assertTrue(hint.text(), hint.text().contains("hint-worker"));
    }
}
