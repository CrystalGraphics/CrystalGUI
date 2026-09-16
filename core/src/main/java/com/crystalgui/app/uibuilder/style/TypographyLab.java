package com.crystalgui.app.uibuilder.style;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.control.SliderControl;
import com.crystalgui.widget.text.UIText;

/**
 * The typography lab: a line of real text, set in the face it will be set in.
 *
 * <pre>{@code
 * TypographyLab.open(chip, fields, property, css, node);
 * }</pre>
 *
 * <p>The gallery's text lab as an editor. The specimen is drawn on dark and on light, because a stroke or a
 * light weight is legible on one ground and gone on the other, and the caption states what the specimen
 * <b>measured</b> rather than repeating the number that was typed.</p>
 *
 * <p>Several declarations, one lab: size, weight, style, the face, the stroke (written as the
 * {@code text-stroke} shorthand, which is the only spelling a sheet accepts) and the paint order. Each is
 * a separate declaration in the file and one text edit of its own.</p>
 */
public final class TypographyLab {

    static final List<String> WEIGHTS = List.of("normal", "bold", "300", "500", "700", "900");
    static final List<String> STYLES = List.of("normal", "italic");
    static final List<String> ORDERS = List.of("normal", "stroke");

    /**
     * How a stroke width is spelled. <b>{@code em} is written as a PERCENTAGE</b>, because that is what
     * one is here: {@code TextStrokeStyle} resolves this property's {@code LengthPercent} against the
     * font size, so {@code 15%} already means 0.15em — while a literal {@code em} does not parse, since
     * {@code LengthPercent} is shared with {@code border-radius}, where the axis is the box and 1em
     * would have to mean the whole width. The gallery's own text lab spells it the same way.
     */
    static final List<String> UNITS = List.of("px", "em");

/**
     * The widest outline the face can carry, as a fraction of the em.
     *
     * <p><b>Asked of the specimen, never assumed.</b> {@code TextStrokeStyle} clamps every stroke to
     * {@code CgFontRegistry.maxStrokeWidthEm} and warns once when it does, so a track that runs past it
     * is a control you can drag through a range where nothing happens — which is what a slider is for
     * saying cannot happen. {@link UIText#maxStrokeWidthEm()} exists to be read for exactly this, and
     * the gallery's own text lab caps its slider the same way.</p>
     *
     * <p>It depends on the FACE: the narrow band every face can hold is about 13%, and a face with no
     * dense script in it is banded over twice as wide. So this moves when the Face row does, and the
     * fallback is only for a tree with no specimen laid out yet or a stack with nothing to load.</p>
     */
    private float strokeCapEm() {
        if (lab.sample() instanceof UIText line) {
            try {
                float cap = line.maxStrokeWidthEm();
                if (cap > 0f) return cap;
            } catch (RuntimeException noFaceToAsk) {
                // Headless, or a family that will not load: the band below is what every face holds.
            }
        }
        return STROKE_CAP_EM;
    }

    /** The narrow band every face can carry, for when there is no specimen to ask. */
    private static final float STROKE_CAP_EM = 0.125f;

    /** The faces that ship: a stack the cache cannot load throws where it is measured, not where it is set. */
    static final List<String> FACES = List.of(
            "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf",
            "crystalgui:ui/fonts/JetBrainsMono-Regular.ttf",
            "crystalgui:ui/fonts/Minecraft.otf");

    private final StyleFields fields;
    private final StyleLab lab;

    /** The stroke row, kept because its range follows the font size. @see #STROKE_EM */
    @Nullable
    private SliderControl strokeSlider;

    /** The element being edited, for the properties the preview copies rather than edits. */
    @Nullable
    private final UIElement node;

    private String text = "Handgloves";

    private TypographyLab(UIElement anchor, StyleFields fields, @Nullable StyleProperty<?> property,
                          Property<String> css, @Nullable UIElement node) {
        this.node = node;
        this.fields = fields;
        this.lab = StyleLab.over(anchor, "Type", property, css);
    }

    public static TypographyLab open(UIElement anchor, StyleFields fields, @Nullable StyleProperty<?> property,
                                     Property<String> css, @Nullable UIElement node) {
        TypographyLab type = new TypographyLab(anchor, fields, property, css, node);
        type.build();
        type.lab.open();
        return type;
    }

    private void build() {
        // ONE PER GROUND: an element lives in one tree at a time, so the dark and light specimens are two
        // texts the lab keeps in step rather than one it moves.
        lab.specimen(() -> {
            UIText line = new UIText(text);
            line.addClass(StyleLab.SAMPLE_CLASS);
            return line;
        });

        lab.form().prop(ConfigDescriptor.text("lab.text", "Text"),
                Property.derived(() -> text, typed -> {
                    text = typed == null || typed.isBlank() ? "Handgloves" : typed;
                    refresh();
                }));
        lab.form().prop(ConfigDescriptor.number("lab.size", "Size").range(6f, 96f).unit("px").integral(true),
                length("font-size"));
        lab.form().prop(ConfigDescriptor.select("lab.weight", "Weight", WEIGHTS), keyword("font-weight", WEIGHTS));
        lab.form().prop(ConfigDescriptor.select("lab.style", "Style", STYLES), keyword("font-style", STYLES));
        lab.form().prop(ConfigDescriptor.select("lab.face", "Face", faceNames()), face());

        lab.form().separator();
        Configurator stroke = lab.form().prop(
                ConfigDescriptor.number("lab.stroke", "Stroke")
                        // Opened on a placeholder span: tuneStrokeRow puts the face's own ceiling
                        // here on the first refresh, in whichever unit the value is written in.
                        .range(0f, 1f).unit("px").step(0.1f).decimals(1),
                Property.derived(() -> (double) strokeAmount(), value -> {
                    // THE NUMBER AS WRITTEN, in whatever unit the value is already in: dragging never
                    // changes the unit, so there is no conversion here to lose precision in.
                    writeStrokeAmount((float) CssValues.dragged(value), strokeColour(), strokeIsEm());
                    refresh();
                }));
        strokeSlider = stroke.control() instanceof SliderControl slider ? slider : null;
        lab.form().prop(ConfigDescriptor.color("lab.stroke-color", "Stroke colour"),
                Property.derived(this::strokeArgb, argb -> {
                    // The width AS WRITTEN, so picking a colour cannot convert the unit under it.
                    writeStrokeAmount(strokeAmount(), CssValues.color(argb), strokeIsEm());
                    refresh();
                }));
        lab.form().prop(ConfigDescriptor.select("lab.unit", "Stroke unit", UNITS),
                Property.derived(() -> UNITS.get(strokeIsEm() ? 1 : 0), unit -> {
                    // The ONE conversion, because this is the one act that changes what the number means.
                    boolean em = UNITS.indexOf(unit) == 1;
                    writeStrokeAmount(convert(strokeAmount(), strokeIsEm(), em), strokeColour(), em);
                    refresh();
                }));
        lab.form().prop(ConfigDescriptor.select("lab.order", "Paint order", ORDERS),
                keyword("paint-order", ORDERS));

        lab.caption(this::measured);
        refresh();
    }

    // ── The declarations ────────────────────────────────────────────────────

    /**
     * A length declaration as a number, written back in whatever spelling the property accepts.
     *
     * <p>Size and stroke step by 1. A font size is chosen in whole pixels in every tool that has one, and
     * a slider that lands on 71.77 is offering a precision nobody asked it for. The blur and the glass
     * knobs stay continuous, where a half is a real answer -- an index of refraction of 1.5 is the
     * point of the control.</p>
     */
    private Property<Double> length(String property) {
        return Property.derived(() -> (double) CssValues.number(fields.valueOf(property), 0f), value -> {
            fields.value(property)
                    .set(CssValues.length(StyleFields.propertyOf(property), CssValues.dragged(value)));
            refresh();
        });
    }

    /**
     * A keyword declaration, reading as the first option when the node says nothing.
     *
     * <p>Matched without regard to case, because what comes back is whatever spelling the source holds —
     * a sheet's own text for a rule, the property's writer for an element — and an option the list does
     * not recognise reads as the first one, so the control silently shows the wrong answer.</p>
     */
    private Property<String> keyword(String property, List<String> options) {
        return Property.derived(() -> {
            String value = fields.valueOf(property).trim();
            for (String option : options) {
                if (option.equalsIgnoreCase(value)) return option;
            }
            return options.get(0);
        }, value -> {
            fields.value(property).set(value);
            refresh();
        });
    }

    /** The face, listed by its file name — a resource path is unreadable in a dropdown. */
    private Property<String> face() {
        return Property.derived(() -> {
            int at = FACES.indexOf(fields.valueOf("font-family").trim());
            return faceNames().get(Math.max(0, at));
        }, name -> {
            fields.value("font-family").set(FACES.get(Math.max(0, faceNames().indexOf(name))));
            refresh();
        });
    }

    private static List<String> faceNames() {
        return FACES.stream().map(TypographyLab::shortName).toList();
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.replace(".ttf", "").replace(".otf", "").replace("-Regular", "");
    }

    /**
     * The stroke, spelled the way the target can hold it.
     *
     * <p><b>A sheet takes only the shorthand and an element only the longhands</b>, and the asymmetry is
     * the engine's: {@code text-stroke-width} and {@code text-stroke-color} name
     * {@code getAuthoredThrough}, so a stylesheet refuses them — while {@code text-stroke} is a shorthand
     * the registry does not hold at all, so there is no property to set inline under that name. Writing
     * the shorthand at an inline target resolved nothing and silently did nothing at all.</p>
     */
    /** The number the slider edits: a percentage when the value is font-relative, pixels otherwise. */
    private float strokeAmount() {
        return CssValues.number(written(), 0f);
    }

    /** The same stroke, measured the other way. Only the unit row does this. */
    private float convert(float amount, boolean fromEm, boolean toEm) {
        float size = fontSize();
        if (fromEm == toEm || size <= 0f) return amount;
        return toEm ? amount / size * 100f : amount / 100f * size;
    }

    /**
     * As above, in a named unit — what the unit row itself writes.
     *
     * <p><b>The value is the only record of which unit is in use.</b> A field beside it was a second
     * answer to the same question and they came apart immediately: the row READ the unit off the value
     * and the writer took it from the field, so a lab opened on a stroke already written in em wrote
     * pixels the moment the slider moved, and the row then dutifully followed the value back to px.</p>
     */
    private void writeStrokeAmount(float amount, String colour, boolean em) {
        String spelled = em ? CssValues.write(amount) + "%" : CssValues.px(amount);
        if (!fields.target().isInline()) {
            fields.value("text-stroke").set(spelled + " " + colour);
            return;
        }
        // A WIDTH OF ZERO IS A WIDTH, not an instruction to forget the rest. Removing both declarations
        // dropped the only record of the colour and of the unit, so `strokeColour` fell back to white and
        // `strokeIsEm` read an empty value as px: dialling the stroke down to nothing and back up again
        // came back white and in pixels. `0px` and `0%` both draw no stroke and remember which was meant.
        fields.value("text-stroke-width").set(spelled);
        fields.value("text-stroke-color").set(colour);
    }

    /**
     * A stroke width in px, as the chosen unit writes it. @see #UNITS
     *
     * <p><b>Not {@code dragged}</b>, which rounds to two places: the gesture authored the PIXELS, and a
     * unit conversion is arithmetic on that answer rather than a second authoring of it. Rounding the
     * derived percentage threw away the precision the drag had, so a stroke set to a clean 2.9 came back
     * as 2.899 the moment it was read through the percentage -- the field and the measured caption then
     * disagreed about the same value. Three places is what {@link CssValues#write} keeps, which lands the
     * round trip back on the slider's own tenth.</p>
     */
    static String spell(float px, float size, boolean em) {
        if (!em || size <= 0f) return CssValues.px(px);
        return CssValues.write(px / size * 100d) + "%";
    }

    /** The reach and the increment of the stroke row, which depend on what it is currently measuring. */
    private void tuneStrokeRow() {
        if (strokeSlider == null) return;
        boolean em = strokeIsEm();
        // THE FACE'S OWN CEILING, in whichever unit the row is measuring: a fraction of the em is a
        // fixed percentage and a moving number of pixels. The gallery's strokeSliderMax says it the
        // same way.
        float cap = strokeCapEm();
        strokeSlider.setRange(0f, em ? cap * 100f : Math.max(1f, cap * fontSize()));
        strokeSlider.setStep(0.1f);
        strokeSlider.setUnit(em ? "%" : "px");
    }

    /** Whether the width as written is font-relative. */
    private boolean strokeIsEm() {
        return written().endsWith("%");
    }

    /** The stroke width as written, in whatever unit the file holds. */
    private String written() {
        if (fields.target().isInline()) return fields.valueOf("text-stroke-width").trim();
        List<String> terms = CssValues.terms(fields.valueOf("text-stroke"));
        return terms.isEmpty() ? "" : terms.get(0).trim();
    }

    /** <b>Always in px</b>, whichever unit the file uses — one number for the slider to move. */
    private float strokeWidth() {
        String width = written();
        float number = CssValues.number(width, 0f);
        return width.endsWith("%") ? number / 100f * fontSize() : number;
    }

    /** What an em is worth here: the size this lab is editing, or the element's own. */
    private float fontSize() {
        float declared = CssValues.number(fields.valueOf("font-size"), 0f);
        if (declared > 0f) return declared;
        Object computed = node == null ? null : node.getStyle().computed().get(StylePropertyRegistry.FONT_SIZE);
        return computed instanceof Number size ? size.floatValue() : 0f;
    }

    private String strokeColour() {
        if (fields.target().isInline()) {
            String colour = fields.valueOf("text-stroke-color");
            return colour.isEmpty() ? "#FFFFFF" : colour;
        }
        List<String> terms = CssValues.terms(fields.valueOf("text-stroke"));
        return terms.size() > 1 ? terms.get(terms.size() - 1) : "#FFFFFF";
    }

    private int strokeArgb() {
        Integer parsed = ColorValue.parseCssColor(strokeColour());
        return parsed == null ? 0xFFFFFFFF : parsed;
    }

    /**
     * The caption, measured: the line box the specimen was laid out at, and the stroke as a fraction of the
     * size — the arithmetic the gallery's page prints, read off the box rather than repeated.
     */
    private String measured() {
        Box box = lab.sample() == null ? null : lab.sample().box();
        float size = CssValues.number(fields.valueOf("font-size"), 0f);
        String line = box == null ? "not laid out yet"
                : "line box " + CssValues.px(box.height()) + " tall, " + CssValues.px(box.width()) + " wide";
        float stroke = strokeWidth();
        if (size <= 0f || stroke <= 0f) return line;
        // THE CAP TOO, because it is the answer to "why does nothing change above here" -- and it is the
        // face's, so it moves when the Face row does.
        return line + " — stroke " + CssValues.px(stroke) + " = " + CssValues.write(stroke / size)
                + "em, cap " + CssValues.write(strokeCapEm() * 100f) + "%";
    }

    /** Both specimens carry what the element carries: every declaration this lab writes, applied. */
    private void refresh() {
        tuneStrokeRow();
        UIElement specimen = lab.sample();
        if (specimen != null) {
            if (specimen instanceof UIText line) line.setText(text);
            apply(specimen, "font-size", fields.valueOf("font-size"));
            apply(specimen, "font-weight", fields.valueOf("font-weight"));
            apply(specimen, "font-style", fields.valueOf("font-style"));
            apply(specimen, "font-family", fields.valueOf("font-family"));
            apply(specimen, "paint-order", fields.valueOf("paint-order"));
            apply(specimen, "text-stroke-width", strokeWidth() > 0f ? CssValues.px(strokeWidth()) : "");
            apply(specimen, "text-stroke-color", strokeWidth() > 0f ? strokeColour() : "");
            // WHAT THE LAB DOES NOT EDIT, taken off the element itself. A stroke is drawn against the
            // fill and clipped by the alignment, so a specimen carrying neither shows a colour that is
            // not the colour: vivid red on the canvas came through washed out in the preview.
            copy(specimen, StylePropertyRegistry.COLOR);
            copy(specimen, StylePropertyRegistry.STROKE_ALIGN);
        }
        lab.refresh();
    }

    /** A property this lab has no control for, as the element computes it right now. */
    private void copy(UIElement specimen, StyleProperty<?> property) {
        Object value = node == null ? null : node.getStyle().computed().get(property);
        apply(specimen, property.name, value == null ? "" : cast(property).write(value));
    }

    private static void apply(UIElement specimen, String property, String value) {
        StyleProperty<?> styled = StyleFields.propertyOf(property);
        if (styled == null) return;
        if (value == null || value.isBlank()) {
            LiveEdits.clearInline(specimen, styled);
        } else {
            LiveEdits.setInline(specimen, cast(styled), value);
        }
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
