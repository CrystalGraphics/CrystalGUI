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
 * An angle in whole degrees, chosen by pointing, picked or typed: clockwise from up, CSS's own reading of a gradient's
 * angle. A dial, the eight directions, and a degrees field — one row's control.
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
    /** The eight directions, a 3x3 grid with nothing in the middle. */
    public static final String PRESETS_CLASS = "__angle-presets__";
    public static final String PRESET_CLASS = "__angle-preset__";
    /** The grid's middle cell: room, and no direction. */
    public static final String PRESET_GAP_CLASS = "__angle-preset-gap__";
    public static final String ACTIVE_CLASS = "__active__";

    /** The grid's cells in reading order, as degrees clockwise from up; the middle one is empty. */
    private static final int[] PRESET_DEGREES = {315, 0, 45, 270, -1, 90, 225, 180, 135};

    private final UIElement dial = new UIElement();
    private final UIElement needle = new UIElement();
    private final UIElement[] presets = new UIElement[PRESET_DEGREES.length];
    private final NumberControl degrees;

    public AngleDial(String id) {
        super(NAME, ConfigDescriptor.number(id, "").unit("°"), 0d);
        addClass(FIELD_CLASS);
        dial.addClass(DIAL_CLASS);
        needle.addClass(NEEDLE_CLASS);
        dial.append(needle);
        append(dial);
        StyleGizmos.aim(dial, this::beginInteraction, degrees -> commitAndShow((double) Math.round(degrees)),
                this::endInteraction);

        // THE EIGHT DIRECTIONS, a press each: the sides and corners a person means most of the time, lit while the angle
        // is one of them. What a direction dropdown offered, without the "custom" that said nothing.
        UIElement grid = new UIElement();
        grid.addClass(PRESETS_CLASS);
        for (int i = 0; i < PRESET_DEGREES.length; i++) {
            UIElement cell = new UIElement();
            int degrees = PRESET_DEGREES[i];
            cell.addClass(degrees >= 0 ? PRESET_CLASS : PRESET_GAP_CLASS);
            if (degrees >= 0) {
                cell.setHitTest(true);
                LiveEdits.setInline(cell, StylePropertyRegistry.TRANSFORM, "rotate(" + degrees + "deg)");
                cell.onMouseDown.attachListener((element, event) -> {
                    commitAndShow((double) degrees);
                    event.preventDefault();
                }, false, true);
                presets[i] = cell;
            }
            grid.append(cell);
        }
        append(grid);

        // THE NUMBER BESIDE IT, for an exact angle: pointing reaches a degree only with a steady hand.
        degrees = new NumberControl(ConfigDescriptor.number(id + ".degrees", "")
                .range(0f, 360f).unit("°").integral(true), 0d);
        degrees.bind(Property.derived(() -> {
            Double now = getValue();
            return now == null ? 0d : now;
        }, typed -> commitAndShow(typed == null ? 0d : (double) Math.round(typed))));
        append(degrees);
    }

    /** The row's label scrubs the degrees, as a number field's does. */
    @Override
    public boolean adoptLabel(UIElement label) {
        return degrees.adoptLabel(label);
    }

    @Override
    protected void writeToWidgets(@Nullable Double degrees) {
        double at = degrees == null ? 0d : degrees;
        LiveEdits.setInline(needle, StylePropertyRegistry.TRANSFORM, "rotate(" + CssValues.write(at) + "deg)");
        long whole = Math.round(((at % 360d) + 360d) % 360d);
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] != null) presets[i].toggleClass(ACTIVE_CLASS, PRESET_DEGREES[i] == whole);
        }
    }
}
