package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.display.SpanTrack;
import com.crystalgui.widget.display.TimelineAxis;
import com.crystalgui.widget.display.TimelineRuler;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One frame's zones, as a row per thread on one shared axis.
 *
 * <pre>{@code
 * FlameChart chart = new FlameChart();
 * chart.onZoneSelected(zone -> tabs.scopeTo(zone.name()));
 * chart.show(model.zonesOfSelection(), frame.beginNanos(), frame.endNanos());
 * }</pre>
 *
 * <h3>One axis, so the rows line up</h3>
 *
 * <p>A worker's zone and the frame it hurt have to be readable as simultaneous, which is the whole
 * reason a worker gets a row here rather than a table of its own. Every {@link SpanTrack} takes the
 * same {@link TimelineAxis}, so one scroll moves all of them and a vertical line through the chart is
 * a moment.</p>
 *
 * <h3>The frame thread is first</h3>
 *
 * <p>Threads are otherwise in the order their first zone was recorded, which is arrival order and
 * means nothing. Whoever opened this came for the frame, so the frame's thread is the top row.</p>
 */
public class FlameChart extends UIElement {

    public static final Name NAME = Name.of("flamechart");

    /** The label beside a thread's row. */
    public static final String LABEL_CLASS = "__thread-label__";
    public static final String ROW_CLASS = "__thread-row__";
    public static final String RULER_ROW_CLASS = "__ruler-row__";

    private final TimelineAxis axis = new TimelineAxis();
    private final List<SpanTrack> tracks = new ArrayList<>();
    private final List<Consumer<SpanTrack.Span>> listeners = new ArrayList<>(2);

    public FlameChart() {
        super(NAME);
        layout(l -> l.flexDirection(FlexDirection.COLUMN));
        detail.addClass(DETAIL_CLASS);
        append(detail);
        describe(null);
    }

    /** The span rows, one per thread, frame thread first. */
    public List<SpanTrack> tracks() {
        return List.copyOf(tracks);
    }

    public TimelineAxis axis() {
        return axis;
    }

    /** The readout line over the chart — what is hovered, what is selected, or how to drive it. */
    public UIText detail() {
        return detail;
    }

    public void onZoneSelected(Consumer<SpanTrack.Span> listener) {
        if (listener != null) listeners.add(listener);
    }

    /** Resets the view to the whole frame, recorded or not. */
    public void showAll() {
        axis.showAll();
    }

    /**
     * Fits the view to the recorded work — the home position, what the chart opens on and what F does.
     *
     * <p>Not the whole frame: a frame's wall time runs to the next frame's start and includes whatever
     * the host did in between, so a 58 ms frame with 9 ms of zones opened with its work squeezed into
     * the left sixth and the rest an empty band. The whole frame is still one wheel-out away.</p>
     */
    public void fit() {
        if (workTo <= workFrom) {
            axis.showAll();
            return;
        }
        long pad = Math.max(TimelineAxis.MIN_SPAN_NANOS, (workTo - workFrom) / 40L);
        axis.show(workFrom - pad, workTo + pad);
    }

    /**
     * Rebuilds the rows for {@code zones}, over a frame spanning {@code [from, to]}.
     *
     * <p>The EXTENT is the frame's, so the time nothing covered is still there to be seen and the ruler
     * counts from the frame's start; the VIEW opens fitted to the zones. @see #fit</p>
     *
     * @param boundaries where each frame after the first begins, when several are shown; empty for one
     */
    public void show(List<CgTraceSnapshot.ZoneView> zones, long from, long to, @Nullable String frameThread,
                     long[] boundaries) {
        removeAll();
        tracks.clear();
        append(detail);
        selectedSpan = null;
        describe(null);

        workFrom = Long.MAX_VALUE;
        workTo = Long.MIN_VALUE;
        for (CgTraceSnapshot.ZoneView zone : zones) {
            workFrom = Math.min(workFrom, zone.startNanos());
            workTo = Math.max(workTo, zone.isOpen() ? to : zone.endNanos());
        }
        axis.setExtent(from, to);
        fit();

        Map<String, List<SpanTrack.Span>> byThread = group(zones, to);
        if (byThread.isEmpty()) {
            // AN ABSENT STATE, not an empty chart: a blank band reads as "the frame did nothing",
            // which is the one thing a profiler must never say when it simply was not recording.
            UIText none = new UIText("No zones in this frame \u2014 nothing on an enabled channel recorded one.");
            none.addClass("__empty__");
            append(none);
            return;
        }

        // THE RULER, in a row of its own with an empty label slot, so its ticks sit exactly over the
        // spans below it rather than a label's width to their left.
        UIElement rulerRow = new UIElement();
        rulerRow.addClass(ROW_CLASS);
        rulerRow.addClass(RULER_ROW_CLASS);
        UIElement slot = new UIElement();
        slot.addClass(LABEL_CLASS);
        rulerRow.append(slot);
        rulerRow.append(new TimelineRuler(axis));
        append(rulerRow);

        for (Map.Entry<String, List<SpanTrack.Span>> entry : ordered(byThread, frameThread)) {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            UIText label = new UIText(entry.getKey());
            label.addClass(LABEL_CLASS);
            row.append(label);

            SpanTrack track = new SpanTrack(axis);
            track.setSpans(withGaps(entry.getValue(), from, to));
            track.setBoundaries(boundaries);
            track.onSelected(span -> {
                // ONE SELECTION ACROSS THE CHART: a ring left on a zone in another thread's row would
                // read as two things selected.
                for (SpanTrack other : tracks) {
                    if (other != track) other.showSelected(null);
                }
                selectedSpan = span;
                describe(null);
                for (Consumer<SpanTrack.Span> listener : listeners) listener.accept(span);
            });
            track.onHover(this::describe);
            row.append(track);
            tracks.add(track);
            append(row);
        }
    }

    /**
     * Writes the readout: the hovered span, else the selected one, else how to drive the chart.
     *
     * <p>THE WAY TO READ A SLIVER. Most zones in a real frame are far too narrow to carry a label, and
     * a chart whose small bars cannot be identified is a picture rather than a tool. Hovering one names
     * it here, with its time and where it was recorded. With nothing under the pointer the line teaches
     * the gestures, which is the one moment somebody is looking for them.</p>
     */
    private void describe(@Nullable SpanTrack.Span hovered) {
        SpanTrack.Span shown = hovered != null ? hovered : selectedSpan;
        if (shown == null || SpanTrack.GAP_NAME.equals(shown.name())) {
            detail.setText(HINT);
            detail.addClass(HINT_CLASS);
            return;
        }
        detail.removeClass(HINT_CLASS);
        String source = shown.source() == null ? "" : "   " + shortSource(shown.source());
        detail.setText(shown.name() + "   " + String.format("%.3f ms", shown.millis()) + source);
    }

    /** {@code BoxPainter.java:212} rather than the package path in front of it. */
    static String shortSource(String source) {
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        return slash >= 0 ? source.substring(slash + 1) : source;
    }

    private static final String HINT =
            "Hover a zone to name it  \u00b7  click to select  \u00b7  wheel to zoom  \u00b7  drag to pan  \u00b7  F to fit";

    public static final String DETAIL_CLASS = "__detail__";
    public static final String HINT_CLASS = "__hint__";

    private final UIText detail = new UIText("");

    @Nullable
    private SpanTrack.Span selectedSpan;

    /** The recorded work's own bounds, which {@link #fit} opens on. */
    private long workFrom;
    private long workTo;

    /**
     * One row list per thread, with depth derived from <b>time containment</b> rather than read off
     * the record.
     *
     * <p>The recorded depth is the recording thread's live stack at the moment a zone was stored, and
     * most zones reach the ring through {@code zoneDone} — a zone handed its own start and end after
     * the fact, stored at whatever depth was open, which is almost always zero. Trusting that number
     * draws every child on top of its parent in one row. A zone wholly inside another on the same
     * thread IS its child, which is how Chrome's trace viewer nests complete events for the same
     * reason.</p>
     */
    private static Map<String, List<SpanTrack.Span>> group(List<CgTraceSnapshot.ZoneView> zones, long frameEnd) {
        Map<String, List<CgTraceSnapshot.ZoneView>> byThread = new LinkedHashMap<>();
        for (CgTraceSnapshot.ZoneView zone : zones) {
            byThread.computeIfAbsent(zone.thread(), ignored -> new ArrayList<>()).add(zone);
        }
        Map<String, List<SpanTrack.Span>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, List<CgTraceSnapshot.ZoneView>> entry : byThread.entrySet()) {
            rows.put(entry.getKey(), nest(entry.getValue(), frameEnd));
        }
        return rows;
    }

    /**
     * Assigns each zone the depth of the innermost zone still open around it.
     *
     * <p>Sorted by start, and on a tie by LONGER first, so a parent that starts on the same tick as its
     * first child is placed before it. A zone that overlaps the one above it without being inside it is
     * treated as a sibling rather than a child: partial overlap is two things running, not a call.</p>
     */
    private static List<SpanTrack.Span> nest(List<CgTraceSnapshot.ZoneView> zones, long frameEnd) {
        List<CgTraceSnapshot.ZoneView> sorted = new ArrayList<>(zones);
        sorted.sort(Comparator.comparingLong(CgTraceSnapshot.ZoneView::startNanos)
                .thenComparing(Comparator.comparingLong((CgTraceSnapshot.ZoneView zone) ->
                        endOf(zone, frameEnd)).reversed()));

        List<SpanTrack.Span> out = new ArrayList<>(sorted.size());
        long[] openEnds = new long[64];
        int open = 0;
        for (CgTraceSnapshot.ZoneView zone : sorted) {
            // AN OPEN ZONE IS DRAWN TO THE FRAME'S END, not dropped: a zone still running when the
            // snapshot was taken is usually the interesting one, and a chart that hides it shows a gap
            // exactly where the work is.
            long start = zone.startNanos();
            long end = endOf(zone, frameEnd);
            while (open > 0 && (openEnds[open - 1] <= start || openEnds[open - 1] < end)) open--;
            out.add(new SpanTrack.Span(zone.name(), open, start, end, zone.source()));
            if (open == openEnds.length) openEnds = Arrays.copyOf(openEnds, open * 2);
            openEnds[open++] = end;
        }
        return out;
    }

    private static long endOf(CgTraceSnapshot.ZoneView zone, long frameEnd) {
        return zone.isOpen() ? frameEnd : zone.endNanos();
    }

    /**
     * The same rows, with the time nothing covered filled in.
     *
     * <p>A blank stretch reads as "nothing here" when it means "nothing <b>instrumented</b> here" —
     * and while CrystalGraphics and CrystalGUI still record on separate paths, a gap is very often
     * precisely the other one. Drawing it is the difference between a frame that looks idle and a
     * frame that says how much of itself it cannot account for.</p>
     */
    private static List<SpanTrack.Span> withGaps(List<SpanTrack.Span> spans, long from, long to) {
        List<SpanTrack.Span> roots = new ArrayList<>();
        for (SpanTrack.Span span : spans) {
            if (span.depth() == 0) roots.add(span);
        }
        roots.sort(Comparator.comparingLong(SpanTrack.Span::startNanos));

        List<SpanTrack.Span> out = new ArrayList<>(spans);
        long cursor = from;
        for (SpanTrack.Span root : roots) {
            if (root.startNanos() - cursor >= GAP_FLOOR_NANOS) {
                out.add(gap(cursor, root.startNanos()));
            }
            cursor = Math.max(cursor, root.endNanos());
        }
        if (to - cursor >= GAP_FLOOR_NANOS) out.add(gap(cursor, to));
        return out;
    }

    private static SpanTrack.Span gap(long from, long to) {
        return new SpanTrack.Span(SpanTrack.GAP_NAME, 0, from, to, null);
    }

    /**
     * Below this a gap is measurement noise rather than unaccounted work.
     *
     * <p>Ten microseconds: the interval between two adjacent zones is a handful of instructions, and
     * marking every one of them would draw a hairline between every pair of bars and call it missing
     * instrumentation.</p>
     */
    private static final long GAP_FLOOR_NANOS = 10_000L;

    private static List<Map.Entry<String, List<SpanTrack.Span>>> ordered(
            Map<String, List<SpanTrack.Span>> byThread, @Nullable String frameThread) {
        List<Map.Entry<String, List<SpanTrack.Span>>> rows = new ArrayList<>(byThread.entrySet());
        rows.sort((a, b) -> {
            boolean first = a.getKey().equals(frameThread);
            boolean second = b.getKey().equals(frameThread);
            return first == second ? 0 : first ? -1 : 1;
        });
        return rows;
    }

    /** The tree behind the same zones — what the Zones and Callers tabs read. */
    public static List<CgTraceAggregate.Node> treeOf(List<CgTraceSnapshot.ZoneView> zones) {
        return CgTraceAggregate.tree(zones);
    }
}
