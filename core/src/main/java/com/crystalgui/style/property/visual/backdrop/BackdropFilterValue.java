package com.crystalgui.style.property.visual.backdrop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.ArgbMath;
import com.crystalgui.render.texture.CgUiBackdropFilter;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.color.ColorValue;

/**
 * {@code backdrop-filter} — <b>what happens to whatever is behind the element</b>.
 *
 * <pre>{@code
 * backdrop-filter: blur(24px) saturate(1.2);
 * background-color: #1C1D21CC;              // drawn OVER the filtered backdrop
 *
 * backdrop-filter: blur(24px) tint(#1F202313) bezel(8px) ior(1.5) noise(0.04);
 * }</pre>
 *
 * <p>{@code blur()} and {@code saturate()} are CSS's own filter functions and mean there what they mean
 * here. The rest belong to this engine, because CSS has no spelling for them: {@code tint},
 * {@code luminosity} (a W3C SetLum blend toward the tint, not CSS's {@code brightness()} multiplier),
 * {@code bezel}, {@code ior}, {@code specular}, {@code glow}, {@code edge}, {@code edge-width},
 * {@code rim-ambient}, {@code chromatic}, {@code noise} and {@code fallback}.</p>
 *
 * <p>Lengths take {@code px} or a bare number; ratios take a number or a percentage, so
 * {@code saturate(180%)} and {@code saturate(1.8)} are the same. An unknown function warns and is
 * ignored, the rule every {@link StyleValue} follows — but a list with nothing recognisable in it is a
 * parse failure, since {@code none} already spells "no filter".</p>
 */
public class BackdropFilterValue extends StyleValue<CgUiBackdropFilter> {

    /** What an unstated function is compared against, both to parse and to write. */
    private static final CgUiBackdropFilter DEFAULTS = new CgUiBackdropFilter();

    public BackdropFilterValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgUiBackdropFilter doCompute(String rawValue) {
        return parse(rawValue);
    }

    @Nullable
    public static CgUiBackdropFilter parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("none")) return null;

        CgUiBackdropFilter filter = new CgUiBackdropFilter();
        boolean anyRecognised = false;
        for (String function : CssParsingUtil.splitFunctionList(value)) {
            int open = function.indexOf('(');
            if (open < 0 || !function.endsWith(")")) continue;
            String name = function.substring(0, open).trim().toLowerCase(Locale.ROOT);
            String argument = function.substring(open + 1, function.length() - 1).trim();
            if (apply(filter, name, argument)) anyRecognised = true;
        }
        return anyRecognised ? filter : null;
    }

    /**
     * One function onto the filter, <b>bounded here</b>.
     *
     * <p>A negative blur, an {@code ior} under 1 or a {@code luminosity} over 1 are not values the
     * shader has an answer for, and this is the only place they can arrive from outside — a slider
     * carries its own range and a scene writes a literal. The setters are Lombok's and assign what they
     * are given, so the bound lives at the boundary rather than fourteen times over.</p>
     */
    private static boolean apply(CgUiBackdropFilter filter, String name, String argument) {
        Float number = number(argument);
        switch (name) {
            case "blur" -> { if (number != null) { filter.setBlurRadius(atLeast(0f, number)); return true; } }
            case "saturate" -> { if (number != null) { filter.setSaturation(atLeast(0f, number)); return true; } }
            case "luminosity" -> { if (number != null) { filter.setLuminosity(clamp01(number)); return true; } }
            case "bezel" -> { if (number != null) { filter.setBezel(atLeast(0f, number)); return true; } }
            case "ior" -> { if (number != null) { filter.setIor(atLeast(1f, number)); return true; } }
            case "specular" -> { if (number != null) { filter.setSpecular(atLeast(0f, number)); return true; } }
            case "glow" -> { if (number != null) { filter.setGlow(atLeast(0f, number)); return true; } }
            case "edge" -> { if (number != null) { filter.setEdgeHighlight(atLeast(0f, number)); return true; } }
            case "edge-width" -> { if (number != null) { filter.setEdgeWidth(atLeast(0f, number)); return true; } }
            case "rim-ambient" -> { if (number != null) { filter.setRimAmbient(clamp01(number)); return true; } }
            case "chromatic" -> { if (number != null) { filter.setChromatic(atLeast(0f, number)); return true; } }
            case "noise" -> { if (number != null) { filter.setNoise(atLeast(0f, number)); return true; } }
            case "tint" -> {
                Integer colour = ColorValue.parseColor(argument);
                if (colour != null) {
                    filter.setTintArgb(colour);
                    return true;
                }
            }
            case "fallback" -> {
                Integer colour = ColorValue.parseColor(argument);
                if (colour != null) {
                    filter.setFallbackColorArgb(colour);
                    return true;
                }
            }
            default -> CrystalGuiCore.LOGGER.warn("Unknown backdrop-filter function {}() — ignored", name);
        }
        return false;
    }

    private static float atLeast(float floor, float value) {
        return Math.max(floor, value);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /** A length or a ratio: {@code 24px}, {@code 24}, {@code 1.8} or {@code 180%}. */
    @Nullable
    private static Float number(String argument) {
        String text = argument.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("%")) {
                return Float.parseFloat(text.substring(0, text.length() - 1).trim()) / 100f;
            }
            if (text.endsWith("px")) text = text.substring(0, text.length() - 2);
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The declaration that would produce {@code filter} — <b>only what differs from the defaults</b>.
     *
     * <p>Fourteen functions written out every time would be unreadable on a clipboard and in a saved
     * document, and would say nothing a reader wants: what matters is where this one differs.
     * {@code blur()} is always written, so the value is never an empty declaration.</p>
     */
    public static String write(@Nullable CgUiBackdropFilter filter) {
        if (filter == null) return "none";
        List<String> out = new ArrayList<>();
        out.add("blur(" + trim(filter.getBlurRadius()) + "px)");
        addRatio(out, "saturate", filter.getSaturation(), DEFAULTS.getSaturation());
        if (filter.getTintArgb() != DEFAULTS.getTintArgb()) {
            out.add("tint(" + ArgbMath.toCss(filter.getTintArgb()) + ")");
        }
        addLength(out, "bezel", filter.getBezel(), DEFAULTS.getBezel());
        addRatio(out, "ior", filter.getIor(), DEFAULTS.getIor());
        addRatio(out, "specular", filter.getSpecular(), DEFAULTS.getSpecular());
        addRatio(out, "glow", filter.getGlow(), DEFAULTS.getGlow());
        addRatio(out, "edge", filter.getEdgeHighlight(), DEFAULTS.getEdgeHighlight());
        addLength(out, "edge-width", filter.getEdgeWidth(), DEFAULTS.getEdgeWidth());
        addRatio(out, "rim-ambient", filter.getRimAmbient(), DEFAULTS.getRimAmbient());
        addRatio(out, "chromatic", filter.getChromatic(), DEFAULTS.getChromatic());
        addRatio(out, "noise", filter.getNoise(), DEFAULTS.getNoise());
        addRatio(out, "luminosity", filter.getLuminosity(), DEFAULTS.getLuminosity());
        if (filter.getFallbackColorArgb() != DEFAULTS.getFallbackColorArgb()) {
            out.add("fallback(" + ArgbMath.toCss(filter.getFallbackColorArgb()) + ")");
        }
        return String.join(" ", out);
    }

    private static void addLength(List<String> out, String name, float value, float initial) {
        if (value != initial) out.add(name + "(" + trim(value) + "px)");
    }

    private static void addRatio(List<String> out, String name, float value, float initial) {
        if (value != initial) out.add(name + "(" + trim(value) + ")");
    }

    /** {@code 24} rather than {@code 24.0}, which is what a person writes. */
    private static String trim(float value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
