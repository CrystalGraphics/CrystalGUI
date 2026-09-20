package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

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
 * <p>They are declared here rather than in {@link FrameProfile} so that a class can record on one
 * without loading the probe, and so the names sit in one place a reader can check against the mask.</p>
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

    /** Whether anything in CrystalGUI is recording. */
    public static boolean isRecording() {
        return CgTrace.isEnabled(FRAME) || CgTrace.isEnabled(FLOW) || CgTrace.isEnabled(BLAME);
    }
}
