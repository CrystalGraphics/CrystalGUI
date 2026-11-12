package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceLog;
import com.crystalgraphics.trace.CgTraceRun;
import com.crystalgui.core.CrystalGuiCore;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
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
    static {
        CgTraceRun.budget(() -> FrameStats.compositor().budgetMs());
    }

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

    /** The workspace server's filesystem work: a watcher poll, each stat and its path checks. */
    public static final CgTraceChannel FS = CgTrace.channel("crystalgui.fs");

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
        allocatedAtBegin = CgTrace.isEnabled(FRAME) ? allocatedByThisThread() : -1L;
    }

    /** The painter's last line: this frame's blame as markers, and the engine's CPU mark. */
    public static void frameEnd() {
        flushBlame();
        // What the frame thread allocated in the frame, which is what a GC pause is paid for. Read here and not
        // after the idle, which allocates nothing of the frame's.
        if (allocatedAtBegin >= 0L) {
            long now = allocatedByThisThread();
            if (now >= 0L) CgTrace.counter(FRAME, "alloc-kb", (now - allocatedAtBegin) >> 10);
        }
        CgTrace.frameEnd();
    }

    /** Bytes this thread has allocated so far, or -1 where the JVM does not say. */
    @SuppressWarnings("deprecation") // Thread.getId: threadId() is Java 19, and a Java 8 copy of this class ships
    private static long allocatedByThisThread() {
        return ALLOCATION == null ? -1L : ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    @Nullable
    private static final ThreadMXBean ALLOCATION = allocationBean();

    @Nullable
    private static ThreadMXBean allocationBean() {
        var threads = ManagementFactory.getThreadMXBean();
        return threads instanceof ThreadMXBean hotspot && hotspot.isThreadAllocatedMemorySupported() ? hotspot : null;
    }

    /** {@link #allocatedByThisThread} at the top of the frame, or -1 while the frame channel is off. */
    private static long allocatedAtBegin = -1L;

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
            blame(what + '@' + (dot < 0 ? at : at.substring(dot + 1)) + '.' + frame.getMethodName() + ':'
                    + frame.getLineNumber(), weight);
            return;
        }
        blame(what + "(unattributed)", weight);
    }

    private static void blame(String site, int weight) {
        synchronized (SITES) {
            SITES.merge(site, weight, Integer::sum);
        }
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
        List<Map.Entry<String, Integer>> sites;
        synchronized (SITES) {
            sites = SITES.isEmpty() ? List.of() : new ArrayList<>(SITES.entrySet());
            SITES.clear();
        }
        if (!sites.isEmpty() && CgTrace.isEnabled(BLAME)) {
            sites.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(MAX_BLAME_MARKERS, sites.size()); i++) {
                CgTrace.marker(BLAME, "invalidated-by", sites.get(i).getKey() + " x" + sites.get(i).getValue());
            }
        }
        blamesThisFrame = 0;
    }

    /** Past this the list is a log rather than a finding. */
    private static final int MAX_BLAME_MARKERS = 10;
    /** Attributed one by one each frame up to this, then one in a stride. */
    private static final int BLAME_EXACTLY = 256;
    private static final int BLAME_STRIDE = 16;

    // Threads: guarded by its own monitor (blame is a diagnostic, off by default).
    private static final Map<String, Integer> SITES = new LinkedHashMap<>();
    private static int blamesThisFrame;

    /** Whether anything in CrystalGUI is recording. */
    public static boolean isRecording() {
        return CgTrace.isEnabled(FRAME) || CgTrace.isEnabled(FLOW) || CgTrace.isEnabled(BLAME);
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
}
