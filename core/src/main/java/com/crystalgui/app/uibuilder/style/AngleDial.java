package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.control.NumberControl;

/**
 * An angle in whole degrees, chosen by pointing or typed: clockwise from up, CSS's own reading of a gradient's
 * angle. A dial and a degrees field, one row's control.
 *
 * <pre>{@code
 * lab.form().control("lab.angle", "Angle", new AngleDial("lab.angle")
 *         .bind(gradient.map(g -> (double) g.angle(), deg -> gradient.get().withDirection(deg + "deg"))));
 * }</pre>
 */
public final class AngleDial extends ValueControl<Double> {

    public static final Name NAME = Name.of("angledial");

    /** The row: the dial and the field beside it. */
    public static final String FIELD_CLASS = "__angle-field__";
    public static final String DIAL_CLASS = "__angle-dial__";
    public static final String NEEDLE_CLASS = "__angle-needle__";

    private final UIElement dial = new UIElement();
    private final UIElement needle = new UIElement();

    public AngleDial(String id) {
        super(NAME, ConfigDescriptor.number(id, "").unit("°"), 0d);
        addClass(FIELD_CLASS);
        dial.addClass(DIAL_CLASS);
        needle.addClass(NEEDLE_CLASS);
        dial.append(needle);
        append(dial);
        StyleGizmos.aim(dial, this::beginInteraction, degrees -> commitAndShow((double) Math.round(degrees)),
                this::endInteraction);

        // THE NUMBER BESIDE IT, for an exact angle: pointing reaches a degree only with a steady hand.
        NumberControl degrees = new NumberControl(ConfigDescriptor.number(id + ".degrees", "")
                .range(0f, 360f).unit("°").integral(true), 0d);
        degrees.bind(Property.derived(() -> {
            Double now = getValue();
            return now == null ? 0d : now;
        }, typed -> commitAndShow(typed == null ? 0d : (double) Math.round(typed))));
        append(degrees);
    }

    @Override
    protected void writeToWidgets(@Nullable Double degrees) {
        LiveEdits.setInline(needle, StylePropertyRegistry.TRANSFORM,
                "rotate(" + CssValues.write(degrees == null ? 0d : degrees) + "deg)");
    }
}
