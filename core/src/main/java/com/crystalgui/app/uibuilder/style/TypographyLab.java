package com.crystalgui.app.uibuilder.style;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

/**
 * The typography lab: a line of real text, set in the face it will be set in.
 *
 * <pre>{@code
 * TypographyLab.open(chip, fields, node);
 * }</pre>
 *
 * <p>The gallery's text lab as an editor. The specimen is drawn on dark and on light, because a stroke or a
 * light weight is legible on one ground and gone on the other, and the caption states the size
 * <b>measured off the specimen</b> rather than repeating the number that was typed — so a value in
 * {@code em}, or a face whose metrics differ, still reads truthfully.</p>
 *
 * <p>Several declarations, one lab: size, weight, style, the face, the stroke (written as the
 * {@code text-stroke} shorthand, which is the only spelling a sheet accepts) and the paint order. Each is a
 * separate declaration in the file and one text edit of its own.</p>
 */
public final class TypographyLab {

    private static final List<String> WEIGHTS = List.of("normal", "bold", "100", "300", "500", "700", "900");
    private static final List<String> FACES = List.of("system-ui", "monospace",
            "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf", "crystalgui:ui/fonts/JetBrainsMono-Regular.ttf");

    private final StyleFields fields;
    private final StyleLab lab;

    @Nullable
    private final UIElement node;

    private final UIText sample = new UIText("Handgloves");
    private final TextField sampleText = new TextField();

    private TypographyLab(UIElement anchor, StyleFields fields, @Nullable StyleProperty<?> property,
                          Property<String> css, @Nullable UIElement node) {
        this.fields = fields;
        this.node = node;
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
        // ONE PER GROUND: an element lives in one tree at a time, so the dark and light specimens are
        // two texts the lab keeps in step rather than one it moves.
        lab.specimens(() -> new UIText(sample.getText()));

        UIElement text = new UIElement();
        text.addClass("__lab-row__");
        sampleText.setText(sample.getText());
        sampleText.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        sampleText.attachListener(typed -> {
            sample.setText(typed.isEmpty() ? "Handgloves" : typed);
            for (UIElement each : lab.contents()) {
                if (each instanceof UIText line) line.setText(sample.getText());
            }
            refresh();
        });
        text.append(sampleText);
        lab.content().append(text);

        slider("font-size", 6f, 96f);
        keywords("font-weight", WEIGHTS);
        keywords("font-style", List.of("normal", "italic"));
        keywords("font-family", FACES);
        keywords("paint-order", List.of("normal", "stroke"));
        slider("text-stroke-width", 0f, 8f);

        UIElement colours = new UIElement();
        colours.addClass("__lab-row__");
        colours.append(new UIText("stroke"));
        ColorSelector stroke = new ColorSelector();
        stroke.onColorChanged.connect(argb -> {
            // THE SHORTHAND IS THE ONLY SPELLING a sheet accepts for a stroke, and it carries both halves:
            // writing the colour alone would be refused by the parser it has to survive.
            fields.value("text-stroke").set(CssValues.px(strokeWidth()) + " " + CssValues.color(argb));
            refresh();
        });
        colours.append(stroke);
        lab.content().append(colours);

        lab.caption(this::measured);
        refresh();
    }

    private void slider(String property, float min, float max) {
        UIElement row = new UIElement();
        row.addClass("__lab-row__");
        row.append(new UIText(property));
        Slider control = new Slider();
        control.setRange(min, max);
        control.setValue(CssValues.number(fields.valueOf(property), min));
        control.onValueChanged.connect(value -> {
            if ("text-stroke-width".equals(property)) {
                fields.value("text-stroke").set(CssValues.px(value) + " " + strokeColour());
            } else {
                fields.value(property).set(CssValues.px(value));
            }
            refresh();
        });
        row.append(control);
        lab.content().append(row);
    }

    private void keywords(String property, List<String> options) {
        UIElement row = new UIElement();
        row.addClass("__lab-row__");
        row.append(new UIText(property));
        for (String option : options) {
            Button button = new Button(shortName(option));
            button.addClass("__lab-keyword__");
            button.attachListener(() -> {
                fields.value(property).set(option);
                refresh();
            });
            row.append(button);
        }
        lab.content().append(row);
    }

    /** A font path is unreadable as a button; its file name is not. */
    private static String shortName(String option) {
        int slash = option.lastIndexOf('/');
        return slash < 0 ? option : option.substring(slash + 1).replace(".ttf", "").replace(".otf", "");
    }

    private float strokeWidth() {
        return CssValues.number(CssValues.terms(fields.valueOf("text-stroke")), 0, 0f);
    }

    private String strokeColour() {
        List<String> terms = CssValues.terms(fields.valueOf("text-stroke"));
        return terms.size() > 1 ? terms.get(terms.size() - 1) : "#FFFFFF";
    }

    /**
     * The caption, measured: the size the specimen was actually laid out at, and the stroke as a fraction
     * of it — the arithmetic the gallery's page prints, read off the box rather than repeated.
     */
    private String measured() {
        Box box = sample.box();
        String size = fields.valueOf("font-size");
        float px = CssValues.number(size, 0f);
        String line = box == null ? "not laid out yet"
                : "line box " + CssValues.px(box.height()) + ", " + CssValues.px(box.width()) + " wide";
        float stroke = strokeWidth();
        String relative = px > 0f && stroke > 0f
                ? ", stroke " + CssValues.px(stroke) + " = " + CssValues.write(stroke / px) + "em"
                : "";
        return line + relative;
    }

    private void refresh() {
        // Both specimens carry what the element carries: every declaration this lab writes, applied.
        for (String property : List.of("font-size", "font-weight", "font-style", "font-family", "paint-order",
                "text-stroke-width", "text-stroke-color")) {
            StyleProperty<?> styled = StyleFields.propertyOf(property);
            if (styled == null) continue;
            String value = property.startsWith("text-stroke")
                    ? strokeTerm(property) : fields.valueOf(property);
            for (UIElement specimen : lab.contents()) {
                if (value.isEmpty()) {
                    LiveEdits.clearInline(specimen, styled);
                } else {
                    LiveEdits.setInline(specimen, cast(styled), value);
                }
            }
        }
        lab.refresh();
    }

    /** The shorthand's two halves, for the specimen — which is styled per property, not per shorthand. */
    private String strokeTerm(String property) {
        String value = fields.valueOf("text-stroke");
        if (value.isBlank()) return "";
        return property.endsWith("width") ? CssValues.px(strokeWidth()) : strokeColour();
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
