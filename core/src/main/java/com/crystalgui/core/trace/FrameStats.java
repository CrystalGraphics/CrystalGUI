package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * How fast the last few seconds actually ran — frame rate, the spread behind it, and what the slowest
 * frame was spent on.
 *
 * <p>A <b>view over {@link CgTrace}'s ring</b>, with no storage of its own: the numbers a frame-rate
 * readout is built from, with no UI and no GL, so the same collector serves a harness scene, an
 * in-game overlay and a headless bench. {@link com.crystalgui.widget.display.FrameStatsOverlay} is the
 * shipped display of it.</p>
 *
 * <pre>{@code
 * FrameStats stats = FrameStats.get();
 * stats.hold();                       // start collecting; release() when done
 * for (String line : stats.lines()) draw(line);
 *
 * stats.fps();                        // frames per second over the window
 * stats.worstMs();                    // the spike everyone actually notices
 * stats.lowOnePercentFps();           // the "1% low" a benchmark reports
 * }</pre>
 *
 * <h3>Collecting is opt-in, and nothing pays for it otherwise</h3>
 *
 * <p>{@link #hold()} enables CrystalGUI's trace channels and {@link #release()} drops them, counted so
 * two holders cannot switch each other off. While nothing holds it, the per-frame cost is one mask
 * test.</p>
 *
 * <h3>Wall time and CPU time are different questions</h3>
 *
 * <p>{@link #lastFrameMs()} is the interval between frame starts — what the frame rate is made of,
 * vsync and all. {@link #lastCpuMs()} is what the frame spent between the document's first line and the
 * painter's last. Under vsync the first is pinned at the refresh rate while the second says how much
 * headroom is left, and a readout showing only the first reports 60fps right up until it collapses.</p>
 *
 * <h3>The breakdown is the ring's, not a held copy</h3>
 *
 * <p>This class used to keep a 2048-deep ring of scalars and one peak breakdown that decayed, because
 * nothing else stored a second frame. The engine stores every frame's zones now, so
 * {@link #peakPhasesByCost()} is simply the slowest frame's zones — no peak to hold, no decay to get
 * right, and the breakdown belongs to the frame the row above it names.</p>
 */
public final class FrameStats {

    private static final FrameStats INSTANCE = new FrameStats();

    public static FrameStats get() {
        return INSTANCE;
    }

    private FrameStats() {
    }

    /** How many holders want samples. @see #hold */
    private int holders;

    /** Whether CrystalGUI's phases are being recorded. */
    public static boolean isCollecting() {
        return CgTrace.isEnabled(UiTrace.FRAME);
    }

    /** Starts collecting, or joins a collection already running. The first holder clears what was there. */
    public void hold() {
        if (++holders == 1) {
            // A FRESH RING ONLY WHEN NOTHING ELSE WAS RECORDING. The ring is one per process, and showing
            // the readout while the profiler recorded wiped the profiler's frames -- the kept start
            // included, which is the one thing that cannot be recorded again.
            if (!recordingSomething()) CgTrace.clear();
            // BY THE CHANNEL AND NOT BY NAME: a prefix only matches channels that have registered,
            // and a channel registers when its declaring class loads -- so the string form would
            // silently enable nothing if nothing had touched UiTrace yet. @see CgTrace#setEnabled
            //
            // NOT `blame`: it walks a stack per invalidation and is asked for by name.
            //
            // REMEMBERED, so release() undoes only what this did. The mask is one global shared with
            // the profiler window, and a release that switched off channels somebody else had switched
            // on left that window recording nothing while its button still said it was.
            enabledFrame = !CgTrace.isEnabled(UiTrace.FRAME);
            enabledFlow = !CgTrace.isEnabled(UiTrace.FLOW);
            CgTrace.setEnabled(UiTrace.FRAME, true);
            CgTrace.setEnabled(UiTrace.FLOW, true);
        }
    }

    /** Whether any channel that measures something is on — the engine's own channels do not count. */
    private static boolean recordingSomething() {
        for (String name : CgTrace.enabledNames()) {
            if (!CgTrace.isEngineOwn(name)) return true;
        }
        return false;
    }

    /** Drops one {@link #hold()}, switching off only the channels that hold switched on. */
    public void release() {
        if (holders > 0 && --holders == 0) {
            if (enabledFrame) CgTrace.setEnabled(UiTrace.FRAME, false);
            if (enabledFlow) CgTrace.setEnabled(UiTrace.FLOW, false);
            enabledFrame = false;
            enabledFlow = false;
        }
    }

    /** Whether the current hold turned each channel on, as opposed to finding it already on. */
    private boolean enabledFrame;
    private boolean enabledFlow;

    private float windowSeconds = 3f;

    /** A frame slower than this missed. 60Hz by default; a 120Hz host should say so. */
    private float budgetMs = 1000f / 60f;

    public float windowSeconds() {
        return windowSeconds;
    }

    /** How far back the derived numbers look. Frames already held are kept. */
    public FrameStats setWindowSeconds(float seconds) {
        windowSeconds = Math.max(0.1f, seconds);
        cachedAt = -1L;
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

    /** Drops every frame held. */
    public void reset() {
        CgTrace.clear();
        cachedAt = -1L;
    }

    // ── The window ──────────────────────────────────────────────────────────────────────────

    private List<CgFrameRecord> cached = List.of();
    private long[] cachedSorted = new long[0];
    private long cachedAt = -1L;

    /**
     * The frames that ended within {@link #windowSeconds}, oldest first.
     *
     * <p>Built once per committed frame and reused. A single row of the readout asks ten questions and
     * every one of them wants this list; rebuilding it per question would walk the ring ten times a
     * refresh for one answer.</p>
     */
    private List<CgFrameRecord> window() {
        long written = CgTrace.frameCount();
        if (written == cachedAt) return cached;
        List<CgFrameRecord> all = CgTrace.frames();
        List<CgFrameRecord> kept = new ArrayList<>(all.size());
        if (!all.isEmpty()) {
            long newest = all.get(all.size() - 1).endNanos();
            long cutoff = newest - (long) (windowSeconds * 1_000_000_000L);
            for (CgFrameRecord record : all) {
                if (record.endNanos() >= cutoff) kept.add(record);
            }
        }
        long[] sorted = new long[kept.size()];
        for (int i = 0; i < kept.size(); i++) sorted[i] = kept.get(i).wallNanos();
        Arrays.sort(sorted);
        cached = kept;
        cachedSorted = sorted;
        cachedAt = written;
        return cached;
    }

    @Nullable
    private CgFrameRecord newest() {
        List<CgFrameRecord> frames = window();
        return frames.isEmpty() ? null : frames.get(frames.size() - 1);
    }

    /** How many frames the window holds. Zero before the second frame. */
    public int sampleCount() {
        return window().size();
    }

    /** Frames per second over the window — the headline number, and an average rather than a reading. */
    public float fps() {
        List<CgFrameRecord> frames = window();
        if (frames.size() < 2) return 0f;
        long span = 0L;
        for (CgFrameRecord record : frames) span += record.wallNanos();
        return span == 0L ? 0f : frames.size() * 1_000_000_000f / span;
    }

    /** The last frame's own rate. Jumps about by design — what {@link #fps()} is smoothing. */
    public float instantFps() {
        float last = lastFrameMs();
        return last <= 0f ? 0f : 1000f / last;
    }

    public float lastFrameMs() {
        CgFrameRecord record = newest();
        return record == null ? 0f : (float) record.wallMillis();
    }

    /** What the last frame spent working, as opposed to waiting. @see FrameStats — wall vs CPU */
    public float lastCpuMs() {
        CgFrameRecord record = newest();
        return record == null || !record.hasCpu() ? 0f : (float) record.cpuMillis();
    }

    /** The fastest frame in the window. */
    public float bestMs() {
        window();
        return cachedSorted.length == 0 ? 0f : cachedSorted[0] / 1_000_000f;
    }

    /** The slowest frame in the window — the one a person actually notices. */
    public float worstMs() {
        window();
        return cachedSorted.length == 0 ? 0f : cachedSorted[cachedSorted.length - 1] / 1_000_000f;
    }

    /** The frame time {@code fraction} of the window is faster than — {@code 0.99} for the 99th. */
    public float percentileMs(double fraction) {
        window();
        if (cachedSorted.length == 0) return 0f;
        int index = (int) Math.round(fraction * (cachedSorted.length - 1));
        return cachedSorted[Math.max(0, Math.min(cachedSorted.length - 1, index))] / 1_000_000f;
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
        window();
        if (cachedSorted.length == 0) return 0f;
        int worst = Math.max(1, (int) Math.round(cachedSorted.length * share));
        long total = 0L;
        for (int i = cachedSorted.length - worst; i < cachedSorted.length; i++) total += cachedSorted[i];
        return total == 0L ? 0f : worst * 1_000_000_000f / total;
    }

    /** How many frames in the window missed {@link #budgetMs()}. */
    public int framesOverBudget() {
        window();
        long budget = (long) (budgetMs * 1_000_000f);
        int missed = 0;
        for (long each : cachedSorted) {
            if (each > budget) missed++;
        }
        return missed;
    }

    /** Milliseconds the collector took inside the window. A number here explains a spike nothing else does. */
    public long gcMillisInWindow() {
        long total = 0L;
        for (CgFrameRecord record : window()) total += record.gcMillis();
        return total;
    }

    /** Total time this JVM has spent collecting. A pause is charged to whatever ran. */
    static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long spent = collector.getCollectionTime();
            if (spent > 0) total += spent;
        }
        return total;
    }

    // ── The breakdown, straight off the ring ────────────────────────────────────────────────

    /** The last frame's phases, slowest first. @see #peakPhasesByCost() — the one worth reading. */
    public List<Map.Entry<String, Long>> phasesByCost() {
        return phasesOf(newest());
    }

    /** The last frame's counts — draw calls, layers, re-matched elements. @see #peakCounts() */
    public Map<String, Integer> counts() {
        return countsOf(newest());
    }

    /**
     * The slowest frame in the window, slowest phase first — what the expanded readout shows.
     *
     * <p>A readout refreshes ten times a second and a scene runs at a hundred, so the frame whose
     * phases are on screen is one arbitrary frame in ten — and the one worth reading is the spike,
     * which is never the one still showing by the time an eye reaches it.</p>
     *
     * <p>By CPU rather than by wall time, because wall includes waiting: under vsync the slowest wall
     * frame is usually the one that waited longest, and there is nothing in it to fix.</p>
     */
    public List<Map.Entry<String, Long>> peakPhasesByCost() {
        return phasesOf(peakFrame());
    }

    /** That same frame's counts — the draw calls and layers of the frame that cost, not of this one. */
    public Map<String, Integer> peakCounts() {
        return countsOf(peakFrame());
    }

    /** What the slowest frame in the window spent working. Zero when nothing has been recorded. */
    public float peakCpuMs() {
        CgFrameRecord record = peakFrame();
        return record == null || !record.hasCpu() ? 0f : (float) record.cpuMillis();
    }

    @Nullable
    private CgFrameRecord peakFrame() {
        CgFrameRecord worst = null;
        for (CgFrameRecord record : window()) {
            if (!record.hasCpu()) continue;
            if (worst == null || record.cpuNanos() > worst.cpuNanos()) worst = record;
        }
        return worst;
    }

    private static List<Map.Entry<String, Long>> phasesOf(@Nullable CgFrameRecord frame) {
        if (frame == null) return List.of();
        // INSERTION ORDER FIRST, then sorted by cost -- two zones of the same name in one frame are one
        // row, which is what makes seventeen `layer:clear` zones read as a phase rather than as a list.
        Map<String, Long> byName = new LinkedHashMap<>();
        for (CgTraceSnapshot.ZoneView zone : CgTrace.zonesIn(frame)) {
            byName.merge(zone.name(), zone.durationNanos(), Long::sum);
        }
        List<Map.Entry<String, Long>> out = new ArrayList<>(byName.entrySet());
        out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return out;
    }

    private static Map<String, Integer> countsOf(@Nullable CgFrameRecord frame) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (frame == null) return out;
        for (CgTraceSnapshot.CounterView counter : CgTrace.countersIn(frame)) {
            out.merge(counter.name(), (int) counter.value(), Integer::sum);
        }
        return out;
    }

    // ── How healthy that is ─────────────────────────────────────────────────────────────────

    /**
     * Green, amber or red — the only judgement this class makes, and it makes it once.
     *
     * <p>A readout is read at a glance or not at all, so the thresholds live here rather than in each
     * host: a harness scene, a game overlay and a log all call a 40ms frame the same thing. What a
     * colour IS stays in the sheet, which is why a {@link Row} carries this and not an ARGB.</p>
     */
    public enum Health {
        /**
         * Not a verdict at all — a row that states something rather than judging it.
         *
         * <p>A phase breakdown has no budget of its own to be measured against. Drawn in the readout's
         * ordinary colour, because green on one of those reads as "this part is healthy" about a row
         * that never asked.</p>
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
     * <p>Measured on the desktop scene at a steady 117fps: every three-second window contains a frame
     * over twice the budget — a tooltip's first layout, a glyph reaching the atlas, one collection — so
     * a verdict taken from {@link #worstMs()} was red the whole time the readout was up. A colour that
     * is always on carries no information and is worse than none, because it trains the eye past it.</p>
     *
     * <p>The worst frame is still SHOWN. What it is not is the verdict.</p>
     */
    public Health spreadHealth() {
        return healthOf(percentileMs(0.95));
    }

    /**
     * How much of the window missed, as a verdict — a SHARE, with a dead zone.
     *
     * <p>Green below {@link #MISSES_WORTH_NOTING}, amber below {@link #MISSES_WORTH_WORRYING}, red
     * above: a couple of stutters in three seconds is what a healthy application looks like, and a
     * verdict that fired on one of them was amber permanently.</p>
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

    // ── The bars ────────────────────────────────────────────────────────────────────────────

    public String sparkline(int columns) {
        return spark(columns).bars();
    }

    /**
     * The bars, and what each column is worth — the row, ready to be coloured a column at a time.
     *
     * <h3>Height is relative and colour is absolute, which is why colouring adds anything</h3>
     *
     * <p>A bar's height is its share of the window's own worst frame, so the tallest bar in a flawless
     * run is as tall as the tallest bar in a terrible one. The colour is the same verdict every other
     * row is judged by — {@link #healthOf} against {@link #budgetMs()} — so between them a glance
     * answers both questions at once.</p>
     */
    public Spark spark(int columns) {
        List<CgFrameRecord> frames = window();
        int kept = frames.size();
        if (kept < 2 || columns < 1) return Spark.NONE;
        long ceiling = Math.max((long) (budgetMs * 1_000_000f), 1L);
        for (CgFrameRecord record : frames) ceiling = Math.max(ceiling, record.wallNanos());
        StringBuilder bars = new StringBuilder(columns);
        List<Health> health = new ArrayList<>(columns);
        for (int column = 0; column < columns; column++) {
            int from = (int) ((long) column * kept / columns);
            int to = Math.min(kept, Math.max(from + 1, (int) ((long) (column + 1) * kept / columns)));
            long worst = 0L;
            // THE MAX OF THE BUCKET, NEVER ITS MEAN. A spike is one frame in thirty, and averaging a
            // bucket is precisely what erases it -- which is the one thing this row exists not to do.
            for (int i = from; i < to; i++) worst = Math.max(worst, frames.get(i).wallNanos());
            int level = (int) (worst * BARS.length / ceiling);
            bars.append(BARS[Math.max(0, Math.min(BARS.length - 1, level))]);
            health.add(healthOf(worst / 1_000_000f));
        }
        return new Spark(bars.toString(), health);
    }

    /**
     * The frame {@code column} of a {@code columns}-wide {@link #spark} stands for — the worst in its
     * bucket, which is the bar drawn — as its {@link CgFrameRecord#index()}, or -1.
     */
    public long frameIndexAt(int column, int columns) {
        List<CgFrameRecord> frames = window();
        int kept = frames.size();
        if (kept < 2 || columns < 1 || column < 0 || column >= columns) return -1L;
        int from = (int) ((long) column * kept / columns);
        int to = Math.min(kept, Math.max(from + 1, (int) ((long) (column + 1) * kept / columns)));
        CgFrameRecord worst = frames.get(from);
        for (int i = from; i < to; i++) {
            if (frames.get(i).wallNanos() > worst.wallNanos()) worst = frames.get(i);
        }
        return worst.index();
    }

    /** {@link #spark}'s answer: the bars, and one verdict per bar. */
    public record Spark(String bars, List<Health> health) {

        /** Nothing to draw yet — fewer than two frames. */
        public static final Spark NONE = new Spark("", List.of());
    }

    /**
     * The bars, lightest first.
     *
     * <p>A short bar rather than a space for the fastest frames: a gap reads as a frame that did not
     * happen. Every one of these is in {@code JetBrainsMono-Regular.ttf}, checked against the font's
     * own character map rather than assumed.</p>
     */
    private static final char[] BARS = {'▁', '▂', '▃', '▄',
                                        '▅', '▆', '▇', '█'};

    // ── As text ─────────────────────────────────────────────────────────────────────────────

    /**
     * One line of the readout, and how bad what it says is.
     *
     * <p>{@code barHealth} is null for every row but the sparkline, where it carries a verdict per
     * CHARACTER — so a renderer that can colour a text range paints the spike red inside an otherwise
     * green row, and one that cannot ignores it and loses nothing but the colour.</p>
     */
    public record Row(String text, Health health, @Nullable List<Health> barHealth) {

        public Row(String text, Health health) {
            this(text, health, null);
        }
    }

    /** The whole readout, one line per row, ready for any renderer. */
    public List<String> lines() {
        List<Row> rows = rows();
        List<String> out = new ArrayList<>(rows.size());
        for (Row row : rows) out.add(row.text());
        return out;
    }

    /** The readout, each row with its own verdict — what a coloured HUD draws. */
    public List<Row> rows() {
        return rows(Detail.SUMMARY);
    }

    /** How much of the breakdown a readout wants. @see #rows(Detail) */
    public enum Detail {
        /** The three verdict rows, the bars, and the phases collapsed onto one line. */
        SUMMARY,
        /** A row per phase, with each one's share — what a readout expands to. */
        FULL
    }

    /**
     * The readout at {@code detail}.
     *
     * <pre>{@code
     * 116 fps   8.3ms wall   8.1ms cpu
     * 3s: best 5.7  p50 8.1  p95 9.6  worst 35.1
     * 1% low 29 fps   over 16.7ms: 6/348   GC 4ms
     * ▁▁▂▁▁█▁▂▁▁▁▃▁▁▁▂▁▁▁▁▁▁▂▁▁▁▁▁▁▂▁▁▁▁▁▁▁▂▁▁▁▁▁▁
     * paint:tree 6.31ms  frame:layout 1.01ms
     * drawcalls=31 layers=17
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

        Spark spark = spark(ROW_CHARS);
        // THE ROW ITSELF IS NEUTRAL and its characters are not: a verdict on the whole row would be a
        // fourth answer to a question the three rows above have already answered three ways.
        out.add(new Row(spark.bars(), Health.NONE, spark.health()));

        // EXPANDED, THE BREAKDOWN IS THE SLOWEST FRAME'S. Collapsed it is the last frame's, which is
        // what a live one-line hint should be; the moment somebody expands it the question has changed
        // from "what is this frame doing" to "what went wrong", and that frame is already gone.
        List<Map.Entry<String, Long>> byCost =
                detail == Detail.FULL ? peakPhasesByCost() : phasesByCost();
        if (byCost.isEmpty()) {
            out.add(new Row("phases: none recorded this frame", Health.NONE));
            return out;
        }
        if (detail == Detail.FULL) addPhaseRows(out, byCost);
        else addPhaseLine(out, byCost);
        addCountRows(out, detail, detail == Detail.FULL ? peakCounts() : counts());
        return out;
    }

    /**
     * The counts, wrapped onto short rows.
     *
     * <p>A readout may not decide how wide it is: a real frame records twenty counters, and one line of
     * them came to 300 characters and grew the panel across the menu bar. Wrapping is the one thing a
     * fixed-width readout must never do, since a wrapped row moves every row under it.</p>
     */
    private void addCountRows(List<Row> out, Detail detail, Map<String, Integer> from) {
        if (from.isEmpty()) return;
        int limit = detail == Detail.FULL ? MAX_COUNT_ROWS : 1;
        StringBuilder row = new StringBuilder();
        int written = 0;
        for (Map.Entry<String, Integer> count : from.entrySet()) {
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
            if (line.length() + each.length() + 2 > ROW_CHARS) break;
            line.append(each).append("  ");
        }
        // NEUTRAL, both this and the counts: a phase's cost is only large RELATIVE to a budget this
        // class cannot attribute. Colouring them would be a verdict on evidence nobody has.
        if (line.length() > 0) out.add(new Row(line.toString().trim(), Health.NONE));
    }

    /**
     * A row per phase, with its share.
     *
     * <p><b>The share is of the phases' own total, not of the frame</b>, so the column adds to 100 and
     * says what it appears to say. Against the frame it would not: phases nest and the gaps between
     * them are nobody's, so a column that summed to 140% of one frame and 60% of the next would read as
     * a broken profiler rather than as an honest total.</p>
     */
    private void addPhaseRows(List<Row> out, List<Map.Entry<String, Long>> byCost) {
        long total = 0L;
        for (Map.Entry<String, Long> phase : byCost) total += phase.getValue();
        if (total <= 0L) return;
        // NAMED, because this is no longer the frame on screen: a breakdown that silently described a
        // different frame from the row above it would be read as the row above it.
        out.add(new Row(String.format(Locale.ROOT, "slowest %.1fms cpu: phases %.1fms",
                peakCpuMs(), total / 1_000_000f), Health.NONE));
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
