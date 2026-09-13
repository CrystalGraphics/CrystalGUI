package com.crystalgui.widget.config.control;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.overlay.Tooltip;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A point on a box, chosen from nine — Photoshop's reference point, Unity's anchor presets.
 *
 * <pre>{@code
 * form.prop(ConfigDescriptor.anchor("pivot", "Pivot"),
 *         Property.derived(() -> new double[] {pivotX(), pivotY()}, at -> placePivot(at[0], at[1])));
 * }</pre>
 *
 * <p>The value is {@code [x, y]}, each a fraction of the box from its top left. A cell puts it on a corner,
 * an edge's middle or the centre, and lights while the value sits there; a value between them lights
 * none, so the nine cells are the round numbers of whatever pair of fields sits beside them.</p>
 *
 * <p>Nine cells rather than a dropdown because the thing being chosen is a position, and a picture of the
 * box is the only spelling that does not need reading. It labels itself — a row adds no label — and says
 * which point a cell is on hover.</p>
 */
public class AnchorControl extends ValueControl<double[]> {

    public static final Name NAME = Name.of("anchorcontrol");

    /** A discrete choice of nine. */
    public static final Event<AnchorControl, double[]> CHANGED =
            ConfigControlContracts.changed(StateTypes.doubleArrayUnder("v"), new double[0], RatePolicy.IMMEDIATE);

    public static final WidgetContract<AnchorControl> CONTRACT = ConfigControlContracts.register(
            AnchorControl.class, "anchorcontrol", StateTypes.doubleArrayUnder("v"), new double[0], CHANGED);

    public static final String ANCHOR_CLASS = "__anchor__";

    /** One line of three cells. */
    public static final String LINE_CLASS = "__anchor-line__";

    public static final String CELL_CLASS = "__anchor-cell__";

    /** On the cell the value sits on. */
    public static final String ON_CLASS = "__on__";

    /** How close a fraction has to be to an anchor to light it. */
    private static final double EPSILON = 2e-3d;

    /** Row by row from the top left. */
    private static final String[] POSITIONS = {
            "top left", "top", "top right",
            "left", "centre", "right",
            "bottom left", "bottom", "bottom right"};

    private final List<UIElement> cells = new ArrayList<>(9);

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL descriptor. */
    public AnchorControl() {
        this(ConfigDescriptor.anchor("", ""), null);
    }

    public AnchorControl(ConfigDescriptor descriptor, @Nullable double[] defaultValue) {
        super(NAME, descriptor, defaultValue == null ? new double[] {0.5d, 0.5d} : defaultValue.clone());
        addClass(ANCHOR_CLASS);
        // ONE TOOLTIP ON THE WHOLE GRID, worded per cell: a cell is a few pixels tall, so a tooltip hanging
        // from one would sit over whatever the grid is in rather than clear it.
        Tooltip hint = Tooltip.attach(this, describe(descriptor, 4));
        for (int row = 0; row < 3; row++) {
            UIElement line = new UIElement();
            line.addClass(LINE_CLASS);
            StyleGroup.defaultPipeline(line.getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));
            for (int column = 0; column < 3; column++) {
                final double[] at = {column * 0.5d, row * 0.5d};
                UIElement cell = new UIElement();
                cell.addClass(CELL_CLASS);
                hint.addRegion(cell, describe(descriptor, row * 3 + column));
                cell.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
                    if (!isEnabled()) return;
                    double[] chosen = at.clone();
                    writeToWidgets(chosen);
                    commit(chosen);
                    event.stopPropagation();
                }, false, false);
                cells.add(cell);
                line.append(cell);
            }
            append(line);
        }
        writeToWidgets(getValue());
    }

    private static String describe(ConfigDescriptor descriptor, int cell) {
        String label = descriptor.label();
        return label == null || label.isEmpty() ? capitalise(POSITIONS[cell]) : label + ": " + POSITIONS[cell];
    }

    private static String capitalise(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    /** The nine cells, row by row from the top left. */
    public List<UIElement> cells() {
        return List.copyOf(cells);
    }

    /** The cell the value sits on, 0..8 row by row, or -1 when it sits between them. */
    public int litCell() {
        return cellOf(getValue());
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        int lit = cellOf(value);
        for (int i = 0; i < cells.size(); i++) {
            UIElement cell = cells.get(i);
            if (cell.hasClass(ON_CLASS) != (i == lit)) cell.toggleClass(ON_CLASS, i == lit);
        }
    }

    private static int cellOf(@Nullable double[] value) {
        if (value == null || value.length < 2) return -1;
        int column = stepOf(value[0]);
        int row = stepOf(value[1]);
        return column < 0 || row < 0 ? -1 : row * 3 + column;
    }

    private static int stepOf(double fraction) {
        for (int step = 0; step <= 2; step++) {
            if (Math.abs(fraction - step * 0.5d) < EPSILON) return step;
        }
        return -1;
    }
}
