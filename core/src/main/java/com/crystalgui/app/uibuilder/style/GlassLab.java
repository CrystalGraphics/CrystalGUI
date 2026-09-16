package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.text.UIText;

/**
 * The glass lab: {@code backdrop-filter} on live sliders, over a backdrop worth filtering.
 *
 * <pre>{@code
 * GlassLab.open(chip, StylePropertyRegistry.BACKDROP_FILTER, css);
 * }</pre>
 *
 * <p>This is the gallery's glass page as an editor. Blur, bezel, index of refraction, specular, noise and
 * saturation are each a slider, and the specimen sits over coloured shapes rather than a flat ground —
 * because every one of those functions is invisible against a plain colour, which is exactly why a number
 * field cannot be used to tune them.</p>
 *
 * <p>The functions are the engine's own grammar ({@code BackdropFilterValue}): CSS's {@code blur()} and
 * {@code saturate()}, and this engine's {@code tint}, {@code bezel}, {@code ior}, {@code specular} and
 * {@code noise}. A function the lab does not offer is kept exactly as written, so opening the lab on a
 * hand-tuned value never quietly drops half of it.</p>
 */
public final class GlassLab {

    private static final String DEFAULT = "blur(12px) saturate(1.2)";

    /** The functions with a slider, in the order the gallery's page lists them: name, min, max. */
    private static final Object[][] KNOBS = {
            {"blur", 0f, 40f}, {"bezel", 0f, 24f}, {"ior", 1f, 2f},
            {"specular", 0f, 1f}, {"noise", 0f, 0.2f}, {"saturate", 0f, 3f}};

    private final Property<String> css;
    private final StyleLab lab;

    /** Every function in the value, by name, so one this lab has no slider for is written back untouched. */
    private final Map<String, String> functions = new LinkedHashMap<>();

    private final UIText numbers = new UIText("");

    private GlassLab(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        this.css = css;
        this.lab = StyleLab.over(anchor, "Glass", property, css);
        read(css.get());
    }

    public static GlassLab open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        GlassLab glass = new GlassLab(anchor, property, css);
        glass.build();
        glass.lab.open();
        return glass;
    }

    private void build() {
        // OVER SOMETHING, not over a flat ground: a blur of nothing looks like no blur at all.
        lab.specimens().backdrop();

        for (Object[] knob : KNOBS) {
            String name = (String) knob[0];
            float min = (Float) knob[1];
            float max = (Float) knob[2];

            UIElement row = new UIElement();
            row.addClass("__lab-row__");
            row.append(new UIText(name));

            Slider slider = new Slider();
            slider.setRange(min, max);
            slider.setValue(valueOf(name, min));
            slider.onValueChanged.connect(value -> {
                functions.put(name, CssValues.function(name, unit(name, value)));
                write();
            });
            row.append(slider);
            lab.content().append(row);
        }

        UIElement tint = new UIElement();
        tint.addClass("__lab-row__");
        tint.append(new UIText("tint"));
        ColorSelector picker = new ColorSelector();
        picker.onColorChanged.connect(argb -> {
            functions.put("tint", CssValues.function("tint", CssValues.color(argb)));
            write();
        });
        tint.append(picker);
        Button clear = new Button("no tint");
        clear.addClass("__lab-keyword__");
        clear.attachListener(() -> {
            functions.remove("tint");
            write();
        });
        tint.append(clear);
        lab.content().append(tint);
        lab.content().append(numbers);

        lab.caption(() -> "blur " + CssValues.px(valueOf("blur", 0f))
                + " · bezel " + CssValues.px(valueOf("bezel", 0f))
                + " · ior " + CssValues.write(valueOf("ior", 1f))
                + " — " + functions.size() + " functions, "
                + (functions.size() - offered() > 0 ? (functions.size() - offered()) + " kept as written" : "all editable here"));
        refresh();
    }

    /** How many of the value's functions this lab has a control for. */
    private int offered() {
        int count = 0;
        for (String name : functions.keySet()) {
            if ("tint".equals(name) || hasKnob(name)) count++;
        }
        return count;
    }

    private static boolean hasKnob(String name) {
        for (Object[] knob : KNOBS) {
            if (knob[0].equals(name)) return true;
        }
        return false;
    }

    /** {@code blur(12px) saturate(1.2)} into its functions, keeping their order. */
    private void read(String value) {
        functions.clear();
        String text = value == null || value.isBlank() || value.trim().equalsIgnoreCase("none") ? DEFAULT : value;
        for (String function : CssValues.functions(text)) {
            String name = CssValues.functionName(function);
            if (!name.isEmpty()) functions.put(name, function);
        }
    }

    /** A function's first argument as a number, or {@code fallback} when it is not there. */
    private float valueOf(String name, float fallback) {
        String function = functions.get(name);
        return function == null ? fallback : CssValues.number(CssValues.arguments(function), fallback);
    }

    /** Lengths take px; ratios take a bare number — the grammar's own split. */
    private static String unit(String name, double value) {
        return "blur".equals(name) || "bezel".equals(name) ? CssValues.px(value) : CssValues.write(value);
    }

    private void write() {
        css.set(CssValues.joinFunctions(new ArrayList<>(functions.values())));
        refresh();
    }

    private void refresh() {
        numbers.setText(CssValues.joinFunctions(new ArrayList<>(functions.values())));
        lab.refresh();
    }
}
