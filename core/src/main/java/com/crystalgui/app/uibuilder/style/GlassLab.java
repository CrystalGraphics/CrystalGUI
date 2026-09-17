package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.render.texture.CgUiBackdropFilter;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PanelForm;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.control.Button;

/**
 * The glass lab: {@code backdrop-filter} on live controls, over a backdrop worth filtering.
 *
 * <pre>{@code
 * GlassLab.open(chip, StylePropertyRegistry.BACKDROP_FILTER, css);
 * }</pre>
 *
 * <p>Every function of the engine's grammar ({@code BackdropFilterValue}) has a control, in the three things glass
 * does to what is behind it: frosts it, bends it, and catches light. A function left out of the value reads as the
 * engine's own default, and one moved back to its default is dropped from the value, so the declaration says only
 * where this glass differs. A function the grammar does not know is kept as written.</p>
 */
public final class GlassLab {

    /** On the glass lab's window. */
    public static final String LAB_CLASS = "__glass-lab__";
    /** The swatch's specimen: the lab's scene in small, two shapes on the dark plate, behind a pane of the glass. */
    public static final String SAMPLE_SCENE_CLASS = "__glass-sample-scene__";
    public static final String SAMPLE_SHAPE_CLASS = "__glass-sample-shape__";
    public static final String SAMPLE_PANE_CLASS = "__glass-sample-pane__";

    /** How much of a length a 28x16 swatch keeps: a 24px blur or a 16px bezel is larger than the swatch itself. */
    private static final float SAMPLE_SCALE = 0.25f;

    /** The row of presets, tighter than a row of gizmos. */
    public static final String PRESETS_CLASS = "__glass-presets__";

    /** What an empty or {@code none} value opens on. */
    private static final String DEFAULT = "blur(12px) saturate(1.2)";

    /** The engine's own filter, which is what an unwritten function means. */
    private static final CgUiBackdropFilter DEFAULTS = new CgUiBackdropFilter();

    /** How a knob's number is shown and written. */
    private enum Scale {
        /** A length: shown and written in px. */
        PX,
        /** A ratio shown as a percentage and written as the bare ratio. */
        PERCENT,
        /** A bare number. */
        RATIO
    }

    /** A function with a slider: its label, its name and its range, in the knob's own scale. */
    private record Knob(String label, String name, float min, float max, Scale scale) {
    }

    /** One starting point, written through {@link #written} so it says only what differs. */
    private record Preset(String label, String css) {
    }

    private static final Knob[] FROST = {
            new Knob("Blur", "blur", 0f, 40f, Scale.PX),
            new Knob("Saturation", "saturate", 0f, 300f, Scale.PERCENT),
            new Knob("Luminosity", "luminosity", 0f, 100f, Scale.PERCENT),
            new Knob("Noise", "noise", 0f, 20f, Scale.PERCENT)};

    private static final Knob[] LENS = {
            new Knob("Bezel", "bezel", 0f, 24f, Scale.PX),
            new Knob("Refraction", "ior", 1f, 2.5f, Scale.RATIO),
            new Knob("Chromatic", "chromatic", 0f, 3f, Scale.RATIO)};

    private static final Knob[] LIGHT = {
            new Knob("Specular", "specular", 0f, 2f, Scale.RATIO),
            new Knob("Glow", "glow", 0f, 1f, Scale.RATIO),
            new Knob("Edge", "edge", 0f, 1f, Scale.RATIO),
            new Knob("Edge width", "edge-width", 0f, 12f, Scale.PX),
            new Knob("Rim ambient", "rim-ambient", 0f, 100f, Scale.PERCENT)};

    /**
     * Each a different material rather than a different amount: a plain pane, a frost, Apple's liquid lens,
     * Windows' two acrylics and a dispersive prism. An unstated function is the engine's default, so each says
     * what it switches off as well as what it turns up.
     */
    private static final Preset[] PRESETS = {
            new Preset("Clear", "blur(0px) saturate(1) tint(#00000000) bezel(10px) ior(1.35) specular(0.6) glow(0.05) "
                    + "chromatic(0) noise(0)"),
            new Preset("Frosted", "blur(24px) saturate(1.2) tint(#FFFFFF1F) bezel(0px) specular(0.3) noise(0.05)"),
            new Preset("Liquid", "blur(4px) saturate(1.8) tint(#FFFFFF14) bezel(20px) ior(1.6) specular(1.2) glow(0.2) "
                    + "edge(0.4) chromatic(0.25) noise(0.02)"),
            // LUMINOSITY TAKES THE TINT'S BRIGHTNESS, so a dark tint with much of it is a dark panel whatever is behind.
            new Preset("Acrylic", "blur(30px) saturate(1.5) tint(#1F1F2340) luminosity(0.1) bezel(0px) specular(0) "
                    + "glow(0) edge(0.12) noise(0.03)"),
            new Preset("Mica", "blur(40px) saturate(1.2) tint(#202124BF) luminosity(0.55) bezel(0px) specular(0) glow(0) "
                    + "edge(0) noise(0)"),
            // DISPERSION, NOT DISPLACEMENT: at ior 2.5 over a 24px bezel the lens folded the text back on itself, and a
            // fringe that wide split into hard coloured lines. A pixel of blur keeps the fringe soft.
            new Preset("Prism", "blur(1px) saturate(1.2) tint(#00000000) bezel(16px) ior(1.9) specular(0.5) glow(0.05) "
                    + "chromatic(1.6) noise(0)")};

    /** The order a value is written in, the grammar's own; anything else follows as written. */
    private static final String[] ORDER = {"blur", "saturate", "tint", "bezel", "ior", "specular", "glow", "edge",
            "edge-width", "rim-ambient", "chromatic", "noise", "luminosity", "fallback"};

    private GlassLab() {
    }

    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        StyleLab lab = StyleLab.over(anchor, "Glass").addClass(LAB_CLASS);
        lab.specimen().backdrop().preview(property, css);

        Property<Map<String, String>> functions = css.map(GlassLab::functionsOf, GlassLab::written);

        UIElement presets = new UIElement();
        presets.addClass(StyleLab.ROW_CLASS);
        presets.addClass(PRESETS_CLASS);
        for (Preset preset : PRESETS) {
            Button button = new Button(preset.label());
            button.addClass(StyleLab.KEYWORD_CLASS);
            String value = written(functionsOf(preset.css()));
            button.attachListener(() -> css.set(value));
            // LIT WHILE THE GLASS IS THIS PRESET, and dark again the moment a slider moves off it.
            PropertyWatch.follow(button, css,
                    now -> button.toggleClass(StyleLab.ACTIVE_CLASS, value.equals(written(functionsOf(now)))));
            presets.append(button);
        }
        lab.form().custom(presets);

        PanelForm frost = lab.form().group("Frost", false);
        knobs(frost, FROST, functions);
        frost.prop(ConfigDescriptor.color("lab.tint", "Tint"), color(functions, "tint", DEFAULTS.getTintArgb()));
        knobs(lab.form().group("Lens", false), LENS, functions);
        knobs(lab.form().group("Light", false), LIGHT, functions);
        lab.form().group("Fallback", true).prop(ConfigDescriptor.color("lab.fallback", "Color"),
                color(functions, "fallback", DEFAULTS.getFallbackColorArgb()));

        lab.readout(property.name, css);
        lab.open();
    }

    /**
     * Draws a {@code backdrop-filter} row's swatch: two of the lab's shapes reaching in from opposite corners, and a
     * pane over the middle carrying the glass {@link #fitted} to the swatch -- so each shape is seen both plain and
     * through the glass. Built once, restyled per value.
     */
    static void paintSample(StyleChip chip, String css) {
        UIElement swatch = chip.swatch();
        UIElement pane = null;
        for (UIElement child : swatch.children()) {
            if (child.hasClass(SAMPLE_PANE_CLASS)) pane = child;
        }
        if (pane == null) {
            UIElement scene = new UIElement();
            scene.addClass(SAMPLE_SCENE_CLASS);
            for (int i = 0; i < 2; i++) {
                scene.append(new UIElement().addClass(SAMPLE_SHAPE_CLASS).addClass(SAMPLE_SHAPE_CLASS + i));
            }
            swatch.append(scene);
            pane = new UIElement().addClass(SAMPLE_PANE_CLASS);
            swatch.append(pane);
        }
        if (css.isBlank()) LiveEdits.clearInline(pane, StylePropertyRegistry.BACKDROP_FILTER);
        else LiveEdits.setInline(pane, StylePropertyRegistry.BACKDROP_FILTER, fitted(css));
    }

    /** {@code css} with every length scaled to a swatch, so its blur and bezel keep their share of the box. */
    static String fitted(String css) {
        Map<String, String> parts = functionsOf(css);
        // ALL THREE, written or not: an unwritten one is the engine's default, which is as much too big for the swatch.
        for (String name : new String[] {"blur", "bezel", "edge-width"}) {
            float length = number(parts, name) * SAMPLE_SCALE;
            parts.put(name, CssValues.function(name, CssValues.px(Math.round(length * 10f) / 10d)));
        }
        return CssValues.joinFunctions(new ArrayList<>(parts.values()));
    }

    private static void knobs(PanelForm form, Knob[] knobs, Property<Map<String, String>> functions) {
        for (Knob knob : knobs) {
            ConfigDescriptor descriptor = ConfigDescriptor.number("lab." + knob.name(), knob.label())
                    .range(knob.min(), knob.max());
            descriptor = switch (knob.scale()) {
                // WHOLE PIXELS: a tenth of one is not a thing a person tunes glass by, and "16.0px" overran its box.
                case PX -> descriptor.unit("px").decimals(0);
                case PERCENT -> descriptor.unit("%").decimals(0);
                case RATIO -> descriptor.decimals(2);
            };
            form.prop(descriptor, functions.map(
                    parts -> shown(knob, number(parts, knob.name())),
                    value -> with(functions.get(), knob.name(), CssValues.function(knob.name(), argument(knob, value)))));
        }
    }

    private static Property<Integer> color(Property<Map<String, String>> functions, String name, int fallback) {
        return functions.map(parts -> argb(parts, name, fallback),
                argb -> with(functions.get(), name, CssValues.function(name, CssValues.color(argb))));
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

    /**
     * The functions as a declaration: the grammar's order, a function at the engine's default left out — as
     * {@code BackdropFilterValue.write} does — and an unknown one kept after. Never empty: all-default glass is its
     * blur.
     */
    static String written(Map<String, String> parts) {
        List<String> out = new ArrayList<>();
        for (String name : ORDER) {
            String function = parts.get(name);
            if (function != null && !isDefault(parts, name)) out.add(function);
        }
        for (Map.Entry<String, String> entry : parts.entrySet()) {
            if (defaultOf(entry.getKey()) == null && !isColor(entry.getKey())) out.add(entry.getValue());
        }
        if (out.isEmpty()) out.add(CssValues.function("blur", CssValues.px(DEFAULTS.getBlurRadius())));
        return CssValues.joinFunctions(out);
    }

    private static boolean isDefault(Map<String, String> parts, String name) {
        if (isColor(name)) {
            int fallback = "tint".equals(name) ? DEFAULTS.getTintArgb() : DEFAULTS.getFallbackColorArgb();
            Integer parsed = ColorValue.parseCssColor(CssValues.arguments(parts.get(name)));
            return parsed != null && parsed == fallback;
        }
        return Math.abs(number(parts, name) - defaultOf(name)) < 1e-4f;
    }

    private static boolean isColor(String name) {
        return "tint".equals(name) || "fallback".equals(name);
    }

    /** What the engine draws for an unwritten numeric function, or null for a name it has no number for. */
    private static Float defaultOf(String name) {
        return switch (name) {
            case "blur" -> DEFAULTS.getBlurRadius();
            case "saturate" -> DEFAULTS.getSaturation();
            case "luminosity" -> DEFAULTS.getLuminosity();
            case "bezel" -> DEFAULTS.getBezel();
            case "ior" -> DEFAULTS.getIor();
            case "specular" -> DEFAULTS.getSpecular();
            case "glow" -> DEFAULTS.getGlow();
            case "edge" -> DEFAULTS.getEdgeHighlight();
            case "edge-width" -> DEFAULTS.getEdgeWidth();
            case "rim-ambient" -> DEFAULTS.getRimAmbient();
            case "chromatic" -> DEFAULTS.getChromatic();
            case "noise" -> DEFAULTS.getNoise();
            default -> null;
        };
    }

    private static Map<String, String> with(Map<String, String> parts, String name, String function) {
        Map<String, String> next = new LinkedHashMap<>(parts);
        next.put(name, function);
        return next;
    }

    /** A function's argument as the grammar reads it — {@code 180%} is 1.8 — or the engine's default when unwritten. */
    private static float number(Map<String, String> parts, String name) {
        Float fallback = defaultOf(name);
        float otherwise = fallback == null ? 0f : fallback;
        String function = parts.get(name);
        if (function == null) return otherwise;
        String argument = CssValues.arguments(function).trim();
        return argument.endsWith("%")
                ? CssValues.number(argument.substring(0, argument.length() - 1), otherwise * 100f) / 100f
                : CssValues.number(argument, otherwise);
    }

    private static int argb(Map<String, String> parts, String name, int fallback) {
        String function = parts.get(name);
        Integer parsed = function == null ? null : ColorValue.parseCssColor(CssValues.arguments(function));
        return parsed == null ? fallback : parsed;
    }

    /** The grammar's number as the knob shows it. */
    private static double shown(Knob knob, float value) {
        return knob.scale() == Scale.PERCENT ? Math.round(value * 1000f) / 10d : value;
    }

    /** The knob's number as the grammar takes it: px for a length, the bare ratio for a percentage. */
    private static String argument(Knob knob, double value) {
        return switch (knob.scale()) {
            case PX -> CssValues.px(Math.round(value));
            case PERCENT -> CssValues.write(Math.round(value) / 100d);
            case RATIO -> CssValues.write(CssValues.dragged(value));
        };
    }
}
