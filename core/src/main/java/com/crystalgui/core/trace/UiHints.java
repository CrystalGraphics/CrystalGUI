package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgraphics.trace.CgTraceHints;
import com.crystalgraphics.trace.CgTraceNames;
import com.crystalgraphics.trace.CgTraceSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a slow CrystalGUI frame is accused of — the rules over this engine's own counter vocabulary.
 *
 * <pre>{@code
 * UiHints.install();                                   // once; UiTrace does it
 * CgTraceHints.forFrame(frame, tree, counters);        // what the report and the window both read
 * }</pre>
 *
 * <p>Here rather than in the engine because a hint is a rule over counter NAMES, and
 * {@code retain-dynamic} and {@code layer-clear-kpx} mean something to whoever wrote the painter and
 * nothing to a capture engine.</p>
 *
 * <p><b>Every hint links somewhere</b>, to a zone or a counter wherever there is one, so the window can
 * take the reader to it: a finding with nowhere to go next is a complaint.</p>
 */
public final class UiHints {

    private UiHints() {
    }

    /** Megapixels of layer traffic past which a frame is worth accusing. */
    private static final long LAYER_KPX_FLOOR = 8_000L;

    /** Draw calls below which that traffic cannot be explained by the amount being drawn. */
    private static final long DRAWCALLS_CEILING = 150L;

    /** Re-matched elements past which the cascade is churning rather than working. */
    private static final long REMATCH_FLOOR = 500L;

    /** How long a zone must take on its first run to be worth naming. */
    private static final long FIRST_DRAW_FLOOR_NANOS = 1_000_000L;

    /** How much of a frame a worker must overlap before the frame is said to have waited on it. */
    private static final long WORKER_OVERLAP_FLOOR_NANOS = 500_000L;

    /** The marker blame writes, heaviest site first. @see FrameProfile */
    private static final String BLAME_MARKER = "invalidated-by";

    private static boolean installed;

    /** Idempotent — a second call adds nothing, so a rule cannot fire twice. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;

        // LAYER-BOUND. The finding the whole trace engine was built to make sayable: megapixels of
        // clear and blit against a two-digit draw count is an FBO layer pass, not geometry.
        CgTraceHints.register((frame, zones, counters, out) -> {
            long kpx = counters.getOrDefault("layer-clear-kpx", 0L)
                    + counters.getOrDefault("layer-blit-kpx", 0L);
            long draws = counters.getOrDefault("drawcalls", 0L);
            if (kpx >= LAYER_KPX_FLOOR && draws > 0L && draws <= DRAWCALLS_CEILING) {
                out.add(new CgTraceHints.Hint("LAYER-BOUND",
                        String.format("%.1fMpx of layer clear and blit for %d draw calls "
                                + "(docs/CGUI_STYLE_RENDER_PIPELINE.md §8)", kpx / 1000d, draws),
                        CgTraceHints.Hint.counter("layer-clear-kpx")));
            }
        });

        // RETENTION-REFUSED. A readout showing many layers and no reuse reads as a broken cache; most
        // of the time nothing asked it, because a subtree that repaints itself may not be kept.
        CgTraceHints.register((frame, zones, counters, out) -> {
            long dynamic = counters.getOrDefault("retain-dynamic", 0L);
            long layers = counters.getOrDefault("layers", 0L);
            if (layers > 0L && dynamic * 2L >= layers) {
                out.add(new CgTraceHints.Hint("RETENTION-REFUSED",
                        dynamic + " of " + layers + " layers were never offered to the cache"
                                + " (paintsDynamically, or a backdrop-filter)",
                        CgTraceHints.Hint.counter("retain-dynamic")));
            }
        });

        // CASCADE-CHURN. "Style is slow" is not actionable; a count with a call site is.
        CgTraceHints.register((frame, zones, counters, out) -> {
            long rematched = counters.getOrDefault("rematched", 0L);
            if (rematched < REMATCH_FLOOR) return;
            String site = topBlamedSite(frame);
            out.add(new CgTraceHints.Hint("CASCADE-CHURN",
                    rematched + " elements re-matched this frame" + (site != null
                            ? ", most from " + site
                            : "; turn on crystalgui.blame to name the call site"),
                    CgTraceHints.Hint.counter("rematched")));
        });

        // COLLECTED. A pause stops every thread and is charged to whichever phase was running, which is
        // indistinguishable from that phase being slow. Say so, and name the phase likely wearing it.
        CgTraceHints.register((frame, zones, counters, out) -> {
            if (!frame.hadGc()) return;
            CgTraceAggregate.Node heaviest = heaviestOnFrameThread(zones);
            out.add(new CgTraceHints.Hint("COLLECTED",
                    frame.gcSummary() + " of collection inside this frame" + (heaviest != null
                            ? ": " + heaviest.name() + String.format(" (%.2f ms)", heaviest.millis())
                            + " may be wearing it"
                            : ": the phase breakdown is not trustworthy"),
                    heaviest != null ? CgTraceHints.Hint.zone(heaviest.name()) : "the GC tick on the strip"));
        });

        // ICONS-DIRECT. An icon is meant to rasterise once and draw as a tinted quad; the direct path
        // is a draw per scanline cell.
        CgTraceHints.register((frame, zones, counters, out) -> {
            long direct = counters.getOrDefault("svg-direct", 0L);
            if (direct > 0L) {
                out.add(new CgTraceHints.Hint("ICONS-DIRECT",
                        direct + " icon draws bypassed the raster cache (SvgRasterCache.accepts)",
                        CgTraceHints.Hint.counter("svg-direct")));
            }
        });

        // FIRST-DRAW. A shader compiled, an atlas built, a font loaded -- the frame is slow ONCE, and
        // nothing in its tree says that this was the first time. Flutter's shader-jank hint, generalised
        // to any zone.
        CgTraceHints.register((frame, zones, counters, out) -> {
            List<CgTraceAggregate.Node> first = new ArrayList<>();
            for (CgTraceAggregate.Node node : flatten(zones)) {
                long seen = CgTraceNames.firstSeenNanos(node.name());
                if (seen >= frame.beginNanos() && seen < frame.endNanos()
                        && node.durationNanos() >= FIRST_DRAW_FLOOR_NANOS) {
                    first.add(node);
                }
            }
            if (first.isEmpty()) return;
            first.sort((a, b) -> Long.compare(b.durationNanos(), a.durationNanos()));
            CgTraceAggregate.Node worst = first.get(0);
            out.add(new CgTraceHints.Hint("FIRST-DRAW",
                    String.format("%s ran for the first time in this frame and took %.2f ms", worst.name(),
                            worst.millis()) + (first.size() > 1 ? " (" + (first.size() - 1) + " more did too)" : ""),
                    CgTraceHints.Hint.zone(worst.name())));
        });

        // GPU-BOUND. Nothing in a CPU profile hints at it, which is the GPU timer's whole reason to exist.
        CgTraceHints.register((frame, zones, counters, out) -> {
            if (frame.hasGpu() && frame.hasCpu() && frame.gpuNanos() > frame.cpuNanos()) {
                out.add(new CgTraceHints.Hint("GPU-BOUND",
                        String.format("the GPU took %.2f ms against %.2f ms of CPU: shortening the CPU zones "
                                + "will not make this frame faster", frame.gpuMillis(), frame.cpuMillis()),
                        largestGpuZone(counters)));
            }
        });

        // BLOCKED-ON-WORKER. A worker busy through the frame, and jobs still running at its end: the frame
        // thread's own zones are short, and the time went to waiting.
        CgTraceHints.register((frame, zones, counters, out) -> {
            long busy = counters.getOrDefault("jobs-busy", 0L);
            if (busy <= 0L) return;
            String frameThread = frameThreadName();
            CgTraceAggregate.Node longest = null;
            long longestOverlap = 0L;
            for (CgTraceAggregate.Node root : zones) {
                if (frameThread != null && frameThread.equals(root.thread())) continue;
                long overlap = Math.min(root.endNanos(), frame.endNanos())
                        - Math.max(root.startNanos(), frame.beginNanos());
                if (overlap > longestOverlap) {
                    longestOverlap = overlap;
                    longest = root;
                }
            }
            if (longest == null || longestOverlap < WORKER_OVERLAP_FLOOR_NANOS) return;
            out.add(new CgTraceHints.Hint("BLOCKED-ON-WORKER",
                    String.format("%s on %s ran for %.2f ms of this frame with %d job%s still busy",
                            longest.name(), longest.thread(), longestOverlap / 1_000_000d, busy, busy == 1 ? "" : "s"),
                    CgTraceHints.Hint.zone(longest.name())));
        });
    }

    /** The heaviest blamed call site in {@code frame}, from blame's markers, or null. */
    @Nullable
    private static String topBlamedSite(CgFrameRecord frame) {
        for (CgTraceSnapshot.MarkerView marker : CgTrace.markersIn(frame)) {
            if (BLAME_MARKER.equals(marker.name()) && marker.detail() != null) return marker.detail();
        }
        return null;
    }

    @Nullable
    private static CgTraceAggregate.Node heaviestOnFrameThread(List<CgTraceAggregate.Node> roots) {
        String frameThread = frameThreadName();
        CgTraceAggregate.Node heaviest = null;
        for (CgTraceAggregate.Node root : roots) {
            if (frameThread != null && !frameThread.equals(root.thread())) continue;
            if (heaviest == null || root.durationNanos() > heaviest.durationNanos()) heaviest = root;
        }
        return heaviest;
    }

    @Nullable
    private static String frameThreadName() {
        Thread thread = CgTrace.frameThread();
        return thread == null ? null : thread.getName();
    }

    /** A link to the frame's costliest GPU zone, or null when none resolved. */
    @Nullable
    private static String largestGpuZone(Map<String, Long> counters) {
        String worst = null;
        long most = -1L;
        for (Map.Entry<String, Long> e : counters.entrySet()) {
            if (e.getKey().startsWith(CgGpuTrace.PREFIX) && e.getValue() > most) {
                worst = e.getKey();
                most = e.getValue();
            }
        }
        return worst == null ? null : CgTraceHints.Hint.counter(worst);
    }

    private static List<CgTraceAggregate.Node> flatten(List<CgTraceAggregate.Node> roots) {
        List<CgTraceAggregate.Node> all = new ArrayList<>();
        List<CgTraceAggregate.Node> todo = new ArrayList<>(roots);
        while (!todo.isEmpty()) {
            CgTraceAggregate.Node node = todo.remove(todo.size() - 1);
            all.add(node);
            todo.addAll(node.children());
        }
        return all;
    }
}
