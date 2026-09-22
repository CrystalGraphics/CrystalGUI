package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.collection.table.TableView;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;

/**
 * Every zone in the selection, rolled up by name — the table a "what cost the most" question ends at.
 *
 * <pre>{@code
 * ZonesTab tab = new ZonesTab();
 * tab.show(model.statsOfSelection(), model.selectedZone(), frame.wallNanos());
 * }</pre>
 *
 * <h3>Self is the column that answers</h3>
 *
 * <p>A root zone's total is the whole frame by construction, which tells nobody anything. <b>Self</b> is
 * its own body with everything nested inside it removed, and it is what identifies the line of code
 * that is actually slow — so it comes first among the times, with its share of the frame beside it,
 * because "7 ms" means little until it is "80% of the frame".</p>
 *
 * <p>Every numeric column sorts by its <b>number</b> and not by its rendered text, or 9.10 ms sorts
 * above 10.0 ms and the table looks broken rather than string-sorted.</p>
 *
 * <h3>The source is a file and a line</h3>
 *
 * <p>{@code BoxPainter.java:212}, not the package path in front of it. The path is what makes it unique
 * and the file name is what somebody reads; at the width a tab gets, the path was all that showed.</p>
 */
public class ZonesTab extends UIElement {

    public static final Name NAME = Name.of("zonestab");

    private final ObservableList<CgTraceAggregate.Stat> rows = new ObservableList<>();
    private final TableView<CgTraceAggregate.Stat> table = new TableView<>(rows);

    /** The wall time a Self % is OF — the frame, or the whole range. */
    private long wallNanos;

    @Nullable
    private String scoped;

    public ZonesTab() {
        super(NAME);
        layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100f).heightPercent(100f));

        table.addColumn(TableColumn.<CgTraceAggregate.Stat>of("Zone", CgTraceAggregate.Stat::name)
                .flexible(2f).minWidth(120f).sortable());
        table.addColumn(TableColumn.<CgTraceAggregate.Stat>of("Calls", stat -> String.valueOf(stat.count()))
                .width(44f).sortable(Comparator.comparingInt(CgTraceAggregate.Stat::count)));
        table.addColumn(TableColumn.<CgTraceAggregate.Stat>of("Self",
                        stat -> String.format("%.3f", stat.selfMillis()))
                .width(62f).sortable(Comparator.comparingLong(CgTraceAggregate.Stat::selfNanos)));
        table.addColumn(TableColumn.<CgTraceAggregate.Stat>of("Self %", this::selfShare)
                .width(52f).sortable(Comparator.comparingLong(CgTraceAggregate.Stat::selfNanos)));
        table.addColumn(TableColumn.<CgTraceAggregate.Stat>of("Total",
                        stat -> String.format("%.3f", stat.totalMillis()))
                .width(62f).sortable(Comparator.comparingLong(CgTraceAggregate.Stat::totalNanos)));
        table.addColumn(TableColumn.<CgTraceAggregate.Stat>of("Max",
                        stat -> String.format("%.3f", stat.maxMillis()))
                .width(62f).sortable(Comparator.comparingLong(CgTraceAggregate.Stat::maxNanos)));
        // THE SOURCE IS A COLUMN, not a tooltip: the whole point of the location the engine walks for
        // every interned name is that a row can be jumped to, and a value nobody can see is not one.
        table.addColumn(TableColumn.<CgTraceAggregate.Stat>of("Source",
                        stat -> stat.source() == null ? "" : FlameChart.shortSource(stat.source()))
                .flexible(1f).minWidth(90f).sortable());

        append(table);
    }

    public TableView<CgTraceAggregate.Stat> table() {
        return table;
    }

    private String selfShare(CgTraceAggregate.Stat stat) {
        if (wallNanos <= 0L) return "";
        double share = stat.selfNanos() * 100d / wallNanos;
        return share >= 10d ? String.format("%.0f%%", share) : String.format("%.1f%%", share);
    }

    /**
     * Replaces the rows, and selects the one for {@code scoped} — the zone selected in the chart.
     *
     * <p>Selected rather than filtered to: a table showing one row would answer "how much did this
     * cost" and lose "compared with what", which is the question the chart could not answer alone.</p>
     */
    public void show(List<CgTraceAggregate.Stat> stats, @Nullable String scoped, long wallNanos) {
        this.wallNanos = wallNanos;
        this.scoped = scoped;
        rows.setAll(stats);
        table.refreshColumns();
        if (scoped == null) return;
        for (int i = 0; i < stats.size(); i++) {
            if (stats.get(i).name().equals(scoped)) {
                table.select(i);
                table.scrollToIndex(i);
                return;
            }
        }
    }

    @Nullable
    public String scoped() {
        return scoped;
    }
}
