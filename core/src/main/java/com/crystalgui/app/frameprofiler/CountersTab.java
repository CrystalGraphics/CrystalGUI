package com.crystalgui.app.frameprofiler;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.display.CounterTrack;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Every counter in the ring, one row each, on the frame strip's columns.
 *
 * <pre>{@code
 * CountersTab tab = new CountersTab();
 * tab.onFrameSelected(model::selectFrame);
 * tab.show(model.counterSeries(), model.frameIndices(), model.selectedIndex(), model.comparableFrom());
 * }</pre>
 *
 * <h3>A tab, not a band</h3>
 *
 * <p>A real run records two dozen counters. As rows in the main view they pushed the flame chart —
 * the thing a frame is opened to read — into a sliver between them, and turned the window into a wall
 * of equally loud stripes. Here they are one click away, still aligned column for column with the
 * strip, and clicking a spike selects its frame exactly as clicking the strip does.</p>
 *
 * <h3>Rows are kept, not rebuilt</h3>
 *
 * <p>Rebuilt only when the SET of counter names changes, which is almost never. Rebuilding on every
 * refresh would destroy a row under the pointer four times a second, and a captured drag routed at a
 * destroyed element goes nowhere.</p>
 */
public class CountersTab extends UIElement {

    public static final Name NAME = Name.of("counterstab");

    public static final String EMPTY_CLASS = "__empty__";

    private final List<CounterTrack> rows = new ArrayList<>();
    private final List<IntConsumer> listeners = new ArrayList<>(2);
    private final UIText empty = new UIText("No counters recorded on an enabled channel.");
    /** What the wheel scrolls: `overflow` on the tab clips, and only a scroller turns a wheel into a scroll. */
    private final ScrollerView list = new ScrollerView();

    public CountersTab() {
        super(NAME);
        layout(l -> l.flexDirection(FlexDirection.COLUMN));
        empty.addClass(EMPTY_CLASS);
        append(list);
    }

    public void onFrameSelected(IntConsumer listener) {
        if (listener != null) listeners.add(listener);
    }

    public List<CounterTrack> rows() {
        return List.copyOf(rows);
    }

    /** What the rows scroll in. */
    public ScrollerView scroller() {
        return list;
    }

    /** On the row a hint pointed at. */
    public static final String HIGHLIGHT_CLASS = "__highlight__";

    /**
     * Marks {@code name}'s row and scrolls it into view — where a hint's counter link lands.
     *
     * <p>After layout: the tab has usually just been switched to, and a row with no box yet has nowhere
     * to scroll to.</p>
     */
    public void highlight(String name) {
        CounterTrack target = null;
        for (CounterTrack row : rows) {
            boolean match = row.label().equals(name);
            if (match) target = row;
            if (match != row.hasClass(HIGHLIGHT_CLASS)) {
                if (match) row.addClass(HIGHLIGHT_CLASS);
                else row.removeClass(HIGHLIGHT_CLASS);
            }
        }
        UIDocument document = document();
        if (target == null || document == null) return;
        CounterTrack row = target;
        document.animation().afterLayout(this, delta -> {
            Box box = row.box();
            if (box == null) return document() != null;
            box.scrollIntoView();
            return false;
        });
    }

    /** The view every row shows — the strip's. @see #showView */
    private double viewFrom;
    private double viewSpan;
    private final List<Runnable> viewListeners = new ArrayList<>(2);

    /** Hears a zoom or pan made on any row, so the strip can follow it. Read the view back from a row. */
    public void onViewChanged(Runnable listener) {
        if (listener != null) viewListeners.add(listener);
    }

    /** Puts every row on the same frames as the strip; {@code span} 0 is the whole ring. */
    public void showView(double from, double span) {
        viewFrom = from;
        viewSpan = span;
        for (CounterTrack row : rows) row.showView(from, span);
    }

    /** On a row whose counter has not been written lately: its channel is off, or it stopped. */
    public static final String STALE_CLASS = "__stale__";

    /** A counter with no value in this many of the newest frames is stale. */
    static final int RECENT_FRAMES = 60;

    /**
     * Shows {@code series}, one value per frame, over frames whose absolute numbers are
     * {@code frameIndices} — what a stale row's note names.
     */
    public void show(Map<String, long[]> series, long[] frameIndices, int selected, int comparableFrom) {
        List<String> order = ordered(series);
        if (!sameNames(order)) {
            list.removeAll();
            rows.clear();
            if (order.isEmpty()) {
                list.append(empty);
                return;
            }
            for (String name : order) {
                CounterTrack row = new CounterTrack();
                row.setSeries(name, series.get(name));
                // THE WHEEL SCROLLS THE LIST: the rows fill the tab, so a row that zoomed on a plain
                // wheel left nothing to scroll with. They follow the strip's zoom instead.
                row.setWheelZooms(false);
                row.onSelected(index -> {
                    for (IntConsumer listener : listeners) listener.accept(index);
                });
                // ONE VIEW FOR EVERY ROW ON THESE COLUMNS: a wheel on a counter moves the strip too.
                row.onViewChanged(() -> {
                    viewFrom = row.viewFrom();
                    viewSpan = row.isZoomed() ? row.visible() : 0d;
                    for (CounterTrack other : rows) {
                        if (other != row) other.showView(viewFrom, viewSpan);
                    }
                    for (Runnable listener : viewListeners) listener.run();
                });
                rows.add(row);
                list.append(row);
            }
        }
        for (CounterTrack row : rows) {
            row.setSeries(row.label(), series.get(row.label()));
            row.setUnit(ProfilerModel.isDuration(row.label())
                    ? CounterTrack.Unit.NANOSECONDS : CounterTrack.Unit.COUNT);
            row.showSelected(selected);
            row.setComparableFrom(comparableFrom);
            row.showView(viewFrom, viewSpan);
            int last = lastWritten(series.get(row.label()));
            boolean stale = isStale(series.get(row.label()));
            if (stale != row.hasClass(STALE_CLASS)) {
                if (stale) row.addClass(STALE_CLASS);
                else row.removeClass(STALE_CLASS);
            }
            row.setNote(!stale ? "" : last < 0 ? "never recorded"
                    : "last recorded #" + (last < frameIndices.length ? frameIndices[last] : last));
        }
    }

    private static int lastWritten(long[] values) {
        for (int i = values.length - 1; i >= 0; i--) {
            if (values[i] != CounterTrack.ABSENT) return i;
        }
        return -1;
    }

    private static boolean isStale(long[] values) {
        return lastWritten(values) < values.length - RECENT_FRAMES;
    }

    /**
     * Counters still being written first, then those that stopped; within each, rows with something in
     * them before rows that stayed at zero, and by name.
     *
     * <p>A counter whose channel was switched off keeps its old values in the ring, so it reads as a row
     * that is empty now and full a long way back — interleaved with live ones, the tab was a wall of
     * rows that mostly said nothing about the frame being looked at. Moved down and dimmed, not hidden:
     * that it was recorded is still a fact. By name within each group, so a row stays where it was
     * found between refreshes.</p>
     */
    private static List<String> ordered(Map<String, long[]> series) {
        List<String> moving = new ArrayList<>();
        List<String> still = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : series.entrySet()) {
            if (isStale(entry.getValue())) stale.add(entry.getKey());
            else (peakOf(entry.getValue()) > 0L ? moving : still).add(entry.getKey());
        }
        moving.sort(String::compareTo);
        still.sort(String::compareTo);
        stale.sort(String::compareTo);
        moving.addAll(still);
        moving.addAll(stale);
        // THE FRAME'S GPU TOTAL LEADS: it is the one row read against the strip above to tell a GPU-bound
        // frame from a CPU-bound one.
        if (moving.remove(ProfilerModel.GPU_SERIES)) moving.add(0, ProfilerModel.GPU_SERIES);
        return moving;
    }

    private static long peakOf(long[] values) {
        long peak = 0L;
        for (long value : values) {
            if (value != CounterTrack.ABSENT) peak = Math.max(peak, value);
        }
        return peak;
    }

    private boolean sameNames(List<String> order) {
        if (order.size() != rows.size()) return false;
        for (int i = 0; i < order.size(); i++) {
            if (!rows.get(i).label().equals(order.get(i))) return false;
        }
        return !order.isEmpty() || list.children().contains(empty);
    }
}
