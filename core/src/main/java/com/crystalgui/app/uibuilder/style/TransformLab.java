package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.CssAngle;
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
 */
public final class TransformLab {

    /** On the transform lab's window. */
    public static final String LAB_CLASS = "__transform-lab__";

    /** Asymmetric both ways, so a turn, a flip and a shear are each legible on it. */
    private static final String MARK = "F";

    private static final String TRANSLATE = "translate";
    private static final String ROTATE = "rotate";
    private static final String SCALE = "scale";
    private static final String SKEW = "skew";

    /** What each kind is added as -- identity, so adding one changes nothing until it is dragged. */
    private static final String[] ADDED = {"translate(0px, 0px)", "rotate(0deg)", "scale(1)", "skew(0deg, 0deg)"};

    /** How far a factor is dragged, and how far one may be typed: a scale has no CSS limit. */
    private static final float SCALE_REACH = 4f;
    private static final float SCALE_MAX = 999f;

    /** How far a shear is dragged, and the right angle it cannot reach: at 90° the box collapses to a line. */
    private static final float SKEW_REACH = 45f;
    private static final float SKEW_MAX = 89f;

    private static final String ORIGIN_X = "transform-origin-x";
    private static final String ORIGIN_Y = "transform-origin-y";

    /** How far an origin may be typed: outside the box is legal CSS and occasionally what somebody means. */
    private static final float ORIGIN_MAX = 999f;

    /** What a row's sample holds a translate and a scale to, so the mark stays inside a 28x16 patch. */
    private static final double SAMPLE_SHIFT = 5d;
    private static final double SAMPLE_SCALE = 1.8d;

    private TransformLab() {
    }

    /** @param hideable whether an op may be switched off rather than deleted, which a writable value can */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css, boolean hideable) {
        open(anchor, property, css, hideable, null, null);
    }

    /**
     * @param node   the element being transformed, whose color and face the mark takes, or null for the lab's own
     * @param fields what the target is styled by, for the origin the ops turn about -- which is a declaration of its
     *               own and edited here because it means nothing without one. Null leaves the group out
     */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css, boolean hideable,
                            @Nullable UIElement node, @Nullable StyleFields fields) {
        StyleLab lab = StyleLab.over(anchor, "Transform").addClass(LAB_CLASS);
        UIText mark = new UIText(MARK);
        lab.specimen(mark).ghost(new UIText(MARK)).preview(property, css);
        if (node != null) {
            // THE ELEMENT'S OWN MARK: a transform is judged against the thing being transformed.
            for (StyleProperty<?> own : List.of(StylePropertyRegistry.COLOR, StylePropertyRegistry.FONT_FAMILY)) {
                LiveEdits.follow(mark, own, TypographyLab.computed(node, own));
            }
        }
        lab.contrastWith(() -> mark.getStyle().computed().get(StylePropertyRegistry.COLOR));

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
        stack.sample(patch -> patch.append(new UIText(MARK).addClass(LayerStack.SAMPLE_TEXT_CLASS)),
                (patch, layer) -> LiveEdits.setInline(patch, property, fitted(layer)));
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

        Property<Boolean> uniform = Property.of(true);
        Rows rows = rows(lab.form(), property, op, uniform);
        if (fields != null) origin(lab, fields, node);

        // OPENED AS THE OP IS, and re-read when another is picked: a scale whose axes agree opens linked.
        PropertyWatch.follow(lab.content(), selected, index -> {
            if (SCALE.equals(CssValues.functionName(op.get()))) {
                uniform.set(factor(op.get(), 0) == factor(op.get(), 1));
            }
        });
        // ONLY THE ROWS THE PICKED OP IS EDITED BY, which two things decide: its kind, and whether a scale is linked.
        PropertyWatch.follow(lab.content(), Property.derived(() -> CssValues.functionName(op.get())),
                kind -> rows.show(kind, Boolean.TRUE.equals(uniform.get())));
        PropertyWatch.follow(lab.content(), uniform,
                linked -> rows.show(CssValues.functionName(op.get()), Boolean.TRUE.equals(linked)));

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
    private record Rows(Configurator offset, Configurator angle, Configurator uniform, UIElement flips,
                        Configurator scale, Configurator scaleX, Configurator scaleY,
                        Configurator skewX, Configurator skewY, Configurator raw) {

        /** Shows the picked kind's rows and hides the rest. */
        void show(String kind, boolean linked) {
            boolean scaling = SCALE.equals(kind);
            offset.set(Attribute.HIDDEN, !TRANSLATE.equals(kind));
            angle.set(Attribute.HIDDEN, !ROTATE.equals(kind));
            uniform.set(Attribute.HIDDEN, !scaling);
            flips.set(Attribute.HIDDEN, !scaling);
            scale.set(Attribute.HIDDEN, !scaling || !linked);
            scaleX.set(Attribute.HIDDEN, !scaling || linked);
            scaleY.set(Attribute.HIDDEN, !scaling || linked);
            skewX.set(Attribute.HIDDEN, !SKEW.equals(kind));
            skewY.set(Attribute.HIDDEN, !SKEW.equals(kind));
            // THE TEXT IS THE FLOOR, not a fallback: matrix() and anything a future engine adds is edited here. An
            // empty stack has no op to edit at all, and the caption is what says so.
            boolean known = TRANSLATE.equals(kind) || ROTATE.equals(kind) || scaling || SKEW.equals(kind);
            raw.set(Attribute.HIDDEN, known || kind.isEmpty());
        }
    }

    private static Rows rows(PanelForm form, StyleProperty<?> property, Property<String> op, Property<Boolean> uniform) {
        // A PIXEL OF HAND IS A PIXEL OF OFFSET, on the pad the shadow lab's offsets use.
        Configurator offset = form.control("lab.translate", "Offset", new OffsetPad("lab.translate")
                .bind(op.map(TransformLab::translation, at -> is(op, TRANSLATE)
                        ? CssValues.function(TRANSLATE, length(op.get(), 0, at[0]), length(op.get(), 1, at[1]))
                        : op.get())));
        // DEGREES, WHATEVER THE FILE SAYS: the engine writes an op's angle in radians, so `rotate(0.78rad)` read as
        // a number was 0.78 degrees on the dial and a value nobody could get back.
        Configurator angle = form.control("lab.rotate", "Angle", new AngleDial("lab.rotate")
                .bind(op.map(text -> degrees(text, 0),
                        turned -> is(op, ROTATE) ? CssValues.function(ROTATE, deg(turned)) : op.get())));

        Configurator uniformRow = form.prop(ConfigDescriptor.bool("lab.scale.uniform", "Uniform"),
                uniform.map(on -> on, on -> {
                    // SQUARED BACK when it is switched on, rather than leaving the two axes apart and saying linked.
                    if (Boolean.TRUE.equals(on) && is(op, SCALE)) op.set(scaled(factor(op.get(), 0), factor(op.get(), 0)));
                    return on;
                }));
        UIElement flips = form.custom(flips(op));
        Configurator scale = form.prop(factorField("lab.scale", "Scale"), factor(op, -1, uniform));
        Configurator scaleX = form.prop(factorField("lab.scale.x", "Scale X"), factor(op, 0, uniform));
        Configurator scaleY = form.prop(factorField("lab.scale.y", "Scale Y"), factor(op, 1, uniform));

        Configurator skewX = form.prop(shearField("lab.skew.x", "Skew X"), shear(op, 0));
        Configurator skewY = form.prop(shearField("lab.skew.y", "Skew Y"), shear(op, 1));

        Configurator raw = form.prop(ConfigDescriptor.text("lab.op", "Function").placeholder("none")
                        .validator(text -> text == null || text.isBlank()
                                || DeclarationEditors.parses(property, text)),
                op.map(CssValues::readable, typed -> typed == null ? "" : typed.trim()));
        return new Rows(offset, angle, uniformRow, flips, scale, scaleX, scaleY, skewX, skewY, raw);
    }

    /**
     * The point every op turns and grows about, as {@code transform-origin-x} and {@code -y}: nine cells for the
     * corners, edges and centre, and the pair of numbers they are the round values of.
     *
     * <p>Written as a percentage of the box, which is what the canvas's own free transform writes and what keeps an
     * origin meaning the same thing as the element resizes.</p>
     */
    private static void origin(StyleLab lab, StyleFields fields, @Nullable UIElement node) {
        PanelForm form = lab.form().group("Origin", false);
        Property<String> x = fields.value(ORIGIN_X);
        Property<String> y = fields.value(ORIGIN_Y);
        // ON THE SPECIMEN TOO, or the picker moves a point nothing on the stage turns about.
        lab.also(StylePropertyRegistry.TRANSFORM_ORIGIN_X, x).also(StylePropertyRegistry.TRANSFORM_ORIGIN_Y, y);

        // ONE EDIT, not one per declaration: the pair is placed by one gesture. @see StyleLab
        Property<double[]> at = Property.derived(
                () -> new double[] {fraction(x.get(), node, true), fraction(y.get(), node, false)},
                next -> {
                    x.set(percent(next[0]));
                    y.set(percent(next[1]));
                }).editedIn(fields.history());
        form.prop(ConfigDescriptor.anchor("lab.origin", "Origin"), at);
        form.prop(originField("lab.origin.x", "X"), axis(at, 0));
        form.prop(originField("lab.origin.y", "Y"), axis(at, 1));
    }

    private static ConfigDescriptor originField(String id, String label) {
        return ConfigDescriptor.number(id, label).range(-ORIGIN_MAX, ORIGIN_MAX).softRange(0f, 100f)
                .unit("%").decimals(1);
    }

    /** One axis of the origin, as the percentage the field shows. */
    private static Property<Double> axis(Property<double[]> origin, int axis) {
        return origin.map(at -> at[axis] * 100d, value -> {
            double[] next = origin.get().clone();
            next[axis] = (value == null ? 0d : value) / 100d;
            return next;
        });
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

    /** A factor field: dragged to {@link #SCALE_REACH}, typed as far as anyone likes. */
    private static ConfigDescriptor factorField(String id, String label) {
        return ConfigDescriptor.number(id, label).range(-SCALE_MAX, SCALE_MAX)
                .softRange(-SCALE_REACH, SCALE_REACH).step(0.01f).decimals(3);
    }

    /** A shear field, in degrees whatever the file spells the angle in. */
    private static ConfigDescriptor shearField(String id, String label) {
        return ConfigDescriptor.number(id, label).range(-SKEW_MAX, SKEW_MAX).softRange(-SKEW_REACH, SKEW_REACH)
                .unit("°").decimals(1);
    }

    /** One axis of a scale, or both at once for {@code axis} of -1 — which is what Uniform edits. */
    private static Property<Double> factor(Property<String> op, int axis, Property<Boolean> uniform) {
        return op.map(text -> factor(text, Math.max(0, axis)), value -> {
            if (!is(op, SCALE)) return op.get();
            double next = value == null ? 1d : value;
            double x = factor(op.get(), 0);
            double y = factor(op.get(), 1);
            // LINKED MOVES BOTH, whichever row the edit came from -- the two rows are not shown together.
            if (axis < 0 || Boolean.TRUE.equals(uniform.get())) return scaled(next, next);
            return axis == 0 ? scaled(next, y) : scaled(x, next);
        });
    }

    /** One axis of a shear, in degrees. */
    private static Property<Double> shear(Property<String> op, int axis) {
        return op.map(text -> degrees(text, axis), value -> {
            if (!is(op, SKEW)) return op.get();
            double next = value == null ? 0d : value;
            double other = degrees(op.get(), axis == 0 ? 1 : 0);
            return CssValues.function(SKEW, deg(axis == 0 ? next : other), deg(axis == 0 ? other : next));
        });
    }

    /** {@code scale(2)} where the axes agree, as a person writes it, and two arguments where they do not. */
    private static String scaled(double x, double y) {
        double sx = CssValues.dragged(x);
        double sy = CssValues.dragged(y);
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

    private static double clamp(double value, double reach) {
        return Math.max(-reach, Math.min(reach, value));
    }

    private static String at(List<String> ops, Integer index) {
        int at = index == null ? 0 : index;
        return at >= 0 && at < ops.size() ? ops.get(at) : "";
    }
}
