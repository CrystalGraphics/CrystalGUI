package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.CrystalGuiCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a frame spent its time on — now a <b>forwarder onto {@link CgTrace}</b>, and still the API every
 * call site in this module uses.
 *
 * <h3>Two APIs in one class, and only one of them is frames</h3>
 *
 * <p>That was invisible from the name and is the whole shape of this class. The tell is in the numbers:
 * 112 {@code begin()} calls against 44 {@code end()}, because sixty-two of them feed {@code step()},
 * which logs a chain rather than accumulating a phase.</p>
 *
 * <table border="1">
 *   <caption>Where each half goes</caption>
 *   <tr><th></th><th>The frame API</th><th>The flow API</th></tr>
 *   <tr><td>Calls</td><td>{@link #begin}/{@link #end}, {@link #count}</td>
 *       <td>{@link #begin}/{@link #step}, {@link #enter}/{@link #leave}, {@link #note}</td></tr>
 *   <tr><td>Lifetime</td><td>Within one frame</td><td>A chain across frames, or outside any</td></tr>
 *   <tr><td>Records as</td><td>Zones and counters on {@link UiTrace#FRAME}</td>
 *       <td>Spans and markers on {@link UiTrace#FLOW}</td></tr>
 * </table>
 *
 * <h3>Why the signatures did not change</h3>
 *
 * <p>275 call sites across 29 files. Keeping the shapes — a {@code long} token from {@link #begin()},
 * a bucket name at {@link #end}, an {@code enter}/{@code leave} pair — meant none of them moved, and
 * the rewrite went live everywhere the moment these bodies changed.</p>
 *
 * <h3>{@code begin()/end()} is an additive bucket, not a stack</h3>
 *
 * <p>So it records through {@link CgTrace#zoneDone} rather than push/pop: a start stamp taken here and
 * a duration attributed there never had a nesting discipline, and imposing one would invent structure.
 * A completed zone lands at whatever depth is open, so these nest correctly the moment an enclosing
 * bracket becomes a real zone and read as siblings until then.</p>
 *
 * <h3>The property still governs LOGGING, and nothing else</h3>
 *
 * <p>{@code -Dcrystalgui.frameprofile=true} writes a line per slow frame, which is a probe somebody is
 * watching a log for. Recording is governed by the channel mask instead, so a readout can ask for the
 * phases without a restart — which is exactly when a stall is least reproducible.</p>
 */
public final class FrameProfile {

    private FrameProfile() {
    }

    /** {@code -Dcrystalgui.frameprofile=true} — the LOG, not the recording. @see UiTrace */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.frameprofile");

    /**
     * Report a frame only if it cost more than this. 120Hz is 8.3ms, so the default is "missed".
     *
     * <p>{@code -Dcrystalgui.frameprofile.floor=0} reports every frame the rate limit allows, which is
     * what a COMPARISON wants: a change that made frames fast enough to stop being reported is
     * indistinguishable here from a probe that was never switched on.</p>
     */
    private static final long SLOW_NANOS =
            Long.getLong("crystalgui.frameprofile.floor", 8L) * 1_000_000L;

    /**
     * At most one report per this many nanos, however many frames are slow.
     *
     * <p>{@code -Dcrystalgui.frameprofile.every=0} reports every frame, which is what a statistic wants:
     * one sample a second is a dozen frames out of fifteen hundred.</p>
     */
    private static final long REPORT_EVERY_NANOS =
            Long.getLong("crystalgui.frameprofile.every", 1000L) * 1_000_000L;

    /** Whether phases are being recorded at all — the log property, or somebody holding the channel. */
    private static boolean timing() {
        return ENABLED || CgTrace.isEnabled(UiTrace.FRAME);
    }

    // ── The frame boundary ──────────────────────────────────────────────────────────────────

    /**
     * Called at the very top of a frame.
     *
     * <p>The engine commits the PREVIOUS frame here, because a frame's wall time is the interval to the
     * next one. @see CgTrace#frameBegin()</p>
     */
    public static void frameBegin() {
        // FLUSHED BEFORE THE BOUNDARY MOVES, not only at frameEnd. A frame that is begun and never
        // ended -- a host that threw mid-frame, a paint that returned early -- would otherwise have its
        // counters cleared below and lost, and a missing counter reads as "nothing happened" rather
        // than as "nothing was written down".
        flushCounts();
        CgTrace.frameBegin();
        if (!timing()) return;
        PHASES.clear();
        COUNTS.clear();
        // SITES IS NOT CLEARED HERE, and that was a bug in this probe rather than in the engine.
        // Input dispatch and paint both run AFTER advanceFrame, so an invalidation raised during frame
        // N is drained by N+1; clearing at the top of N+1 threw the evidence away moments before
        // reporting the work it explained. Cleared at the end of frameEnd instead.
        frameStart = System.nanoTime();
        gcAtFrameStart = FrameStats.gcMillis();
    }

    /**
     * Called at the very end of a frame.
     *
     * <p>Flushes this frame's counters and blame to the ring, gives the engine its CPU mark, and logs
     * if the property is set and the frame was slow.</p>
     */
    public static void frameEnd() {
        flushCounts();
        flushBlame();
        CgTrace.frameEnd();
        // THE FILE IS REASON ENOUGH. The line used to require the property because the property was
        // the only thing that made it affordable; now that it goes to a queue rather than a console,
        // a run with a log open wants it whether or not anybody is watching a terminal.
        if (frameStart == 0L || (!ENABLED && !com.crystalgraphics.trace.CgTraceLog.isOpen())) return;
        logSlowFrame();
    }

    // ── Phases ──────────────────────────────────────────────────────────────────────────────

    /** Adds {@code nanos} to a named bucket — for work that is not a whole phase. */
    public static void add(String bucket, long nanos) {
        if (!timing()) return;
        long now = System.nanoTime();
        CgTrace.zoneDone(UiTrace.FRAME, bucket, now - nanos, now);
        if (ENABLED) PHASES.merge(bucket, nanos, Long::sum);
    }

    /** Starts a timing for {@link #end}; returns 0 when nothing is recording, so a caller needs no branch. */
    public static long begin() {
        return timing() ? System.nanoTime() : 0L;
    }

    /** Ends a {@link #begin} timing into {@code bucket}. */
    public static void end(long started, String bucket) {
        if (started == 0L) return;
        long now = System.nanoTime();
        CgTrace.zoneDone(UiTrace.FRAME, bucket, started, now);
        if (ENABLED) PHASES.merge(bucket, now - started, Long::sum);
    }

    /** Records a count worth seeing beside the times — how many elements, rows, marks. */
    public static void count(String what, int howMany) {
        if (!timing()) return;
        // ACCUMULATED AND FLUSHED ONCE A FRAME. `count("drawcalls", 1)` fires once per draw, so writing
        // each call through as its own counter value would be a hundred rows a frame saying 1.
        COUNTS.merge(what, howMany, Integer::sum);
    }

    /**
     * Writes this frame's accumulated counts to the ring, once.
     *
     * <p><b>Clears as it goes</b>, which is what makes it safe to call from both ends of a frame: the
     * boundary at {@link #frameBegin} is a backstop for a frame whose {@link #frameEnd} never ran, and
     * without the clear it would write every counter a second time. Measured as exactly that: 480
     * counter events in an export that should have had 240.</p>
     */
    private static void flushCounts() {
        if (COUNTS.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : COUNTS.entrySet()) {
            CgTrace.counter(UiTrace.FRAME, entry.getKey(), entry.getValue());
        }
        COUNTS.clear();
    }

    // ── The flow API: a chain, not a frame ──────────────────────────────────────────────────

    /**
     * Records ONE step of a flow as it happens, rather than aggregating it into a frame.
     *
     * <p>{@link #end} answers "where did this frame go", which is the right question for a sustained
     * cost and the wrong one for a sequence. Opening a file is a chain — a command, a picker, a search
     * per keystroke, an accept, a dock open, a read, a parse — and what matters is the ORDER and where
     * the chain stalls. Several of those steps happen in no frame at all.</p>
     */
    public static void step(long started, String what) {
        if (started == 0L) return;
        CgTrace.spanDone(UiTrace.FLOW, what, started);
        long took = System.nanoTime() - started;
        if (took >= STEP_FLOOR) emit("[step] " + indent() + what + ' ' + took / 1_000L + "us");
    }

    /**
     * Records a duration that was <b>accumulated</b> rather than measured from a single start.
     *
     * <p>{@link #step} takes a start stamp, which cannot express a total summed across a loop.</p>
     */
    public static void report(long nanos, String what) {
        if (nanos <= 0L) return;
        long now = System.nanoTime();
        CgTrace.spanDone(UiTrace.FLOW, what, now - nanos);
        if (nanos >= STEP_FLOOR) emit("[step] " + indent() + what + ' ' + nanos / 1_000L + "us");
    }

    /** Notes a step that has no duration worth timing — an entry point, a decision, a count. */
    public static void note(String what) {
        CgTrace.marker(UiTrace.FLOW, what);
        emit("[step] " + indent() + ". " + what);
    }

    /**
     * Opens a nesting level, so a chain reads as a chain. Always paired with {@link #leave}.
     *
     * @return what {@link #leave} takes back — a span id now, where it used to be a start stamp. Both
     *         are a {@code long} the caller only passes on, which is why no call site changed.
     */
    public static long enter(String what) {
        long span = CgTrace.spanBegin(UiTrace.FLOW, what);
        emit("[step] " + indent() + "> " + what);
        depth++;
        return span;
    }

    /** Closes an {@link #enter} and reports what the whole of it cost. */
    public static void leave(long span, String what) {
        depth = Math.max(0, depth - 1);
        emit("[step] " + indent() + "< " + what);
        if (span >= 0L) CgTrace.spanEnd(span);
    }

    /**
     * Where a line goes: this run's {@code trace.log}, and the console only if the property asked.
     *
     * <p>The file is the default and the console is the exception, which is the inversion T4 exists
     * for. {@link com.crystalgraphics.trace.CgTraceLog#line} queues and returns; the logger does not,
     * and on a Minecraft host it is a synchronous hop to a console appender. A probe that reports a
     * slow frame by blocking the frame thread on terminal I/O has changed what it was measuring.</p>
     */
    private static void emit(String line) {
        UiTrace.log(line);
        if (ENABLED) CrystalGuiCore.LOGGER.info(line);
    }

    private static String indent() {
        StringBuilder out = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) out.append("  ");
        return out.toString();
    }

    /** 100µs. Low on purpose: a step that is FAST is a finding when the one after it is not. */
    private static final long STEP_FLOOR = 100_000L;

    private static int depth;

    // ── Blame ───────────────────────────────────────────────────────────────────────────────

    /**
     * Blames the CALLER for one occurrence of {@code what} — the probe that names a churn source.
     *
     * <p>A count says three hundred elements were re-matched; it cannot say who asked. This walks up to
     * the first frame outside the packages doing the bookkeeping and counts that, so a report reads
     * {@code Tooltip.reposition:214=280} rather than {@code rematched=300}.</p>
     *
     * <p><b>Priced like the probe it is.</b> Capturing a stack is microseconds and this runs per
     * invalidation, so it lives on {@link UiTrace#BLAME} — its own channel, asked for by name.</p>
     */
    public static void blame(String what, String... ignorePackages) {
        if (!ENABLED && !CgTrace.isEnabled(UiTrace.BLAME)) return;
        // A STACK WALK PER CALL, and a frame that re-matches two thousand elements makes two thousand
        // of them: the probe was a quarter of the frames it measured. Every call is attributed up to a
        // cap, then one in a stride stands for the stride, so the totals hold and the cost does not.
        int nth = blamesThisFrame++;
        int weight = 1;
        if (nth >= BLAME_EXACTLY) {
            if ((nth - BLAME_EXACTLY) % BLAME_STRIDE != 0) return;
            weight = BLAME_STRIDE;
        }
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement frame : stack) {
            String at = frame.getClassName();
            if (at.startsWith(FrameProfile.class.getName())) continue;
            boolean skip = false;
            for (String ignored : ignorePackages) {
                if (at.startsWith(ignored)) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;
            int dot = at.lastIndexOf('.');
            SITES.merge((dot < 0 ? at : at.substring(dot + 1))
                    + '.' + frame.getMethodName() + ':' + frame.getLineNumber(), weight, Integer::sum);
            return;
        }
        SITES.merge(what + "(unattributed)", weight, Integer::sum);
    }

    /**
     * Writes this frame's blame as markers, top sites first.
     *
     * <p>ONE marker per SITE rather than one per invalidation: two thousand markers in a frame would
     * fill the ring with the evidence for a single finding and evict everything else.</p>
     */
    private static void flushBlame() {
        if (SITES.isEmpty() || !CgTrace.isEnabled(UiTrace.BLAME)) return;
        List<Map.Entry<String, Integer>> sites = new ArrayList<>(SITES.entrySet());
        sites.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(MAX_BLAME_MARKERS, sites.size()); i++) {
            CgTrace.marker(UiTrace.BLAME, "invalidated-by",
                    sites.get(i).getKey() + " x" + sites.get(i).getValue());
        }
    }

    /** Past this the list is a log rather than a finding. */
    private static final int MAX_BLAME_MARKERS = 10;

    private static final Map<String, Integer> SITES = new LinkedHashMap<>();

    /** How many invalidations {@link #blame} attributes one by one each frame, and its stride after. */
    private static final int BLAME_EXACTLY = 256;
    private static final int BLAME_STRIDE = 16;

    private static int blamesThisFrame;

    // ── The log, which the property still governs ───────────────────────────────────────────

    private static final Map<String, Long> PHASES = new LinkedHashMap<>();
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();

    private static long frameStart;
    private static long lastReport;
    private static long gcAtFrameStart;

    /** What the last reported frame cost, so an escalation can be told from a plateau. */
    private static long lastReportedTotal = SLOW_NANOS;

    private static int framesSinceReport;
    private static int slowFramesSinceReport;
    private static long worstSinceReport;

    /**
     * Whether this frame gets past the rate limit.
     *
     * <p>A flat "at most one report a second" is right for a sustained cost and exactly wrong for a
     * <b>stall</b>: the frames around one are also slow, so whichever mildly-slow frame arrives first
     * claims the second and the 237ms one that follows is discarded without a word. So a frame also
     * reports when it is substantially worse than the last one reported.</p>
     */
    private static boolean worthReporting(long total, long now) {
        if (now - lastReport >= REPORT_EVERY_NANOS) return true;
        return total >= lastReportedTotal * 2;
    }

    /**
     * How many frames since the last report missed the budget — <b>"a spike" or "a plateau"</b>.
     *
     * <p>One printed line otherwise stands for both, and they need opposite fixes: {@code [frame] 25ms}
     * is equally one hiccup nobody would notice and forty consecutive 25ms frames, which is 40fps.</p>
     */
    private static String census() {
        String out = "(" + slowFramesSinceReport + "/" + framesSinceReport + " over budget, worst "
                + worstSinceReport / 1_000_000L + "ms)";
        framesSinceReport = 0;
        slowFramesSinceReport = 0;
        worstSinceReport = 0;
        return out;
    }

    private static void logSlowFrame() {
        long now = System.nanoTime();
        long total = now - frameStart;
        // COUNTED BEFORE ANYTHING IS DECIDED, so the census covers every frame rather than the
        // reported ones.
        framesSinceReport++;
        if (total >= SLOW_NANOS) slowFramesSinceReport++;
        if (total > worstSinceReport) worstSinceReport = total;
        if (total < SLOW_NANOS || !worthReporting(total, now)) {
            SITES.clear();
            blamesThisFrame = 0;
            return;
        }
        lastReport = now;
        lastReportedTotal = total;

        List<Map.Entry<String, Long>> phases = new ArrayList<>(PHASES.entrySet());
        phases.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder line = new StringBuilder();
        line.append("[frame] ").append(total / 1_000_000L).append("ms ").append(census()).append("  ");
        long collected = FrameStats.gcMillis() - gcAtFrameStart;
        if (collected > 0) line.append("GC ").append(collected).append("ms  ");
        for (Map.Entry<String, Long> phase : phases) {
            if (phase.getValue() < 200_000L) continue;
            line.append(phase.getKey()).append(' ').append(phase.getValue() / 1_000L).append("us  ");
        }
        if (!COUNTS.isEmpty()) {
            line.append("  [");
            for (Map.Entry<String, Integer> count : COUNTS.entrySet()) {
                line.append(count.getKey()).append('=').append(count.getValue()).append(' ');
            }
            line.append(']');
        }
        emit(line.toString());
        if (!SITES.isEmpty()) {
            List<Map.Entry<String, Integer>> sites = new ArrayList<>(SITES.entrySet());
            sites.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder blamed = new StringBuilder("[frame]   invalidated by:");
            int blamedTotal = 0;
            for (Integer each : SITES.values()) blamedTotal += each;
            blamed.append(" (").append(blamedTotal).append(" total)");
            for (int i = 0; i < Math.min(10, sites.size()); i++) {
                blamed.append("  ").append(sites.get(i).getKey())
                        .append(" x").append(sites.get(i).getValue());
            }
            emit(blamed.toString());
        }
        SITES.clear();
        blamesThisFrame = 0;
    }
}
