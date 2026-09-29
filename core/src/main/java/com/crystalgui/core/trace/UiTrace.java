package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceLog;
import com.crystalgraphics.trace.CgTraceReport;
import com.crystalgui.core.CrystalGuiCore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * CrystalGUI's trace channels — what {@code enable("crystalgui")} turns on.
 *
 * <pre>{@code
 * CgTrace.enable("crystalgui");            // all three
 * CgTrace.enable("crystalgui.frame");      // the frame phases alone
 * CgTrace.disable("crystalgui.blame");     // the expensive one
 * }</pre>
 *
 * <h3>Three, because they cost different amounts</h3>
 *
 * <p>A channel is the unit somebody opts in to, so the split follows price rather than subject.
 * {@link #FRAME} and {@link #FLOW} are two clock reads apiece; {@link #BLAME} walks a stack per
 * invalidation and was once a quarter of the frames it measured, which is exactly the kind of thing
 * that has to be asked for by name.</p>
 *
 * <h3>Recording on them</h3>
 *
 * <pre>{@code
 * long t = CgTrace.stamp(UiTrace.FRAME);             // a phase: 0 while the channel is off
 * layout();
 * CgTrace.zoneDone(UiTrace.FRAME, "frame:layout", t);
 * CgTrace.add(UiTrace.FRAME, "drawcalls", 1);         // a per-frame count
 *
 * long span = CgTrace.spanBegin(UiTrace.FLOW, "open:file");   // a chain across frames
 * CgTrace.spanEnd(span);
 *
 * UiTrace.blame("rematch");                           // who asked, on the blame channel
 * }</pre>
 *
 * <p>A host brackets each frame with {@link #frameBegin()} and {@link #frameEnd()}.</p>
 */
public final class UiTrace {

    private UiTrace() {
    }

    /** Phases and counters inside a frame: {@code paint:tree}, {@code frame:layout}, {@code drawcalls}. */
    public static final CgTraceChannel FRAME = CgTrace.channel("crystalgui.frame");

    /**
     * Chains that are not frames — opening a file, a search, a document parse.
     *
     * <p>Separate from {@link #FRAME} because it answers a different question. A chain spans frames or
     * happens outside any, and somebody profiling a paint has no use for it.</p>
     */
    public static final CgTraceChannel FLOW = CgTrace.channel("crystalgui.flow");

    /** Who asked for the work — a stack walk per invalidation, so it is opted into on its own. */
    public static final CgTraceChannel BLAME = CgTrace.channel("crystalgui.blame");

    static {
        // HERE, because this class is loaded by anything that records a CrystalGUI zone -- so the
        // accusations exist wherever the counters do, without a host being told to install them.
        UiHints.install();
        // THE LOG PROPERTY STILL MEANS "show me slow frames": only an enabled channel records, so it
        // switches the frame channel on rather than leaving the line with no breakdown.
        if (SlowFrameLog.ECHO) CgTrace.setEnabled(FRAME, true);
    }

    // ── The frame boundary ──────────────────────────────────────────────────────────────────

    /**
     * The very top of a frame, from the host that owns the frame loop.
     *
     * <p>The engine commits the PREVIOUS frame here, since a frame's wall time is the interval to the next
     * one — and that frame's slow-frame line is written now, when its counters have landed.</p>
     */
    public static void frameBegin() {
        CgTrace.frameBegin();
        SlowFrameLog.frameCommitted();
    }

    /** The painter's last line: this frame's blame as markers, and the engine's CPU mark. */
    public static void frameEnd() {
        flushBlame();
        CgTrace.frameEnd();
    }

    // ── Blame ───────────────────────────────────────────────────────────────────────────────

    /**
     * Blames the CALLER for one occurrence of {@code what} — the probe that names a churn source.
     *
     * <pre>{@code
     * UiTrace.blame("rematch", "com.crystalgui.style.");   // skip the bookkeeping's own frames
     * }</pre>
     *
     * <p>A count says three hundred elements were re-matched; it cannot say who asked. This walks up to
     * the first frame outside the given packages and counts that, so the frame carries
     * {@code Tooltip.reposition:214 x280} rather than {@code rematched=300}. A stack walk per call, so it
     * records only while {@link #BLAME} is on.</p>
     */
    public static void blame(String what, String... ignorePackages) {
        if (!CgTrace.isEnabled(BLAME)) return;
        // CAPPED: a frame that re-matches two thousand elements would make two thousand walks, and the
        // probe was a quarter of the frames it measured. Every call is attributed up to a cap, then one
        // in a stride stands for the stride, so the totals hold and the cost does not.
        int nth = blamesThisFrame++;
        int weight = 1;
        if (nth >= BLAME_EXACTLY) {
            if ((nth - BLAME_EXACTLY) % BLAME_STRIDE != 0) return;
            weight = BLAME_STRIDE;
        }
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String at = frame.getClassName();
            if (at.equals(UiTrace.class.getName()) || ignored(at, ignorePackages)) continue;
            int dot = at.lastIndexOf('.');
            SITES.merge((dot < 0 ? at : at.substring(dot + 1)) + '.' + frame.getMethodName() + ':'
                    + frame.getLineNumber(), weight, Integer::sum);
            return;
        }
        SITES.merge(what + "(unattributed)", weight, Integer::sum);
    }

    private static boolean ignored(String className, String[] packages) {
        for (String each : packages) {
            if (className.startsWith(each)) return true;
        }
        return false;
    }

    /**
     * This frame's blame as markers, top sites first — ONE per site, since two thousand markers a frame
     * would fill the ring with the evidence for a single finding.
     */
    private static void flushBlame() {
        if (!SITES.isEmpty() && CgTrace.isEnabled(BLAME)) {
            List<Map.Entry<String, Integer>> sites = new ArrayList<>(SITES.entrySet());
            sites.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(MAX_BLAME_MARKERS, sites.size()); i++) {
                CgTrace.marker(BLAME, "invalidated-by", sites.get(i).getKey() + " x" + sites.get(i).getValue());
            }
        }
        SITES.clear();
        blamesThisFrame = 0;
    }

    /** Past this the list is a log rather than a finding. */
    private static final int MAX_BLAME_MARKERS = 10;
    /** Attributed one by one each frame up to this, then one in a stride. */
    private static final int BLAME_EXACTLY = 256;
    private static final int BLAME_STRIDE = 16;

    private static final Map<String, Integer> SITES = new LinkedHashMap<>();
    private static int blamesThisFrame;

    /** Whether anything in CrystalGUI is recording. */
    public static boolean isRecording() {
        return CgTrace.isEnabled(FRAME) || CgTrace.isEnabled(FLOW) || CgTrace.isEnabled(BLAME);
    }

    /** {@code -Dcrystalgui.trace.dir=<path>} — a run directory for a host that has no cache root. */
    public static final String DIR_PROPERTY = "crystalgui.trace.dir";

    /**
     * Points this run's output at {@code cacheRoot}, which a host gives at startup.
     *
     * <pre>{@code
     * UiTrace.useCacheRoot(StorageLayout.cacheIn(gameDirectory));   // a host, at startup
     * }</pre>
     *
     * <p>{@code cache/} is the right tier by the layout's own rule — deletable at any moment — and the
     * engine keeps the last few runs, so a before-and-after comparison survives a restart. A null root
     * changes nothing, so a caller that does not know one need not check.</p>
     *
     * <p>Idempotent: a run is a process lifetime rather than a profiling session, so a second call is a
     * no-op and the directory cannot change under a reader.</p>
     */
    public static void useCacheRoot(@Nullable Path cacheRoot) {
        if (cacheRoot == null) return;
        CgTraceLog.useRoot(cacheRoot.resolve("trace"));
        TraceFiles.useRunRoot(cacheRoot.resolve("trace"));
    }

    /**
     * Opens the run directory from {@link #DIR_PROPERTY} if a host has not given one.
     *
     * <p>For the harness and for a test, where there is no game directory to hang a cache off. Does
     * nothing when the property is unset, which is the normal case in production.</p>
     */
    public static void useDirectoryProperty() {
        String configured = System.getProperty(DIR_PROPERTY);
        if (configured == null || configured.isEmpty()) return;
        CgTraceLog.useRoot(Paths.get(configured));
        TraceFiles.useRunRoot(Paths.get(configured));
    }

    /**
     * Writes one line into this run's {@code trace.log}, from any thread, without blocking.
     *
     * <p>Never the game console: that is a synchronous hop through the loader's log pipeline, and a
     * probe that reports a slow frame by blocking the frame thread on terminal I/O has changed the
     * thing it was measuring.</p>
     */
    public static void log(String line) {
        CgTraceLog.line(line);
    }

    /**
     * Writes the tiered text report beside the log, and says where it went.
     *
     * <p><b>The surface an agent reads.</b> The loop is: run the scene, read one file, edit, run again,
     * {@code diff} — which needs a path that does not change, and {@link CgTraceLog#LATEST} is it.</p>
     *
     * @return the file written, or null when no run directory was given
     */
    @Nullable
    public static Path writeReport(CgTraceReport.Tier tier) {
        Path dir = CgTraceLog.dir();
        if (dir == null) return null;
        String text = CgTraceReport.of(CgTrace.snapshot())
                .budget(FrameStats.get().budgetMs())
                .render(tier);
        CgTraceLog.write("report.txt", text);
        return dir.resolve("report.txt");
    }

    /** {@link #writeReport} at the tier {@code -Dcrystalgraphics.trace.report} names, if it names one. */
    public static void writeReportIfAsked() {
        String asked = System.getProperty("crystalgraphics.trace.report");
        if (asked == null || asked.isEmpty()) return;
        for (CgTraceReport.Tier tier : CgTraceReport.Tier.values()) {
            if (tier.name().equalsIgnoreCase(asked)) {
                writeReport(tier);
                return;
            }
        }
        writeReport(CgTraceReport.Tier.VERDICT);
    }

    /**
     * Records what this run was, beside its log — so a partial trace cannot be read as a complete one.
     *
     * <p>Channels recording, channels NOT recording, the frames held and anything the writer dropped.
     * A reader who cannot see which channels were off will read a gap in the timeline as work that did
     * not happen, which is the one way a trace lies without being wrong.</p>
     */
    public static void writeMeta() {
        if (CgTraceLog.dir() == null) return;
        StringBuilder out = new StringBuilder(256);
        out.append("{\n  \"run\": \"").append(CgTraceLog.runId()).append("\",\n");
        out.append("  \"frames\": ").append(CgTrace.frameCount()).append(",\n");
        out.append("  \"droppedLines\": ").append(CgTraceLog.dropped()).append(",\n");
        // AND DROPPED ZONES, which nothing surfaced until this review. A reader who cannot see that
        // the arena overflowed will read a short frame as a fast one.
        out.append("  \"droppedZones\": ").append(CgTrace.droppedZones()).append(",\n");
        out.append("  \"recording\": [");
        List<String> on = CgTrace.enabledNames();
        for (int i = 0; i < on.size(); i++) {
            if (i > 0) out.append(", ");
            out.append('"').append(on.get(i)).append('"');
        }
        out.append("],\n  \"notRecording\": [");
        boolean first = true;
        for (CgTraceChannel channel : CgTrace.channels()) {
            if (channel.isEnabled()) continue;
            if (!first) out.append(", ");
            first = false;
            out.append('"').append(channel.name()).append('"');
        }
        out.append("]\n}\n");
        CgTraceLog.write("meta.json", out.toString());
    }
}
