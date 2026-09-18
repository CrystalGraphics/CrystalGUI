package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.transform.PivotMark;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.box.Box;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.CssAngle;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.PanelForm;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The transform lab: the ops in the order they compose, and the picked one edited by the gesture its own kind wants.
 *
 * <pre>{@code
 * TransformLab.open(chip, StylePropertyRegistry.TRANSFORM, css, fields.canWrite(), node);
 * }</pre>
 *
 * <p><b>Order is the semantics.</b> {@code translate(10px) scale(2)} and {@code scale(2) translate(10px)} put the
 * element in different places, so the stack is the editor. <b>The rows follow the picked op</b>: a pad for a translate,
 * a dial for a rotate, factors for a scale, angles for a shear -- and the function's own text for anything else, so a
 * value this lab has no gesture for is still editable. Rows for the other kinds are not shown, rather than shown and
 * inert.</p>
 *
 * <p>The specimen is a mark on a plate with a <b>ghost of where it started</b> behind it: a translate on an empty plate
 * moves a plate on an empty stage, and a rotate and a flip leave a rectangle looking like a rectangle. An {@code F} is
 * asymmetric on both axes, which is why every graphics text draws one.</p>
 *
 * <p>The <b>pivot</b> every op turns and grows about is edited here too — {@code transform-origin}, which means
 * nothing on its own — as the canvas's own mark on the box, and as the nine places and two percentages of a row.</p>
 */
public final class TransformLab {

    /** On the transform lab's window. */
    public static final String LAB_CLASS = "__transform-lab__";

    /** Asymmetric both ways, so a turn, a flip and a shear are each legible on it. The row chips draw the same one. */
    static final String MARK = "F";

    /** The pivot on the box: a zero-sized anchor at the point, and the mark drawn around it. */
    public static final String PIVOT_CLASS = "__lab-pivot__";
    public static final String PIVOT_MARK_CLASS = "__lab-pivot-mark__";

    private static final String TRANSLATE = "translate";
    private static final String ROTATE = "rotate";
    private static final String SCALE = "scale";
    private static final String SKEW = "skew";

    /** What each kind is added as -- identity, so adding one changes nothing until it is dragged. */
    private static final String[] ADDED = {"translate(0px, 0px)", "rotate(0deg)", "scale(1)", "skew(0deg, 0deg)"};

    /**
     * How far a factor is dragged and typed, as the PERCENTAGE its fields show: CSS writes a scale as a bare
     * multiplier, and nobody reads 1.24 as a quarter bigger. The free transform bar says 111.45% for the same
     * quantity.
     */
    private static final float SCALE_REACH = 400f;
    private static final float SCALE_MAX = 10000f;

    /**
     * What a scrub is worth, across this lab: a WHOLE unit — a percent, a degree — every three pixels. The rate
     * alone moved a third of one per pixel, so a degree field crept 0.3 at a time; the step is what makes the
     * gesture land on units. A typed value keeps its decimals.
     */
    private static final double SCRUB_RATE = 1d / 3d;
    private static final float SCRUB_STEP = 1f;

    /**
     * As near the right angle as a tenth of a degree gets, because 90° is not a large shear but the asymptote: a
     * shear is {@code tan(angle)} ({@code Transform.shear}), which is undefined there — the matrix goes singular
     * and the element has no area left to draw. Every angle short of it is a real shear, so the field stops at the
     * last one it can print rather than at some rounder number.
     */
    private static final float SKEW_MAX = 89.9f;

    private static final String ORIGIN_X = "transform-origin-x";
    private static final String ORIGIN_Y = "transform-origin-y";

    /** What a sample holds a translate and a scale to, so a 28x16 patch shows the mark and not a corner of it. */
    private static final double SAMPLE_SHIFT = 4d;
    private static final double SAMPLE_SCALE = 1.5d;

    private TransformLab() {
    }

    /** @param hideable whether an op may be switched off rather than deleted, which a writable value can */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css, boolean hideable) {
        open(anchor, property, css, hideable, null, null);
    }

    /**
     * @param node   the element being transformed, for the one question the lab has to ask it: how long a pivot
     *               written in pixels is as a fraction of the box. Null reads such a pivot as the middle
     * @param fields what the target is styled by, for the pivot the ops turn about -- which is a declaration of its
     *               own and edited here because it means nothing without one. Null leaves the row out
     */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css, boolean hideable,
                            @Nullable UIElement node, @Nullable StyleFields fields) {
        StyleLab lab = StyleLab.over(anchor, "Transform").addClass(LAB_CLASS);
        // THE LAB'S OWN MARK, not the element's colour and face: a transform is geometry, and the element's white
        // text on the white plate was a specimen you could not see at all. The sheet colours it per plate.
        lab.specimen(new UIText(MARK)).ghost(new UIText(MARK)).preview(property, css);

        Property<Integer> selected = Property.of(0);
        Property<List<String>> ops = css.map(CssValues::functionStack, CssValues::joinFunctionStack);
        Property<String> op = Property.derived(
                () -> CssValues.bodyOf(at(ops.get(), selected.get())),
                next -> {
                    List<String> list = new ArrayList<>(ops.get());
                    int index = selected.get() == null ? 0 : selected.get();
                    if (index >= 0 && index < list.size()) list.set(index, CssValues.withBody(list.get(index), next));
                    ops.set(list);
                }).editedIn(css.history());

        // THE STACK FIRST: it chooses which op the rows under it edit, as the shadow lab's does.
        LayerStack stack = new LayerStack("lab.ops", property, selected).titled("Transforms").hideable(hideable);
        // THE MARK CARRIES IT, not the patch: a patch cannot clip its own transform, and a rotated one drew over
        // the rows either side of it.
        stack.sample(patch -> {
            UIText glyph = new UIText(MARK);
            glyph.addClass(LayerStack.SAMPLE_TEXT_CLASS);
            patch.append(glyph);
            return glyph;
        }, (glyph, layer) -> LiveEdits.setInline(glyph, property, fitted(layer)));
        for (String added : ADDED) {
            stack.adding("+ " + CssValues.functionName(added), () -> {
                List<String> list = new ArrayList<>(ops.get());
                list.add(added);
                ops.set(list);
                selected.set(list.size() - 1);
            });
        }
        stack.bind(ops);
        lab.content().append(stack);

        // W AND H, which is what a scale's two factors are: the free transform bar names them the same.
        LinkedPair factors = new LinkedPair("lab.scale", factorField("lab.scale.x", "W"),
                factorField("lab.scale.y", "H"));
        Rows rows = rows(lab.form(), property, op, factors);
        if (fields != null) pivot(lab, fields, node);

        // OPENED AS THE OP IS, and re-read when another is picked: a scale whose axes agree opens chained.
        PropertyWatch.follow(lab.content(), selected, index -> {
            if (SCALE.equals(CssValues.functionName(op.get()))) {
                factors.linked(factor(op.get(), 0) == factor(op.get(), 1));
            }
        });
        // ONLY THE ROWS THE PICKED OP IS EDITED BY.
        PropertyWatch.follow(lab.content(), Property.derived(() -> CssValues.functionName(op.get())), rows::show);

        lab.caption(Property.derived(() -> {
            int count = ops.get().size();
            if (count == 0) return "no transform — add one above";
            String kind = CssValues.functionName(op.get());
            return count + (count == 1 ? " op" : " ops, applied left to right")
                    + (kind.isEmpty() ? "" : " — editing " + kind);
        }));
        lab.readout(property.name, css);
        lab.open();
    }

    /** Every row the lab has, and which kind each belongs to. @see #show */
    private record Rows(Configurator offset, Configurator angle, UIElement flips, Configurator scale,
                        Configurator skew, Configurator raw) {

        /** Shows the picked kind's rows and hides the rest. */
        void show(String kind) {
            boolean scaling = SCALE.equals(kind);
            offset.set(Attribute.HIDDEN, !TRANSLATE.equals(kind));
            angle.set(Attribute.HIDDEN, !ROTATE.equals(kind));
            flips.set(Attribute.HIDDEN, !scaling);
            scale.set(Attribute.HIDDEN, !scaling);
            skew.set(Attribute.HIDDEN, !SKEW.equals(kind));
            // THE TEXT IS THE FLOOR, not a fallback: matrix() and anything a future engine adds is edited here. An
            // empty stack has no op to edit at all, and the caption is what says so.
            boolean known = TRANSLATE.equals(kind) || ROTATE.equals(kind) || scaling || SKEW.equals(kind);
            raw.set(Attribute.HIDDEN, known || kind.isEmpty());
        }
    }

    private static Rows rows(PanelForm form, StyleProperty<?> property, Property<String> op, LinkedPair factors) {
        // A PIXEL OF HAND IS A PIXEL OF OFFSET, on the pad the shadow lab's offsets use.
        Configurator offset = form.control("lab.translate", "Translate", new OffsetPad("lab.translate")
                .bind(op.map(TransformLab::translation, at -> is(op, TRANSLATE)
                        ? CssValues.function(TRANSLATE, length(op.get(), 0, at[0]), length(op.get(), 1, at[1]))
                        : op.get())));
        // DEGREES, WHATEVER THE FILE SAYS: the engine writes an op's angle in radians, so `rotate(0.78rad)` read as
        // a number was 0.78 degrees on the dial and a value nobody could get back.
        Configurator angle = form.control("lab.rotate", "Angle", new AngleDial("lab.rotate")
                .bind(op.map(text -> degrees(text, 0),
                        turned -> is(op, ROTATE) ? CssValues.function(ROTATE, deg(turned)) : op.get())));

        UIElement flips = form.custom(flips(op));
        // ONE ROW WITH A CHAIN IN IT, as the free transform bar's W and H: two factors that move together while it
        // holds, at the ratio they had.
        Configurator scale = form.control("lab.scale", "Scale", factors.bind(factors(op)));

        // ONE ROW FOR THE PAIR, as every other two-axis value in the kit is: X and Y of one shear, not two shears.
        Configurator skew = form.prop(ConfigDescriptor.vector("lab.skew", "Skew", 2)
                .range(-SKEW_MAX, SKEW_MAX).unit("°").decimals(1)
                .scrubRate(SCRUB_RATE).step(SCRUB_STEP), shear(op));

        Configurator raw = form.prop(ConfigDescriptor.text("lab.op", "Function").placeholder("none")
                        .validator(text -> text == null || text.isBlank()
                                || DeclarationEditors.parses(property, text)),
                op.map(CssValues::readable, typed -> typed == null ? "" : typed.trim()));
        return new Rows(offset, angle, flips, scale, skew, raw);
    }

    /**
     * The pivot: {@code transform-origin-x} and {@code -y}, <b>dragged on the specimen</b> where it can be seen, and
     * typed as the two percentages it is.
     *
     * <p>A mark on the thing rather than a picker beside it, because a point on a box is the one value a grid of nine
     * cells cannot explain — the mark sits where the specimen visibly turns, and dragging it near a corner, an edge's
     * middle or the centre snaps it there, which is what the nine cells were for.</p>
     *
     * <p>Written as a percentage of the box, which is what the canvas's own free transform writes and what keeps a
     * pivot meaning the same thing as the element resizes.</p>
     */
    private static void pivot(StyleLab lab, StyleFields fields, @Nullable UIElement node) {
        Property<String> x = fields.value(ORIGIN_X);
        Property<String> y = fields.value(ORIGIN_Y);
        // ON THE SPECIMEN TOO, or the mark moves a point nothing on the stage turns about.
        lab.also(StylePropertyRegistry.TRANSFORM_ORIGIN_X, x).also(StylePropertyRegistry.TRANSFORM_ORIGIN_Y, y);

        // ONE EDIT, not one per declaration: the pair is placed by one gesture. @see StyleLab
        Property<double[]> at = Property.derived(
                () -> new double[] {fraction(x.get(), node, true), fraction(y.get(), node, false)},
                next -> {
                    x.set(percent(next[0]));
                    y.set(percent(next[1]));
                }).editedIn(fields.history());

        lab.mark(pin(at, fields));
        // ONE ROW, under a rule: the nine places, the pad, and the two percentages. @see OffsetPad.Space#BOX
        lab.form().separator();
        lab.form().control("lab.pivot", "Pivot", new OffsetPad("lab.pivot", OffsetPad.Space.BOX).bind(at));
    }

    /**
     * The pivot's mark: a ring with a cross through it, at {@code at} of the specimen's own box, dragged to move it.
     *
     * <p>A zero-sized anchor holding the drawing, so nothing has to match a negative margin to its size. It is on
     * the element's own box rather than the transformed one: an origin is measured before anything is applied, which
     * is the box the ghost draws.</p>
     */
    private static UIElement pin(Property<double[]> at, StyleFields fields) {
        UIElement pin = new UIElement();
        pin.addClass(PIVOT_CLASS);
        UIElement mark = new Mark();
        mark.addClass(PIVOT_MARK_CLASS);
        pin.append(mark);
        // IN MEASURED PIXELS, not a percentage: a percentage inset resolves against the containing block, and this
        // layer's own size is its content's -- so the engine answered zero and the mark sat in the corner whatever
        // the pivot said. Re-read every frame, so it follows the box as well as the value.
        PropertyWatch.follow(pin, Property.derived(() -> on(pin, at.get(), true)),
                left -> LiveEdits.setInline(pin, LayoutProperties.LEFT, left));
        PropertyWatch.follow(pin, Property.derived(() -> on(pin, at.get(), false)),
                top -> LiveEdits.setInline(pin, LayoutProperties.TOP, top));

        double[] from = new double[2];
        StyleGizmos.drag(mark, () -> {
            double[] now = at.get();
            from[0] = now[0];
            from[1] = now[1];
            // ONE UNDO STEP FOR THE DRAG, not one a frame: the run is closed on every exit path below.
            UndoStack history = fields.history();
            if (history != null) history.beginMergeRun();
        }, (dx, dy) -> {
            UIElement box = pin.parentElement();
            if (box == null || box.box() == null || box.box().width() <= 0f || box.box().height() <= 0f) return;
            // THE PAD'S OWN RULE, so the mark and the pad snap alike. @see OffsetPad#onBox
            at.set(new double[] {OffsetPad.onBox(from[0] + dx / box.box().width()),
                    OffsetPad.onBox(from[1] + dy / box.box().height())});
        }, () -> {
            UndoStack history = fields.history();
            if (history != null) history.endMergeRun();
        });
        return pin;
    }

    /**
     * The mark itself, drawn as the canvas's free transform draws its pivot — the same ring and arms over the same
     * halo, from the same painter, so the two are recognisably one thing. The colours are the two the sheet lends
     * it, as they are there. @see PivotMark
     */
    private static final class Mark extends UIElement {
        @Override
        public void paintContent(CgUiPaintContext paint, Box box) {
            PivotMark.paint(paint, box.width() / 2f, box.height() / 2f,
                    getStyle().computed().get(StylePropertyRegistry.OUTLINE_COLOR),
                    getStyle().computed().get(StylePropertyRegistry.TEXT_DECORATION_COLOR));
        }
    }

    /**
     * Where the mark sits on the box it is drawn over, in px — the fraction of a side it is given.
     *
     * <p><b>Not rounded to a whole pixel.</b> The stage is a zoomable plane, so a logical pixel is thirteen of them
     * at thirteen times: a mark snapped to whole ones stepped visibly under a pointer moving smoothly.</p>
     */
    private static String on(UIElement pin, double[] at, boolean horizontal) {
        UIElement box = pin.parentElement();
        float side = box == null || box.box() == null ? 0f : horizontal ? box.box().width() : box.box().height();
        return CssValues.px((horizontal ? at[0] : at[1]) * side);
    }

    /**
     * An origin as a fraction of the box. A percentage is one already; a length is one at the size the element is
     * now, and half without a box to ask -- which is the property's own initial.
     */
    private static double fraction(@Nullable String css, @Nullable UIElement node, boolean horizontal) {
        String text = css == null ? "" : css.trim();
        if (text.isEmpty()) return 0.5d;
        if (text.endsWith("%")) return CssValues.number(text.substring(0, text.length() - 1), 50f) / 100d;
        float side = node == null || node.box() == null ? 0f
                : horizontal ? node.box().width() : node.box().height();
        return side <= 0f ? 0.5d : CssValues.number(text, 0f) / side;
    }

    private static String percent(double fraction) {
        return CssValues.write(Math.round(fraction * 1000d) / 10d) + "%";
    }

    /** Mirroring is a negative factor and nothing a slider finds, so each axis has a button. */
    private static UIElement flips(Property<String> op) {
        UIElement row = new UIElement();
        row.addClass(StyleLab.ROW_CLASS);
        row.addClass(StyleLab.PRESETS_CLASS);
        for (int axis = 0; axis < 2; axis++) {
            int flipped = axis;
            Button button = new Button(axis == 0 ? "Flip X" : "Flip Y");
            button.addClass(StyleLab.KEYWORD_CLASS);
            button.attachListener(() -> {
                if (!is(op, SCALE)) return;
                double x = factor(op.get(), 0);
                double y = factor(op.get(), 1);
                op.set(flipped == 0 ? scaled(-x, y) : scaled(x, -y));
            });
            PropertyWatch.follow(button, op,
                    now -> button.toggleClass(StyleLab.ACTIVE_CLASS, factor(now, flipped) < 0d));
            row.append(button);
        }
        Button reset = new Button("Reset");
        reset.addClass(StyleLab.KEYWORD_CLASS);
        reset.attachListener(() -> {
            if (is(op, SCALE)) op.set(scaled(1d, 1d));
        });
        row.append(reset);
        return row;
    }

    /** A factor field, as a percentage: dragged to {@link #SCALE_REACH}, typed as far as anyone likes. */
    private static ConfigDescriptor factorField(String id, String label) {
        return ConfigDescriptor.number(id, label).range(-SCALE_MAX, SCALE_MAX)
                .softRange(-SCALE_REACH, SCALE_REACH).unit("%").decimals(2)
                .scrubRate(SCRUB_RATE).step(SCRUB_STEP);
    }

    /** A scale's two factors as the percentages the fields show, which the chain keeps in proportion. */
    private static Property<double[]> factors(Property<String> op) {
        return op.map(text -> new double[] {factor(text, 0) * 100d, factor(text, 1) * 100d},
                value -> !is(op, SCALE) || value == null ? op.get() : scaled(value[0] / 100d, value[1] / 100d));
    }

    /** A shear's two angles, in degrees whatever the file spells them in. */
    private static Property<double[]> shear(Property<String> op) {
        return op.map(text -> new double[] {degrees(text, 0), degrees(text, 1)},
                value -> !is(op, SKEW) || value == null ? op.get()
                        : CssValues.function(SKEW, deg(value[0]), deg(value[1])));
    }

    /** {@code scale(2)} where the axes agree, as a person writes it, and two arguments where they do not. */
    private static String scaled(double x, double y) {
        // FOUR PLACES, not the two a dragged length carries: a multiplier's third place is a tenth of a percent.
        double sx = Math.round(x * 10000d) / 10000d;
        double sy = Math.round(y * 10000d) / 10000d;
        return sx == sy ? CssValues.function(SCALE, CssValues.write(sx))
                : CssValues.function(SCALE, CssValues.write(sx), CssValues.write(sy));
    }

    private static boolean is(Property<String> op, String kind) {
        return kind.equals(CssValues.functionName(op.get()));
    }

    /** {@code translate}'s two lengths in px, or zero for an op of another kind. */
    private static double[] translation(String op) {
        if (!TRANSLATE.equals(CssValues.functionName(op))) return new double[2];
        return new double[] {CssValues.number(argument(op, 0), 0f), CssValues.number(argument(op, 1), 0f)};
    }

    /** A translate's axis as it is spelled: a percentage stays one, so a value of the box's own width survives. */
    private static String length(String op, int axis, double value) {
        return argument(op, axis).trim().endsWith("%")
                ? CssValues.write(CssValues.dragged(value)) + "%" : CssValues.px(CssValues.dragged(value));
    }

    /**
     * An angle argument in degrees, however it is spelled -- {@code deg}, {@code rad}, {@code grad}, {@code turn}.
     * Zero for an op that is not an angle's, and for one whose angle does not parse.
     */
    private static double degrees(String op, int axis) {
        String kind = CssValues.functionName(op);
        if (!ROTATE.equals(kind) && !SKEW.equals(kind)) return 0d;
        Float radians = CssAngle.parse(argument(op, axis));
        return radians == null ? 0d : Math.round(Math.toDegrees(radians) * 100d) / 100d;
    }

    private static String deg(double degrees) {
        return CssValues.write(CssValues.dragged(degrees)) + "deg";
    }

    /**
     * A scale's factor on one axis. {@code scale(2)} is CSS for both axes, unlike {@code translate}, whose second
     * argument defaults to zero -- so a missing second factor is the first.
     */
    private static double factor(String op, int axis) {
        if (!SCALE.equals(CssValues.functionName(op))) return 1d;
        List<String> arguments = CssValues.layers(CssValues.arguments(op));
        if (arguments.isEmpty()) return 1d;
        return CssValues.number(arguments.get(Math.min(axis, arguments.size() - 1)), 1f);
    }

    /** Argument {@code index} of a function, or "" — the ops that take two default the second themselves. */
    private static String argument(String op, int index) {
        List<String> arguments = CssValues.layers(CssValues.arguments(op));
        return index >= 0 && index < arguments.size() ? arguments.get(index) : "";
    }

    /**
     * One op as a 28x16 row sample can show it: a translate and a scale are held to what fits, so the mark stays in
     * the patch, while a rotate and a shear are drawn as they are. <b>Display only.</b>
     */
    static String fitted(String op) {
        String kind = CssValues.functionName(op);
        if (TRANSLATE.equals(kind)) {
            double[] at = translation(op);
            return CssValues.function(TRANSLATE, CssValues.px(clamp(at[0], SAMPLE_SHIFT)),
                    CssValues.px(clamp(at[1], SAMPLE_SHIFT)));
        }
        if (SCALE.equals(kind)) {
            return scaled(clamp(factor(op, 0), SAMPLE_SCALE), clamp(factor(op, 1), SAMPLE_SCALE));
        }
        return op;
    }

    /** A whole {@code transform} as a swatch can show it, every op {@link #fitted}. <b>Display only.</b> */
    static String fittedValue(String css) {
        List<String> out = new ArrayList<>();
        for (String function : CssValues.functions(css)) out.add(fitted(function));
        return CssValues.joinFunctions(out);
    }

    private static double clamp(double value, double reach) {
        return Math.max(-reach, Math.min(reach, value));
    }

    private static String at(List<String> ops, Integer index) {
        int at = index == null ? 0 : index;
        return at >= 0 && at < ops.size() ? ops.get(at) : "";
    }
}
