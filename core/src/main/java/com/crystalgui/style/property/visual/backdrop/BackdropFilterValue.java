package com.crystalgui.style.property.visual.backdrop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.ArgbMath;
import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
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
public class BackdropFilterValue extends StyleValue<CgBackdropFilter> {

    private static final CgBackdropFilter DEFAULTS = CgBackdropFilter.DEFAULT;

    public BackdropFilterValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgBackdropFilter doCompute(String rawValue) {
        return parse(rawValue);
    }

    /** The filter {@code raw} declares over {@link CgBackdropFilter#DEFAULT}, {@code NONE} for none, null when unreadable. */
    @Nullable
    public static CgBackdropFilter parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (value.equalsIgnoreCase("none")) return CgBackdropFilter.NONE;
        Builder b = new Builder(DEFAULTS);
        boolean anyRecognised = false;
        for (String function : CssParsingUtil.splitFunctionList(value)) {
            int open = function.indexOf('(');
            if (open < 0 || !function.endsWith(")")) continue;
            String name = function.substring(0, open).trim().toLowerCase(Locale.ROOT);
            String argument = function.substring(open + 1, function.length() - 1).trim();
            if (b.apply(name, argument)) anyRecognised = true;
        }
        return anyRecognised ? b.build() : null;
    }

    /** The record's fields while a declaration is read; its withers group them, a declaration sets them one by one. */
    private static final class Builder {
        float blur, saturation, luminosity, bezel, ior, chromatic, specular, glow, rim, rimWidth, rimAmbient, noise;
        int tint, fallback;
        final float lightX, lightY;

        Builder(CgBackdropFilter f) {
            blur = f.blur();
            saturation = f.saturation();
            tint = f.tint();
            luminosity = f.luminosity();
            bezel = f.bezel();
            ior = f.ior();
            chromatic = f.chromatic();
            specular = f.specular();
            glow = f.glow();
            rim = f.rim();
            rimWidth = f.rimWidth();
            rimAmbient = f.rimAmbient();
            noise = f.noise();
            lightX = f.lightX();
            lightY = f.lightY();
            fallback = f.fallback();
        }

        CgBackdropFilter build() {
            return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                    rimWidth, rimAmbient, noise, lightX, lightY, fallback);
        }

        boolean apply(String name, String argument) {
            Float number = number(argument);
            switch (name) {
                case "blur" -> { if (number != null) { blur = atLeast(0f, number); return true; } }
                case "saturate" -> { if (number != null) { saturation = atLeast(0f, number); return true; } }
                case "luminosity" -> { if (number != null) { luminosity = clamp01(number); return true; } }
                case "bezel" -> { if (number != null) { bezel = atLeast(0f, number); return true; } }
                case "ior" -> { if (number != null) { ior = atLeast(1f, number); return true; } }
                case "specular" -> { if (number != null) { specular = atLeast(0f, number); return true; } }
                case "glow" -> { if (number != null) { glow = atLeast(0f, number); return true; } }
                case "edge" -> { if (number != null) { rim = atLeast(0f, number); return true; } }
                case "edge-width" -> { if (number != null) { rimWidth = atLeast(0f, number); return true; } }
                case "rim-ambient" -> { if (number != null) { rimAmbient = clamp01(number); return true; } }
                case "chromatic" -> { if (number != null) { chromatic = atLeast(0f, number); return true; } }
                case "noise" -> { if (number != null) { noise = atLeast(0f, number); return true; } }
                case "tint" -> {
                    Integer colour = ColorValue.parseColor(argument);
                    if (colour != null) {
                        tint = colour;
                        return true;
                    }
                }
                case "fallback" -> {
                    Integer colour = ColorValue.parseColor(argument);
                    if (colour != null) {
                        fallback = colour;
                        return true;
                    }
                }
                default -> CrystalGuiCore.LOGGER.warn("Unknown backdrop-filter function {}() — ignored", name);
            }
            return false;
        }
    }

    private static float atLeast(float floor, float value) {
        return Math.max(floor, value);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

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

    /** The declaration that reads back as {@code filter}: what differs from the default, {@code none} for none. */
    public static String write(@Nullable CgBackdropFilter filter) {
        if (filter == null || filter == CgBackdropFilter.NONE) return "none";
        List<String> out = new ArrayList<>();
        out.add("blur(" + trim(filter.blur()) + "px)");
        addRatio(out, "saturate", filter.saturation(), DEFAULTS.saturation());
        if (filter.tint() != DEFAULTS.tint()) out.add("tint(" + ArgbMath.toCss(filter.tint()) + ")");
        addLength(out, "bezel", filter.bezel(), DEFAULTS.bezel());
        addRatio(out, "ior", filter.ior(), DEFAULTS.ior());
        addRatio(out, "specular", filter.specular(), DEFAULTS.specular());
        addRatio(out, "glow", filter.glow(), DEFAULTS.glow());
        addRatio(out, "edge", filter.rim(), DEFAULTS.rim());
        addLength(out, "edge-width", filter.rimWidth(), DEFAULTS.rimWidth());
        addRatio(out, "rim-ambient", filter.rimAmbient(), DEFAULTS.rimAmbient());
        addRatio(out, "chromatic", filter.chromatic(), DEFAULTS.chromatic());
        addRatio(out, "noise", filter.noise(), DEFAULTS.noise());
        addRatio(out, "luminosity", filter.luminosity(), DEFAULTS.luminosity());
        if (filter.fallback() != DEFAULTS.fallback()) out.add("fallback(" + ArgbMath.toCss(filter.fallback()) + ")");
        return String.join(" ", out);
    }

    private static void addLength(List<String> out, String name, float value, float initial) {
        if (value != initial) out.add(name + "(" + trim(value) + "px)");
    }

    private static void addRatio(List<String> out, String name, float value, float initial) {
        if (value != initial) out.add(name + "(" + trim(value) + ")");
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
