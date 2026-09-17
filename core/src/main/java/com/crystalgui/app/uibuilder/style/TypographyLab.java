package com.crystalgui.app.uibuilder.style;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
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

    /**
     * How a stroke width is spelled. <b>{@code em} is written as a percentage</b>: {@code TextStrokeStyle}
     * resolves the width against the font size, so {@code 15%} is 0.15em, while a literal {@code em} does not
     * parse there. The gallery's text lab spells it the same way.
     */
    static final List<String> UNITS = List.of("px", "em");

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
        StyleLab lab = StyleLab.over(anchor, "Type");
        Property<String> text = Property.of("Handgloves");
        UIText line = new UIText("");
        PropertyWatch.follow(line, text, line::setText);
        lab.specimen(line);

        // WHAT THE ELEMENT DRAWS WHEN NOTHING IS DECLARED, not the sample's own look: a sample is set bold, so an
        // undeclared weight showed bold and choosing bold changed nothing.
        for (String name : List.of("font-size", "font-weight", "font-style", "font-family", "paint-order")) {
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
        LiveEdits.follow(line, StylePropertyRegistry.TEXT_STROKE_COLOR, stroke.map(TypographyLab::colour));
        // WHAT THE LAB DOES NOT EDIT, off the element: a stroke is drawn against the fill and clipped by the
        // alignment, so a specimen carrying neither shows a colour that is not the colour.
        LiveEdits.follow(line, StylePropertyRegistry.COLOR, computed(node, StylePropertyRegistry.COLOR));
        LiveEdits.follow(line, StylePropertyRegistry.STROKE_ALIGN, computed(node, StylePropertyRegistry.STROKE_ALIGN));

        lab.form().prop(ConfigDescriptor.text("lab.text", "Text"),
                text.map(shown -> shown, typed -> typed == null || typed.isBlank() ? "Handgloves" : typed));
        lab.form().prop(ConfigDescriptor.number("lab.size", "Size").range(6f, 96f).unit("px").integral(true),
                fields.value("font-size").map(css -> (double) CssValues.number(css, 0f),
                        size -> CssValues.length(StylePropertyRegistry.FONT_SIZE, CssValues.dragged(size))));
        lab.form().prop(ConfigDescriptor.select("lab.weight", "Weight", WEIGHTS),
                fields.value("font-weight").map(TypographyLab::weight, chosen -> chosen));
        lab.form().prop(ConfigDescriptor.select("lab.style", "Style", STYLES), keyword(fields, "font-style", STYLES));
        lab.form().prop(ConfigDescriptor.select("lab.face", "Face", faceNames()), fields.value("font-family").map(
                css -> faceNames().get(Math.max(0, FACES.indexOf(css.trim()))),
                name -> FACES.get(Math.max(0, faceNames().indexOf(name)))));

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
                    return (isEm(now) ? CssValues.write(dragged) + "%" : CssValues.px(dragged)) + " " + colourOr(now);
                }));
        lab.form().prop(ConfigDescriptor.color("lab.stroke-color", "Stroke colour"), stroke.map(
                css -> {
                    Integer parsed = ColorValue.parseCssColor(colourOr(css));
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
                    return spell(px, size, UNITS.indexOf(unit) == 1) + " " + colourOr(now);
                }));
        lab.form().prop(ConfigDescriptor.select("lab.order", "Paint order", ORDERS), keyword(fields, "paint-order", ORDERS));

        lab.caption(Property.derived(() -> measured(line, stroke.get(), fields, node)));
        lab.open();
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
    private static Property<String> computed(@Nullable UIElement node, StyleProperty<?> property) {
        return Property.derived(() -> {
            Object value = node == null ? null : node.getStyle().computed().get(property);
            return value == null ? "" : StyleFields.cast(property).write(value);
        });
    }

    /** The width term of a {@code text-stroke}, or "" — a width is whichever term is not a colour. */
    static String width(String stroke) {
        for (String term : CssValues.terms(stroke)) {
            if (ColorValue.parseCssColor(term) == null) return term;
        }
        return "";
    }

    /** The colour term of a {@code text-stroke}, or "". */
    static String colour(String stroke) {
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

    /** The colour, or white — what a stroke draws when none is named. */
    private static String colourOr(String stroke) {
        String colour = colour(stroke);
        return colour.isEmpty() ? "#FFFFFF" : colour;
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

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.replace(".ttf", "").replace(".otf", "").replace("-Regular", "");
    }

    /** The caption, measured: the line box the specimen was laid out at, and the stroke against the face's cap. */
    private static String measured(UIText line, String stroke, StyleFields fields, @Nullable UIElement node) {
        Box box = line.box();
        String laid = box == null ? "not laid out yet"
                : "line box " + CssValues.px(box.height()) + " tall, " + CssValues.px(box.width()) + " wide";
        float size = fontSize(fields, node);
        float number = CssValues.number(width(stroke), 0f);
        float px = isEm(stroke) ? number / 100f * size : number;
        if (size <= 0f || px <= 0f) return laid;
        return laid + " — stroke " + CssValues.px(px) + " = " + CssValues.write(px / size) + "em, cap "
                + CssValues.write(capEm(line) * 100f) + "%";
    }
}
