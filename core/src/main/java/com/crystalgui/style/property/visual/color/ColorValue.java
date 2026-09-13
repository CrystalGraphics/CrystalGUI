package com.crystalgui.style.property.visual.color;

import com.crystalgui.style.property.StyleValue;

import java.util.Locale;

/**
 * A CSS {@code <color>} declaration, and the one parser for that syntax: every style property, every
 * value that embeds a colour and the SVG renderer read colours here.
 *
 * <pre>{@code
 * ColorValue.parseCssColor("#4cf");                  // 0xFF44CCFF
 * ColorValue.parseCssColor("rgb(255 0 0 / 50%)");    // 0x80FF0000
 * ColorValue.parseCssColor("gold");                  // 0xFFFFD700
 * ColorValue.parseCssColor("-1");                    // null: CSS has no integer colour
 * ColorValue.parseColor("-1");                       // 0xFFFFFFFF: a colour PROPERTY also takes ARGB literals
 * }</pre>
 *
 * <p>Easy to get wrong: a grammar where a bare number means something else, such as a shadow's lengths,
 * must use {@link #parseCssColor}, or its {@code 0} reads as a transparent colour. And {@code currentcolor}
 * is not a colour here; a property that means it keeps it unresolved itself, as {@link StyleColor} does.</p>
 */
public class ColorValue extends StyleValue<Integer> {

    public ColorValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Integer doCompute(String rawValue) {
        return parseColor(rawValue);
    }

    /**
     * A colour property's value, straight ARGB: a CSS {@code <color>}, or a decimal ARGB literal such as
     * {@code -1} for opaque white. Null when it is neither.
     */
    public static Integer parseColor(String value) {
        Integer css = parseCssColor(value);
        if (css != null || value == null) return css;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notAColour) {
            return null;
        }
    }

    /** A CSS {@code <color>}, straight ARGB, or null when {@code value} is not one. */
    public static Integer parseCssColor(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        value = value.trim().toLowerCase(Locale.ROOT);

        // `transparent` — a CSS-wide colour keyword, and the one this parser was missing.
        //
        // CSS Color 4 defines it as exactly rgba(0, 0, 0, 0), NOT as "no colour": it is a real colour
        // that happens to have zero alpha, which is why it composites and interpolates like any other.
        // That distinction matters for transitions — animating to `transparent` must fade toward
        // transparent BLACK, and returning null here instead would drop the declaration and animate
        // toward whatever the cascade fell back to.
        //
        // Twelve declarations in the user-agent sheet use it. Every one of them was failing to parse,
        // so each logged a warning and was skipped — visible as a wall of
        // "Stylesheet declaration 'background-color: transparent' failed to parse — skipping" on any
        // client that loads the default sheet, and as a handful of surfaces quietly keeping a
        // background they were written to clear.
        if (value.equals("transparent")) return 0x00000000;

        if (value.charAt(0) == '#') return hex(value.substring(1));
        if (value.startsWith("rgb(") || value.startsWith("rgba(")) return functional(value);

        return NamedColors.argb(value);
    }

    /**
     * {@code #rgb}, {@code #rgba}, {@code #rrggbb}, {@code #rrggbbaa}.
     *
     * <p>The three- and four-digit forms double each digit rather than shifting: {@code #f00} is
     * {@code #ff0000}, not {@code #f00000}. Shifting is the tempting one-liner and it darkens every short
     * hex by a few percent, which is invisible per-colour and wrong everywhere.</p>
     */
    private static Integer hex(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) return null;
        }
        switch (digits.length()) {
            case 3:
            case 4: {
                int alpha = digits.length() == 4 ? nibble(digits.charAt(3)) : 0xF;
                return pack(alpha * 17, nibble(digits.charAt(0)) * 17,
                        nibble(digits.charAt(1)) * 17, nibble(digits.charAt(2)) * 17);
            }
            case 6:
                return 0xFF000000 | Integer.parseInt(digits, 16);
            case 8: {
                long full = Long.parseLong(digits, 16);
                // #rrggbbaa puts alpha LAST, unlike our ARGB int.
                return (int) (((full & 0xFF) << 24) | (full >>> 8));
            }
            default:
                return null;
        }
    }

    private static int nibble(char c) {
        return Character.digit(c, 16);
    }

    /**
     * {@code rgb()} and {@code rgba()}, either name with or without alpha, channels as numbers or
     * percentages, separated by commas or by spaces with a slash before the alpha. Out-of-range values
     * clamp, as CSS Color 4 says they do.
     */
    private static Integer functional(String value) {
        int open = value.indexOf('(');
        if (!value.endsWith(")")) return null;
        String[] parts = value.substring(open + 1, value.length() - 1).trim().split("[\\s,/]+");
        if (parts.length < 3 || parts.length > 4) return null;
        try {
            int r = channel(parts[0]);
            int g = channel(parts[1]);
            int b = channel(parts[2]);
            int a = parts.length > 3 ? Math.round(clamp01(alpha(parts[3])) * 255f) : 255;
            return pack(a, r, g, b);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static int channel(String raw) {
        if (raw.endsWith("%")) {
            float percent = Float.parseFloat(raw.substring(0, raw.length() - 1));
            return Math.round(clamp01(percent / 100f) * 255f);
        }
        return Math.max(0, Math.min(255, Math.round(Float.parseFloat(raw))));
    }

    private static float alpha(String raw) {
        return raw.endsWith("%") ? Float.parseFloat(raw.substring(0, raw.length() - 1)) / 100f : Float.parseFloat(raw);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int pack(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
