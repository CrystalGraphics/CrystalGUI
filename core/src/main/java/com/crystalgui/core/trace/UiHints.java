package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgTraceHints;

/**
 * What a slow CrystalGUI frame is accused of — the rules over this engine's own counter vocabulary.
 *
 * <p>Registered once, from {@link UiTrace}. They are here rather than in the engine because a hint is a
 * rule over counter NAMES, and {@code retain-dynamic} and {@code layer-clear-kpx} mean something to
 * whoever wrote the painter and nothing to a capture engine.</p>
 *
 * <h3>Each of these was a real investigation</h3>
 *
 * <p>The measured frame that started this work was 5.2ms with {@code paint:tree} at 83%, 17 layers,
 * 24 megapixels cleared and 31 draw calls — layer-bound rather than draw-bound, which took a person
 * reading counters to notice. That is the first rule below, and the point of writing them down is that
 * nobody has to notice it again.</p>
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
                        String.format("%.1fMpx of layer clear and blit for %d draw calls",
                                kpx / 1000d, draws),
                        "docs/CGUI_STYLE_RENDER_PIPELINE.md §8"));
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
                        "Box.retainable"));
            }
        });

        // CASCADE-CHURN. "Style is slow" is not actionable; a count with a call site is.
        CgTraceHints.register((frame, zones, counters, out) -> {
            long rematched = counters.getOrDefault("rematched", 0L);
            if (rematched >= REMATCH_FLOOR) {
                out.add(new CgTraceHints.Hint("CASCADE-CHURN",
                        rematched + " elements re-matched this frame",
                        "enable crystalgui.blame for the call site"));
            }
        });

        // COLLECTED. A pause stops every thread and is charged to whichever phase was running, which is
        // indistinguishable from that phase being slow. Say so rather than letting the longest row wear it.
        CgTraceHints.register((frame, zones, counters, out) -> {
            if (frame.gcMillis() > 0L) {
                out.add(new CgTraceHints.Hint("COLLECTED",
                        frame.gcMillis() + "ms of collection inside this frame:"
                                + " the phase breakdown below is not trustworthy",
                        null));
            }
        });

        // ICONS-DIRECT. An icon is meant to rasterise once and draw as a tinted quad; the direct path
        // is a draw per scanline cell.
        CgTraceHints.register((frame, zones, counters, out) -> {
            long direct = counters.getOrDefault("svg-direct", 0L);
            if (direct > 0L) {
                out.add(new CgTraceHints.Hint("ICONS-DIRECT",
                        direct + " icon draws bypassed the raster cache",
                        "SvgRasterCache.accepts"));
            }
        });
    }
}
