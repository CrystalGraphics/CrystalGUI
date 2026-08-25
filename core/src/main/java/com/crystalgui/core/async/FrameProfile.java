package com.crystalgui.core.async;

import com.crystalgui.core.CrystalGuiCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a slow frame actually spent its time on — a <b>probe</b>, enabled by a system property.
 *
 * <h3>Why this exists rather than more measuring in tests</h3>
 *
 * <p>Two rounds of this investigation were spent measuring components in isolation and fixing what they
 * showed, and the report kept coming back unchanged. Isolated measurement can only ever confirm a
 * suspicion; it cannot say what a real frame in a real workbench is doing, because a test cannot build
 * the dock, the tab strip, the breadcrumbs, the status bar, the problems panel and the file tree around
 * the editor. The measured evidence for that gap is already in hand: a 7-line README costs about 1.7ms a
 * frame in the application, while a 7-row editor on its own costs 317µs.</p>
 *
 * <p>So this reports from the running application, names the phase, and — for the cascade, which is the
 * phase most able to be quietly enormous — names <b>which elements</b> are being re-matched. "Style is
 * slow" is not actionable; "2,143 elements re-matched this frame, 2,000 of them {@code __error-stripe__}"
 * is a fix.</p>
 *
 * <h3>Off unless asked for</h3>
 *
 * <p>{@code -Dcrystalgui.frameprofile=true}. Unlike {@link UiBudget}, which is always on because it costs
 * two clock reads and reports once per operation, this samples several phases per frame and prints a line
 * per slow frame — the sort of thing that is worth its cost while somebody is looking at it and not
 * otherwise. Rate-limited regardless, because a sustained bad patch would otherwise write faster than a
 * log can be read.</p>
 */
public final class FrameProfile {

    private FrameProfile() {
    }

    /** {@code -Dcrystalgui.frameprofile=true}. Read once — a probe that could turn on mid-run is a probe
     * whose numbers cannot be compared. */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.frameprofile");

    /** Report a frame only if it cost more than this. 120Hz is 8.3ms, so this is "missed the budget". */
    private static final long SLOW_NANOS = 8_000_000L;

    /** At most one report per this many nanos, however many frames are slow. */
    private static final long REPORT_EVERY_NANOS = 1_000_000_000L;

    private static final Map<String, Long> PHASES = new LinkedHashMap<>();
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();

    private static long frameStart;
    private static long lastMark;
    private static long lastReport;

    /** Called at the very top of a frame. */
    public static void frameBegin() {
        if (!ENABLED) return;
        PHASES.clear();
        COUNTS.clear();
        // SITES IS NOT CLEARED HERE, and that was a bug in this probe rather than in the engine.
        //
        // A frame reported 243 elements re-matched with only 15 blamed callers, which read as an
        // invalidation route that bypassed markDirty -- it is not. Input dispatch and paint both run
        // AFTER advanceFrame, so an invalidation raised by a click or by a ticker during frame N is
        // drained by frame N+1; clearing at the top of N+1 threw the evidence away moments before
        // reporting the work it explained. Cleared at the end of frameEnd instead, so the window the
        // blame covers is the same window whose drain is being reported.
        frameStart = System.nanoTime();
        lastMark = frameStart;
    }

    /** Attributes everything since the previous mark (or the frame start) to {@code phase}. */
    public static void mark(String phase) {
        if (!ENABLED) return;
        long now = System.nanoTime();
        PHASES.merge(phase, now - lastMark, Long::sum);
        lastMark = now;
    }

    /** Adds {@code nanos} to a named bucket — for work that is not a whole phase. */
    public static void add(String bucket, long nanos) {
        if (!ENABLED) return;
        PHASES.merge(bucket, nanos, Long::sum);
    }

    /** Starts a timing for {@link #add}; returns 0 when disabled so a caller needs no branch. */
    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Ends a {@link #begin} timing into {@code bucket}. */
    public static void end(long started, String bucket) {
        if (!ENABLED || started == 0L) return;
        add(bucket, System.nanoTime() - started);
    }

    /**
     * Logs ONE step of a flow as it happens, rather than aggregating it into a frame.
     *
     * <h3>Why a second shape</h3>
     *
     * <p>{@link #mark} answers "where did this frame go", which is the right question for a sustained
     * cost and the wrong one for a sequence. Opening a file is a chain — a command, a picker, a search
     * per keystroke, an accept, a dock open, a read, a parse — and what matters is the ORDER and where
     * the chain stalls. Aggregating that into per-frame buckets loses exactly the part being asked
     * about, and several of these steps do not happen during a frame at all.</p>
     *
     * <p>Logged immediately, in sequence, with a depth indent so nesting reads. Everything over
     * {@link #STEP_FLOOR} — deliberately low, because a step that is fast is itself a finding when the
     * next one is not.</p>
     */
    public static void step(long started, String what) {
        if (!ENABLED || started == 0L) return;
        long took = System.nanoTime() - started;
        if (took < STEP_FLOOR) return;
        CrystalGuiCore.LOGGER.info("[step] {}{} {}us", indent(), what, took / 1_000L);
    }

    /**
     * Logs a duration that was <b>accumulated</b> rather than measured from a single start.
     *
     * <p>{@link #step} takes a start stamp, which cannot express a total summed across a loop — and
     * {@link #add} can, but a bucket only ever surfaces inside a reported {@code [frame]} line, and the
     * frame that opens a document is routinely suppressed by the rate limit. The first attempt at
     * splitting a per-row cost that way printed nothing at all and read as the code not having run.</p>
     */
    public static void report(long nanos, String what) {
        if (!ENABLED || nanos < STEP_FLOOR) return;
        CrystalGuiCore.LOGGER.info("[step] {}{} {}us", indent(), what, nanos / 1_000L);
    }

    /** Notes a step that has no duration worth timing — an entry point, a decision, a count. */
    public static void note(String what) {
        if (!ENABLED) return;
        CrystalGuiCore.LOGGER.info("[step] {}. {}", indent(), what);
    }

    /** Opens a nesting level, so a chain reads as a chain. Always paired with {@link #leave}. */
    public static long enter(String what) {
        if (!ENABLED) return 0L;
        CrystalGuiCore.LOGGER.info("[step] {}> {}", indent(), what);
        depth++;
        return System.nanoTime();
    }

    /** Closes a {@link #enter} and reports what the whole of it cost. */
    public static void leave(long started, String what) {
        if (!ENABLED || started == 0L) return;
        depth = Math.max(0, depth - 1);
        CrystalGuiCore.LOGGER.info("[step] {}< {} {}us",
                indent(), what, (System.nanoTime() - started) / 1_000L);
    }

    private static String indent() {
        StringBuilder out = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) out.append("  ");
        return out.toString();
    }

    /** 100µs. Low on purpose: a step that is FAST is a finding when the one after it is not. */
    private static final long STEP_FLOOR = 100_000L;

    private static int depth;

    /** Records a count worth seeing beside the times — how many elements, rows, marks. */
    public static void count(String what, int howMany) {
        if (!ENABLED) return;
        COUNTS.merge(what, howMany, Integer::sum);
    }

    /**
     * Blames the CALLER for one occurrence of {@code what} — the probe that names a churn source.
     *
     * <p>A count says three hundred elements were re-matched; it cannot say who asked. This walks up to
     * the first frame outside the packages doing the bookkeeping and counts that, so a report reads
     * {@code Tooltip.reposition:214=280} rather than {@code rematched=300}.</p>
     *
     * <p><b>A probe, and priced like one.</b> Capturing a stack is microseconds and this runs per
     * invalidation; that is affordable while somebody is watching a log and nowhere else, which is why
     * the whole class is behind a property.</p>
     */
    public static void blame(String what, String... ignorePackages) {
        if (!ENABLED) return;
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
                    + '.' + frame.getMethodName() + ':' + frame.getLineNumber(), 1, Integer::sum);
            return;
        }
        SITES.merge(what + "(unattributed)", 1, Integer::sum);
    }

    private static final Map<String, Integer> SITES = new LinkedHashMap<>();

    /** Called at the very end of a frame; reports if the frame was slow and the rate limit allows. */
    /**
     * Whether this frame gets past the rate limit.
     *
     * <h3>The limit was hiding the one frame worth reporting</h3>
     *
     * <p>A flat "at most one report a second" is right for a sustained cost — a stretch of 12ms frames
     * needs one line, not sixty. It is exactly wrong for a <b>stall</b>, which is what this probe exists
     * for: the frames around a stall are also slow, so whichever mildly-slow frame happens to arrive
     * first claims the second and the 237ms one that follows is discarded without a word. Measured: an
     * automated run whose own summary reported a worst frame of <b>236.6ms</b> contained no report of any
     * frame over 50ms, so the log described the wrong frame entirely and read as if the stall had been
     * fixed.</p>
     *
     * <p>So a frame also reports when it is <b>substantially worse than the last one reported</b>. The
     * limit still holds against a plateau — a run of equally slow frames is not each other's escalation
     * — while the peak, which is the whole finding, always gets through.</p>
     */
    private static boolean worthReporting(long total, long now) {
        // Comfortably past the limit: an ordinary periodic report.
        if (now - lastReport >= REPORT_EVERY_NANOS) return true;
        // Otherwise only an ESCALATION, so a plateau still reports once. Twice as expensive is the
        // threshold because at 8ms it takes 16ms to qualify, which is already the next budget down.
        return total >= lastReportedTotal * 2;
    }

    /** What the last reported frame cost, so {@link #worthReporting} can tell an escalation from a plateau. */
    private static long lastReportedTotal = SLOW_NANOS;

    private static int framesSinceReport;
    private static int slowFramesSinceReport;
    private static long worstSinceReport;

    /**
     * How many frames since the last report missed the budget — <b>"a spike" or "a plateau"</b>.
     *
     * <h3>Without it, one line cannot tell those apart, and they need opposite fixes</h3>
     *
     * <p>The rate limit means one printed line stands for every frame in that second. So {@code [frame]
     * 25ms} is equally a single hiccup nobody would notice and forty consecutive 25ms frames, which is a
     * steady <b>40fps</b> — and 120-to-40 is exactly the report this probe exists to serve. Reading a
     * plateau as a spike sends the next round after whatever happened to be biggest in that one frame,
     * which is how a stall gets chased through code that was only ever slow once.</p>
     *
     * <p>Cheap enough to be unconditional: three counters, incremented before the rate limit is even
     * consulted, so the census covers frames that are never printed. That is the whole point — the
     * printed frame is a sample, and this says what it is a sample OF.</p>
     */
    private static String census() {
        String out = "(" + slowFramesSinceReport + "/" + framesSinceReport + " over budget, worst "
                + worstSinceReport / 1_000_000L + "ms)";
        framesSinceReport = 0;
        slowFramesSinceReport = 0;
        worstSinceReport = 0;
        return out;
    }

    public static void frameEnd() {
        if (!ENABLED || frameStart == 0L) return;
        long now = System.nanoTime();
        long total = now - frameStart;
        // COUNTED BEFORE ANYTHING IS DECIDED, so the census covers every frame rather than the reported
        // ones. @see #census
        framesSinceReport++;
        if (total >= SLOW_NANOS) slowFramesSinceReport++;
        if (total > worstSinceReport) worstSinceReport = total;
        if (total < SLOW_NANOS || !worthReporting(total, now)) {
            // STILL CLEARED. @see #frameBegin -- the blame window has to advance every frame, or a quiet
            // stretch accumulates into the next report and blames it for work it never did.
            SITES.clear();
            return;
        }
        lastReport = now;
        lastReportedTotal = total;

        List<Map.Entry<String, Long>> phases = new ArrayList<>(PHASES.entrySet());
        phases.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder line = new StringBuilder();
        line.append("[frame] ").append(total / 1_000_000L).append("ms ").append(census()).append("  ");
        for (Map.Entry<String, Long> phase : phases) {
            if (phase.getValue() < 200_000L) continue;
            line.append(phase.getKey()).append(' ')
                    .append(phase.getValue() / 1_000L).append("us  ");
        }
        if (!COUNTS.isEmpty()) {
            line.append("  [");
            for (Map.Entry<String, Integer> count : COUNTS.entrySet()) {
                line.append(count.getKey()).append('=').append(count.getValue()).append(' ');
            }
            line.append(']');
        }
        CrystalGuiCore.LOGGER.info(line.toString());
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
            CrystalGuiCore.LOGGER.info(blamed.toString());
        }
        SITES.clear();
    }
}
