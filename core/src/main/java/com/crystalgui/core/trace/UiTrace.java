package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceExport;
import com.crystalgraphics.trace.CgTraceLog;
import com.crystalgui.core.CrystalGuiCore;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
    }

    /**
     * Opens the run directory from {@link #DIR_PROPERTY} if a host has not given one.
     *
     * <p>For the harness and for a test, where there is no game directory to hang a cache off. Does
     * nothing when the property is unset, which is the normal case in production.</p>
     */
    public static void useDirectoryProperty() {
        String configured = System.getProperty(DIR_PROPERTY);
        if (configured != null && !configured.isEmpty()) CgTraceLog.useRoot(Paths.get(configured));
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
     * Writes the current trace to {@code trace.json} in the run directory.
     *
     * <p>Chrome's JSON, which {@code ui.perfetto.dev} opens by drag and drop — so a trace is readable
     * in a mature viewer, queryable in SQL and attachable to a bug report, none of which this project
     * has to build.</p>
     *
     * @return the file written, or null when no run directory was given
     */
    @Nullable
    public static Path export() {
        Path dir = CgTraceLog.dir();
        if (dir == null) return null;
        Path file = dir.resolve("trace.json");
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            CgTraceExport.writeChromeJson(out, CgTrace.snapshot());
        } catch (IOException failed) {
            CrystalGuiCore.LOGGER.warn("trace export failed: {}", failed.toString());
            return null;
        }
        return file;
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
