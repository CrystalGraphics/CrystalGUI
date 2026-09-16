package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.style.CssParsingUtil;

/**
 * Reading and writing the composite values a lab edits: the layers of a declaration, and one number in a
 * function's arguments.
 *
 * <pre>{@code
 * List<String> layers = CssValues.layers("0 1px 2px #000, 0 0 8px #4cf");   // two shadows
 * String written = CssValues.join(layers);                                  // back, in order
 * float blur = CssValues.number(shadow, 2, 0f);                             // the third term
 * }</pre>
 *
 * <p><b>Top-level only.</b> A comma inside {@code rgba(0, 0, 0, .5)} does not divide two shadows, and the
 * whole reason the labs can be written against text is that splitting is done once, here, the same way the
 * engine's own parser does it.</p>
 */
public final class CssValues {

    private CssValues() {
    }

    /** A declaration's layers: what {@code background}, {@code text-shadow} and {@code overlay} hold. */
    public static List<String> layers(@Nullable String css) {
        if (css == null || css.isBlank() || css.trim().equalsIgnoreCase("none")) return List.of();
        List<String> parts = new ArrayList<>();
        for (String part : CssParsingUtil.splitTopLevelCommas(css.trim())) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    /** Layers back into a declaration, first on top — {@code none} for an empty stack. */
    public static String join(List<String> layers) {
        return layers.isEmpty() ? "none" : String.join(", ", layers);
    }

    /**
     * The functions of a {@code transform}, in order: {@code translate(…) rotate(…)} is two.
     *
     * <p>Space-separated rather than comma-separated, and a function's own arguments hold both — so this
     * scans for the bracket depth rather than splitting on anything.</p>
     */
    public static List<String> functions(@Nullable String css) {
        if (css == null || css.isBlank() || css.trim().equalsIgnoreCase("none")) return List.of();
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : css.trim().toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && (c == ' ' || c == ',') && current.length() > 0 && current.charAt(current.length() - 1) == ')') {
                out.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.toString().trim().length() > 0) out.add(current.toString().trim());
        return out;
    }

    /** Functions back into a {@code transform}, in order. */
    public static String joinFunctions(List<String> functions) {
        return functions.isEmpty() ? "none" : String.join(" ", functions);
    }

    /** {@code rotate} out of {@code rotate(14deg)}, or "" when it is not a function at all. */
    public static String functionName(String function) {
        int open = function.indexOf('(');
        return open <= 0 ? "" : function.substring(0, open).trim().toLowerCase(Locale.ROOT);
    }

    /** What is between the brackets, or "" — the arguments a lab edits. */
    public static String arguments(String function) {
        int open = function.indexOf('(');
        int close = function.lastIndexOf(')');
        return open < 0 || close < open ? "" : function.substring(open + 1, close).trim();
    }

    /** {@code name(a, b)} from its parts. */
    public static String function(String name, String... arguments) {
        return name + "(" + String.join(", ", arguments) + ")";
    }

    /** The whitespace-separated terms of a value: {@code 0 1px 2px #000} is four. */
    public static List<String> terms(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : value.trim().toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    /** Term {@code index} as a number, ignoring its unit; {@code fallback} when it is not one. */
    public static float number(List<String> terms, int index, float fallback) {
        if (index < 0 || index >= terms.size()) return fallback;
        return number(terms.get(index), fallback);
    }

    /** {@code 12px} as 12, {@code 0.25turn} as 0.25 — the unit is the caller's business. */
    public static float number(@Nullable String term, float fallback) {
        if (term == null) return fallback;
        int end = 0;
        while (end < term.length() && (Character.isDigit(term.charAt(end)) || term.charAt(end) == '.'
                || term.charAt(end) == '-' || term.charAt(end) == '+')) {
            end++;
        }
        try {
            return end == 0 ? fallback : Float.parseFloat(term.substring(0, end));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** A number as CSS: {@code 12} rather than {@code 12.0}, so what is written is what a person types. */
    public static String write(double value) {
        double rounded = Math.round(value * 1000d) / 1000d;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /** A pixel length, the unit every lab's pads and dials work in. */
    public static String px(double value) {
        return write(value) + "px";
    }

    /** {@code #RRGGBB}, or {@code #AARRGGBB} when there is transparency to state. */
    public static String color(int argb) {
        return (argb >>> 24) == 0xFF
                ? String.format("#%06X", argb & 0xFFFFFF)
                : String.format("#%08X", argb);
    }
}
