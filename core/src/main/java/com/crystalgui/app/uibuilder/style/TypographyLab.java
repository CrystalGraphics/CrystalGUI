package com.crystalgui.app.uibuilder.style;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.property.visual.text.TextDecorationLine;
import com.crystalgui.style.property.visual.text.FontWeight;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.text.UIText;

/**
 * The typography lab: a line of real text, set in the face it will be set in.
 *
 * <pre>{@code
 * TypographyLab.open(chip, fields, node);
 * }</pre>
 *
 * <p>Size, weight, style, face, stroke and paint order, each its own declaration and each a row bound to it.
 * The caption states what the specimen measured rather than repeating the number that was typed.</p>
 */
public final class TypographyLab {

    /** What the engine can draw: a face and its synthetic bold. A number on the 100-900 scale reads as one of them. */
    static final List<String> WEIGHTS = List.of("normal", "bold");
    static final List<String> STYLES = List.of("normal", "italic");
    static final List<String> ORDERS = List.of("normal", "stroke");
    static final List<String> ALIGNS = List.of("outset", "center", "inset");

    /**
     * How a stroke width is spelled. <b>{@code em} is written as a percentage</b>: {@code TextStrokeStyle}
     * resolves the width against the font size, so {@code 15%} is 0.15em, while a literal {@code em} does not
     * parse there. The gallery's text lab spells it the same way.
     */
    /** The second is a share of the font size, written and shown as the percentage the number field reads. */
    static final List<String> UNITS = List.of("px", "%");

    /** The faces that ship: a stack the cache cannot load throws where it is measured, not where it is set. */
    static final List<String> FACES = List.of(
            "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf",
            "crystalgui:ui/fonts/JetBrainsMono-Regular.ttf",
            "crystalgui:ui/fonts/Minecraft.otf");

    /** The narrow band every face can carry, for when there is no specimen to ask. */
    private static final float STROKE_CAP_EM = 0.125f;

    private TypographyLab() {
    }

    public static void open(UIElement anchor, StyleFields fields, @Nullable UIElement node) {
        StyleLab lab = StyleLab.over(anchor, "Typography");
        Property<String> text = Property.of("Handgloves");
        UIText line = new UIText("");
        // THE PLATE READS AGAINST THE TEXT: the color the specimen is actually drawn in.
        lab.contrastWith(() -> line.getStyle().computed().get(StylePropertyRegistry.COLOR));
        PropertyWatch.follow(line, text, line::setText);
        lab.specimen(line);

        // WHAT THE ELEMENT DRAWS WHEN NOTHING IS DECLARED, not the sample's own look: a sample is set bold, so an
        // undeclared weight showed bold and choosing bold changed nothing.
        for (String name : List.of("color", "font-size", "font-weight", "font-style", "font-family", "paint-order",
                "stroke-align", "line-height",
                "text-decoration-line", "text-decoration-color")) {
            StyleProperty<?> property = StyleFields.propertyOf(name);
            Property<String> declared = fields.value(name);
            Property<String> element = computed(node, property);
            LiveEdits.follow(line, property, Property.derived(() -> {
                String css = declared.get();
                return css == null || css.isBlank() ? element.get() : css;
            }));
        }
        Property<String> stroke = fields.value(StyleFields.TEXT_STROKE);
        LiveEdits.follow(line, StylePropertyRegistry.TEXT_STROKE_WIDTH, stroke.map(TypographyLab::width));
        LiveEdits.follow(line, StylePropertyRegistry.TEXT_STROKE_COLOR, stroke.map(TypographyLab::color));

        lab.form().prop(ConfigDescriptor.text("lab.text", "Text"),
                text.map(shown -> shown, typed -> typed == null || typed.isBlank() ? "Handgloves" : typed));
        lab.form().prop(ConfigDescriptor.number("lab.size", "Size").range(MIN_SIZE, MAX_SIZE).unit("px").integral(true),
                fields.value("font-size").map(css -> (double) CssValues.number(css, 0f),
                        size -> CssValues.length(StylePropertyRegistry.FONT_SIZE, CssValues.dragged(size))));
        lab.form().prop(ConfigDescriptor.color("lab.color", "Color"), color(fields, node, "color"));
        // TYPED OR PICKED: the bundled faces by name and every family installed here, and any other name a person
        // knows. A bundled face is written as its resource path, anything else as the family name.
        lab.form().prop(ConfigDescriptor.text("lab.font", "Font")
                        .suggestionGroups(() -> List.of(faceNames(), FontFamilyCache.installedFamilies()))
                        .validator(typed -> typed != null && !typed.isBlank()),
                // WHAT THE ELEMENT DRAWS IN when nothing is declared: picking the default face declares nothing -- the
                // edit is dropped as changing nothing -- and the row read blank over text set in that very face.
                fields.value("font-family").map(css -> fontName(css == null || css.isBlank()
                        ? computed(node, StylePropertyRegistry.FONT_FAMILY).get() : css), TypographyLab::fontCss));
        lab.form().prop(ConfigDescriptor.select("lab.weight", "Weight", WEIGHTS),
                fields.value("font-weight").map(TypographyLab::weight, chosen -> chosen));
        lab.form().prop(ConfigDescriptor.select("lab.style", "Style", STYLES), keyword(fields, "font-style", STYLES));
        // A MULTIPLIER OR `normal`, typed or picked: the common steps are offered, anything the property parses goes.
        lab.form().prop(ConfigDescriptor.text("lab.line-height", "Line height")
                        .suggestions(() -> LINE_HEIGHTS)
                        .validator(typed -> typed != null && !typed.isBlank()
                                && DeclarationEditors.parses(StylePropertyRegistry.LINE_HEIGHT, typed)),
                fields.value("line-height").map(css -> css == null || css.isBlank() ? "normal" : css,
                        typed -> "normal".equalsIgnoreCase(typed.trim()) ? "" : typed.trim()));

        lab.form().separator();
        // THE NUMBER AS WRITTEN, in the unit it is written in: a percentage of the em or pixels. Its reach is
        // the face's own ceiling in that unit, since the renderer clamps every stroke to it.
        lab.form().prop(ConfigDescriptor.number("lab.stroke", "Stroke")
                        .range(() -> new ConfigDescriptor.Range(0f, isEm(stroke.get())
                                ? capEm(line) * 100f : Math.max(1f, capEm(line) * fontSize(fields, node))))
                        .unit(() -> isEm(stroke.get()) ? "%" : "px")
                        .step(0.1f).decimals(1),
                stroke.map(css -> (double) CssValues.number(width(css), 0f), amount -> {
                    String now = stroke.get();
                    float dragged = (float) CssValues.dragged(amount);
                    return (isEm(now) ? CssValues.write(dragged) + "%" : CssValues.px(dragged)) + " " + colorOr(now);
                }));
        lab.form().prop(ConfigDescriptor.color("lab.stroke-color", "Stroke color"), stroke.map(
                css -> {
                    Integer parsed = ColorValue.parseCssColor(colorOr(css));
                    return parsed == null ? 0xFFFFFFFF : parsed;
                },
                argb -> widthOr(stroke.get()) + " " + CssValues.color(argb)));
        // THE ONE CONVERSION: switching the unit is the only act that changes what the number means.
        lab.form().prop(ConfigDescriptor.select("lab.unit", "Stroke unit", UNITS), stroke.map(
                css -> UNITS.get(isEm(css) ? 1 : 0),
                unit -> {
                    String now = stroke.get();
                    float size = fontSize(fields, node);
                    float number = CssValues.number(width(now), 0f);
                    float px = isEm(now) ? number / 100f * size : number;
                    return spell(px, size, UNITS.indexOf(unit) == 1) + " " + colorOr(now);
                }));
        // WHERE THE STROKE GOES decides whether the order means anything: an outset stroke never overlaps the fill,
        // so paint order only shows once part of the stroke is inside the letter.
        lab.form().prop(ConfigDescriptor.select("lab.align", "Stroke align", ALIGNS), keyword(fields, "stroke-align", ALIGNS));
        lab.form().prop(ConfigDescriptor.select("lab.order", "Paint order", ORDERS), keyword(fields, "paint-order", ORDERS));

        lab.form().separator();
        // ONE ROW FOR THE LINES, any number of them: the kit's mask is a set of flags, which is what the value is.
        lab.form().prop(ConfigDescriptor.mask("lab.decoration", "Decoration", DECORATIONS).emptyText("None"),
                fields.value("text-decoration-line").map(TypographyLab::decorationNames, TypographyLab::decorationCss));
        lab.form().prop(ConfigDescriptor.color("lab.decoration-color", "Decoration color"),
                color(fields, node, "text-decoration-color"));

        lab.caption(Property.derived(() -> measured(line, stroke.get(), fields, node)));
        lab.open();
    }

    /** The lines a {@code text-decoration-line} declaration names; empty for {@code none} or blank. */
    static java.util.EnumSet<TextDecorationLine> lines(String css) {
        java.util.EnumSet<TextDecorationLine> out = java.util.EnumSet.noneOf(TextDecorationLine.class);
        for (String term : CssValues.terms(css == null ? "" : css)) {
            for (TextDecorationLine line : TextDecorationLine.values()) {
                if (keywordOf(line).equalsIgnoreCase(term)) out.add(line);
            }
        }
        return out;
    }

    /**
     * A color declaration as the color row edits it: what is declared, else what the element draws, so an unset
     * row opens on the color in front of the person rather than on black.
     */
    private static Property<Integer> color(StyleFields fields, @Nullable UIElement node, String name) {
        return fields.value(name).map(css -> {
            Integer parsed = css == null || css.isBlank() ? null : ColorValue.parseCssColor(css);
            if (parsed != null && parsed != 0) return parsed;
            Integer own = node == null ? null : node.getStyle().computed().get(StylePropertyRegistry.COLOR);
            return own == null ? 0xFFFFFFFF : own;
        }, CssValues::color);
    }

    /** A {@code font-family} as the row shows it: a bundled face by its name, anything else as written. */
    static String fontName(String css) {
        String trimmed = css == null ? "" : css.trim();
        int face = FACES.indexOf(trimmed);
        return face >= 0 ? faceNames().get(face) : trimmed;
    }

    /** What the row writes for a typed or picked name: a bundled face's resource path, else the name itself. */
    static String fontCss(String typed) {
        String trimmed = typed == null ? "" : typed.trim();
        int face = faceNames().indexOf(trimmed);
        return face >= 0 ? FACES.get(face) : trimmed;
    }

    /** The size slider's reach, in px — the Style tab's font-size row slides over the same. */
    static final float MIN_SIZE = 6f;
    static final float MAX_SIZE = 96f;

    /** What the line-height row offers: the keyword, then the multipliers a type scale reaches for. */
    static final List<String> LINE_HEIGHTS = List.of("normal", "1", "1.2", "1.4", "1.5", "2");

    /** The mask's options, in the order a reader meets the lines: under, through, over. */
    static final List<String> DECORATIONS = List.of("Underline", "Strike through", "Overline");

    private static final List<TextDecorationLine> DECORATION_LINES =
            List.of(TextDecorationLine.UNDERLINE, TextDecorationLine.LINE_THROUGH, TextDecorationLine.OVERLINE);

    /** The mask's selection for a declaration. */
    static java.util.Set<String> decorationNames(String css) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.EnumSet<TextDecorationLine> on = lines(css);
        for (int i = 0; i < DECORATION_LINES.size(); i++) {
            if (on.contains(DECORATION_LINES.get(i))) out.add(DECORATIONS.get(i));
        }
        return out;
    }

    /** The declaration for a mask's selection, {@code none} when nothing is ticked. */
    static String decorationCss(@Nullable java.util.Set<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < DECORATION_LINES.size(); i++) {
            if (names == null || !names.contains(DECORATIONS.get(i))) continue;
            if (out.length() > 0) out.append(' ');
            out.append(keywordOf(DECORATION_LINES.get(i)));
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static String keywordOf(TextDecorationLine line) {
        return line.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** A keyword declaration, matched without regard to case and reading as the first option when unset. */
    private static Property<String> keyword(StyleFields fields, String property, List<String> options) {
        return fields.value(property).map(css -> {
            for (String option : options) {
                if (option.equalsIgnoreCase(css.trim())) return option;
            }
            return options.get(0);
        }, chosen -> chosen);
    }

    /** A {@code font-weight} as the option it draws as: {@code 600} and above is bold, as {@link FontWeight} rules. */
    static String weight(String css) {
        String trimmed = css.trim();
        if (!trimmed.isEmpty() && Character.isDigit(trimmed.charAt(0))) {
            try {
                return FontWeight.ofNumeric(Integer.parseInt(trimmed)).isBold() ? "bold" : "normal";
            } catch (NumberFormatException ignored) {
                return "normal";
            }
        }
        return "bold".equalsIgnoreCase(trimmed) ? "bold" : "normal";
    }

    /** What {@code node} computes for {@code property}, as CSS — for what the lab shows but does not edit. */
    static Property<String> computed(@Nullable UIElement node, StyleProperty<?> property) {
        return Property.derived(() -> {
            Object value = node == null ? null : node.getStyle().computed().get(property);
            return value == null ? "" : StyleFields.cast(property).write(value);
        });
    }

    /** The width term of a {@code text-stroke}, or "" — a width is whichever term is not a color. */
    static String width(String stroke) {
        for (String term : CssValues.terms(stroke)) {
            if (ColorValue.parseCssColor(term) == null) return term;
        }
        return "";
    }

    /** The color term of a {@code text-stroke}, or "". */
    static String color(String stroke) {
        for (String term : CssValues.terms(stroke)) {
            if (ColorValue.parseCssColor(term) != null) return term;
        }
        return "";
    }

    /** The width, keeping a zero's unit so dialling a stroke to nothing and back does not lose it. */
    private static String widthOr(String stroke) {
        String width = width(stroke);
        return width.isEmpty() ? "0px" : width;
    }

    /** The color, or white — what a stroke draws when none is named. */
    private static String colorOr(String stroke) {
        String color = color(stroke);
        return color.isEmpty() ? "#FFFFFF" : color;
    }

    private static boolean isEm(String stroke) {
        return width(stroke).endsWith("%");
    }

    /** A stroke width of {@code px}, spelled in px or as the percentage of {@code size} an em width is. */
    static String spell(float px, float size, boolean em) {
        if (!em || size <= 0f) return CssValues.px(px);
        return CssValues.write(px / size * 100d) + "%";
    }

    /** What an em is worth here: the size the target declares, or the element's own. */
    private static float fontSize(StyleFields fields, @Nullable UIElement node) {
        float declared = CssValues.number(fields.valueOf("font-size"), 0f);
        if (declared > 0f) return declared;
        Object computed = node == null ? null : node.getStyle().computed().get(StylePropertyRegistry.FONT_SIZE);
        return computed instanceof Number size ? size.floatValue() : 0f;
    }

    /**
     * The widest outline the face carries, as a fraction of the em — asked of the specimen, since the renderer
     * clamps to it per face and a track past it drags through a range where nothing changes.
     */
    private static float capEm(UIText line) {
        try {
            float cap = line.maxStrokeWidthEm();
            if (cap > 0f) return cap;
        } catch (RuntimeException noFaceToAsk) {
            // Headless, or a family that will not load: the band every face holds.
        }
        return STROKE_CAP_EM;
    }

    private static List<String> faceNames() {
        return FACES.stream().map(TypographyLab::shortName).toList();
    }

    static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.replace(".ttf", "").replace(".otf", "").replace("-Regular", "");
    }

    /** The caption, measured: the line box the specimen was laid out at, and the stroke against the face's cap. */
    private static String measured(UIText line, String stroke, StyleFields fields, @Nullable UIElement node) {
        Box box = line.box();
        // A TENTH OF A PIXEL AT MOST: three places of a measurement wrapped the caption onto a second line of noise.
        String laid = box == null ? "not laid out yet"
                : "line box " + tenth(box.height()) + "px tall, " + tenth(box.width()) + "px wide";
        float size = fontSize(fields, node);
        float number = CssValues.number(width(stroke), 0f);
        float px = isEm(stroke) ? number / 100f * size : number;
        if (size <= 0f || px <= 0f) return laid;
        return laid + " — stroke " + tenth(px) + "px = " + CssValues.write(Math.round(px / size * 1000f) / 1000d)
                + "em, cap " + tenth(capEm(line) * 100f) + "%";
    }

    private static String tenth(double value) {
        return CssValues.write(Math.round(value * 10d) / 10d);
    }
}
