package com.crystalgui.harness;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceReport;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.core.trace.UiTrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;

/**
 * What an open shader graph costs a frame while nobody touches it — the idle-graph measurement.
 *
 * <pre>{@code
 * ShaderGraphCostProbe probe = new ShaderGraphCostProbe(900, target);
 * ... the scene's frame and paint ...
 * if (probe.frame()) running = false;   // true once it has printed
 * }</pre>
 *
 * <p>Runs in ONE process, in blocks: the desktop with no graph open, then the same desktop with the graph open and
 * untouched for four blocks in a row, then closed again. A cost that grows while the graph sits there shows as the
 * open blocks getting worse one after another; a cost the graph leaves behind shows in the last block. Two ranges of
 * one ring are the honest comparison — same process, same JIT — so the per-zone table comes from
 * {@link CgTraceReport#compare}.</p>
 *
 * <p>Prints {@code [graph-cost]} lines and writes the same text to {@code harness-output/cgui-desktop/graph-cost.txt}.
 * Record at least {@code crystalgui.frame} and {@code crystalgraphics.shadergraph}, and hold the frames: the ring has
 * to reach back to the first block.</p>
 */
public final class ShaderGraphCostProbe {

    /** What the probe drives. The scene answers it with its editor. */
    public interface Target {
        boolean isOpen();

        void open();

        void close();

        int hooks();

        int afterLayoutHooks();

        /** Changes one constant in the graph as an edit in its editor would, so every affected shader recompiles. */
        void edit(int n);

        /** Pans the graph's canvas by screen pixels, as a drag does. */
        void pan(float dx, float dy);

        /** Moves one node by world units, as one frame of dragging it does. */
        void moveNode(float dx, float dy);
    }

    private static final int SETTLE = 120;
    private static final int OPEN_SETTLE = 240;
    /** {@code -Dcrystalgui.harness.desktop.graphCost.stopAfterHitchMs}: stop recording after the first frame over it. */
    private static final int HITCH_MS = Integer.getInteger("crystalgui.harness.desktop.graphCost.stopAfterHitchMs", 0);
    private static final int HITCH_FRAMES_AFTER = 5;
    private boolean hitchArmed;
    private static final int BLOCK = 240;
    private static final int OPEN_BLOCKS = 3;
    private static final int EDITS = 6;
    /** Frames between edits: long enough for every recompile an edit causes to land before the next. */
    private static final int EDIT_GAP = 90;
    private static final int PANS = 3;
    /** Screen pixels a pan moves: far enough that every node leaves the viewport and is culled. */
    private static final float PAN_DISTANCE = 6000f;
    /** Frames spent away before panning back. */
    private static final int PAN_AWAY = 30;
    /** Frames from one pan-away to the next: past the ~2 s a returning node's work was seen to land after. */
    private static final int PAN_GAP = 360;
    private static final int DRAGS = 3;
    /** Frames a drag lasts, moving the node a step each. */
    private static final int DRAG_FRAMES = 40;
    private static final float DRAG_STEP = 4f;
    /** Frames from one drag's start to the next: past the ~2 s a release was seen to cost after. */
    private static final int DRAG_GAP = 360;
    /** The baseline block is kept for good in the ring's first frames; the rest must fit the newest. */
    private static final int FIRST_FRAMES = SETTLE + BLOCK;
    private static final int NEWEST_FRAMES = 3500;
    /** Sized so each arena stays at one power-of-two step; the open desktop records ~3,000 zones a frame. */
    private static final int ZONES_PER_FRAME = 4000;

    /** One step of the schedule: settle frames are not measured. {@code each} runs on every frame of it. */
    private record Step(String name, int frames, boolean measured, Runnable onEnter, IntConsumer each) {
        Step(String name, int frames, boolean measured, Runnable onEnter) {
            this(name, frames, measured, onEnter, frame -> { });
        }
    }

    /** A measured block: its frame range and the hook counts at its end. */
    private record Block(String name, long from, long to, int hooks, int afterLayoutHooks) {
    }

    private final int startFrame;
    private final Target target;
    private final List<Step> steps = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();

    private int frame;
    private int stepIndex = -1;
    private int stepFrame;
    private long blockFrom;
    private boolean done;

    public ShaderGraphCostProbe(int startFrame, Target target) {
        this.startFrame = startFrame;
        this.target = target;
        // CLOSED FIRST, whatever the restored session left open: the baseline is the desktop without it.
        steps.add(new Step("close if open", SETTLE, false, () -> {
            if (target.isOpen()) target.close();
        }));
        steps.add(new Step("no graph", BLOCK, true, () -> { }));
        steps.add(new Step("open, settling", OPEN_SETTLE, false, () -> {
            CgTrace.marker(UiTrace.FRAME, "graph-cost:open");
            target.open();
        }));
        for (int i = 1; i <= OPEN_BLOCKS; i++) {
            // ARMED ONCE THE GRAPH HAS SETTLED, so the first hitch it catches is not the open itself: recording
            // stops a few frames after it, and the report keeps that frame's whole tree whatever it recorded.
            Runnable arm = i > 1 || HITCH_MS <= 0 ? () -> { } : () -> hitchArmed = true;
            steps.add(new Step("open, idle #" + i, BLOCK, true, arm));
        }
        steps.add(new Step("editing", EDITS * EDIT_GAP, true, () -> { }, at -> {
            if (at % EDIT_GAP != 0) return;
            CgTrace.marker(UiTrace.FRAME, "graph-cost:edit");
            target.edit(at / EDIT_GAP);
            edits.add(CgTrace.currentFrameIndex());
        }));
        steps.add(new Step("panning", PANS * PAN_GAP, true, () -> { }, at -> {
            int into = at % PAN_GAP;
            if (into == 0) {
                CgTrace.marker(UiTrace.FRAME, "graph-cost:pan-away");
                target.pan(PAN_DISTANCE, 0f);
            } else if (into == PAN_AWAY) {
                CgTrace.marker(UiTrace.FRAME, "graph-cost:pan-back");
                target.pan(-PAN_DISTANCE, 0f);
                pans.add(CgTrace.currentFrameIndex());
            }
        }));
        steps.add(new Step("dragging", DRAGS * DRAG_GAP, true, () -> { }, at -> {
            int into = at % DRAG_GAP;
            // Out and back, so the graph ends where it began.
            if (into < DRAG_FRAMES) target.moveNode((at / DRAG_GAP) % 2 == 0 ? DRAG_STEP : -DRAG_STEP, 0f);
            if (into == DRAG_FRAMES - 1) {
                CgTrace.marker(UiTrace.FRAME, "graph-cost:release");
                releases.add(CgTrace.currentFrameIndex());
            }
        }));
        steps.add(new Step("closing", SETTLE, false, () -> {
            CgTrace.marker(UiTrace.FRAME, "graph-cost:close");
            target.close();
        }));
        steps.add(new Step("closed again", BLOCK, true, () -> { }));
    }

    /**
     * The name of a picture to take on this frame, or null: the graph open and settled, to compare paint changes
     * against, and the desktop after it closed, to see that it did.
     */
    @Nullable
    public String captureNow() {
        if (stepIndex < 0 || stepFrame != 60) return null;
        String step = steps.get(stepIndex).name();
        if (step.equals("open, idle #1")) return "graph-open";
        if (step.equals("closed again")) return "graph-closed";
        return null;
    }

    /** Called once a frame, after paint. @return true once the report has been printed */
    public boolean frame() {
        if (done) return true;
        if (frame++ < startFrame) return false;
        if (hitchArmed) {
            // BY CPU, not wall: a wall hitch is usually the present waiting, and the question is the frame's work.
            CgFrameRecord last = CgTrace.frame(CgTrace.currentFrameIndex() - 1);
            if (last != null && last.hasCpu() && last.cpuNanos() > HITCH_MS * 1_000_000L) {
                hitchArmed = false;
                CgTrace.stopAfterHitch(1L, HITCH_FRAMES_AFTER);
            }
        }
        // The Frame Profiler's settings size the ring at autostart, over any -D; the ring must reach the first block.
        if (stepIndex < 0) CgTrace.configure(FIRST_FRAMES, NEWEST_FRAMES, ZONES_PER_FRAME);
        if (stepIndex < 0 || ++stepFrame >= steps.get(stepIndex).frames()) {
            if (stepIndex >= 0 && steps.get(stepIndex).measured()) endBlock(steps.get(stepIndex));
            if (++stepIndex >= steps.size()) {
                report();
                done = true;
                return true;
            }
            stepFrame = 0;
            steps.get(stepIndex).onEnter().run();
            blockFrom = CgTrace.currentFrameIndex() + 1;
            // The wall-clock instant too, so a JFR recording of the same run can be cut by block.
            System.out.println("[graph-cost] " + steps.get(stepIndex).name() + " from frame #" + blockFrom
                    + " at " + Instant.now());
        }
        steps.get(stepIndex).each().accept(stepFrame);
        return false;
    }

    /** The frame each edit was made in; its cost lands in the frames after. */
    private final List<Long> edits = new ArrayList<>();
    /** The frame each pan back was made in: every node returns to view then. */
    private final List<Long> pans = new ArrayList<>();
    /** The last frame of each node drag. */
    private final List<Long> releases = new ArrayList<>();

    private void endBlock(Step step) {
        blocks.add(new Block(step.name(), blockFrom, CgTrace.currentFrameIndex(),
                target.hooks(), target.afterLayoutHooks()));
    }

    private void report() {
        List<String> out = new ArrayList<>();
        out.add("[graph-cost] per block: median / p90 of wall, cpu and gpu in ms; hooks live at the block's end");
        for (Block block : blocks) out.add(line(block));

        CgTraceSnapshot snapshot = CgTrace.snapshot();
        out.add("");
        out.add("[graph-cost] counters per block: median over the frames that wrote one (gpu:* in ms), and in how many");
        for (Block block : blocks) out.addAll(counterLines(snapshot, block));

        CgTraceReport report = CgTraceReport.of(snapshot).budget(1000d / 60d);
        Block none = blocks.get(0);
        Block first = blocks.get(1);
        if (HITCH_MS > 0) {
            // THE HITCH THE STOP CAUGHT: the slowest frame from the arm onwards, whole, since recording ended
            // a few frames after it and nothing later can have overwritten its zones.
            CgFrameRecord hitch = worstCpuAfter(first.from() - 1);
            out.add("");
            out.add("[graph-cost] the hitch recording stopped on (armed at #" + first.from() + ", over " + HITCH_MS + " ms)");
            if (hitch != null) out.add(report.frame(hitch.index()));
        }
        Block last = blocks.get(OPEN_BLOCKS);
        Block editing = blocks.get(OPEN_BLOCKS + 1);
        Block after = blocks.get(blocks.size() - 1);
        out.add("");
        out.add("[graph-cost] each edit: the worst frame in the " + EDIT_GAP + " after it, and how many went over 16.7 ms");
        for (long edit : edits) out.add(afterLine("edit", edit, EDIT_GAP));
        out.add(compileLine(snapshot, editing, edits.size()));
        for (long edit : edits) {
            CgFrameRecord worst = worstAfter(edit, EDIT_GAP);
            if (worst != null) out.add(report.frame(worst.index()));
        }
        int panWindow = PAN_GAP - PAN_AWAY;
        Block panning = blocks.get(OPEN_BLOCKS + 2);
        out.add("");
        out.add("[graph-cost] each pan back: the worst frame in the " + panWindow + " after it, and how many went over 16.7 ms");
        for (long pan : pans) out.add(afterLine("pan back", pan, panWindow));
        out.add(compileLine(snapshot, panning, pans.size()));
        for (long pan : pans) {
            CgFrameRecord worst = worstAfter(pan, panWindow);
            if (worst != null) out.add(report.frame(worst.index()));
        }
        int releaseWindow = DRAG_GAP - DRAG_FRAMES;
        Block dragging = blocks.get(OPEN_BLOCKS + 3);
        out.add("");
        out.add("[graph-cost] each node drag's release: the worst frame in the " + releaseWindow
                + " after it, and how many went over 16.7 ms");
        for (long release : releases) out.add(afterLine("release", release, releaseWindow));
        out.add(compileLine(snapshot, dragging, releases.size()));
        for (long release : releases) {
            CgFrameRecord worst = worstAfter(release, releaseWindow);
            if (worst != null) out.add(report.frame(worst.index()));
        }
        out.add("[graph-cost] last open block -> editing (what an edit costs, spread over the block)");
        out.add(report.compare(last.from(), last.to(), editing.from(), editing.to()));
        out.add("[graph-cost] no graph -> first open block");
        out.add(report.compare(none.from(), none.to(), first.from(), first.to()));
        out.add("[graph-cost] first open block -> last open block (what grows while it sits there)");
        out.add(report.compare(first.from(), first.to(), last.from(), last.to()));
        out.add("[graph-cost] no graph before -> after closing it (what it leaves behind)");
        out.add(report.compare(none.from(), none.to(), after.from(), after.to()));

        for (String each : out) System.out.println(each);
        Path file = Path.of("harness-output", "cgui-desktop", "graph-cost.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            System.out.println("[graph-cost] written to " + file.toAbsolutePath());
        } catch (IOException failed) {
            System.out.println("[graph-cost] could not write " + file + ": " + failed);
        }
    }

    /** The top-level compile zones: each nests the rest, so summing these counts nothing twice. */
    private static final Set<String> COMPILES = Set.of("material.recompile", "material.submitRecompile",
            "material.commit", "material.awaitPending");

    /** What compiling cost the frame thread across a block: per event in it, and in the single worst frame. */
    private String compileLine(CgTraceSnapshot snapshot, Block block, int events) {
        // ONE pass over the zones: zonesIn scans every zone held, and per frame of a block that is millions each.
        List<CgFrameRecord> frames = new ArrayList<>();
        for (CgFrameRecord record : snapshot.frames()) {
            if (record.index() >= block.from() && record.index() <= block.to()) frames.add(record);
        }
        Map<Long, Double> perFrame = new HashMap<>();
        Map<String, Double> parts = new TreeMap<>();
        for (CgTraceSnapshot.ZoneView zone : snapshot.zones()) {
            if (!zone.name().startsWith("material.") || zone.name().startsWith("material.doBind")
                    || zone.name().equals("material.getOrCompileVariant")) {
                continue;
            }
            CgFrameRecord owner = null;
            for (CgFrameRecord record : frames) {
                if (record.contains(zone.startNanos())) {
                    owner = record;
                    break;
                }
            }
            if (owner == null) continue;
            parts.merge(zone.name(), zone.millis(), Double::sum);
            if (COMPILES.contains(zone.name())) perFrame.merge(owner.index(), zone.millis(), Double::sum);
        }
        double total = 0, worst = 0;
        long worstFrame = -1;
        for (Map.Entry<Long, Double> frame : perFrame.entrySet()) {
            total += frame.getValue();
            if (frame.getValue() > worst) {
                worst = frame.getValue();
                worstFrame = frame.getKey();
            }
        }
        int count = Math.max(1, events);
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "[graph-cost] compiling on the frame thread in %s: %.2f ms per event, worst frame #%d with %.2f ms",
                block.name(), total / count, worstFrame, worst));
        parts.forEach((name, ms) -> line.append(String.format(Locale.ROOT, "%n[graph-cost]     %-28s %8.2f ms per event",
                name, ms / count)));
        return line.toString();
    }

    /** The frame after {@code event} that worked longest. */
    @Nullable
    private static CgFrameRecord worstCpuAfter(long event) {
        CgFrameRecord worst = null;
        for (CgFrameRecord record : CgTrace.frames()) {
            if (record.index() <= event || !record.hasCpu()) continue;
            if (worst == null || record.cpuNanos() > worst.cpuNanos()) worst = record;
        }
        return worst;
    }

    @Nullable
    private static CgFrameRecord worstAfter(long event, int window) {
        CgFrameRecord worst = null;
        for (CgFrameRecord record : CgTrace.frames()) {
            if (record.index() <= event || record.index() > event + window) continue;
            if (worst == null || record.wallMillis() > worst.wallMillis()) worst = record;
        }
        return worst;
    }

    private static String afterLine(String what, long event, int window) {
        CgFrameRecord worst = worstAfter(event, window);
        int over = 0;
        for (CgFrameRecord record : CgTrace.frames()) {
            if (record.index() > event && record.index() <= event + window && record.wallMillis() > 1000d / 60d) over++;
        }
        if (worst == null) return "[graph-cost]   " + what + " at #" + event + ": no frames held";
        return String.format(Locale.ROOT, "[graph-cost]   %s at #%d: worst #%d (+%d)  wall %.2f  cpu %s  gpu %s  over budget %d",
                what, event, worst.index(), worst.index() - event, worst.wallMillis(),
                worst.hasCpu() ? String.format(Locale.ROOT, "%.2f", worst.cpuMillis()) : "absent",
                worst.hasGpu() ? String.format(Locale.ROOT, "%.2f", worst.gpuMillis()) : "absent", over);
    }

    private static String line(Block block) {
        List<Double> wall = new ArrayList<>();
        List<Double> cpu = new ArrayList<>();
        List<Double> gpu = new ArrayList<>();
        List<Double> zones = new ArrayList<>();
        int collections = 0;
        long gcMillis = 0;
        long dropped = 0;
        long leaked = 0;
        int droppingFrames = 0;
        for (CgFrameRecord record : CgTrace.frames()) {
            if (record.index() < block.from() || record.index() > block.to()) continue;
            wall.add(record.wallMillis());
            if (record.hasCpu()) cpu.add(record.cpuMillis());
            if (record.hasGpu()) gpu.add(record.gpuMillis());
            zones.add((double) CgTrace.zonesIn(record).size());
            collections += record.gcCollections();
            gcMillis += record.gcMillis();
            dropped += record.droppedZones();
            leaked += record.leakedZones();
            if (record.droppedZones() > 0) droppingFrames++;
        }
        return String.format(Locale.ROOT,
                "[graph-cost]   %-16s frames #%d-#%d (%d held)  wall %s  cpu %s  gpu %s  gc %d in %d ms  hooks %d  afterLayout %d"
                        + "  zones %s  dropped %d in %d frames  leaked %d",
                block.name(), block.from(), block.to(), wall.size(), stat(wall), stat(cpu), stat(gpu),
                collections, gcMillis, block.hooks(), block.afterLayoutHooks(), stat(zones), dropped, droppingFrames,
                leaked);
    }

    /** The GPU zones and the shader graph's own counters, which a zone table leaves out. */
    private static boolean reported(String name) {
        return name.startsWith(CgGpuTrace.PREFIX) || name.startsWith("preview.") || name.startsWith("mainPreview.")
                || name.startsWith("material.generated") || name.startsWith("sg-") || name.startsWith("graph-")
                || name.startsWith("layer") || name.startsWith("retain-") || name.endsWith("switches")
                || name.equals("drawcalls") || name.equals("scissors") || name.equals("alloc-kb");
    }

    private static List<String> counterLines(CgTraceSnapshot snapshot, Block block) {
        // Summed per frame first: a counter written several times in one frame is one reading of that frame.
        Map<String, Map<Long, Long>> perFrame = new TreeMap<>();
        for (CgTraceSnapshot.CounterView counter : snapshot.counters()) {
            if (counter.frameIndex() < block.from() || counter.frameIndex() > block.to() || !reported(counter.name())) {
                continue;
            }
            perFrame.computeIfAbsent(counter.name(), k -> new HashMap<>())
                    .merge(counter.frameIndex(), counter.value(), Long::sum);
        }
        List<String> lines = new ArrayList<>();
        lines.add("[graph-cost]   " + block.name());
        perFrame.forEach((name, frames) -> {
            double[] values = frames.values().stream().mapToDouble(Long::doubleValue).sorted().toArray();
            double median = values[values.length / 2];
            boolean gpu = name.startsWith(CgGpuTrace.PREFIX);
            lines.add(String.format(Locale.ROOT, "[graph-cost]     %-34s %10s  in %d frames", name,
                    gpu ? String.format(Locale.ROOT, "%.3f ms", median / 1e6) : String.valueOf((long) median),
                    values.length));
        });
        return lines;
    }

    /** Median and p90, or "absent" -- a figure nobody recorded is not zero. */
    private static String stat(List<Double> values) {
        if (values.isEmpty()) return "absent";
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "%.2f/%.2f", sorted[sorted.length / 2],
                sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * 0.9d))]);
    }
}
