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
 * <p>The gallery's glass page as an editor. Every function is invisible against a flat color, so the
 * specimen sits over colored shapes. The functions are the engine's grammar ({@code BackdropFilterValue});
 * one the lab has no slider for is kept exactly as written.</p>
 */
public final class GlassLab {

    private static final String DEFAULT = "blur(12px) saturate(1.2)";

    /** A function with a slider: its label, its name and its range. */
    private record Knob(String label, String name, float min, float max) {
    }

    /** In the order the gallery's page lists them. */
    private static final Knob[] KNOBS = {
            new Knob("Blur", "blur", 0f, 40f),
            new Knob("Bezel", "bezel", 0f, 24f),
            new Knob("Refraction", "ior", 1f, 2f),
            new Knob("Specular", "specular", 0f, 1f),
            new Knob("Noise", "noise", 0f, 0.2f),
            new Knob("Saturation", "saturate", 0f, 3f)};

    private GlassLab() {
    }

    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        StyleLab lab = StyleLab.over(anchor, "Glass");
        lab.specimen().backdrop().preview(property, css);

        Property<Map<String, String>> functions = css.map(GlassLab::functionsOf,
                parts -> CssValues.joinFunctions(new ArrayList<>(parts.values())));
        for (Knob knob : KNOBS) {
            lab.form().prop(ConfigDescriptor.number("lab." + knob.name(), knob.label())
                            .range(knob.min(), knob.max()).decimals(2),
                    functions.map(parts -> (double) number(parts, knob.name(), knob.min()),
                            value -> with(functions.get(), knob.name(),
                                    CssValues.function(knob.name(), unit(knob.name(), CssValues.dragged(value))))));
        }
        lab.form().separator();
        lab.form().prop(ConfigDescriptor.color("lab.tint", "Tint"),
                functions.map(GlassLab::tint,
                        argb -> with(functions.get(), "tint", CssValues.function("tint", CssValues.color(argb)))));

        lab.caption(functions.map(parts -> {
            int kept = parts.size() - offered(parts);
            return parts.size() + " functions" + (kept > 0 ? ", " + kept + " kept as written" : ", all editable here")
                    + " — blur " + CssValues.px(number(parts, "blur", 0f))
                    + ", ior " + CssValues.write(number(parts, "ior", 1f));
        }));
        lab.readout(property.name, css);
        lab.open();
    }

    /** {@code blur(12px) saturate(1.2)} into its functions by name, in order. */
    private static Map<String, String> functionsOf(String css) {
        String text = css == null || css.isBlank() || css.trim().equalsIgnoreCase("none") ? DEFAULT : css;
        Map<String, String> parts = new LinkedHashMap<>();
        for (String function : CssValues.functions(text)) {
            String name = CssValues.functionName(function);
            if (!name.isEmpty()) parts.put(name, function);
        }
        return parts;
    }

    private static Map<String, String> with(Map<String, String> parts, String name, String function) {
        Map<String, String> next = new LinkedHashMap<>(parts);
        next.put(name, function);
        return next;
    }

    /** A function's first argument as a number, or {@code fallback} when it is not there. */
    private static float number(Map<String, String> parts, String name, float fallback) {
        String function = parts.get(name);
        return function == null ? fallback : CssValues.number(CssValues.arguments(function), fallback);
    }

    private static int tint(Map<String, String> parts) {
        String function = parts.get("tint");
        Integer parsed = function == null ? null : ColorValue.parseCssColor(CssValues.arguments(function));
        return parsed == null ? 0x00000000 : parsed;
    }

    private static int offered(Map<String, String> parts) {
        int count = 0;
        for (String name : parts.keySet()) {
            if ("tint".equals(name)) count++;
            for (Knob knob : KNOBS) {
                if (knob.name().equals(name)) count++;
            }
        }
        return count;
    }

    /** Lengths take px; ratios take a bare number — the grammar's own split. */
    private static String unit(String name, double value) {
        return "blur".equals(name) || "bezel".equals(name) ? CssValues.px(value) : CssValues.write(value);
    }
}
