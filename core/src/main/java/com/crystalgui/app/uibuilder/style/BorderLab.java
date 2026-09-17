package com.crystalgui.app.uibuilder.style;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.PanelForm;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.control.Button;

/**
 * The border lab: the element's corners, border and outline, over a miniature of the element that draws all three.
 *
 * <pre>{@code
 * BorderLab.open(chip, fields);   // opened from any corner, border-width or outline row
 * }</pre>
 *
 * <ul>
 *   <li><b>Corners</b> -- a handle on each corner's curve, presets, and the radii, linked or per corner, circular or
 *       elliptical.</li>
 *   <li><b>Border</b> -- a handle at each side's inner edge, dragged inward to thicken it; the width linked or per side;
 *       the color, and the top and bottom colors that override it.</li>
 *   <li><b>Outline</b> -- its width and color, and its offset linked or per side.</li>
 * </ul>
 *
 * <p>Read and written as the declarations a sheet writes -- {@code border-radius}, {@code border-width},
 * {@code outline-offset} -- which {@link StyleFields} lands as shorthands in a rule and as longhands on an element. A
 * percentage radius is shown in pixels at the element's current size, and an edit writes pixels -- except the Full
 * preset, which is {@code 50%}.</p>
 */
public final class BorderLab {

    public static final String STAGE_CLASS = "__corner-stage__";

    /** Radius presets in px, then two by name. */
    private static final double[] PRESETS = {0, 4, 8, 12};

    /** An ellipse, or a circle on a square: half of each side. */
    private static final String FULL = "50%";

    /** The pill radius and a slider's reach when the element has no size to measure. */
    private static final float FALLBACK_PILL = 80f;

    /** What a typed length is held to: past any box, since a declaration may outlast the size it was set at. */
    private static final float MAX_TYPED = 9999f;

    /** How far an outline offset is dragged either way; typed, it goes as far as {@link #MAX_TYPED}. */
    private static final float OFFSET_REACH = 16f;

    private static final String[] CORNER_LABELS = {"Top left", "Top right", "Bottom right", "Bottom left"};
    private static final String[] SIDE_LABELS = {"Top", "Right", "Bottom", "Left"};

    private BorderLab() {
    }

    /** Opens the lab over {@code anchor}, editing whatever {@code fields} writes to. */
    public static void open(UIElement anchor, StyleFields fields) {
        StyleLab lab = StyleLab.over(anchor, "Border").withoutStage();
        UIElement node = fields.node();

        // ONE DECLARATION EACH, one undo step per edit: StyleFields lands a shorthand on the element's longhands as one.
        Property<String> radiusCss = fields.value(StyleFields.BORDER_RADIUS);
        Property<double[]> radii = radiusCss.map(text -> px(text, node), next -> {
            // UNCHANGED KEEPS ITS SPELLING: a percentage survives a no-op write rather than turning into pixels.
            String written = shorthand(next);
            return written.equals(shorthand(px(radiusCss.get(), node))) ? radiusCss.get() : written;
        });
        Property<String> widthCss = fields.value(StyleFields.BORDER_WIDTH);
        Property<double[]> widths = sides(widthCss, StyleFields.group(StyleFields.BORDER_WIDTH));
        Property<String> offsetCss = fields.value(StyleFields.OUTLINE_OFFSET);
        Property<double[]> offsets = sides(offsetCss, StyleFields.group(StyleFields.OUTLINE_OFFSET));

        // OPENED AS THE VALUE IS: linked when every corner or side agrees, elliptical when any corner is.
        double[] opened = radii.get();
        Property<Boolean> linked = Property.of(allEqual(opened, 0) && allEqual(opened, 1));
        Property<Boolean> elliptical = Property.of(isElliptical(opened));
        Property<Boolean> sidesLinked = Property.of(same(widths.get()));
        Property<Boolean> offsetsLinked = Property.of(same(offsets.get()));

        // THE MINIATURE, drawing what the element draws: corners, border widths and colors, and the outline.
        CornerBox box = new CornerBox("lab.corners", linked, elliptical).subject(node).sides(widths, sidesLinked);
        box.bind(radii);
        UIElement stage = new UIElement();
        stage.addClass(STAGE_CLASS);
        stage.append(box);
        // OUT OF THE SCROLL, as every other lab's stage is: the rows scroll under the shape they edit.
        lab.header(stage);

        corners(lab.form().group("Corners", false), node, radiusCss, radii, linked, elliptical, stage);
        border(lab.form().group("Border", false), fields, node, widths, sidesLinked, stage);
        outline(lab.form().group("Outline", false), fields, offsets, offsetsLinked, stage);

        Map<String, Property<String>> readout = new LinkedHashMap<>();
        for (String name : new String[] {StyleFields.BORDER_RADIUS, StyleFields.BORDER_WIDTH, "border-color",
                "border-top-color", "border-bottom-color", StyleFields.OUTLINE, StyleFields.OUTLINE_OFFSET}) {
            readout.put(name, name.equals(StyleFields.BORDER_RADIUS) ? radiusCss
                    : name.equals(StyleFields.BORDER_WIDTH) ? widthCss
                    : name.equals(StyleFields.OUTLINE_OFFSET) ? offsetCss : fields.value(name));
        }
        lab.readout(readout);
        lab.open();
    }

    private static void corners(PanelForm form, UIElement node, Property<String> css, Property<double[]> radii,
                                Property<Boolean> linked, Property<Boolean> elliptical, UIElement owner) {
        // PAST HALF THE SHORTER SIDE NOTHING CHANGES: CSS scales the radii back to fit, so that is the pill, and a
        // gesture stops there -- asked as the element resizes. A typed radius is kept, as a pill's 999px is.
        UIElement presets = new UIElement();
        presets.addClass(StyleLab.ROW_CLASS);
        presets.addClass(StyleLab.PRESETS_CLASS);
        for (double preset : PRESETS) {
            presets.append(preset(css, linked, elliptical, CssValues.write(preset), CssValues.px(preset)));
        }
        presets.append(preset(css, linked, elliptical, "Pill", CssValues.px(half(node, true, true))));
        presets.append(preset(css, linked, elliptical, "Full", FULL));
        form.custom(presets);

        // SQUARED BACK TO CIRCLES when elliptical is switched off, rather than leaving a hidden vertical radius.
        form.prop(ConfigDescriptor.bool("lab.elliptical", "Elliptical"), elliptical.map(on -> on, on -> {
            if (!Boolean.TRUE.equals(on)) {
                double[] circles = radii.get().clone();
                for (int i = 0; i < 4; i++) circles[i * 2 + 1] = circles[i * 2];
                radii.set(circles);
            }
            return on;
        }));

        Configurator radius = form.prop(length("lab.radius", "Radius", () -> half(node, true, true)),
                radii.map(r -> r[0], v -> {
                    double[] next = new double[8];
                    Arrays.fill(next, whole(v));
                    return next;
                }));
        Configurator radiusX = form.prop(length("lab.radius-x", "Radius X", () -> half(node, true, false)), axis(radii, 0));
        Configurator radiusY = form.prop(length("lab.radius-y", "Radius Y", () -> half(node, false, true)), axis(radii, 1));

        Configurator[] circles = new Configurator[4];
        Configurator[] ellipses = new Configurator[4];
        for (int i = 0; i < 4; i++) {
            int corner = i;
            circles[i] = form.prop(length("lab.corner." + i, CORNER_LABELS[i], () -> half(node, true, true)),
                    radii.map(r -> r[corner * 2], v -> {
                        double[] next = radii.get().clone();
                        next[corner * 2] = whole(v);
                        next[corner * 2 + 1] = whole(v);
                        return next;
                    }));
            ellipses[i] = form.prop(ConfigDescriptor.vector("lab.ellipse." + i, CORNER_LABELS[i], 2).unit("px")
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
        PropertyWatch.follow(owner, Property.derived(() -> mode(linked.get(), elliptical.get())), mode -> {
            radius.set(Attribute.HIDDEN, mode != 0);
            radiusX.set(Attribute.HIDDEN, mode != 1);
            radiusY.set(Attribute.HIDDEN, mode != 1);
            for (int i = 0; i < 4; i++) {
                circles[i].set(Attribute.HIDDEN, mode != 2);
                ellipses[i].set(Attribute.HIDDEN, mode != 3);
            }
        });
    }

    private static void border(PanelForm form, StyleFields fields, UIElement node, Property<double[]> widths,
                               Property<Boolean> linked, UIElement owner) {
        form.prop(ConfigDescriptor.bool("lab.border.per-side", "Per side"), linked.map(on -> !on, per -> !per));
        Supplier<Float> reach = () -> half(node, true, true);
        sideRows(form, "lab.border", widths, linked, owner, () -> new ConfigDescriptor.Range(0f, reach.get()), 0f);
        form.prop(ConfigDescriptor.color("lab.border.color", "Color"), color(fields.value("border-color"), 0xFF000000));
        // OVERRIDES, as the engine reads them: transparent is "the color above".
        form.prop(ConfigDescriptor.color("lab.border.top-color", "Top color"), color(fields.value("border-top-color"), 0));
        form.prop(ConfigDescriptor.color("lab.border.bottom-color", "Bottom color"),
                color(fields.value("border-bottom-color"), 0));
    }

    private static void outline(PanelForm form, StyleFields fields, Property<double[]> offsets, Property<Boolean> linked,
                                UIElement owner) {
        // ONE DECLARATION, `outline: 1px #FFF`, as a sheet writes it: the width and the color are its two terms.
        Property<String> outlineCss = fields.value(StyleFields.OUTLINE);
        form.prop(ConfigDescriptor.number("lab.outline.width", "Width").range(0f, MAX_TYPED).softRange(0f, OFFSET_REACH)
                        .unit("px").integral(true),
                outlineCss.map(text -> (double) CssValues.number(outlineTerm(text, false), 0f),
                        v -> outlineOf(CssValues.px(whole(v)), outlineTerm(outlineCss.get(), true))));
        form.prop(ConfigDescriptor.color("lab.outline.color", "Color"), outlineCss.map(text -> {
            Integer argb = ColorValue.parseCssColor(outlineTerm(text, true));
            return argb == null ? 0xFFFFFFFF : argb;
        }, argb -> outlineOf(outlineTerm(outlineCss.get(), false), CssValues.color(argb))));
        form.prop(ConfigDescriptor.bool("lab.outline.per-side", "Offset per side"), linked.map(on -> !on, per -> !per));
        sideRows(form, "lab.outline.offset", offsets, linked, owner,
                () -> new ConfigDescriptor.Range(-OFFSET_REACH, OFFSET_REACH), -MAX_TYPED);
    }

    /** One row for all four sides while {@code linked}, else one a side; the label reads "Offset" for an offset. */
    private static void sideRows(PanelForm form, String id, Property<double[]> sides, Property<Boolean> linked,
                                 UIElement owner, Supplier<ConfigDescriptor.Range> soft, float min) {
        String label = id.endsWith("offset") ? "Offset" : "Width";
        Configurator all = form.prop(side(id, label, soft, min), sides.map(s -> s[0], v -> {
            double[] next = new double[4];
            Arrays.fill(next, Math.round(v == null ? 0d : v));
            return next;
        }));
        Configurator[] each = new Configurator[4];
        for (int i = 0; i < 4; i++) {
            int at = i;
            each[i] = form.prop(side(id + "." + i, SIDE_LABELS[i], soft, min), sides.map(s -> s[at], v -> {
                double[] next = sides.get().clone();
                next[at] = Math.round(v == null ? 0d : v);
                return next;
            }));
        }
        PropertyWatch.follow(owner, linked, on -> {
            all.set(Attribute.HIDDEN, !Boolean.TRUE.equals(on));
            for (Configurator row : each) row.set(Attribute.HIDDEN, Boolean.TRUE.equals(on));
        });
    }

    private static ConfigDescriptor side(String id, String label, Supplier<ConfigDescriptor.Range> soft, float min) {
        return ConfigDescriptor.number(id, label).range(min, MAX_TYPED).softRange(soft).unit("px").integral(true);
    }

    /** An outline's color term or its width term: whichever parses as a color, and whichever does not. */
    private static String outlineTerm(String css, boolean color) {
        for (String term : CssValues.terms(css == null ? "" : css)) {
            if ((ColorValue.parseCssColor(term) != null) == color) return term;
        }
        return "";
    }

    private static String outlineOf(String width, String color) {
        return ((width.isEmpty() ? "0px" : width) + " " + color).trim();
    }

    /** A color declaration as a swatch, {@code fallback} while it is unset. */
    private static Property<Integer> color(Property<String> css, int fallback) {
        return css.map(text -> {
            Integer argb = text == null || text.isBlank() ? null : ColorValue.parseCssColor(text.trim());
            return argb == null ? fallback : argb;
        }, argb -> CssValues.color(argb));
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

    /**
     * A four-sided declaration as px, top, right, bottom, left -- unset reading as 0 -- written back in the group's
     * spelling, and left as it is when nothing moved.
     */
    private static Property<double[]> sides(Property<String> css, StyleFields.Group group) {
        return css.map(text -> px(text, group), next -> {
            String[] values = new String[4];
            for (int i = 0; i < 4; i++) values[i] = CssValues.px(next[i]);
            String now = css.get();
            return Arrays.equals(px(now, group), next) && !now.isBlank() ? now : group.pack(values);
        });
    }

    private static double[] px(String css, StyleFields.Group group) {
        double[] out = new double[4];
        String[] values = css == null || css.isBlank() ? null : group.unpack(css);
        if (values == null) return out;
        for (int i = 0; i < 4; i++) out[i] = CssValues.number(values[i], 0f);
        return out;
    }

    private static boolean same(double[] values) {
        for (double value : values) {
            if (value != values[0]) return false;
        }
        return true;
    }

    /**
     * Half the element's width, height, or shorter of the two, rounded up: past it a length on that axis changes
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
