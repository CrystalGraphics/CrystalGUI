package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.input.DragScrub;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.control.VectorControl;

/**
 * An offset in pixels, dragged on a pad or typed: {@code [x, y]}, drawn as a dot that far from the pad's crosshair.
 * A pad and an X/Y vector beside it, one row's control.
 *
 * <pre>{@code
 * lab.form().control("lab.offset", "Offset", new OffsetPad("lab.offset")
 *         .bind(shadow.map(Shadow::offset, at -> shadow.get().withOffset(at))));
 * }</pre>
 *
 * <p>A drag moves the value by what the pointer moved and is one undo step. Ctrl moves it a tenth as far, to a tenth
 * of a pixel; Shift holds it to the axis it first moved along; a right press puts it back at zero. The dot stops at
 * the pad's edge; the value does not.</p>
 */
public final class OffsetPad extends ValueControl<double[]> {

    public static final Name NAME = Name.of("offsetpad");

    /** The row: the pad and its two fields. */
    public static final String FIELD_CLASS = "__offset-field__";
    public static final String PAD_CLASS = "__offset-pad__";
    public static final String DOT_CLASS = "__offset-dot__";
    public static final String AXIS_CLASS = "__offset-axis__";
    public static final String HORIZONTAL_CLASS = "__horizontal__";
    public static final String VERTICAL_CLASS = "__vertical__";

    /** How far the dot may travel from the crosshair, in px: half the pad less half the dot. */
    private static final double DOT_REACH = 14d;

    private final UIElement pad = new UIElement();
    private final UIElement dot = new UIElement();

    public OffsetPad(String id) {
        super(NAME, ConfigDescriptor.vector(id, "", 2), new double[2]);
        addClass(FIELD_CLASS);
        pad.addClass(PAD_CLASS);
        // A CROSSHAIR, so zero is somewhere: a dot on a blank square has no scale and no origin.
        for (String axis : new String[] {HORIZONTAL_CLASS, VERTICAL_CLASS}) {
            UIElement line = new UIElement();
            line.addClass(AXIS_CLASS);
            line.addClass(axis);
            pad.append(line);
        }
        dot.addClass(DOT_CLASS);
        pad.append(dot);
        append(pad);

        // A PIXEL OF TRAVEL IS A PIXEL OF OFFSET, from the first pixel, so the dot stays under the pointer.
        DragScrub.Gesture drag = new DragScrub.Gesture(DragScrub.Spec.FLOAT).threshold(0f).shiftLocksAxis();
        StyleGizmos.drag(pad, () -> {
            drag.begin(current());
            beginInteraction();
        }, (dx, dy) -> {
            if (drag.update(dx, dy)) commitAndShow(drag.point());
        }, this::endInteraction);
        pad.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            commitAndShow(new double[2]);
            event.preventDefault();
        }, false, true);

        // THE NUMBERS, for a value too exact to drag to: one X/Y vector, as every other pair of lengths in the kit.
        // COMMITTED AS TYPED, so the dot follows each digit rather than waiting for Enter.
        VectorControl numbers = new VectorControl(ConfigDescriptor.vector(id + ".xy", "", 2).unit("px")
                .commitWhileTyping(true), new double[2]);
        numbers.bind(Property.derived(this::current,
                typed -> commitAndShow(typed == null ? new double[2] : new double[] {tenth(typed[0]), tenth(typed[1])})));
        append(numbers);
    }

    private double[] current() {
        double[] now = getValue();
        return now == null ? new double[2] : now;
    }

    private static double tenth(double value) {
        return Math.round(value * 10d) / 10d;
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        double x = value == null ? 0d : clampToPad(value[0]);
        double y = value == null ? 0d : clampToPad(value[1]);
        LiveEdits.setInline(dot, StylePropertyRegistry.TRANSFORM,
                "translate(" + CssValues.px(x) + ", " + CssValues.px(y) + ")");
    }

    private static double clampToPad(double offset) {
        return Math.max(-DOT_REACH, Math.min(DOT_REACH, offset));
    }
}
