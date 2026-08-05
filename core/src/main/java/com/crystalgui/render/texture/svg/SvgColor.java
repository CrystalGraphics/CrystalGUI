package com.crystalgui.render.texture.svg;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses an SVG paint value into an ARGB int.
 *
 * <h3>The four answers a paint value can give</h3>
 *
 * <p>A {@code fill} or {@code stroke} attribute is not simply a colour. It is one of: a colour, the
 * keyword {@code none} (draw nothing), the keyword {@code currentColor} (whatever the consumer is
 * tinting with), or a {@code url(#id)} reference to a paint server. All four have to be distinguishable
 * by the caller, which is why this returns a small record rather than an int — an {@code int} would have
 * to encode "absent" as a colour, and every such encoding is a colour somebody eventually wants.</p>
 *
 * <h3>Named colours: the full CSS set, not a guess</h3>
 *
 * <p>Icon sets use names constantly — {@code black}, {@code white}, {@code none}, and logos reach for
 * {@code red}/{@code gray}. The list below is CSS Level 2's sixteen plus the handful that show up in
 * practice. An unknown name falls back to black rather than to "no paint": a shape drawn in the wrong
 * colour is a bug you can see and fix, and a shape that silently vanishes is one you cannot.</p>
 */
public final class SvgColor {

    /** What a {@code fill}/{@code stroke} value turned out to be. */
    public record Paint(int argb, boolean present, boolean currentColor, String reference) {

        public static final Paint NONE = new Paint(0, false, false, null);
        public static final Paint CURRENT = new Paint(0xFF000000, true, true, null);

        public static Paint of(int argb) {
            return new Paint(argb, true, false, null);
        }

        public static Paint url(String id) {
            return new Paint(0xFF000000, true, false, id);
        }
    }

    private SvgColor() {
    }

    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        NAMED.put("black", 0xFF000000);
        NAMED.put("silver", 0xFFC0C0C0);
        NAMED.put("gray", 0xFF808080);
        NAMED.put("grey", 0xFF808080);
        NAMED.put("white", 0xFFFFFFFF);
        NAMED.put("maroon", 0xFF800000);
        NAMED.put("red", 0xFFFF0000);
        NAMED.put("purple", 0xFF800080);
        NAMED.put("fuchsia", 0xFFFF00FF);
        NAMED.put("magenta", 0xFFFF00FF);
        NAMED.put("green", 0xFF008000);
        NAMED.put("lime", 0xFF00FF00);
        NAMED.put("olive", 0xFF808000);
        NAMED.put("yellow", 0xFFFFFF00);
        NAMED.put("navy", 0xFF000080);
        NAMED.put("blue", 0xFF0000FF);
        NAMED.put("teal", 0xFF008080);
        NAMED.put("aqua", 0xFF00FFFF);
        NAMED.put("cyan", 0xFF00FFFF);
        NAMED.put("orange", 0xFFFFA500);
        NAMED.put("pink", 0xFFFFC0CB);
        NAMED.put("brown", 0xFFA52A2A);
        NAMED.put("darkgray", 0xFFA9A9A9);
        NAMED.put("darkgrey", 0xFFA9A9A9);
        NAMED.put("lightgray", 0xFFD3D3D3);
        NAMED.put("lightgrey", 0xFFD3D3D3);
        NAMED.put("transparent", 0x00000000);
    }

    /** Parses a paint value. An empty or unrecognised value is reported as absent, not as black. */
    public static Paint parse(String raw) {
        if (raw == null) return Paint.NONE;
        String value = raw.trim();
        if (value.isEmpty()) return Paint.NONE;
        if (value.equalsIgnoreCase("none")) return Paint.NONE;
        if (value.equalsIgnoreCase("currentColor")) return Paint.CURRENT;

        if (value.regionMatches(true, 0, "url(", 0, 4)) {
            int close = value.indexOf(')');
            String id = value.substring(4, close < 0 ? value.length() : close).trim();
            if (id.startsWith("#")) id = id.substring(1);
            // A quoted reference -- url("#a") -- is legal CSS and appears in exported artwork.
            id = id.replace("\"", "").replace("'", "");
            return Paint.url(id);
        }

        Integer argb = parseColor(value);
        return argb == null ? Paint.NONE : Paint.of(argb);
    }

    /** Parses a plain colour — {@code #rgb}, {@code #rrggbb}, {@code rgb()}, or a name. Null when it is not one. */
    public static Integer parseColor(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;

        if (value.charAt(0) == '#') return hex(value.substring(1));
        if (value.regionMatches(true, 0, "rgb", 0, 3)) return functional(value);

        Integer named = NAMED.get(value.toLowerCase());
        if (named != null) return named;
        return null;
    }

    /**
     * {@code #rgb}, {@code #rgba}, {@code #rrggbb}, {@code #rrggbbaa}.
     *
     * <p>The three- and four-digit forms double each digit rather than shifting: {@code #f00} is
     * {@code #ff0000}, not {@code #f00000}. Shifting is the tempting one-liner and it darkens every short
     * hex in the file by a few percent, which is invisible per-colour and wrong everywhere.</p>
     */
    private static Integer hex(String digits) {
        try {
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
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static int nibble(char c) {
        return Character.digit(c, 16);
    }

    /** {@code rgb(1,2,3)}, {@code rgba(1,2,3,0.5)}, and the percentage forms of both. */
    private static Integer functional(String value) {
        int open = value.indexOf('(');
        int close = value.lastIndexOf(')');
        if (open < 0 || close < open) return null;
        String[] parts = value.substring(open + 1, close).split("[\\s,/]+");
        if (parts.length < 3) return null;
        try {
            int r = channel(parts[0]);
            int g = channel(parts[1]);
            int b = channel(parts[2]);
            int a = parts.length > 3 ? Math.round(clamp01(Float.parseFloat(parts[3].trim())) * 255f) : 255;
            return pack(a, r, g, b);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static int channel(String raw) {
        String value = raw.trim();
        if (value.endsWith("%")) {
            float percent = Float.parseFloat(value.substring(0, value.length() - 1));
            return Math.round(clamp01(percent / 100f) * 255f);
        }
        return Math.max(0, Math.min(255, Math.round(Float.parseFloat(value))));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int pack(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /** Multiplies a colour's alpha, for {@code opacity} / {@code fill-opacity} / {@code stroke-opacity}. */
    public static int withOpacity(int argb, float opacity) {
        if (opacity >= 1f) return argb;
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamp01(opacity));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Linear blend, for collapsing a gradient's stops to one representative colour. */
    public static int mix(int from, int to, float t) {
        float u = 1f - t;
        int a = Math.round(((from >>> 24) & 0xFF) * u + ((to >>> 24) & 0xFF) * t);
        int r = Math.round(((from >>> 16) & 0xFF) * u + ((to >>> 16) & 0xFF) * t);
        int g = Math.round(((from >>> 8) & 0xFF) * u + ((to >>> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * u + (to & 0xFF) * t);
        return pack(a, r, g, b);
    }
}
