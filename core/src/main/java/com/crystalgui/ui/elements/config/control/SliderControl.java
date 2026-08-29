package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.elements.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;

import javax.annotation.Nullable;

/**
 * A track and a number that edit the same value.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/02-slider-with-range.png} — track and value
 * field on one row, with {@code Min}/{@code Max} on a second row of their own.</p>
 *
 * <h3>The range is per instance</h3>
 * <p>Unity authors {@code Min}/{@code Max} on the Slider node, not on the node type; LDLib2 spells it
 * {@code @ConfigNumber(min, max)} per field. Two systems built independently reaching the same answer
 * is why {@link ConfigDescriptor#range()} lives on the descriptor and not in a registry of kinds. A
 * descriptor with no range is a plain {@link NumberControl} — {@code ConfigControls} decides that, so
 * this class can assume it has one.</p>
 *
 * <h3>The two halves must not echo each other</h3>
 * <p>Dragging the track writes the field, and writing the field moves the track. Both go through
 * {@code setValueObject}, whose guard is what stops the pair oscillating — which is the entire reason
 * that guard is on the base class rather than reimplemented per control.</p>
 */
public class SliderControl extends ValueControl<Double> {

    /** A drag from end to end would otherwise be a packet per pixel. */
    public static final Event<SliderControl, Double> CHANGED =
            ConfigControlContracts.changed(StateTypes.DOUBLE, 0d, RatePolicy.DRAGGING);

    public static final WidgetContract<SliderControl> CONTRACT = ConfigControlContracts.register(
            SliderControl.class, "slidercontrol", StateTypes.DOUBLE, 0d, CHANGED);


    private final Slider slider = new Slider();
    private final NumberControl number;

    public SliderControl(ConfigDescriptor descriptor, double defaultValue) {
        super(descriptor, defaultValue);
        ConfigDescriptor.Range range = descriptor.range();
        float min = range == null ? 0f : range.min();
        float max = range == null ? 1f : range.max();

        addClass("__slider__");
        markAsInternal();

        slider.setRange(min, max);
        slider.setValue((float) defaultValue);
        if (descriptor.integral()) slider.setStep(1f);

        number = new NumberControl(
                ConfigDescriptor.number(descriptor.id() + ".value", "")
                        .integral(descriptor.integral())
                        .range(min, max),
                defaultValue);

        addInternalChild(slider);
        addInternalChild(number);

        slider.attachListener(v -> {
            number.setValue((double) v);
            commit((double) v);
        });
        number.changed.connect(v -> {
            double d = v == null ? 0d : (Double) v;
            slider.setValue((float) d);
            commit(d);
        });
    }

    public Slider slider() {
        return slider;
    }

    public NumberControl number() {
        return number;
    }

    @Override
    protected void writeToWidgets(@Nullable Double value) {
        double d = value == null ? 0d : value;
        slider.setValue((float) d);
        number.setValue(d);
    }
}
