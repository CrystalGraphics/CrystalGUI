package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;

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

    /** The functions with a slider, in the order the gallery's page lists them: label, name, min, max. */
    private static final Object[][] KNOBS = {
            {"Blur", "blur", 0f, 40f},
            {"Bezel", "bezel", 0f, 24f},
            {"Refraction", "ior", 1f, 2f},
            {"Specular", "specular", 0f, 1f},
            {"Noise", "noise", 0f, 0.2f},
            {"Saturation", "saturate", 0f, 3f}};

    private final Property<String> css;
    private final StyleLab lab;

    /** Every function in the value, by name, so one this lab has no slider for is written back untouched. */
    private final Map<String, String> functions = new LinkedHashMap<>();

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
        lab.specimen().backdrop();

        for (Object[] knob : KNOBS) {
            String label = (String) knob[0];
            String name = (String) knob[1];
            float min = (Float) knob[2];
            float max = (Float) knob[3];
            lab.form().prop(ConfigDescriptor.number("lab." + name, label).range(min, max).decimals(2),
                    Property.derived(() -> (double) valueOf(name, min), value -> {
                        functions.put(name, CssValues.function(name, unit(name, CssValues.dragged(value))));
                        write();
                    }));
        }

        lab.form().separator();
        lab.form().prop(ConfigDescriptor.color("lab.tint", "Tint"),
                Property.derived(this::tint, argb -> {
                    functions.put("tint", CssValues.function("tint", CssValues.color(argb)));
                    write();
                }));

        lab.caption(() -> {
            int kept = functions.size() - offered();
            return functions.size() + " functions"
                    + (kept > 0 ? ", " + kept + " kept as written" : ", all editable here")
                    + " — blur " + CssValues.px(valueOf("blur", 0f))
                    + ", ior " + CssValues.write(valueOf("ior", 1f));
        });
        lab.refresh();
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
            if (knob[1].equals(name)) return true;
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

    private int tint() {
        String function = functions.get("tint");
        Integer parsed = function == null ? null : ColorValue.parseCssColor(CssValues.arguments(function));
        return parsed == null ? 0x00000000 : parsed;
    }

    /** Lengths take px; ratios take a bare number — the grammar's own split. */
    private static String unit(String name, double value) {
        return "blur".equals(name) || "bezel".equals(name) ? CssValues.px(value) : CssValues.write(value);
    }

    private void write() {
        css.set(CssValues.joinFunctions(new ArrayList<>(functions.values())));
        lab.refresh();
    }
}
