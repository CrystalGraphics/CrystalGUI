package com.crystalgui.harness;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceReport;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.ui.dom.DocumentDriver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The desktop's baseline: what a user feels on it, measured the same way every time, so a change to how the desktop
 * runs is judged against numbers rather than impressions.
 *
 * <pre>{@code
 * DesktopBaselineProbe probe = new DesktopBaselineProbe(warmup, target, outputDir);
 * // the render thread, after the desktop's frame, every host frame:
 * if (probe.frame()) running = false;   // true once it has written baseline.txt
 * }</pre>
 *
 * <p>Runs five phases on the desktop, in order: the editor's first open; settling with VFX Lab and the Frame Profiler
 * open too; steady state; the editor kept busy by theme switches; a large file opened. In each it measures:</p>
 * <ul>
 *   <li><b>host frame</b>: the render thread's frame interval;</li>
 *   <li><b>new picture</b>: the interval between frames that show a new commit of the document, which is how often the
 *       taskbar and every window, pinned ones included, actually change;</li>
 *   <li><b>input to screen</b>: from a pointer move over the taskbar reaching the document's input to the first frame
 *       showing a commit recorded after it was dispatched; a move every {@value #POINTER_EVERY} host frames;</li>
 *   <li><b>document frame</b>: cpu and wall of the document's frames in the phase, from the trace.</li>
 * </ul>
 *
 * <p>Prints {@code [baseline]} lines and writes {@code baseline.txt}, with the trace's per-zone comparison of steady
 * state against each busy phase. Record at least {@code crystalgui.frame}, with a ring that holds the run
 * ({@code -Dcrystalgraphics.trace.frames}).</p>
 */
public final class DesktopBaselineProbe {

    /** What the probe drives on the desktop. Each action runs on the desktop document's thread. */
    public interface Target {
        DocumentDriver<?> driver();

        /** Launches the editor, as a player opens it. */
        void openEditor();

        /** Opens VFX Lab and the Frame Profiler beside the editor. */
        void openOthers();

        /** Switches the theme to the next one, and back on the next call. */
        void switchTheme();

        /** Opens a large source file in the editor; {@code onOpened} runs once it shows. */
        void openLargeFile(Runnable onOpened);

        /** Closes it again, so the editor's saved session does not bring it back into the next run. */
        void closeLargeFile();

        /**
         * A point over the taskbar, in surface pixels: {@code which} 0 or 1, two points far enough apart to change hover.
         * Asked once, after {@link #openOthers}.
         */
        float[] taskbarPoint(int which);

        /** Hands a pointer event to the document's input. */
        void dispatch(CgSystemInput.Mouse.Event event);
    }

    private static final int POINTER_EVERY = 6;
    private static final int THEME_EVERY = 30;

    private enum Phase {
        OPEN("editor's first open", 300),
        SETTLE("settling, three applications", 300),
        STEADY("steady state", 600),
        THEME("theme switch every " + THEME_EVERY + " frames", 600),
        FILE("large file opened", 900);

        final String title;
        final int frames;

        Phase(String title, int frames) {
            this.title = title;
            this.frames = frames;
        }
    }

    /** One phase's samples, in nanoseconds. */
    private static final class Samples {
        final List<Long> hostFrame = new ArrayList<>();
        final List<Long> newPicture = new ArrayList<>();
        final List<Long> inputToScreen = new ArrayList<>();
        long traceFrom, traceTo;
        /** The longest stretch without a new picture: from, to. */
        long worstFrom, worstTo;
    }

    /** A pointer move on its way to the screen. */
    private static final class Pending {
        final long offered;
        /** The commit count when the document dispatched it; -1 until then. Written on the document's thread. */
        volatile long dispatchedAfter = -1L;

        Pending(long offered) {
            this.offered = offered;
        }
    }

    private final int startFrame;
    private final Target target;
    private final Path outputDir;
    private final Samples[] samples = new Samples[Phase.values().length];
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final List<String> events = new ArrayList<>();
    /** Each theme switch's own time on the document's thread: a posted task, outside every document frame. */
    private final List<Long> themeSwitches = Collections.synchronizedList(new ArrayList<>());

    private int frame;
    private int phase = -1;
    private int inPhase;
    private long lastFrameNanos;
    private long lastPicture = -1L;
    private long lastPictureNanos;
    private boolean done;

    private volatile long editorLaunched, editorLaunchedAfter = -1L;
    private long editorPosted;
    private volatile long fileOpened, fileOpenedAfter = -1L;
    private long filePosted;

    public DesktopBaselineProbe(int startFrame, Target target, Path outputDir) {
        this.startFrame = startFrame;
        this.target = target;
        this.outputDir = outputDir;
        for (int i = 0; i < samples.length; i++) samples[i] = new Samples();
    }

    /** One host frame, on the render thread after the desktop's. @return true once it has written its report */
    public boolean frame() {
        if (done) return true;
        long now = System.nanoTime();
        DocumentDriver<?> driver = target.driver();
        if (frame++ < startFrame) {
            lastFrameNanos = now;
            return false;
        }
        if (phase < 0 || inPhase >= Phase.values()[phase].frames) {
            if (phase >= 0) samples[phase].traceTo = CgTrace.currentFrameIndex() - 1;
            if (phase == Phase.FILE.ordinal()) driver.post(target::closeLargeFile);
            if (++phase == Phase.values().length) {
                report();
                done = true;
                return true;
            }
            inPhase = 0;
            samples[phase].traceFrom = CgTrace.currentFrameIndex() + 1;
            begin(Phase.values()[phase], driver, now);
        }
        Samples s = samples[phase];
        s.hostFrame.add(now - lastFrameNanos);
        lastFrameNanos = now;

        long shown = driver.presentedCommit();
        if (shown != lastPicture) {
            if (lastPicture >= 0L && inPhase > 0) {
                s.newPicture.add(now - lastPictureNanos);
                if (now - lastPictureNanos > s.worstTo - s.worstFrom) {
                    s.worstFrom = lastPictureNanos;
                    s.worstTo = now;
                }
            }
            lastPicture = shown;
            lastPictureNanos = now;
        }
        while (!pending.isEmpty()) {
            long after = pending.peekFirst().dispatchedAfter;
            if (after < 0L || shown <= after) break;
            s.inputToScreen.add(now - pending.pollFirst().offered);
        }
        watchOpens(shown, now);

        Phase current = Phase.values()[phase];
        if (current == Phase.THEME && inPhase % THEME_EVERY == 0) driver.post(() -> {
            long at = System.nanoTime();
            target.switchTheme();
            themeSwitches.add(System.nanoTime() - at);
        });
        if (current != Phase.OPEN && inPhase % POINTER_EVERY == 0) movePointer(driver, inPhase / POINTER_EVERY % 2);
        inPhase++;
        return false;
    }

    private void begin(Phase phase, DocumentDriver<?> driver, long now) {
        switch (phase) {
            case OPEN -> {
                editorPosted = now;
                driver.post(() -> {
                    target.openEditor();
                    editorLaunched = System.nanoTime();
                    editorLaunchedAfter = driver.committedFrames();
                });
            }
            case SETTLE -> driver.post(() -> {
                target.openOthers();
                taskbar = new float[][] {target.taskbarPoint(0), target.taskbarPoint(1)};
            });
            case FILE -> {
                filePosted = now;
                driver.post(() -> target.openLargeFile(() -> {
                    fileOpened = System.nanoTime();
                    fileOpenedAfter = driver.committedFrames();
                }));
            }
            default -> {
            }
        }
    }

    /** The editor's and the file's open, timed to the first picture that shows each. */
    private void watchOpens(long shown, long now) {
        if (editorLaunchedAfter >= 0L && shown > editorLaunchedAfter) {
            events.add(String.format(Locale.ROOT, "editor's first open: launched in %.1f ms, on screen after %.1f ms",
                    (editorLaunched - editorPosted) / 1e6, (now - editorPosted) / 1e6));
            editorLaunchedAfter = -1L;
        }
        if (fileOpenedAfter >= 0L && shown > fileOpenedAfter) {
            events.add(String.format(Locale.ROOT, "large file: opened in %.1f ms, on screen after %.1f ms",
                    (fileOpened - filePosted) / 1e6, (now - filePosted) / 1e6));
            fileOpenedAfter = -1L;
        }
    }

    /** Read once on the document's thread, so a move never asks a busy document where to go. */
    private volatile float[][] taskbar;

    private void movePointer(DocumentDriver<?> driver, int which) {
        float[][] points = taskbar;
        float[] at = points == null ? null : points[which];
        if (at == null || at[0] < 0f) return;
        CgSystemInput.Mouse.Event event = new CgSystemInput.Mouse.Event((int) at[0], (int) at[1], 0, 0,
                CgMouseCodes.NONE, false, 0f, -1L);
        Pending move = new Pending(System.nanoTime());
        pending.addLast(move);
        driver.offerMouse(event, () -> {
            move.dispatchedAfter = driver.committedFrames();
            target.dispatch(event);
            return true;
        });
    }

    private void report() {
        CgTraceSnapshot snapshot = CgTrace.snapshot();
        List<String> lines = new ArrayList<>();
        lines.add("Desktop baseline: one document on one sequence. Times in ms, median / p95 / max.");
        lines.add("'new picture' is how often the taskbar and every window, pinned ones included, change on screen.");
        lines.add("");
        for (Phase p : Phase.values()) {
            Samples s = samples[p.ordinal()];
            lines.add(p.title + " (" + p.frames + " host frames)");
            lines.add("  host frame      " + stats(s.hostFrame));
            lines.add("  new picture     " + stats(s.newPicture) + rate(s.newPicture));
            lines.add("  input to screen " + stats(s.inputToScreen) + "  n=" + s.inputToScreen.size());
            lines.add("  trace frame     " + documentFrames(snapshot, s.traceFrom, s.traceTo));
            lines.addAll(worstGap(snapshot, s));
        }
        lines.add("");
        lines.add("theme switch task (on the document's thread, outside its frames) " + stats(themeSwitches)
                + "  n=" + themeSwitches.size());
        if (editorLaunchedAfter >= 0L) events.add("editor's first open: not on screen by the end of its phase");
        if (fileOpenedAfter >= 0L || fileOpened == 0L) events.add("large file: not on screen by the end of its phase");
        lines.addAll(events);
        Samples steady = samples[Phase.STEADY.ordinal()];
        for (Phase busy : new Phase[] {Phase.THEME, Phase.FILE, Phase.OPEN}) {
            Samples b = samples[busy.ordinal()];
            lines.add("");
            lines.add("Zones: steady state (A) against " + busy.title + " (B)");
            String compare = CgTraceReport.of(snapshot).budget(1000d / 60d)
                    .compare(steady.traceFrom, steady.traceTo, b.traceFrom, b.traceTo);
            Collections.addAll(lines, compare.split("\n"));
        }
        for (String line : lines) System.out.println("[baseline] " + line);
        try {
            Files.createDirectories(outputDir);
            Path file = outputDir.resolve("baseline.txt");
            Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            System.out.println("[baseline] written " + file.toAbsolutePath());
        } catch (IOException failed) {
            System.out.println("[baseline] could not write baseline.txt: " + failed);
        }
    }

    /**
     * What ran during the phase's longest stretch without a new picture: the top-level zones overlapping it, summed by
     * thread and name, each thread's share left uncovered, and the collector's pauses in it.
     */
    private static List<String> worstGap(CgTraceSnapshot snapshot, Samples s) {
        List<String> out = new ArrayList<>();
        if (s.worstTo <= s.worstFrom) return out;
        long from = s.worstFrom, to = s.worstTo;
        long epochMillis = System.currentTimeMillis() - (System.nanoTime() - from) / 1_000_000L;
        out.add(String.format(Locale.ROOT, "  longest stretch without a new picture, %.1f ms, from %s; what ran in it:",
                (to - from) / 1e6, java.time.Instant.ofEpochMilli(epochMillis)));
        java.util.Map<String, Long> byZone = new java.util.TreeMap<>();
        java.util.Map<String, Long> byThread = new java.util.TreeMap<>();
        for (CgTraceSnapshot.ZoneView zone : snapshot.zones()) {
            if (zone.depth() > 1) continue;
            long end = zone.isOpen() ? to : zone.endNanos();
            long overlap = Math.min(end, to) - Math.max(zone.startNanos(), from);
            if (overlap <= 0L) continue;
            // One level down too, to say what a long zone spent its time on; coverage counts the outer level alone.
            byZone.merge(zone.thread() + "  " + (zone.depth() == 1 ? "  " : "") + zone.name(), overlap, Long::sum);
            if (zone.depth() == 0) byThread.merge(zone.thread(), overlap, Long::sum);
        }
        byZone.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(16)
                .forEach(e -> out.add(String.format(Locale.ROOT, "    %8.1f ms  %s", e.getValue() / 1e6, e.getKey())));
        byThread.forEach((thread, covered) -> out.add(String.format(Locale.ROOT,
                "    thread %s: zones cover %.1f ms, %.1f ms uncovered", thread, covered / 1e6,
                Math.max(0L, to - from - covered) / 1e6)));
        long gc = 0L;
        for (CgFrameRecord frame : snapshot.frames()) {
            if (frame.endNanos() < from || frame.beginNanos() > to) continue;
            for (CgTraceSnapshot.GcPauseView pause : snapshot.gcPausesIn(frame)) {
                long overlap = Math.min(pause.startNanos() + pause.durationNanos(), to) - Math.max(pause.startNanos(), from);
                if (overlap > 0L) gc += overlap;
            }
        }
        if (gc > 0L) out.add(String.format(Locale.ROOT, "    garbage collection: %.1f ms", gc / 1e6));
        // THE DOCUMENT'S SEQUENCE moves between the pool's threads from turn to turn: its coverage is theirs together,
        // and its timeline says what it did when, which a sum by name cannot.
        long sequence = 0L;
        List<CgTraceSnapshot.ZoneView> timeline = new ArrayList<>();
        for (CgTraceSnapshot.ZoneView zone : snapshot.zones()) {
            if (zone.depth() != 0 || !zone.thread().startsWith("cgui-ui")) continue;
            long end = zone.isOpen() ? to : zone.endNanos();
            long overlap = Math.min(end, to) - Math.max(zone.startNanos(), from);
            if (overlap <= 0L) continue;
            sequence += overlap;
            timeline.add(zone);
        }
        out.add(String.format(Locale.ROOT, "    the document's sequence: zones cover %.1f ms, %.1f ms uncovered", sequence / 1e6,
                Math.max(0L, to - from - sequence) / 1e6));
        timeline.sort((x, y) -> Long.compare(x.startNanos(), y.startNanos()));
        long previous = from;
        int shown = 0;
        for (CgTraceSnapshot.ZoneView zone : timeline) {
            long start = Math.max(zone.startNanos(), from);
            long idle = start - previous;
            if (idle > 2_000_000L) out.add(String.format(Locale.ROOT, "      +%7.1f  (nothing for %.1f ms)", (previous - from) / 1e6, idle / 1e6));
            if (shown++ < 40) out.add(String.format(Locale.ROOT, "      +%7.1f  %6.1f ms  %s", (start - from) / 1e6,
                    ((zone.isOpen() ? to : Math.min(zone.endNanos(), to)) - start) / 1e6, zone.name()));
            previous = Math.max(previous, zone.isOpen() ? to : zone.endNanos());
        }
        if (to - previous > 2_000_000L) out.add(String.format(Locale.ROOT, "      +%7.1f  (nothing for %.1f ms, to the gap's end)",
                (previous - from) / 1e6, (to - previous) / 1e6));
        return out;
    }

    private static String documentFrames(CgTraceSnapshot snapshot, long from, long to) {
        List<Long> cpu = new ArrayList<>(), wall = new ArrayList<>();
        for (CgFrameRecord record : snapshot.frames()) {
            if (record.index() < from || record.index() > to) continue;
            if (record.hasCpu()) cpu.add(record.cpuNanos());
            wall.add(record.wallNanos());
        }
        return wall.isEmpty() ? "no frames held: is crystalgui.frame recording, and the ring big enough?"
                : "cpu " + stats(cpu) + ", wall " + stats(wall) + "  n=" + wall.size();
    }

    private static String rate(List<Long> intervals) {
        if (intervals.isEmpty()) return "";
        long total = 0L;
        for (long v : intervals) total += v;
        return String.format(Locale.ROOT, "  (%.1f a second)", intervals.size() / (total / 1e9));
    }

    private static String stats(List<Long> values) {
        if (values.isEmpty()) return "none";
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.format(Locale.ROOT, "%.2f / %.2f / %.2f", at(sorted, 0.5), at(sorted, 0.95),
                sorted.get(sorted.size() - 1) / 1e6);
    }

    private static double at(List<Long> sorted, double p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * p))) / 1e6;
    }
}
