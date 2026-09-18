package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * Two numbers with a chain between them: while the chain is on, moving either takes the other with it <b>at the ratio
 * the two had</b> — the free transform bar's W and H, as one row's control.
 *
 * <pre>{@code
 * lab.form().control("lab.scale", "Scale",
 *         new LinkedPair("lab.scale", axis("x", "X"), axis("y", "Y")).bind(factors));
 * }</pre>
 *
 * <p>A ratio rather than equality, because that is what linking a pair means everywhere it exists: a 2:1 box
 * scaled by its width stays 2:1. Two axes that happen to agree stay agreed, which is the case a "uniform" flag was
 * for.</p>
 */
public final class LinkedPair extends ValueControl<double[]> {

    public static final Name NAME = Name.of("linkedpair");

    /** The row: a letter and a field, the chain, then the other letter and field. */
    public static final String FIELD_CLASS = "__linked-pair__";
    public static final String AXIS_CLASS = "__linked-axis__";
    public static final String CHAIN_CLASS = "__linked-chain__";
    /** On the chain while it holds. */
    public static final String ACTIVE_CLASS = "__active__";

    private final Button chain = new Button("");
    private boolean linked = true;

    /** @param first the descriptor each half takes — its own id, its unit and its range */
    public LinkedPair(String id, ConfigDescriptor first, ConfigDescriptor second) {
        super(NAME, ConfigDescriptor.vector(id, "", 2), new double[] {1d, 1d});
        addClass(FIELD_CLASS);

        half(first, 0);
        chain.addClass(CHAIN_CLASS);
        chain.attachListener(() -> linked(!linked));
        append(chain);
        half(second, 1);
        linked(linked);
    }

    /** One half: its letter, then its field — and <b>the letter is what scrubs it</b>, as a vector cell's is. */
    private void half(ConfigDescriptor descriptor, int axis) {
        UIText letter = new UIText(descriptor.label());
        letter.addClass(AXIS_CLASS);
        NumberControl field = new NumberControl(descriptor.shortLabel(""), 0d);
        field.bind(Property.derived(() -> current()[axis], value -> moved(axis, value)));
        field.scrubWith(letter);
        append(letter);
        append(field);
    }

    /** Links the pair, or lets the two go their own way. */
    public LinkedPair linked(boolean on) {
        linked = on;
        chain.toggleClass(ACTIVE_CLASS, on);
        return this;
    }

    private void moved(int axis, @Nullable Double value) {
        double next = value == null ? 0d : value;
        double[] now = current();
        double[] out = now.clone();
        out[axis] = next;
        if (linked) {
            int other = 1 - axis;
            // AT THE RATIO THE TWO HAD, and equally when there is no ratio to keep: a zero has none.
            out[other] = now[axis] == 0d ? next : now[other] * next / now[axis];
        }
        commitAndShow(out);
    }

    private double[] current() {
        double[] now = getValue();
        return now == null || now.length < 2 ? new double[] {1d, 1d} : now;
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        // THE HALVES FOLLOW PROPERTIES OF THEIR OWN, which read this one: there is nothing to push at them.
    }
}
