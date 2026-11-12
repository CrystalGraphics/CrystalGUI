package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceSnapshot;
import java.util.List;

/**
 * Where a {@link FrameStats} reads its frames from: the process's trace ring (the compositor's frames), or one view's
 * own {@link FrameLog}.
 *
 * <pre>{@code
 * FrameStats compositor = FrameStats.compositor();      // over TRACE
 * FrameStats mine = view.frameStats();                  // over the view's FrameLog
 * }</pre>
 *
 * <p>Easy to get wrong: a log has frames and no zones, so its breakdown rows are empty; only the trace says what a
 * frame was spent on.</p>
 */
public interface FrameSource {

    /** How many frames have ever been written; changes exactly when the list does. */
    long count();

    /** The frames held, oldest first. */
    List<CgFrameRecord> frames();

    /** What {@code frame} spent its time on; empty where the source records none. */
    List<CgTraceSnapshot.ZoneView> zones(CgFrameRecord frame);

    /** What {@code frame} counted; empty where the source records none. */
    List<CgTraceSnapshot.CounterView> counters(CgFrameRecord frame);

    /** Drops every frame held. */
    void clear();

    /** The process's trace ring: the compositor's frames, with their zones and counters. */
    FrameSource TRACE = new FrameSource() {
        @Override
        public long count() {
            return CgTrace.frameCount();
        }

        @Override
        public List<CgFrameRecord> frames() {
            return CgTrace.frames();
        }

        @Override
        public List<CgTraceSnapshot.ZoneView> zones(CgFrameRecord frame) {
            return CgTrace.zonesIn(frame);
        }

        @Override
        public List<CgTraceSnapshot.CounterView> counters(CgFrameRecord frame) {
            return CgTrace.countersIn(frame);
        }

        @Override
        public void clear() {
            CgTrace.clear();
        }
    };
}
