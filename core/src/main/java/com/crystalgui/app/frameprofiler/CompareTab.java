package com.crystalgui.app.frameprofiler;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.table.TableCellRenderer;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Two selections side by side — a frame or a range each — with every zone's time per frame on both and
 * the difference, biggest change first.
 *
 * <pre>{@code
 * CompareTab tab = new CompareTab(model);
 * // select frames, press "Pin as A"; select others, press "Pin as B"
 * tab.show();
 * }</pre>
 *
 * <p>From one ring first — this run, before and after a change — because that needs no file format and
 * answers most of the question. A side remembers its frames by INDEX, so it keeps meaning the same
 * frames as the ring moves on, and says so once they have been overwritten rather than comparing
 * against nothing.</p>
 */
public class CompareTab extends UIElement {

    public static final Name NAME = Name.of("comparetab");

    public static final String BAR_CLASS = "__compare-bar__";
    public static final String SIDE_CLASS = "__compare-side__";
    public static final String SUMMARY_CLASS = "__compare-summary__";
    public static final String WORSE_CLASS = "__worse__";
    public static final String BETTER_CLASS = "__better__";

    private final ProfilerModel model;
    private final ObservableList<ProfilerModel.CompareRow> rows = new ObservableList<>();
    private final TableView<ProfilerModel.CompareRow> table = new TableView<>(rows);
    private final UIText sideA = new UIText("");
    private final UIText sideB = new UIText("");
    private final UIText summary = new UIText("");
    private final Button pinA = new Button("Pin selection as A");
    private final Button pinB = new Button("Pin selection as B");
    private final Button swap = new Button("Swap");

    public CompareTab(ProfilerModel model) {
        super(NAME);
        this.model = model;

        UIElement bar = new UIElement();
        bar.addClass(BAR_CLASS);
        pinA.attachListener(model::pinA);
        pinB.attachListener(model::pinB);
        swap.attachListener(model::swapSides);
        sideA.addClass(SIDE_CLASS);
        sideB.addClass(SIDE_CLASS);
        bar.append(pinA, sideA, pinB, sideB, swap);
        append(bar);

        summary.addClass(SUMMARY_CLASS);
        append(summary);

        table.addColumn(TableColumn.<ProfilerModel.CompareRow>of("Zone", ProfilerModel.CompareRow::name)
                .flexible(2f).minWidth(120f).sortable());
        table.addColumn(TableColumn.<ProfilerModel.CompareRow>of("A ms/frame", row -> millis(row.aMillis()))
                .width(80f).sortable(Comparator.comparingDouble(ProfilerModel.CompareRow::aMillis)));
        table.addColumn(TableColumn.<ProfilerModel.CompareRow>of("B ms/frame", row -> millis(row.bMillis()))
                .width(80f).sortable(Comparator.comparingDouble(ProfilerModel.CompareRow::bMillis)));
        // TWO COLOURS, on the change alone: slower in the error colour, faster in the success one. The
        // whole row coloured would make the zone name read as the finding.
        table.addColumn(TableColumn.<ProfilerModel.CompareRow>of("Change", CompareTab::change)
                .width(80f).sortable(Comparator.comparingDouble(ProfilerModel.CompareRow::delta))
                .cells(new DeltaCell(CompareTab::change)));
        table.addColumn(TableColumn.<ProfilerModel.CompareRow>of("%", CompareTab::percent)
                .width(56f).sortable(Comparator.comparingDouble(CompareTab::ratio))
                .cells(new DeltaCell(CompareTab::percent)));
        table.addColumn(TableColumn.<ProfilerModel.CompareRow>of("Source",
                        row -> row.source() == null ? "" : FlameChart.shortSource(row.source()))
                .flexible(1f).minWidth(90f).sortable());
        append(table);
    }

    public TableView<ProfilerModel.CompareRow> table() {
        return table;
    }

    public Button pinAButton() {
        return pinA;
    }

    public Button pinBButton() {
        return pinB;
    }

    public String summaryText() {
        return summary.getText();
    }

    /** Re-reads both sides from the model. Cheap when neither is pinned. */
    public void show() {
        sideA.setText(describe(model.sideA()));
        sideB.setText(describe(model.sideB()));
        swap.setEnabled(model.sideA() != null || model.sideB() != null);
        if (model.sideA() == null || model.sideB() == null) {
            summary.setText("Select a frame or drag a range on the strip, pin it as A; then pin another as B.");
            rows.setAll(List.of());
            table.refreshColumns();
            return;
        }
        double a = model.meanFrameMillis(model.sideA());
        double b = model.meanFrameMillis(model.sideB());
        if (a < 0d || b < 0d) {
            summary.setText((a < 0d ? "A" : "B") + "'s frames are no longer in the ring. Pin it again.");
            rows.setAll(List.of());
        } else {
            double change = b - a;
            summary.setText(String.format("Frame time %.2f ms → %.2f ms  (%s%.2f ms, %s)", a, b,
                    change >= 0d ? "+" : "−", Math.abs(change), share(change, a))
                    + gpuSummary(model.meanGpuMillis(model.sideA()), model.meanGpuMillis(model.sideB())));
            rows.setAll(model.compare());
        }
        table.refreshColumns();
    }

    /** A change under this is noise, and drawn in neither colour. */
    private static final double CHANGE_FLOOR_MS = 0.01d;

    /** A text cell carrying {@link #WORSE_CLASS} or {@link #BETTER_CLASS} by the row's change. */
    private static final class DeltaCell implements TableCellRenderer<ProfilerModel.CompareRow> {

        private final Function<ProfilerModel.CompareRow, String> text;

        DeltaCell(Function<ProfilerModel.CompareRow, String> text) {
            this.text = text;
        }

        @Override
        public UIElement createTemplate() {
            UIText cell = new UIText("");
            cell.addClass(CELL_CLASS);
            return cell;
        }

        @Override
        public void bind(ProfilerModel.CompareRow item, int rowIndex, UIElement template) {
            ((UIText) template).setText(text.apply(item));
            boolean worse = item.delta() > CHANGE_FLOOR_MS;
            boolean better = item.delta() < -CHANGE_FLOOR_MS;
            if (worse != template.hasClass(WORSE_CLASS)) {
                if (worse) template.addClass(WORSE_CLASS);
                else template.removeClass(WORSE_CLASS);
            }
            if (better != template.hasClass(BETTER_CLASS)) {
                if (better) template.addClass(BETTER_CLASS);
                else template.removeClass(BETTER_CLASS);
            }
        }
    }

    public static final String CELL_CLASS = "__cell__";

    private String describe(@Nullable ProfilerModel.Side side) {
        if (side == null) return "not pinned";
        int held = model.framesOf(side).size();
        return side.label() + (side.count() > 1 ? " (" + side.count() + " frames)" : "")
                + (held < side.count() ? ", " + held + " still held" : "");
    }

    /** The GPU beside the frame time, or what is missing — a side with no landed figure is not zero. */
    private static String gpuSummary(double a, double b) {
        if (a < 0d && b < 0d) return "";
        if (a < 0d || b < 0d) return "   ·   GPU: " + (a < 0d ? "A" : "B") + " has no GPU figure";
        double change = b - a;
        return String.format("   ·   GPU %.2f ms → %.2f ms  (%s%.2f ms, %s)", a, b,
                change >= 0d ? "+" : "−", Math.abs(change), share(change, a));
    }

    private static String millis(double value) {
        return value == 0d ? "—" : String.format("%.3f", value);
    }

    private static String change(ProfilerModel.CompareRow row) {
        double delta = row.delta();
        return (delta >= 0d ? "+" : "−") + String.format("%.3f", Math.abs(delta));
    }

    private static double ratio(ProfilerModel.CompareRow row) {
        return row.aMillis() <= 0d ? Double.MAX_VALUE : row.delta() / row.aMillis();
    }

    private static String percent(ProfilerModel.CompareRow row) {
        if (row.aMillis() <= 0d) return row.bMillis() > 0d ? "new" : "";
        if (row.bMillis() <= 0d) return "gone";
        return share(row.delta(), row.aMillis());
    }

    private static String share(double delta, double base) {
        if (base <= 0d) return "";
        double pct = delta * 100d / base;
        return (pct >= 0d ? "+" : "−") + String.format(Math.abs(pct) >= 10d ? "%.0f%%" : "%.1f%%", Math.abs(pct));
    }
}
