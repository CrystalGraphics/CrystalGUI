package com.crystalgui.app.uibuilder.style;

import java.util.Arrays;
import java.util.function.Supplier;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.control.Button;

/**
 * The corners lab: a box with a handle in each corner, presets, and the radii as numbers.
 *
 * <pre>{@code
 * CornersLab.open(chip, fields);   // edits all eight longhands
 * }</pre>
 *
 * <p>Read and written as {@code border-radius}, which {@link StyleFields} lands as the shorthand in a rule and as its
 * eight longhands on an element. A percentage is shown in pixels at the element's current size, and an edit writes
 * pixels -- except the Full preset, which is {@code 50%}.</p>
 *
 * <p>The rows follow two switches: linked (one radius for every corner, else one per corner) and elliptical (a
 * horizontal and a vertical radius, else one). Each combination shows only the rows it edits.</p>
 */
public final class CornersLab {

    public static final String STAGE_CLASS = "__corner-stage__";

    /** Presets in px, then two by name. */
    private static final double[] PRESETS = {0, 4, 8, 12};

    /** An ellipse, or a circle on a square: half of each side. */
    private static final String FULL = "50%";

    /** The pill radius and the slider's reach when the element has no size to measure. */
    private static final float FALLBACK_PILL = 80f;

    /** What a typed radius is held to: past any box, since a declaration may outlast the size it was set at. */
    private static final float MAX_TYPED = 9999f;

    private static final String[] CORNER_LABELS = {"Top left", "Top right", "Bottom right", "Bottom left"};

    private CornersLab() {
    }

    /** Opens the lab over {@code anchor}, editing the corners of whatever {@code fields} writes to. */
    public static void open(UIElement anchor, StyleFields fields) {
        StyleLab lab = StyleLab.over(anchor, "Corners").withoutStage();

        // EIGHT DECLARATIONS AS ONE VALUE, [tlX, tlY, trX, trY, brX, brY, blX, blY]: one undo step per gesture.
        // ONE DECLARATION, one undo step per edit: StyleFields lands it on the element's longhands as a single edit.
        Property<String> css = fields.value(StyleFields.BORDER_RADIUS);
        Property<double[]> radii = css.map(text -> px(text, fields.node()), next -> {
            // UNCHANGED KEEPS ITS SPELLING: a percentage survives a no-op write rather than turning into pixels.
            String written = shorthand(next);
            return written.equals(shorthand(px(css.get(), fields.node()))) ? css.get() : written;
        });
        // PAST HALF THE SHORTER SIDE NOTHING CHANGES: CSS scales the radii back to fit, so that is the pill, and a
        // gesture stops there -- asked as the element resizes. A typed radius is kept, as a pill's 999px is.
        UIElement node = fields.node();
        float pill = pillRadius(node);

        // OPENED AS THE VALUE IS: linked when every corner agrees, elliptical when any corner is.
        double[] opened = radii.get();
        Property<Boolean> linked = Property.of(allEqual(opened, 0) && allEqual(opened, 1));
        Property<Boolean> elliptical = Property.of(isElliptical(opened));

        UIElement stage = new UIElement();
        stage.addClass(STAGE_CLASS);
        stage.append(new CornerBox("lab.corners", linked, elliptical).subject(fields.node()).bind(radii));
        lab.form().custom(stage);

        UIElement presets = new UIElement();
        presets.addClass(StyleLab.ROW_CLASS);
        presets.addClass(StyleLab.PRESETS_CLASS);
        for (double preset : PRESETS) {
            presets.append(preset(css, linked, elliptical, CssValues.write(preset), CssValues.px(preset)));
        }
        presets.append(preset(css, linked, elliptical, "Pill", CssValues.px(pill)));
        presets.append(preset(css, linked, elliptical, "Full", FULL));
        lab.form().custom(presets);

        // SQUARED BACK TO CIRCLES when elliptical is switched off, rather than leaving a hidden vertical radius.
        lab.form().prop(ConfigDescriptor.bool("lab.elliptical", "Elliptical"), elliptical.map(
                on -> on, on -> {
                    if (!Boolean.TRUE.equals(on)) {
                        double[] circles = radii.get().clone();
                        for (int i = 0; i < 4; i++) circles[i * 2 + 1] = circles[i * 2];
                        radii.set(circles);
                    }
                    return on;
                }));

        Configurator radius = lab.form().prop(length("lab.radius", "Radius", () -> half(node, true, true)), radii.map(r -> r[0], v -> {
            double[] next = new double[8];
            Arrays.fill(next, whole(v));
            return next;
        }));
        Configurator radiusX = lab.form().prop(length("lab.radius-x", "Radius X", () -> half(node, true, false)), axis(radii, 0));
        Configurator radiusY = lab.form().prop(length("lab.radius-y", "Radius Y", () -> half(node, false, true)), axis(radii, 1));

        Configurator[] corners = new Configurator[4];
        Configurator[] ellipses = new Configurator[4];
        for (int i = 0; i < 4; i++) {
            int corner = i;
            corners[i] = lab.form().prop(length("lab.corner." + i, CORNER_LABELS[i], () -> half(node, true, true)), radii.map(r -> r[corner * 2], v -> {
                double[] next = radii.get().clone();
                next[corner * 2] = whole(v);
                next[corner * 2 + 1] = whole(v);
                return next;
            }));
            ellipses[i] = lab.form().prop(ConfigDescriptor.vector("lab.ellipse." + i, CORNER_LABELS[i], 2).unit("px")
                            .range(0f, MAX_TYPED).softRange(() -> new ConfigDescriptor.Range(0f,
                                    Math.max(half(node, true, false), half(node, false, true)))),
                    radii.map(r -> new double[] {r[corner * 2], r[corner * 2 + 1]}, v -> {
                        double[] next = radii.get().clone();
                        next[corner * 2] = whole(v[0]);
                        next[corner * 2 + 1] = whole(v[1]);
                        return next;
                    }));
        }

        // ONLY THE ROWS THIS MODE EDITS.
        PropertyWatch.follow(stage, Property.derived(() -> mode(linked.get(), elliptical.get())), mode -> {
            radius.set(Attribute.HIDDEN, mode != 0);
            radiusX.set(Attribute.HIDDEN, mode != 1);
            radiusY.set(Attribute.HIDDEN, mode != 1);
            for (int i = 0; i < 4; i++) {
                corners[i].set(Attribute.HIDDEN, mode != 2);
                ellipses[i].set(Attribute.HIDDEN, mode != 3);
            }
        });

        lab.readout(StyleFields.BORDER_RADIUS, css);
        lab.open();
    }

    /** A preset pill, lit while the declaration is exactly {@code value}. */
    private static Button preset(Property<String> css, Property<Boolean> linked, Property<Boolean> elliptical,
                                 String label, String value) {
        Button button = new Button(label);
        button.addClass(StyleLab.KEYWORD_CLASS);
        button.attachListener(() -> {
            css.set(value);
            linked.set(true);
            elliptical.set(false);
        });
        PropertyWatch.follow(button, css, now -> button.toggleClass(StyleLab.ACTIVE_CLASS, value.equals(now)));
        return button;
    }

    /** Half the element's shorter side, rounded up: the smallest radius that is already a pill. */
    private static float pillRadius(UIElement node) {
        return half(node, true, true);
    }

    /**
     * Half the element's width, height, or shorter of the two, rounded up: past it a radius on that axis changes
     * nothing. {@link #FALLBACK_PILL} while the element has no size.
     */
    static float half(UIElement node, boolean width, boolean height) {
        if (node == null || node.box() == null) return FALLBACK_PILL;
        float w = node.box().width(), h = node.box().height();
        float side = width && height ? Math.min(w, h) : width ? w : h;
        return side <= 0f ? FALLBACK_PILL : (float) Math.ceil(side / 2f);
    }

    /**
     * {@code border-radius} as eight pixel radii. A percentage is of the element's width for a horizontal radius and
     * its height for a vertical one, so it reads at the size the element is now; unresolvable, it reads as 0.
     */
    static double[] px(String css, UIElement node) {
        double[] out = new double[8];
        String[] corners = css == null || css.isBlank() ? null : StyleFields.radiusLonghands(css);
        if (corners == null) return out;
        for (int i = 0; i < 8; i++) {
            String value = corners[i].trim();
            if (value.endsWith("%")) {
                float side = node == null || node.box() == null ? 0f
                        : i % 2 == 0 ? node.box().width() : node.box().height();
                out[i] = Math.round(CssValues.number(value.substring(0, value.length() - 1), 0f) / 100f * side);
            } else {
                out[i] = CssValues.number(value, 0f);
            }
        }
        return out;
    }

    /** 0 linked circles, 1 linked ellipses, 2 a circle per corner, 3 an ellipse per corner. */
    private static int mode(Boolean linked, Boolean elliptical) {
        return (Boolean.TRUE.equals(linked) ? 0 : 2) + (Boolean.TRUE.equals(elliptical) ? 1 : 0);
    }

    /** A radius field: typed as far as {@link #MAX_TYPED}, dragged as far as {@code softMax} says it can be seen. */
    private static ConfigDescriptor length(String id, String label, Supplier<Float> softMax) {
        return ConfigDescriptor.number(id, label).range(0f, MAX_TYPED)
                .softRange(() -> new ConfigDescriptor.Range(0f, softMax.get())).unit("px").integral(true);
    }

    /** Every corner's radius on one axis, 0 horizontal and 1 vertical, as one number. */
    private static Property<Double> axis(Property<double[]> radii, int axis) {
        return radii.map(r -> r[axis], v -> {
            double[] next = radii.get().clone();
            for (int i = 0; i < 4; i++) next[i * 2 + axis] = whole(v);
            return next;
        });
    }

    private static double whole(Double value) {
        return value == null ? 0d : Math.max(0d, Math.round(value));
    }

    private static double whole(double value) {
        return Math.max(0d, Math.round(value));
    }

    private static boolean allEqual(double[] radii, int axis) {
        for (int i = 1; i < 4; i++) {
            if (radii[i * 2 + axis] != radii[axis]) return false;
        }
        return true;
    }

    private static boolean isElliptical(double[] radii) {
        for (int i = 0; i < 4; i++) {
            if (radii[i * 2] != radii[i * 2 + 1]) return true;
        }
        return false;
    }

    /** The radii as {@code border-radius} is written. @see StyleFields#radiusShorthand */
    static String shorthand(double[] radii) {
        String[] corners = new String[8];
        for (int i = 0; i < 8; i++) corners[i] = CssValues.px(radii[i]);
        return StyleFields.radiusShorthand(corners);
    }
}
