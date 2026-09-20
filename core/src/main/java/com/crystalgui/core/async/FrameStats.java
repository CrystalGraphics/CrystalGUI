package com.crystalgui.core.async;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How fast the last few seconds actually ran — frame rate, the spread behind it, and what the profiler
 * blamed the last frame on.
 *
 * <p>The numbers a frame-rate readout is built from, with no UI and no GL, so the same collector serves a
 * harness scene, an in-game overlay and a headless bench. {@link com.crystalgui.widget.display.FrameStatsOverlay}
 * is the shipped display of it.</p>
 *
 * <pre>{@code
 * FrameStats stats = FrameStats.get();
 * stats.hold();                       // start collecting; release() when done
 * // ...per frame, driven by FrameProfile's own frame boundary...
 * for (String line : stats.lines()) draw(line);
 *
 * stats.fps();                        // frames per second over the window
 * stats.worstMs();                    // the spike everyone actually notices
 * stats.lowOnePercentFps();           // the "1% low" a benchmark reports
 * }</pre>
 *
 * <h3>Collecting is opt-in, and nothing pays for it otherwise</h3>
 *
 * <p>{@link #hold()} switches it on and {@link #release()} off, counted so two holders cannot switch each
 * other off. While nothing holds it, the per-frame cost is one boolean read in {@link FrameProfile}.</p>
 *
 * <h3>Wall time and CPU time are different questions</h3>
 *
 * <p>{@link #lastFrameMs()} is the interval between frame starts — what the frame rate is made of, vsync
 * and all. {@link #lastCpuMs()} is what the frame spent between the document's first line and the
 * painter's last. Under vsync the first is pinned at the refresh rate while the second says how much
 * headroom is left, and a readout showing only the first reports 60fps right up until it collapses.</p>
 *
 * <h3>What it deliberately does not do</h3>
 *
 * <p>No phase breakdown of its own: {@link FrameProfile} already times the phases, and a second timer
 * around the same code would be a second answer to one question. The phases here are that probe's, which
 * is why they are empty unless {@code -Dcrystalgui.frameprofile=true}.</p>
 */
public final class FrameStats {

    private static final FrameStats INSTANCE = new FrameStats();

    public static FrameStats get() {
        return INSTANCE;
    }

    private FrameStats() {
    }

    /**
     * Read once per frame by {@link FrameProfile} from whatever thread is drawing, and written by a
     * widget attaching on the frame thread — volatile so the writer's switch-on is seen rather than
     * hoisted out of the frame loop.
     */
    private static volatile boolean collecting;

    /** How many holders want samples. @see #hold */
    private int holders;

    public static boolean isCollecting() {
        return collecting;
    }

    /** Starts collecting, or joins a collection already running. The first holder clears what was there. */
    public void hold() {
        if (++holders == 1) {
            reset();
            collecting = true;
        }
    }

    /** Drops one {@link #hold()}. */
    public void release() {
        if (holders > 0 && --holders == 0) collecting = false;
    }

    /**
     * Frames held at once. 2048 covers three seconds at 680fps, and a window longer than the ring is
     * truncated to what the ring holds rather than reporting a span it does not have.
     */
    private static final int CAPACITY = 2048;

    private final long[] endedAt = new long[CAPACITY];
    private final long[] wallNanos = new long[CAPACITY];
    private final long[] cpuNanos = new long[CAPACITY];
    /** Cumulative collector time at each sample, so a window's GC share is one subtraction. */
    private final long[] gcTotalMillis = new long[CAPACITY];

    private int next;
    private int size;

    private long frameStart;
    private long previousStart;
    /** The CPU cost of the frame now running, committed with its interval at the next one. */
    private long pendingCpu;

    private float windowSeconds = 3f;

    /** A frame slower than this missed. 60Hz by default; a 120Hz host should say so. */
    private float budgetMs = 1000f / 60f;

    private final Map<String, Long> phases = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public float windowSeconds() {
        return windowSeconds;
    }

    /** How far back the derived numbers look. Samples already held are kept. */
    public FrameStats setWindowSeconds(float seconds) {
        windowSeconds = Math.max(0.1f, seconds);
        return this;
    }

    public float budgetMs() {
        return budgetMs;
    }

    /** What counts as a missed frame — the host's refresh interval. @see #framesOverBudget */
    public FrameStats setBudgetMs(float millis) {
        budgetMs = Math.max(0.1f, millis);
        return this;
    }

    public void reset() {
        next = 0;
        size = 0;
        frameStart = 0L;
        previousStart = 0L;
        pendingCpu = 0L;
        phases.clear();
        counts.clear();
    }

    // ── Collection ──────────────────────────────────────────────────────────────────────────────

    /**
     * The top of a frame, which is where a frame's sample is <b>committed</b> — the one before it.
     *
     * <h3>Why not at the end of the frame it describes</h3>
     *
     * <p>A frame's wall time is the interval to the NEXT one, so it is not known until that one starts.
     * Committing at the end of the frame instead means using the previous interval, and then a frame that
     * never reaches its end contributes nothing at all — which is not hypothetical: the end of a frame is
     * the painter's last line, so a document that is framed without being painted (a test, a headless
     * step, a minimised window) recorded no frames whatever while the readout said "warming up" forever.
     * The cost is that the readout is one frame behind, which at a tenth of a second between refreshes is
     * not observable.</p>
     */
    void frameBegan(long nowNanos) {
        // NOTHING TO COMMIT ON THE FIRST ONE: there is no interval yet, and charging it the whole gap
        // since the collector was switched on would put one enormous sample into every window it reaches.
        if (previousStart != 0L) {
            endedAt[next] = nowNanos;
            wallNanos[next] = nowNanos - previousStart;
            cpuNanos[next] = pendingCpu;
            gcTotalMillis[next] = gcMillis();
            next = (next + 1) % CAPACITY;
            if (size < CAPACITY) size++;
        }
        previousStart = nowNanos;
        frameStart = nowNanos;
        pendingCpu = 0L;
    }

    /** The end of the same frame, paint included — its CPU cost, committed with its interval. */
    void frameEnded(long nowNanos) {
        if (frameStart == 0L) return;
        pendingCpu = nowNanos - frameStart;
    }

    /** The last frame's phase timings and counts, copied out of {@link FrameProfile} while it is on. */
    void describeFrame(Map<String, Long> timedPhases, Map<String, Integer> countedThings) {
        phases.clear();
        phases.putAll(timedPhases);
        counts.clear();
        counts.putAll(countedThings);
    }

    /** Total time this JVM has spent collecting. @see FrameProfile — a pause is charged to whatever ran. */
    static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long spent = collector.getCollectionTime();
            if (spent > 0) total += spent;
        }
        return total;
    }

    // ── What the window says ────────────────────────────────────────────────────────────────────

    /** Frames held that ended within {@link #windowSeconds}, oldest first, as indices into the ring. */
    private int windowStart() {
        if (size == 0) return 0;
        long newest = endedAt[(next - 1 + CAPACITY) % CAPACITY];
        long cutoff = newest - (long) (windowSeconds * 1_000_000_000L);
        int kept = 0;
        for (int i = 0; i < size; i++) {
            int at = (next - 1 - i + CAPACITY * 2) % CAPACITY;
            if (endedAt[at] < cutoff) break;
            kept++;
        }
        return kept;
    }

    private long at(int fromNewest, long[] values) {
        return values[(next - 1 - fromNewest + CAPACITY * 2) % CAPACITY];
    }

    /** How many frames the window holds. Zero before the second frame. */
    public int sampleCount() {
        return windowStart();
    }

    /** Frames per second over the window — the headline number, and an average rather than a reading. */
    public float fps() {
        int kept = windowStart();
        if (kept < 2) return 0f;
        long span = 0L;
        for (int i = 0; i < kept; i++) span += at(i, wallNanos);
        return span == 0L ? 0f : kept * 1_000_000_000f / span;
    }

    /** The last frame's own rate. Jumps about by design — what {@link #fps()} is smoothing. */
    public float instantFps() {
        float last = lastFrameMs();
        return last <= 0f ? 0f : 1000f / last;
    }

    public float lastFrameMs() {
        return size == 0 ? 0f : at(0, wallNanos) / 1_000_000f;
    }

    /** What the last frame spent working, as opposed to waiting. @see FrameStats — wall vs CPU */
    public float lastCpuMs() {
        return size == 0 ? 0f : at(0, cpuNanos) / 1_000_000f;
    }

    /** The fastest frame in the window. */
    public float bestMs() {
        int kept = windowStart();
        if (kept == 0) return 0f;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < kept; i++) best = Math.min(best, at(i, wallNanos));
        return best / 1_000_000f;
    }

    /** The slowest frame in the window — the one a person actually notices. */
    public float worstMs() {
        int kept = windowStart();
        if (kept == 0) return 0f;
        long worst = 0L;
        for (int i = 0; i < kept; i++) worst = Math.max(worst, at(i, wallNanos));
        return worst / 1_000_000f;
    }

    /** The window's frame times, sorted, in nanos — the scratch every order statistic below reads. */
    private long[] sortedWindow() {
        int kept = windowStart();
        long[] sorted = new long[kept];
        for (int i = 0; i < kept; i++) sorted[i] = at(i, wallNanos);
        java.util.Arrays.sort(sorted);
        return sorted;
    }

    /** The frame time {@code fraction} of the window is faster than — {@code 0.99} for the 99th. */
    public float percentileMs(double fraction) {
        long[] sorted = sortedWindow();
        if (sorted.length == 0) return 0f;
        int index = (int) Math.round(fraction * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1_000_000f;
    }

    /**
     * The rate the worst one percent of frames ran at — a benchmark's "1% low", and the number that
     * moves when a stall is fixed while the average does not.
     *
     * <p>An average of the slowest 1%, not the 99th percentile of one frame: a single sample is noise,
     * and the stutter being measured is a handful of frames rather than one.</p>
     */
    public float lowOnePercentFps() {
        return lowFps(0.01);
    }

    /** As {@link #lowOnePercentFps()}, for the worst tenth of a percent — a rarer, deeper stall. */
    public float lowTenthPercentFps() {
        return lowFps(0.001);
    }

    private float lowFps(double share) {
        long[] sorted = sortedWindow();
        if (sorted.length == 0) return 0f;
        int worst = Math.max(1, (int) Math.round(sorted.length * share));
        long total = 0L;
        for (int i = sorted.length - worst; i < sorted.length; i++) total += sorted[i];
        return total == 0L ? 0f : worst * 1_000_000_000f / total;
    }

    /** How many frames in the window missed {@link #budgetMs()}. */
    public int framesOverBudget() {
        int kept = windowStart();
        long budget = (long) (budgetMs * 1_000_000f);
        int missed = 0;
        for (int i = 0; i < kept; i++) {
            if (at(i, wallNanos) > budget) missed++;
        }
        return missed;
    }

    /** Milliseconds the collector took inside the window. A number here explains a spike nothing else does. */
    public long gcMillisInWindow() {
        int kept = windowStart();
        if (kept < 2) return 0L;
        return at(0, gcTotalMillis) - at(kept - 1, gcTotalMillis);
    }

    /** The last frame's phases, slowest first. Empty unless {@code -Dcrystalgui.frameprofile=true}. */
    public List<Map.Entry<String, Long>> phasesByCost() {
        List<Map.Entry<String, Long>> out = new ArrayList<>(phases.entrySet());
        out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return out;
    }

    /** The last frame's counts — draw calls, re-matched elements. Same condition as {@link #phasesByCost()}. */
    public Map<String, Integer> counts() {
        return new LinkedHashMap<>(counts);
    }

    // ── How healthy that is ─────────────────────────────────────────────────────────────────────

    /**
     * Green, amber or red — the only judgement this class makes, and it makes it once.
     *
     * <p>A readout is read at a glance or not at all, so the thresholds live here rather than in each
     * host: a harness scene, a game overlay and a log all call a 40ms frame the same thing. What a colour
     * IS stays in the sheet, which is why a {@link Row} carries this and not an ARGB.</p>
     */
    public enum Health {
        /**
         * Not a verdict at all — a row that states something rather than judging it.
         *
         * <p>A phase breakdown has no budget of its own to be measured against, and a hint is not a
         * measurement. Drawn in the readout's ordinary colour, because green on one of those reads as
         * "this part is healthy" about a row that never asked.</p>
         */
        NONE,
        /** Inside the budget. */
        GOOD,
        /** Past the budget and inside twice it — visible, not yet a stutter. */
        WARN,
        /** Past twice the budget: at 60Hz that is a frame that took two of them, which reads as a hitch. */
        BAD
    }

    /** Where {@code frameMs} falls against {@link #budgetMs()}. */
    public Health healthOf(float frameMs) {
        if (frameMs <= budgetMs) return Health.GOOD;
        return frameMs <= budgetMs * 2f ? Health.WARN : Health.BAD;
    }

    /** The window's own rate, judged against the budget it was given. */
    public Health rateHealth() {
        float fps = fps();
        // AGAINST THE AVERAGE FRAME, not the instant one: a single 20ms frame at 60Hz is not a red HUD.
        return fps <= 0f ? Health.GOOD : healthOf(1000f / fps);
    }

    /**
     * What the window's <b>typical bad frame</b> is worth — the 95th percentile, not the worst one.
     *
     * <h3>Judging the worst frame makes the verdict permanent</h3>
     *
     * <p>Measured on the desktop scene at a steady 117fps: every three-second window contains a frame
     * over twice the budget — a tooltip's first layout, a glyph reaching the atlas, one collection — so
     * a verdict taken from {@link #worstMs()} was red the whole time the readout was up. A colour that
     * is always on carries no information and is worse than none, because it trains the eye past it.</p>
     *
     * <p>The worst frame is still SHOWN. What it is not is the verdict, because one outlier in three
     * hundred frames is what a healthy application looks like.</p>
     */
    public Health spreadHealth() {
        return healthOf(percentileMs(0.95));
    }

    /**
     * How much of the window missed, as a verdict — a SHARE, with a dead zone.
     *
     * <p>Green below {@link #MISSES_WORTH_NOTING}, amber below {@link #MISSES_WORTH_WORRYING}, red above:
     * a couple of stutters in three seconds is what a healthy application looks like, and a verdict that
     * fired on one of them was amber permanently. A fifth of the window missing is the other end — by
     * then the misses are the normal case rather than an event.</p>
     */
    public Health missHealth() {
        int kept = sampleCount();
        if (kept == 0) return Health.GOOD;
        float missed = framesOverBudget() / (float) kept;
        if (missed < MISSES_WORTH_NOTING) return Health.GOOD;
        return missed < MISSES_WORTH_WORRYING ? Health.WARN : Health.BAD;
    }

    /** Below this share of missed frames, a window is healthy rather than merely imperfect. */
    private static final float MISSES_WORTH_NOTING = 0.02f;

    /** Above this share, the misses are the shape of the run rather than events in it. */
    private static final float MISSES_WORTH_WORRYING = 0.10f;

    // ── As text ─────────────────────────────────────────────────────────────────────────────────

    /** One line of the readout, and how bad what it says is. @see #rows */
    public record Row(String text, Health health) {
    }

    /**
     * The whole readout, one line per row, ready for any renderer.
     *
     * <pre>{@code
     * 118 fps   8.5ms wall   5.1ms cpu
     * 3s: best 6.2  p50 8.3  p95 12.0  worst 31.4
     * 1% low 42 fps   over 16.7ms: 4/354   GC 11ms
     * style 2103us  layout 1442us  paint 1210us
     * drawcalls=143 rematched=18
     * }</pre>
     */
    public List<String> lines() {
        List<Row> rows = rows();
        List<String> out = new ArrayList<>(rows.size());
        for (Row row : rows) out.add(row.text());
        return out;
    }

    /**
     * The readout, each row with its own verdict — what a coloured HUD draws.
     *
     * <pre>{@code
     * 118 fps   8.5ms wall   5.1ms cpu                        GOOD
     * 3s: best 6.2  p50 8.3  p95 12.0  worst 31.4             GOOD   (the p95, not the outlier)
     * 1% low 42 fps   over 16.7ms: 4/354   GC 11ms            GOOD   (1% of the window missed)
     * style 2.10ms  layout 1.44ms  paint 1.21ms               NONE   (a statement, not a verdict)
     * drawcalls=143 rematched=18                              NONE
     * }</pre>
     */
    public List<Row> rows() {
        return rows(Detail.SUMMARY);
    }

    /** How much of the breakdown a readout wants. @see #rows(Detail) */
    public enum Detail {
        /** The three verdict rows, with the phases collapsed onto one line. */
        SUMMARY,
        /**
         * A row per phase, with each one's share of the frame — what a readout expands to when the
         * summary has said there is something to look at.
         */
        FULL
    }

    /**
     * The readout at {@code detail}.
     *
     * <p>{@link Detail#FULL} spends the height on the breakdown rather than on more statistics, because
     * by the time somebody expands it they have read the summary and the question has changed from "is
     * this slow" to "what is slow":</p>
     *
     * <pre>{@code
     * 72 fps   13.7ms wall   13.1ms cpu
     * 3s: best 9.1  p50 13.3  p95 21.3  worst 56.2
     * 1% low 20 fps   over 16.7ms: 41/215   GC 5ms
     * phases 12.9ms of 13.1ms cpu
     *   style                6.20ms  48%
     *   layout               3.11ms  24%
     *   paint                2.40ms  19%
     *   gl:draw              1.18ms   9%
     * drawcalls=143 rematched=2143
     * }</pre>
     */
    public List<Row> rows(Detail detail) {
        List<Row> out = new ArrayList<>(8);
        if (sampleCount() < 2) {
            out.add(new Row("frame stats: warming up", Health.NONE));
            return out;
        }
        out.add(new Row(String.format(Locale.ROOT, "%.0f fps   %.1fms wall   %.1fms cpu",
                fps(), lastFrameMs(), lastCpuMs()), rateHealth()));
        out.add(new Row(String.format(Locale.ROOT, "%.0fs: best %.1f  p50 %.1f  p95 %.1f  worst %.1f",
                windowSeconds, bestMs(), percentileMs(0.5), percentileMs(0.95), worstMs()), spreadHealth()));
        StringBuilder third = new StringBuilder(String.format(Locale.ROOT,
                "1%% low %.0f fps   over %.1fms: %d/%d",
                lowOnePercentFps(), budgetMs, framesOverBudget(), sampleCount()));
        long gc = gcMillisInWindow();
        if (gc > 0) third.append(String.format(Locale.ROOT, "   GC %dms", gc));
        out.add(new Row(third.toString(), missHealth()));

        List<Map.Entry<String, Long>> byCost = phasesByCost();
        if (byCost.isEmpty()) {
            // Only a host that frames without painting gets here now -- the phases follow the readout
            // rather than the property. @see FrameProfile
            out.add(new Row("phases: none recorded this frame", Health.NONE));
            return out;
        }
        if (detail == Detail.FULL) addPhaseRows(out, byCost);
        else addPhaseLine(out, byCost);
        addCountRows(out, detail);
        return out;
    }

    /**
     * The counts, wrapped onto short rows.
     *
     * <h3>A readout may not decide how wide it is</h3>
     *
     * <p>These were one line, which was fine while two things counted themselves and ruinous the moment
     * the phase timing stopped needing a property: a real frame records twenty of them, the line came to
     * 300 characters, and the panel grew to the width of the window and drew itself over the menu bar.
     * A HUD that resizes itself to its noisiest frame is a HUD that covers the application it is
     * measuring, so the rows are cut to {@link #ROW_CHARS} here rather than left to a sheet — wrapping is
     * the one thing a fixed-width readout must never do, since a wrapped row moves every row under it.</p>
     */
    private void addCountRows(List<Row> out, Detail detail) {
        if (counts.isEmpty()) return;
        int limit = detail == Detail.FULL ? MAX_COUNT_ROWS : 1;
        StringBuilder row = new StringBuilder();
        int written = 0;
        // INSERTION ORDER, which is the order the frame recorded them in -- stable from frame to frame,
        // where sorting by value would shuffle the rows under the eye reading them.
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            String each = count.getKey() + '=' + count.getValue();
            if (row.length() > 0 && row.length() + each.length() + 1 > ROW_CHARS) {
                out.add(new Row(row.toString(), Health.NONE));
                row.setLength(0);
                if (++written == limit) return;
            }
            if (row.length() > 0) row.append(' ');
            row.append(each);
        }
        if (row.length() > 0) out.add(new Row(row.toString(), Health.NONE));
    }

    /** What a row is cut to. The three verdict rows are the widest thing here, and they fit. */
    private static final int ROW_CHARS = 46;

    /** Expanded, the counts get four rows; past that they are a log rather than a readout. */
    private static final int MAX_COUNT_ROWS = 4;

    /** The top few phases on one line — enough to say WHERE without spending five rows on it. */
    private void addPhaseLine(List<Row> out, List<Map.Entry<String, Long>> byCost) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < Math.min(4, byCost.size()); i++) {
            Map.Entry<String, Long> phase = byCost.get(i);
            if (phase.getValue() < PHASE_FLOOR_NANOS) break;
            String each = phase.getKey() + ' ' + millis(phase.getValue());
            // CUT TO THE SAME WIDTH AS EVERY OTHER ROW: a phase name here can be `frame:afterLayout`,
            // and three of those is a line half as wide again as the readout.
            if (line.length() + each.length() + 2 > ROW_CHARS) break;
            line.append(each).append("  ");
        }
        // NEUTRAL, both this and the counts: a phase's cost is only large RELATIVE to a budget this class
        // cannot attribute -- 4ms of layout is most of a 60Hz frame and nothing at all in a 30Hz one that
        // spent the rest waiting. Colouring them would be a verdict on evidence nobody has.
        if (line.length() > 0) out.add(new Row(line.toString().trim(), Health.NONE));
    }

    /**
     * A row per phase, with its share.
     *
     * <p><b>The share is of the phases' own total, not of the frame</b>, so the column adds to 100 and
     * says what it appears to say. Against the frame it would not: phases nest (a mark inside a marked
     * phase is counted twice) and the gaps between them are nobody's, so a column that summed to 140% of
     * one frame and 60% of the next would read as a broken profiler rather than as an honest total.</p>
     */
    private void addPhaseRows(List<Row> out, List<Map.Entry<String, Long>> byCost) {
        long total = 0L;
        for (Map.Entry<String, Long> phase : byCost) total += phase.getValue();
        if (total <= 0L) return;
        out.add(new Row(String.format(Locale.ROOT, "phases %.1fms of %.1fms cpu",
                total / 1_000_000f, lastCpuMs()), Health.NONE));
        for (int i = 0; i < Math.min(MAX_PHASE_ROWS, byCost.size()); i++) {
            Map.Entry<String, Long> phase = byCost.get(i);
            if (phase.getValue() < PHASE_FLOOR_NANOS) break;
            out.add(new Row(String.format(Locale.ROOT, "  %-18s %8s  %2.0f%%",
                    phase.getKey(), millis(phase.getValue()),
                    phase.getValue() * 100f / total), Health.NONE));
        }
    }

    /**
     * A duration as milliseconds, which is the unit every other number in the readout is in.
     *
     * <p>Two decimals because the floor is 0.1ms and one decimal would round half the tail to
     * {@code 0.1ms} — and the tail is where a phase breakdown earns its keep.</p>
     */
    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000f);
    }

    /** Under this a phase is noise, and a row of noise costs the same height as a row of finding. */
    private static final long PHASE_FLOOR_NANOS = 100_000L;

    /** Enough to hold a frame's real shape; past it the tail is all floor-level anyway. */
    private static final int MAX_PHASE_ROWS = 8;
}
