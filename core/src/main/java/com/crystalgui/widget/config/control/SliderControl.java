package com.crystalgui.widget.config.control;

import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.property.Property;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;

import javax.annotation.Nullable;
import com.crystalgui.ui.dom.Name;

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

    public static final Name NAME = Name.of("slidercontrol");

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code slidercontrol } by tag. */
    public SliderControl() {
        this(ConfigDescriptor.number("", ""), 0d);
    }

    /** A drag from end to end would otherwise be a packet per pixel. */
    public static final Event<SliderControl, Double> CHANGED =
            ConfigControlContracts.changed(StateTypes.DOUBLE, 0d, RatePolicy.DRAGGING);

    public static final WidgetContract<SliderControl> CONTRACT = ConfigControlContracts.register(
            SliderControl.class, "slidercontrol", StateTypes.DOUBLE, 0d, CHANGED);


    private final Slider slider = new Slider();
    private final NumberControl number;

    public SliderControl(ConfigDescriptor descriptor, double defaultValue) {
        super(NAME, descriptor, defaultValue);
        addClass("__slider__");
        track(track(descriptor));
        slider.setValue((float) defaultValue);

        // ONE DESCRIPTOR FOR BOTH HALVES, suppliers included, so a live range or unit reaches the field.
        number = new NumberControl(descriptor.part(descriptor.id() + ".value", ""), defaultValue);
        if (descriptor.live()) {
            PropertyWatch watch = new PropertyWatch(this, Property.derived(() -> track(descriptor)),
                    (was, now) -> track(now));
            whileConnected(watch::start);
        }

        append(slider);
        append(number);

        // ONE GESTURE, whichever half made it: the host is handed this control, so a drag of the track or
        // a scrub of the label has to surface here or it records one undo step per frame.
        slider.onDragging.connect(this::interactionRelay);
        number.interacting.connect(this::interactionRelay);
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

    private void interactionRelay(Boolean active) {
        if (Boolean.TRUE.equals(active)) beginInteraction();
        else endInteraction();
    }

    /** The label scrubs the number beside the track. @see NumberControl#scrubWith */
    @Override
    public boolean adoptLabel(UIElement label) {
        number.scrubWith(label);
        return true;
    }

    /** The track's span and increment as the descriptor states them now. */
    private record Track(float min, float max, float step) {
    }

    private static Track track(ConfigDescriptor descriptor) {
        // THE SOFT RANGE is the track: a gesture's span. The number beside it still takes what the range allows.
        ConfigDescriptor.Range range = descriptor.softRange();
        // A DECLARED STEP FIRST: `integral` is the same thing at 1.
        float step = descriptor.step() > 0f ? descriptor.step() : descriptor.integral() ? 1f : 0f;
        return range == null ? new Track(0f, 1f, step) : new Track(range.min(), range.max(), step);
    }

    private void track(Track track) {
        slider.setRange(track.min(), track.max());
        if (track.step() > 0f) slider.setStep(track.step());
    }

    public Slider slider() {
        return slider;
    }

    public NumberControl number() {
        return number;
    }

    /** While the number beside the slider holds typed text that has not landed. */
    @Override
    public boolean isEditing() {
        return number.isEditing();
    }

    @Override
    protected void writeToWidgets(@Nullable Double value) {
        double d = value == null ? 0d : value;
        slider.setValue((float) d);
        number.setValue(d);
    }
}
