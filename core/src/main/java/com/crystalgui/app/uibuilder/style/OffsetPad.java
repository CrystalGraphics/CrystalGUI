package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.input.DragScrub;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.control.AnchorControl;
import com.crystalgui.widget.config.control.VectorControl;

/**
 * A point put on a pad, dragged or typed: the pad and its two fields, one row's control.
 *
 * <pre>{@code
 * lab.form().control("lab.offset", "Offset", new OffsetPad("lab.offset").bind(at));   // px from the crosshair
 * lab.form().control("lab.pivot", "Pivot",
 *         new OffsetPad("lab.pivot", OffsetPad.Space.BOX).bind(at));                  // a point ON the box
 * }</pre>
 *
 * <p>A drag moves the value by what the pointer moved and is one undo step. A right press puts it back — to nothing,
 * or to the middle of the box. <b>The dot stops at the pad's edge; the value does not.</b></p>
 */
public final class OffsetPad extends ValueControl<double[]> {

    public static final Name NAME = Name.of("offsetpad");

    /**
     * What the pad stands for, and so what its value is.
     *
     * <p>One control rather than two, because the difference between them is where the dot sits and what the numbers
     * are called: everything else — the pad, the fields, the drag, the reset — is the same gesture.</p>
     */
    public enum Space {
        /**
         * Pixels from the crosshair at its centre: a shadow's offset, a translate. Ctrl moves it a tenth as far, to a
         * tenth of a pixel, and Shift holds it to the axis it first moved along.
         */
        OFFSET,
        /**
         * The element's own box, 0 at its top left and 1 at its bottom right: a transform's pivot. The value is a pair
         * of FRACTIONS, shown as the percentages a sheet writes, and <b>the nine places anyone means are cells beside
         * the pad</b> — a drag snaps to those same nine unless Ctrl is down, and reaches half a box past either edge.
         */
        BOX
    }

    /** The row: the pad and its two fields, and for a box the nine cells in front of them. */
    public static final String FIELD_CLASS = "__offset-field__";
    public static final String PAD_CLASS = "__offset-pad__";
    public static final String DOT_CLASS = "__offset-dot__";
    public static final String AXIS_CLASS = "__offset-axis__";
    public static final String HORIZONTAL_CLASS = "__horizontal__";
    public static final String VERTICAL_CLASS = "__vertical__";
    /** On the row and the pad of a {@link Space#BOX} pad, whose dot is placed at a fraction of it. */
    public static final String BOX_CLASS = "__box__";
    /** Where a box's dot travels: the pad inset by half a dot, so the ends of the range are drawn whole. */
    public static final String TRACK_CLASS = "__offset-track__";

    /** How far the dot may travel from the crosshair, in px: half the pad less half the dot. */
    private static final double DOT_REACH = 14d;

    /** The middle of a box, where a pivot starts and what a right press puts it back to. */
    private static final double[] CENTRE = {0.5d, 0.5d};

    /** How near one of the nine a dragged pivot lands on it, as a fraction of the box. */
    private static final double SNAP = 0.04d;

    /**
     * How far past the box a drag on the pad may take the point. A pivot outside the element is legal CSS and
     * sometimes the point — a hinge past the edge, a hand swinging about a centre somewhere else — so the pad
     * reaches half a box either way rather than stopping at its own edges. The dot stops there; the value does
     * not, which is what the offset pad does with its own unbounded space.
     */
    private static final double[] BOX_RANGE = {-0.5d, 1.5d};

    private final Space space;
    private final UIElement pad = new UIElement();
    private final UIElement dot = new UIElement();

    public OffsetPad(String id) {
        this(id, Space.OFFSET);
    }

    public OffsetPad(String id, Space space) {
        super(NAME, ConfigDescriptor.vector(id, "", 2), space == Space.BOX ? CENTRE.clone() : new double[2]);
        this.space = space;
        addClass(FIELD_CLASS);
        if (space == Space.BOX) {
            addClass(BOX_CLASS);
            pad.addClass(BOX_CLASS);
            // THE NINE PLACES, the kit's own picker, as the pad's presets: a cell lights while the value sits on it
            // and none while it is between them, so the cells and the dot cannot disagree.
            AnchorControl anchors = new AnchorControl(ConfigDescriptor.anchor(id + ".anchor", ""), CENTRE.clone());
            anchors.bind(Property.derived(this::current, this::commitAndShow));
            append(anchors);
        }

        pad.addClass(PAD_CLASS);
        // A CROSSHAIR, so zero is somewhere: a dot on a blank square has no scale and no origin. On a box it is the
        // centre, which is the one place that reads without a number.
        for (String axis : new String[] {HORIZONTAL_CLASS, VERTICAL_CLASS}) {
            UIElement line = new UIElement();
            line.addClass(AXIS_CLASS);
            line.addClass(axis);
            pad.append(line);
        }
        dot.addClass(DOT_CLASS);
        if (space == Space.BOX) {
            // INSET BY HALF THE DOT, as the offset pad's own reach is: at the far edge of the box half of it was
            // outside the pad and the pad clips.
            UIElement track = new UIElement();
            track.addClass(TRACK_CLASS);
            track.append(dot);
            pad.append(track);
        } else {
            pad.append(dot);
        }
        append(pad);

        if (space == Space.BOX) {
            dragOverBox();
        } else {
            dragByPixels();
        }
        pad.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            commitAndShow(origin());
            event.preventDefault();
        }, false, true);

        // THE NUMBERS, for a value too exact to drag to: one X/Y vector, as every other pair in the kit.
        // COMMITTED AS TYPED, so the dot follows each digit rather than waiting for Enter.
        ConfigDescriptor typed = ConfigDescriptor.vector(id + ".xy", "", 2).commitWhileTyping(true)
                .unit(space == Space.BOX ? "%" : "px");
        if (space == Space.BOX) typed = typed.decimals(1).softRange(0f, 100f);
        VectorControl numbers = new VectorControl(typed, shown(origin()));
        numbers.bind(Property.derived(() -> shown(current()), value -> commitAndShow(value == null ? origin()
                : space == Space.BOX ? new double[] {value[0] / 100d, value[1] / 100d}
                : new double[] {tenth(value[0]), tenth(value[1])})));
        append(numbers);
    }

    /** A PIXEL OF TRAVEL IS A PIXEL OF OFFSET, from the first pixel, so the dot stays under the pointer. */
    private void dragByPixels() {
        DragScrub.Gesture drag = new DragScrub.Gesture(DragScrub.Spec.FLOAT).threshold(0f).shiftLocksAxis();
        StyleGizmos.drag(pad, () -> {
            drag.begin(current());
            begin();
        }, (dx, dy) -> {
            if (drag.update(dx, dy)) commitAndShow(drag.point());
        }, this::end);
    }

    /** A drag across the pad is the same fraction of the box, so the dot stays under the pointer there too. */
    private void dragOverBox() {
        double[] from = new double[2];
        StyleGizmos.drag(pad, () -> {
            double[] now = current();
            from[0] = now[0];
            from[1] = now[1];
            begin();
        }, (dx, dy) -> {
            if (pad.box() == null || pad.box().width() <= 0f || pad.box().height() <= 0f) return;
            commitAndShow(new double[] {onBox(from[0] + dx / pad.box().width()),
                    onBox(from[1] + dy / pad.box().height())});
        }, this::end);
    }

    /** Opens the gesture, and the undo run it is one step of. */
    private void begin() {
        beginInteraction();
        UndoStack history = property().history();
        if (history != null) history.beginMergeRun();
    }

    /** Closes both, on every exit path a drag has. */
    private void end() {
        UndoStack history = property().history();
        if (history != null) history.endMergeRun();
        endInteraction();
    }

    /**
     * A fraction of a box as a drag lands it: held to {@link #BOX_RANGE}, and snapped to the nine places the cells
     * offer unless Ctrl is down.
     *
     * <pre>{@code
     * at.set(new double[] {OffsetPad.onBox(from[0] + dx / w), OffsetPad.onBox(from[1] + dy / h)});
     * }</pre>
     *
     * <p><b>Public because a pad is not the only thing a point is dragged on</b> — the transform lab drags the same
     * pivot on its specimen, and two copies of a snap are two snaps that drift apart.</p>
     */
    public static double onBox(double fraction) {
        double held = Math.max(BOX_RANGE[0], Math.min(BOX_RANGE[1], fraction));
        if (!CgModifiers.hasCtrl(CgPlatform.input().getCurrentModifiers())) {
            for (double ninth : new double[] {0d, 0.5d, 1d}) {
                if (Math.abs(held - ninth) < SNAP) return ninth;
            }
        }
        return Math.round(held * 1000d) / 1000d;
    }

    private double[] current() {
        double[] now = getValue();
        return now == null || now.length < 2 ? origin() : now;
    }

    /** What a right press writes: no offset at all, or the middle of the box. */
    private double[] origin() {
        return space == Space.BOX ? CENTRE.clone() : new double[2];
    }

    /** The value as its fields say it: pixels, or the percentage of the box a fraction is. */
    private double[] shown(double[] value) {
        return space == Space.BOX ? new double[] {value[0] * 100d, value[1] * 100d} : value;
    }

    private static double tenth(double value) {
        return Math.round(value * 10d) / 10d;
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        double[] at = value == null || value.length < 2 ? origin() : value;
        if (space == Space.BOX) {
            // THE DOT STOPS AT THE EDGE: the pad is the box, and a pivot outside it is still a pivot.
            LiveEdits.setInline(dot, LayoutProperties.LEFT, CssValues.write(clamped(at[0]) * 100d) + "%");
            LiveEdits.setInline(dot, LayoutProperties.TOP, CssValues.write(clamped(at[1]) * 100d) + "%");
            return;
        }
        LiveEdits.setInline(dot, StylePropertyRegistry.TRANSFORM,
                "translate(" + CssValues.px(clampToPad(at[0])) + ", " + CssValues.px(clampToPad(at[1])) + ")");
    }

    private static double clampToPad(double offset) {
        return Math.max(-DOT_REACH, Math.min(DOT_REACH, offset));
    }

    private static double clamped(double fraction) {
        return Math.max(0d, Math.min(1d, fraction));
    }
}
