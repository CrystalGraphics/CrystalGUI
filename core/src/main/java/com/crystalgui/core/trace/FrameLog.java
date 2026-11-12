package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTraceSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * One view's frames: when each started and what it cost, kept by the sequence that frames the document. A document
 * on its own sequence has no frame of its own in the host's trace, so its readout reads this.
 *
 * <pre>{@code
 * long start = System.nanoTime();
 * frameAndPaint();
 * log.committed(start, System.nanoTime() - start);   // the document's thread
 *
 * FrameStats stats = new FrameStats(log);            // any thread
 * }</pre>
 *
 * <p>A frame's wall time is the interval to the next frame's start, so a frame is written when the next one begins.
 * Written by one thread, read by any.</p>
 */
public final class FrameLog implements FrameSource {

    /** About eight seconds at 60 Hz: more than any readout's window. */
    public static final int CAPACITY = 512;

    private final ArrayDeque<CgFrameRecord> frames = new ArrayDeque<>();
    private long written;
    private long previousStart = -1L;
    private long previousCpu;

    /** A frame began at {@code startNanos} and the work of the one before it took {@code cpuNanos}. */
    public synchronized void committed(long startNanos, long cpuNanos) {
        if (previousStart >= 0L && startNanos > previousStart) {
            frames.addLast(new CgFrameRecord(written, previousStart, startNanos, previousCpu, CgFrameRecord.ABSENT,
                    0L, 0, 0, 0L));
            if (frames.size() > CAPACITY) frames.removeFirst();
            written++;
        }
        previousStart = startNanos;
        previousCpu = cpuNanos;
    }

    @Override
    public synchronized long count() {
        return written;
    }

    @Override
    public synchronized List<CgFrameRecord> frames() {
        return new ArrayList<>(frames);
    }

    @Override
    public List<CgTraceSnapshot.ZoneView> zones(CgFrameRecord frame) {
        return List.of();
    }

    @Override
    public List<CgTraceSnapshot.CounterView> counters(CgFrameRecord frame) {
        return List.of();
    }

    @Override
    public synchronized void clear() {
        frames.clear();
        previousStart = -1L;
        // `written` keeps counting: a reader keyed on it sees the clear as a change.
        written++;
    }
}
