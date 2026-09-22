package com.crystalgui.app.frameprofiler;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.display.CounterTrack;
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
 * tab.show(model.counterSeries(), model.selectedIndex(), model.comparableFrom());
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

    public CountersTab() {
        super(NAME);
        layout(l -> l.flexDirection(FlexDirection.COLUMN));
        empty.addClass(EMPTY_CLASS);
    }

    public void onFrameSelected(IntConsumer listener) {
        if (listener != null) listeners.add(listener);
    }

    public List<CounterTrack> rows() {
        return List.copyOf(rows);
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

    public void show(Map<String, long[]> series, int selected, int comparableFrom) {
        List<String> order = ordered(series);
        if (!sameNames(order)) {
            removeAll();
            rows.clear();
            if (order.isEmpty()) {
                append(empty);
                return;
            }
            for (String name : order) {
                CounterTrack row = new CounterTrack();
                row.setSeries(name, series.get(name));
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
                append(row);
            }
        }
        for (CounterTrack row : rows) {
            row.setSeries(row.label(), series.get(row.label()));
            row.showSelected(selected);
            row.setComparableFrom(comparableFrom);
            row.showView(viewFrom, viewSpan);
        }
    }

    /**
     * Rows with something in them first, each group by name.
     *
     * <p>A counter that stayed at zero for the whole ring draws an empty row, and two dozen counters in
     * recording order put three of those at the top. Moved to the end, not hidden: that a counter never
     * moved is a fact, just not the first one worth reading. By name within each group, so a row stays
     * where it was found between refreshes.</p>
     */
    private static List<String> ordered(Map<String, long[]> series) {
        List<String> moving = new ArrayList<>();
        List<String> still = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : series.entrySet()) {
            (peakOf(entry.getValue()) > 0L ? moving : still).add(entry.getKey());
        }
        moving.sort(String::compareTo);
        still.sort(String::compareTo);
        moving.addAll(still);
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
        return !order.isEmpty() || children().contains(empty);
    }
}
