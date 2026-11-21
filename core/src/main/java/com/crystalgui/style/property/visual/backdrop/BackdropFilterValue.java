package com.crystalgui.style.property.visual.backdrop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.api.paint.CgColor;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.color.ColorValue;

/**
 * {@code backdrop-filter} — <b>what happens to whatever is behind the element</b>, as CSS writes it: filter
 * functions applied in the order written, then this engine's glass.
 *
 * <pre>{@code
 * backdrop-filter: blur(8px) saturate(1.2);
 * background-color: #1C1D21CC;              // drawn OVER the filtered backdrop
 *
 * backdrop-filter: blur(8px) saturate(1.5) luminosity(0.1) tint(#401F1F23) bezel(8px) ior(1.5) noise(0.04);
 * }</pre>
 *
 * <p>CSS's filter functions mean here what they mean in CSS: {@code blur}, {@code brightness}, {@code contrast},
 * {@code grayscale}, {@code hue-rotate}, {@code invert}, {@code opacity}, {@code saturate}, {@code sepia}. Past CSS, as
 * steps of the chain: {@code tint} (a colour laid over by its alpha) and {@code luminosity} (Fluent's: the brightness of
 * the tint, its hue kept). The glass's lens: {@code bezel}, {@code ior}, {@code specular}, {@code glow}, {@code edge},
 * {@code edge-width}, {@code rim-ambient}, {@code chromatic}, {@code noise}; and {@code fallback}, what a pane paints
 * inside a kept layer.</p>
 *
 * <p>A function not written does nothing: no lens function, no lens. Lengths take {@code px} or a bare number, ratios
 * a number or a percentage, hues {@code deg}, {@code rad} or {@code turn}. An unknown function warns and is ignored;
 * a list with nothing recognisable is a parse failure, since {@code none} already spells "no filter".</p>
 */
public class BackdropFilterValue extends StyleValue<CgBackdropFilter> {

    public BackdropFilterValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgBackdropFilter doCompute(String rawValue) {
        return parse(rawValue);
    }

    /** The filter {@code raw} declares, {@code NONE} for none, null when unreadable. */
    @Nullable
    public static CgBackdropFilter parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (value.equalsIgnoreCase("none")) return CgBackdropFilter.NONE;
        List<CgFilter.Step> steps = new ArrayList<>();
        Lens lens = new Lens();
        Integer tint = null, fallback = null;
        float luminosity = -1f;
        boolean any = false;
        for (String function : CssParsingUtil.splitFunctionList(value)) {
            int open = function.indexOf('(');
            if (open < 0 || !function.endsWith(")")) continue;
            String name = function.substring(0, open).trim().toLowerCase(Locale.ROOT);
            String argument = function.substring(open + 1, function.length() - 1).trim();
            Float n = number(argument);
            CgFilter step = switch (name) {
                case "blur" -> n == null ? null : CgFilter.blur(Math.max(0f, n));
                case "brightness" -> n == null ? null : CgFilter.brightness(n);
                case "contrast" -> n == null ? null : CgFilter.contrast(n);
                case "grayscale" -> n == null ? null : CgFilter.grayscale(n);
                case "invert" -> n == null ? null : CgFilter.invert(n);
                case "opacity" -> n == null ? null : CgFilter.opacity(n);
                case "saturate" -> n == null ? null : CgFilter.saturate(n);
                case "sepia" -> n == null ? null : CgFilter.sepia(n);
                case "hue-rotate" -> {
                    Float deg = degrees(argument);
                    yield deg == null ? null : CgFilter.hueRotate(deg);
                }
                case "tint" -> {
                    Integer colour = ColorValue.parseColor(argument);
                    if (colour != null) tint = colour;
                    yield colour == null ? null : CgFilter.tint(colour);
                }
                // Its colour is the tint's, known once the whole list is read
                case "luminosity" -> {
                    if (n != null) luminosity = Math.max(0f, Math.min(1f, n));
                    yield n == null ? null : CgFilter.luminosity(0, luminosity);
                }
                default -> null;
            };
            if (step != null) {
                steps.add(step.steps().get(0));
                any = true;
            } else if (lens.apply(name, n)) {
                any = true;
            } else if (name.equals("fallback") && ColorValue.parseColor(argument) != null) {
                fallback = ColorValue.parseColor(argument);
                any = true;
            } else if (!isKnown(name)) {
                CrystalGuiCore.LOGGER.warn("Unknown backdrop-filter function {}() — ignored", name);
            }
        }
        if (!any) return null;
        if (luminosity >= 0f) {
            int at = indexOf(steps, CgFilter.Fn.LUMINOSITY);
            steps.set(at, CgFilter.luminosity(tint == null ? 0 : tint, luminosity).steps().get(0));
        }
        CgBackdropFilter f = CgBackdropFilter.of(CgFilter.of(steps)).lens(lens.build());
        return fallback == null ? f : f.fallback(fallback);
    }

    /** The declaration that reads back as {@code filter}: its steps in order, its lens, {@code none} for none. */
    public static String write(@Nullable CgBackdropFilter filter) {
        if (filter == null || filter == CgBackdropFilter.NONE) return "none";
        List<String> out = new ArrayList<>();
        for (CgFilter.Step step : filter.filter().steps()) {
            float v = step.value();
            switch (step.function()) {
                case BLUR -> out.add("blur(" + trim(v) + "px)");
                case BRIGHTNESS -> out.add("brightness(" + trim(v) + ")");
                case CONTRAST -> out.add("contrast(" + trim(v) + ")");
                case GRAYSCALE -> out.add("grayscale(" + trim(v) + ")");
                case INVERT -> out.add("invert(" + trim(v) + ")");
                case OPACITY -> out.add("opacity(" + trim(v) + ")");
                case SATURATE -> out.add("saturate(" + trim(v) + ")");
                case SEPIA -> out.add("sepia(" + trim(v) + ")");
                case HUE_ROTATE -> out.add("hue-rotate(" + trim(v) + "deg)");
                case TINT -> out.add("tint(" + CgColor.toHex(step.color()) + ")");
                case LUMINOSITY -> out.add("luminosity(" + trim(v) + ")");
                default -> throw new IllegalArgumentException("backdrop-filter has no spelling for " + step.function());
            }
        }
        CgBackdropFilter.Lens l = filter.lens(), none = CgBackdropFilter.Lens.NONE;
        addLength(out, "bezel", l.bezel(), none.bezel());
        addRatio(out, "ior", l.ior(), none.ior());
        addRatio(out, "specular", l.specular(), none.specular());
        addRatio(out, "glow", l.glow(), none.glow());
        addRatio(out, "edge", l.rim(), none.rim());
        addLength(out, "edge-width", l.rimWidth(), none.rimWidth());
        addRatio(out, "rim-ambient", l.rimAmbient(), none.rimAmbient());
        addRatio(out, "chromatic", l.chromatic(), none.chromatic());
        addRatio(out, "noise", l.grain(), none.grain());
        if (filter.fallback() != CgBackdropFilter.of(CgFilter.NONE).fallback()) {
            out.add("fallback(" + CgColor.toHex(filter.fallback()) + ")");
        }
        return out.isEmpty() ? "blur(0px)" : String.join(" ", out);
    }

    /** The lens's functions, from no lens. */
    private static final class Lens {
        CgBackdropFilter.Lens lens = CgBackdropFilter.Lens.NONE;

        boolean apply(String name, @Nullable Float n) {
            if (n == null) return false;
            CgBackdropFilter.Lens l = lens;
            switch (name) {
                case "bezel" -> lens = l.refraction(Math.max(0f, n), l.ior());
                case "ior" -> lens = l.refraction(l.bezel(), Math.max(1f, n));
                case "specular" -> lens = l.highlight(Math.max(0f, n), l.glow(), l.rim(), l.rimWidth(), l.rimAmbient());
                case "glow" -> lens = l.highlight(l.specular(), Math.max(0f, n), l.rim(), l.rimWidth(), l.rimAmbient());
                case "edge" -> lens = l.highlight(l.specular(), l.glow(), Math.max(0f, n), l.rimWidth(), l.rimAmbient());
                case "edge-width" -> lens = l.highlight(l.specular(), l.glow(), l.rim(), Math.max(0f, n), l.rimAmbient());
                case "rim-ambient" -> lens = l.highlight(l.specular(), l.glow(), l.rim(), l.rimWidth(),
                        Math.max(0f, Math.min(1f, n)));
                case "chromatic" -> lens = l.chromatic(Math.max(0f, n));
                case "noise" -> lens = l.grain(Math.max(0f, n));
                default -> {
                    return false;
                }
            }
            return true;
        }

        CgBackdropFilter.Lens build() {
            return lens;
        }
    }

    private static boolean isKnown(String name) {
        return switch (name) {
            case "blur", "brightness", "contrast", "grayscale", "invert", "opacity", "saturate", "sepia", "hue-rotate",
                 "tint", "luminosity", "fallback" -> true;
            default -> false;
        };
    }

    private static int indexOf(List<CgFilter.Step> steps, CgFilter.Fn fn) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).function() == fn) return i;
        return -1;
    }

    @Nullable
    private static Float number(String argument) {
        String text = argument.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("%")) return Float.parseFloat(text.substring(0, text.length() - 1).trim()) / 100f;
            if (text.endsWith("px")) text = text.substring(0, text.length() - 2);
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Float degrees(String argument) {
        String t = argument.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("deg")) return Float.parseFloat(t.substring(0, t.length() - 3));
            if (t.endsWith("grad")) return Float.parseFloat(t.substring(0, t.length() - 4)) * 0.9f;
            if (t.endsWith("rad")) return (float) Math.toDegrees(Float.parseFloat(t.substring(0, t.length() - 3)));
            if (t.endsWith("turn")) return Float.parseFloat(t.substring(0, t.length() - 4)) * 360f;
            return Float.parseFloat(t);
        } catch (NumberFormatException e) {
            return null;
        }
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
