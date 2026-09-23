package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceLog;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.core.CrystalGuiCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One line per slow frame in the run's {@code trace.log}: its time, how many frames since the last line
 * were slow, what it spent that on, its counts, and who invalidated what — read from the ring once the
 * frame has been committed.
 *
 * <pre>
 * [frame] 35ms (4/60 over budget, worst 35ms)  GC 6ms  paint:tree 21400us  frame:layout 8100us  [drawcalls=412 ]
 * [frame]   invalidated by: (300 total)  Tooltip.reposition:214 x280  ...
 * </pre>
 *
 * <p>{@code -Dcrystalgui.frameprofile=true} echoes it to the console too, and turns the frame channel on
 * at startup; {@code .floor=<ms>} is the slowest frame not reported (8), {@code .every=<ms>} the rate
 * limit (1000).</p>
 */
final class SlowFrameLog {

    private SlowFrameLog() {
    }

    /** {@code -Dcrystalgui.frameprofile=true}: the console as well as the file. */
    static final boolean ECHO = Boolean.getBoolean("crystalgui.frameprofile");

    /**
     * Report a frame only if it cost more than this. 120Hz is 8.3ms, so the default is "missed".
     *
     * <p>{@code .floor=0} reports every frame the rate limit allows, which is what a COMPARISON wants: a
     * change that made frames fast enough to stop being reported is indistinguishable from a probe that
     * was never switched on.</p>
     */
    private static final long SLOW_NANOS = Long.getLong("crystalgui.frameprofile.floor", 8L) * 1_000_000L;

    /** At most one report per this long, however many frames are slow; {@code .every=0} reports every one. */
    private static final long REPORT_EVERY_NANOS = Long.getLong("crystalgui.frameprofile.every", 1000L) * 1_000_000L;

    /** A phase under this is left out of the line. */
    private static final long PHASE_FLOOR = 200_000L;

    private static long lastReport;
    /** What the last reported frame cost, so an escalation can be told from a plateau. */
    private static long lastReportedTotal = SLOW_NANOS;
    private static int framesSinceReport;
    private static int slowFramesSinceReport;
    private static long worstSinceReport;

    /** The frame {@link CgTrace#frameBegin} just committed. Frame thread. */
    static void frameCommitted() {
        if (!ECHO && !CgTraceLog.isOpen()) return;
        CgFrameRecord frame = CgTrace.frame(CgTrace.currentFrameIndex() - 1L);
        if (frame == null) return;
        long total = frame.hasCpu() ? frame.cpuNanos() : frame.wallNanos();
        long now = System.nanoTime();
        // COUNTED BEFORE ANYTHING IS DECIDED, so the census covers every frame rather than the reported ones.
        framesSinceReport++;
        if (total >= SLOW_NANOS) slowFramesSinceReport++;
        if (total > worstSinceReport) worstSinceReport = total;
        if (total < SLOW_NANOS || !worthReporting(total, now)) return;
        lastReport = now;
        lastReportedTotal = total;

        Map<String, Long> phases = new LinkedHashMap<>();
        for (CgTraceSnapshot.ZoneView zone : CgTrace.zonesIn(frame)) {
            if (UiTrace.FRAME.name().equals(zone.channel())) phases.merge(zone.name(), zone.durationNanos(), Long::sum);
        }
        List<Map.Entry<String, Long>> byCost = new ArrayList<>(phases.entrySet());
        byCost.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        StringBuilder line = new StringBuilder("[frame] ").append(total / 1_000_000L).append("ms ").append(census()).append("  ");
        if (frame.gcMillis() > 0L) line.append("GC ").append(frame.gcMillis()).append("ms  ");
        for (Map.Entry<String, Long> phase : byCost) {
            if (phase.getValue() < PHASE_FLOOR) continue;
            line.append(phase.getKey()).append(' ').append(phase.getValue() / 1_000L).append("us  ");
        }
        List<CgTraceSnapshot.CounterView> counters = CgTrace.countersIn(frame);
        if (!counters.isEmpty()) {
            line.append("  [");
            for (CgTraceSnapshot.CounterView counter : counters) {
                line.append(counter.name()).append('=').append(counter.value()).append(' ');
            }
            line.append(']');
        }
        emit(line.toString());

        StringBuilder blamed = new StringBuilder();
        for (CgTraceSnapshot.MarkerView marker : CgTrace.markersIn(frame)) {
            if (UiTrace.BLAME.name().equals(marker.channel())) blamed.append("  ").append(marker.detail());
        }
        if (blamed.length() > 0) emit("[frame]   invalidated by:" + blamed);
    }

    /**
     * Whether this frame gets past the rate limit.
     *
     * <p>A flat "one a second" is right for a sustained cost and wrong for a <b>stall</b>: the frames
     * around one are slow too, so a mildly slow one claims the second and the 237ms one after it is
     * dropped without a word. So a frame also reports when it is twice the last one reported.</p>
     */
    private static boolean worthReporting(long total, long now) {
        if (now - lastReport >= REPORT_EVERY_NANOS) return true;
        return total >= lastReportedTotal * 2;
    }

    /**
     * How many frames since the last report missed — <b>a spike or a plateau</b>. One line otherwise
     * stands for both, and {@code [frame] 25ms} is equally one hiccup and forty frames at 40fps.
     */
    private static String census() {
        String out = "(" + slowFramesSinceReport + "/" + framesSinceReport + " over budget, worst "
                + worstSinceReport / 1_000_000L + "ms)";
        framesSinceReport = 0;
        slowFramesSinceReport = 0;
        worstSinceReport = 0;
        return out;
    }

    private static void emit(String line) {
        UiTrace.log(line);
        if (ECHO) CrystalGuiCore.LOGGER.info(line);
    }
}
