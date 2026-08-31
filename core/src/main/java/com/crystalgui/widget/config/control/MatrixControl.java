package com.crystalgui.widget.config.control;

import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.ValueControl;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import com.crystalgui.ui.dom.Name;

/**
 * {@link ConfigDescriptor#arity()} squared numbers, unlabelled, in a grid.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/11-matrix-grid.png}.</p>
 *
 * <h3>Self-labelling — a matrix says what it is by its shape</h3>
 * <p>A 4×4 grid of fields reads as a matrix on sight; a row label to its left would cost it the width to
 * stay legible at inspector scale and say nothing the grid does not already. Same reasoning as
 * {@link ArrayControl}, and the same override.</p>
 *
 * <h3>Row-major, flat — not a 2D array</h3>
 * <p>{@code double[]} of length {@code arity * arity}, index {@code row * arity + col}. A
 * {@code double[][]} would let a caller hand in a non-square jagged array with nothing here to refuse
 * it; a flat array of a fixed, descriptor-declared length cannot be jagged.</p>
 *
 * <h3>It composes {@link NumberControl}, exactly as {@link VectorControl} does</h3>
 * <p>Sixteen independent copies of "what does a partly-typed number mean" is the failure mode this
 * avoids — every cell is the same control VectorControl and NumberControl already are.</p>
 */
public class MatrixControl extends ValueControl<double[]> {

    public static final Name NAME = Name.of("matrixcontrol");

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code matrixcontrol } by tag. */
    public MatrixControl() {
        this(ConfigDescriptor.vector("", "", 4), null);
    }

    /** As the vector control, with more cells. */
    public static final Event<MatrixControl, double[]> CHANGED =
            ConfigControlContracts.changed(StateTypes.doubleArrayUnder("v"), new double[0], RatePolicy.TYPING);

    public static final WidgetContract<MatrixControl> CONTRACT = ConfigControlContracts.register(
            MatrixControl.class, "matrixcontrol", StateTypes.doubleArrayUnder("v"), new double[0], CHANGED);


    public static final String ROW_CLASS = "__matrix-row__";
    public static final String CELL_CLASS = "__matrix-cell__";

    private final int arity;
    private final List<NumberControl> cells = new ArrayList<>();

    public MatrixControl(ConfigDescriptor descriptor, @Nullable double[] defaultValue) {
        super(NAME, descriptor, defaultValue);
        this.arity = Math.max(1, descriptor.arity());
        addClass("__matrix__");
        for (int row = 0; row < arity; row++) {
            UINode rowEl = new UINode();
            rowEl.addClass(ROW_CLASS);
            for (int col = 0; col < arity; col++) {
                final int index = row * arity + col;
                UINode cell = new UINode();
                cell.addClass(CELL_CLASS);

                NumberControl number = new NumberControl(
                        ConfigDescriptor.number(descriptor.id() + "." + index, ""),
                        defaultValue != null && index < defaultValue.length ? defaultValue[index] : 0d);
                number.changed.connect(v -> onCellChanged(index, (Double) v));

                cell.append(number);
                cells.add(number);
                rowEl.append(cell);
            }
            append(rowEl);
        }
    }

    private void onCellChanged(int index, @Nullable Double value) {
        double[] current = getValue();
        // Copied, not mutated in place — same reason VectorControl copies: the array a listener is
        // holding from the last commit must not change out from under it.
        double[] next = new double[cells.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = current != null && i < current.length ? current[i] : 0d;
        }
        next[index] = value == null ? 0d : value;
        commit(next);
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setValue(value != null && i < value.length ? value[i] : 0d);
        }
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    public int arity() {
        return arity;
    }

    public List<NumberControl> cells() {
        return List.copyOf(cells);
    }
}
