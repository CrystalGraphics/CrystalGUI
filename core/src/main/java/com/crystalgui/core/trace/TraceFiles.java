package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTraceExport;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Exported traces: written where a later run can find them, listed newest first, and read back.
 *
 * <pre>{@code
 * Path file = TraceFiles.export(CgTrace.snapshot(), Long.MIN_VALUE, Long.MAX_VALUE);
 * // later, perhaps after a restart
 * CgTraceSnapshot before = TraceFiles.read(TraceFiles.list().get(0));
 * }</pre>
 *
 * <p>A file is Chrome's JSON, as {@link CgTraceExport} writes it, so the same file opens in
 * {@code ui.perfetto.dev}. {@link #read} understands that export and nothing wider: frames, zones,
 * counters and markers come back; chains do not.</p>
 *
 * <p>The directory is {@code trace-exports/} beside the trace runs, never inside them: the run directory
 * is pruned to the last few runs, and an export is kept until somebody deletes it. It exists once a host
 * has given {@link UiTrace#useCacheRoot}; until then {@link #directory()} is null and export refuses.</p>
 */
public final class TraceFiles {

    private static volatile Path directory;

    private TraceFiles() {
    }

    /** Where exports go, given the trace runs' root. Called by {@link UiTrace}. */
    static void useRunRoot(Path runRoot) {
        if (directory == null) directory = runRoot.resolveSibling(runRoot.getFileName() + "-exports");
    }

    /** The exports directory, or null when no host has said where its cache is. */
    @Nullable
    public static Path directory() {
        return directory;
    }

    /**
     * Writes the frames {@code [fromFrame, toFrame]} of {@code snapshot} to a new file.
     *
     * @return the file written
     * @throws IOException also when no directory has been given
     */
    public static Path export(CgTraceSnapshot snapshot, long fromFrame, long toFrame) throws IOException {
        Path dir = directory;
        if (dir == null) throw new IOException("no trace directory: the host gave no cache root");
        Files.createDirectories(dir);
        long frames = snapshot.frames().stream()
                .filter(f -> f.index() >= fromFrame && f.index() <= toFrame).count();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        Path file = dir.resolve(stamp + "-" + frames + "f.json");
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            CgTraceExport.writeChromeJson(out, snapshot, fromFrame, toFrame);
        }
        return file;
    }

    /** Every exported trace, newest first. Empty when there is no directory or nothing in it. */
    public static List<Path> list() {
        Path dir = directory;
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /**
     * Reads an exported trace back into a snapshot the profiler's readers take.
     *
     * <p>Times come back on the file's own clock, which starts at zero; frame indices are the ones the
     * recording gave. A frame's GPU figure is present only where the export carried one.</p>
     */
    public static CgTraceSnapshot read(Path file) throws IOException {
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(in);
        }
    }

    public static CgTraceSnapshot read(Reader in) throws IOException {
        List<Event> events = new ArrayList<>();
        try (JsonReader json = new JsonReader(in)) {
            json.beginObject();
            while (json.hasNext()) {
                if (json.nextName().equals("traceEvents")) {
                    json.beginArray();
                    while (json.hasNext()) events.add(event(json));
                    json.endArray();
                } else {
                    json.skipValue();
                }
            }
            json.endObject();
        } catch (IllegalStateException | NumberFormatException malformed) {
            throw new IOException("not a trace export: " + malformed.getMessage(), malformed);
        }
        return snapshotOf(events);
    }

    /** One event, the fields this reader uses. */
    private static final class Event {
        String ph = "";
        String name = "";
        String cat = "";
        long tid;
        long ts;
        long dur;
        String argName;
        String src;
        String detail;
        double cpuMs = -1d;
        double gpuMs = -1d;
        long value;
    }

    private static Event event(JsonReader json) throws IOException {
        Event event = new Event();
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "ph" -> event.ph = json.nextString();
                case "name" -> event.name = json.nextString();
                case "cat" -> event.cat = json.nextString();
                case "tid" -> event.tid = json.nextLong();
                case "ts" -> event.ts = nanos(json.nextDouble());
                case "dur" -> event.dur = nanos(json.nextDouble());
                case "args" -> args(json, event);
                default -> json.skipValue();
            }
        }
        json.endObject();
        return event;
    }

    private static void args(JsonReader json, Event event) throws IOException {
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            if (json.peek() == JsonToken.NULL) {
                json.skipValue();
                continue;
            }
            switch (key) {
                case "name" -> event.argName = json.nextString();
                case "src" -> event.src = json.nextString();
                case "detail" -> event.detail = json.nextString();
                case "cpu_ms" -> event.cpuMs = json.nextDouble();
                case "gpu_ms" -> event.gpuMs = json.nextDouble();
                case "value" -> event.value = json.nextLong();
                default -> json.skipValue();
            }
        }
        json.endObject();
    }

    private static long nanos(double micros) {
        return Math.round(micros * 1000d);
    }

    private static CgTraceSnapshot snapshotOf(List<Event> events) {
        Map<Long, String> threads = new HashMap<>();
        for (Event event : events) {
            if (event.ph.equals("M") && event.argName != null) threads.put(event.tid, event.argName);
        }
        long frameTid = Long.MIN_VALUE;
        long chainTid = Long.MIN_VALUE;
        for (Map.Entry<Long, String> thread : threads.entrySet()) {
            if (thread.getValue().equals("Frames")) frameTid = thread.getKey();
            if (thread.getValue().equals("Chains")) chainTid = thread.getKey();
        }

        List<CgFrameRecord> frames = new ArrayList<>();
        List<CgTraceSnapshot.ZoneView> zones = new ArrayList<>();
        List<Event> counters = new ArrayList<>();
        List<CgTraceSnapshot.MarkerView> markers = new ArrayList<>();
        for (Event event : events) {
            switch (event.ph) {
                case "X" -> {
                    if (event.tid == frameTid) {
                        frames.add(new CgFrameRecord(frameIndex(event.name, frames.size()), event.ts,
                                event.ts + event.dur, figure(event.cpuMs), figure(event.gpuMs),
                                0L, 0, 0, 0L));
                    } else if (event.tid != chainTid) {
                        zones.add(new CgTraceSnapshot.ZoneView(event.name, event.src,
                                threads.getOrDefault(event.tid, "thread " + event.tid), event.cat,
                                0, event.ts, event.ts + event.dur));
                    }
                }
                case "C" -> counters.add(event);
                case "i", "I" -> markers.add(new CgTraceSnapshot.MarkerView(event.name, event.cat,
                        event.ts, event.detail));
                default -> {
                }
            }
        }
        frames.sort(Comparator.comparingLong(CgFrameRecord::index));
        zones.sort(Comparator.comparingLong(CgTraceSnapshot.ZoneView::startNanos));

        // A counter is stamped with its frame's start, so that start names the frame it was for.
        Map<Long, Long> frameAt = new HashMap<>();
        for (CgFrameRecord frame : frames) frameAt.put(frame.beginNanos(), frame.index());
        List<CgTraceSnapshot.CounterView> counterViews = new ArrayList<>();
        for (Event counter : counters) {
            Long index = frameAt.get(counter.ts);
            if (index != null) counterViews.add(new CgTraceSnapshot.CounterView(counter.name, index, counter.value));
        }
        return CgTraceSnapshot.of(frames, zones, counterViews, markers, List.of());
    }

    /** {@code "Frame 412"} is frame 412; anything else takes its place in the file. */
    private static long frameIndex(String name, int ordinal) {
        if (name.startsWith("Frame ")) {
            try {
                return Long.parseLong(name.substring("Frame ".length()).trim());
            } catch (NumberFormatException ignored) {
                // falls through to the ordinal
            }
        }
        return ordinal;
    }

    private static long figure(double millis) {
        return millis < 0d ? CgFrameRecord.ABSENT : Math.round(millis * 1_000_000d);
    }
}
