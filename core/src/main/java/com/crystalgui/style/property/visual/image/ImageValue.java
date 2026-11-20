package com.crystalgui.style.property.visual.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.render.image.CgUiImage.Gradient;
import com.crystalgui.render.image.CgUiMark;
import com.crystalgui.render.image.CgUiPainter;
import com.crystalgui.style.CssAngle;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.color.ColorValue;

/**
 * Parses an image property ({@code background}, {@code overlay}, {@code mask}, {@code outline}) into a
 * {@link CgUiImage}, which writes itself back ({@link CgUiImage#css}).
 *
 * <pre>
 *   #RRGGBB, #RRGGBBAA, rgb(...), rgba(...)         a colour
 *   image("path"[, "x y w h"][, "refW refH"][, #tint])
 *   sprite("path", "x y w h", "left top right bottom"[, "refW refH"][, "repeat round"])     a nine-slice
 *   asset("sheet", "element")                       a sprite sheet's entry
 *   icon("ns:name"[, #tint][, monochrome])          an SVG icon, its light or dark drawing chosen by the theme
 *   shape("chevron-down")                           a built-in mark
 *   linear-gradient(...), radial-gradient(...)       CSS's
 *   grid(cell[ cellY], colour[, line width])
 *   paint(name)                                     a registered painter
 *   cross-fade(a 40%, b)                            a in 40%, b in the rest
 *   a, b, c                                         layers, the first on top
 * </pre>
 *
 * <ul>
 *   <li>{@code none} (and LSS's {@code empty}) is {@link CgUiImage#NONE}; a value that does not parse is null, a
 *       failed declaration. One unreadable layer fails the whole list.</li>
 *   <li>{@code icon()} of a file that is missing fails, so a typo is not an invisible icon.</li>
 * </ul>
 */
public class ImageValue extends StyleValue<CgUiImage> {

    public ImageValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgUiImage doCompute(String rawValue) {
        return parse(rawValue);
    }

    /** {@code css} as an image, or null when it does not read. */
    @Nullable
    public static CgUiImage parse(String css) {
        String value = css.trim();
        if (value.isEmpty()) return null;
        List<String> parts = CssParsingUtil.splitTopLevelCommas(value);
        if (parts.size() == 1) return layer(value);
        List<CgUiImage> layers = new ArrayList<>(parts.size());
        for (String part : parts) {
            CgUiImage layer = layer(part.trim());
            if (layer == null) return null;
            layers.add(layer);
        }
        return CgUiImage.layers(layers);
    }

    @Nullable
    private static CgUiImage layer(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("none") || lower.equals("empty")) return CgUiImage.NONE;
        return ImageKinds.parse(value);
    }

    // ── Functions ─────────────────────────────────────────────────────────────

    /** {@code grid(<cell>[ <cellY>], <colour>[, <line width>])}, logical pixels, {@code px} accepted. */
    @Nullable
    static CgUiImage parseGrid(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() < 2 || parts.size() > 3) return null;
        String[] cells = parts.get(0).trim().split("\\s+");
        if (cells.length > 2) return null;
        Float cellX = number(cells[0]);
        Float cellY = cells.length == 2 ? number(cells[1]) : cellX;
        if (cellX == null || cellY == null || cellX <= 0f || cellY <= 0f) return null;
        Integer color = ColorValue.parseColor(parts.get(1).trim());
        if (color == null) return null;
        float line = 1f;
        if (parts.size() == 3) {
            Float parsed = number(parts.get(2).trim());
            if (parsed == null || parsed < 0f) return null;
            line = parsed;
        }
        return new CgUiImage.Grid(cellX, cellY, line, color);
    }

    /** {@code linear-gradient([<angle> | to <side> | to <corner>,] <stop>, <stop>...)}. */
    @Nullable
    static CgUiImage parseLinearGradient(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        float angle = 180f;
        Gradient.Corner corner = null;
        int first = 0;
        String head = parts.get(0).trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("to ")) {
            String[] words = head.substring(3).trim().split("\\s+");
            boolean top = false, bottom = false, left = false, right = false;
            for (String word : words) {
                switch (word) {
                    case "top" -> top = true;
                    case "bottom" -> bottom = true;
                    case "left" -> left = true;
                    case "right" -> right = true;
                    default -> {
                        return null;
                    }
                }
            }
            if (words.length > 2 || (top && bottom) || (left && right)) return null;
            if (words.length == 2) {
                if (!(top || bottom) || !(left || right)) return null;
                corner = top ? (left ? Gradient.Corner.TOP_LEFT : Gradient.Corner.TOP_RIGHT)
                        : (left ? Gradient.Corner.BOTTOM_LEFT : Gradient.Corner.BOTTOM_RIGHT);
            } else {
                angle = top ? 0f : right ? 90f : bottom ? 180f : 270f;
            }
            first = 1;
        } else {
            Float radians = CssAngle.parse(head);
            if (radians != null) {
                angle = (float) Math.toDegrees(radians);
                first = 1;
            }
        }
        List<Gradient.Stop> stops = stops(parts, first);
        if (stops == null) return null;
        return corner != null ? Gradient.linear(corner, stops) : Gradient.linear(angle, stops);
    }

    /** {@code radial-gradient([circle | ellipse] [<extent> | <length>{1,2}] [at <position>], <stop>...)}. */
    @Nullable
    static CgUiImage parseRadialGradient(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        Gradient.Radial radial = Gradient.Radial.DEFAULT;
        int first = 0;
        if (stops(parts.subList(0, 1), 0, 1) == null) {
            radial = radialShape(parts.get(0).trim().toLowerCase(Locale.ROOT));
            if (radial == null) return null;
            first = 1;
        }
        List<Gradient.Stop> stops = stops(parts, first);
        return stops == null ? null : Gradient.radial(radial, stops);
    }

    @Nullable
    private static Gradient.Radial radialShape(String head) {
        String[] tokens = head.split("\\s+");
        int at = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals("at")) {
                at = i;
                break;
            }
        }
        int sizeEnd = at < 0 ? tokens.length : at;
        Boolean circle = null;
        Gradient.Extent extent = null;
        List<Gradient.Length> sizes = new ArrayList<>(2);
        for (int i = 0; i < sizeEnd; i++) {
            String token = tokens[i];
            switch (token) {
                case "circle", "ellipse" -> {
                    if (circle != null) return null;
                    circle = token.equals("circle");
                }
                case "closest-side", "closest-corner", "farthest-side", "farthest-corner" -> {
                    if (extent != null || !sizes.isEmpty()) return null;
                    extent = Gradient.Extent.valueOf(token.replace('-', '_').toUpperCase(Locale.ROOT));
                }
                default -> {
                    Gradient.Length length = length(token);
                    if (length == null || extent != null || sizes.size() == 2) return null;
                    sizes.add(length);
                }
            }
        }
        if (circle == null) circle = sizes.size() == 1;
        if (circle && (sizes.size() == 2 || (sizes.size() == 1 && sizes.get(0).percent()))) return null;
        if (!circle && sizes.size() == 1) return null;
        Gradient.Length centreX = Gradient.Length.CENTRE, centreY = Gradient.Length.CENTRE;
        if (at >= 0) {
            int count = tokens.length - at - 1;
            if (count < 1 || count > 2) return null;
            String a = tokens[at + 1], b = count == 2 ? tokens[at + 2] : "center";
            // `top left` and `top` alone name the vertical first; CSS reads either order of keywords
            if (a.equals("top") || a.equals("bottom") || b.equals("left") || b.equals("right")) {
                String swap = a;
                a = b;
                b = swap;
            }
            centreX = position(a, "left", "right");
            centreY = position(b, "top", "bottom");
            if (centreX == null || centreY == null) return null;
        }
        return new Gradient.Radial(circle, extent != null ? extent : Gradient.Extent.FARTHEST_CORNER,
                sizes.isEmpty() ? null : sizes.get(0), sizes.size() == 2 ? sizes.get(1) : null, centreX, centreY);
    }

    @Nullable
    private static Gradient.Length position(String token, String start, String end) {
        if (token.equals(start)) return new Gradient.Length(0f, true);
        if (token.equals("center")) return Gradient.Length.CENTRE;
        if (token.equals(end)) return new Gradient.Length(1f, true);
        return length(token);
    }

    /** {@code <n>%} as a fraction, or {@code <n>px} / a bare number as pixels; negative is refused. */
    @Nullable
    private static Gradient.Length length(String token) {
        boolean percent = token.endsWith("%");
        Float value = number(percent ? token.substring(0, token.length() - 1) : token);
        if (value == null || value < 0f) return null;
        return new Gradient.Length(percent ? value / 100f : value, percent);
    }

    /** The stops from {@code parts[first]} on: at least two, or null. */
    @Nullable
    private static List<Gradient.Stop> stops(List<String> parts, int first) {
        List<Gradient.Stop> stops = stops(parts, first, parts.size());
        return stops == null || stops.size() < 2 ? null : stops;
    }

    /** Each a colour with an optional {@code <n>%}, read off the end: an {@code rgba(...)} has spaces of its own. */
    @Nullable
    private static List<Gradient.Stop> stops(List<String> parts, int first, int end) {
        List<Gradient.Stop> stops = new ArrayList<>();
        for (int i = first; i < end; i++) {
            String stop = parts.get(i).trim();
            float position = Float.NaN;
            int lastSpace = stop.lastIndexOf(' ');
            if (lastSpace > 0 && stop.endsWith("%")) {
                Float percent = number(stop.substring(lastSpace + 1, stop.length() - 1).trim());
                if (percent == null) return null;
                position = percent / 100f;
                stop = stop.substring(0, lastSpace).trim();
            }
            Integer color = ColorValue.parseColor(stop);
            if (color == null) return null;
            stops.add(new Gradient.Stop(position, color));
        }
        return stops;
    }

    /** {@code icon("ns:name"[, #tint][, monochrome])}; null when the file is missing or cannot be drawn. */
    @Nullable
    static CgUiImage parseIcon(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        String name = unquote(parts.get(0).trim());
        if (name.isEmpty()) return null;
        CgUiImage.Icon icon = CgUiImage.findIcon(name);
        if (icon == null) return null;
        for (int i = 1; i < parts.size(); i++) {
            String arg = unquote(parts.get(i).trim());
            if (arg.equalsIgnoreCase("monochrome")) {
                icon = icon.monochrome(true);
                continue;
            }
            Integer tint = ColorValue.parseColor(arg);
            if (tint == null) return null;
            icon = icon.tint(tint);
        }
        return icon;
    }

    /** {@code shape("name")}: one of {@link CgUiMark}'s. */
    @Nullable
    static CgUiImage parseShape(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 1) return null;
        CgUiMark mark = CgUiMark.parse(unquote(parts.get(0).trim()));
        return mark == null ? null : CgUiImage.mark(mark);
    }

    /** {@code image("path"[, "x y w h"][, "refW refH"][, #tint])}, the trailing arguments in any order. */
    @Nullable
    static CgUiImage parseImage(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        CgUiImage.Picture picture = CgUiImage.Picture.of(unquote(parts.get(0).trim()));
        for (int i = 1; i < parts.size(); i++) {
            String arg = unquote(parts.get(i).trim());
            int[] region = ints(arg, 4);
            if (region != null) {
                picture = picture.region(region[0], region[1], region[2], region[3]);
                continue;
            }
            int[] reference = ints(arg, 2);
            if (reference != null) {
                picture = picture.reference(reference[0], reference[1]);
                continue;
            }
            Integer tint = ColorValue.parseColor(arg);
            if (tint == null) return null;
            picture = picture.tint(tint);
        }
        return picture;
    }

    /** {@code sprite("path", "x y w h", "l t r b"[, "refW refH"][, "<repeat>[ <repeat>]"])}. */
    @Nullable
    static CgUiImage parseSprite(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() < 3) return null;
        int[] region = ints(unquote(parts.get(1).trim()), 4);
        int[] slices = ints(unquote(parts.get(2).trim()), 4);
        if (region == null || slices == null) return null;
        CgUiImage.Picture picture = CgUiImage.Picture.of(unquote(parts.get(0).trim()))
                .region(region[0], region[1], region[2], region[3]).slices(slices[0], slices[1], slices[2], slices[3]);
        for (int i = 3; i < parts.size(); i++) {
            String arg = unquote(parts.get(i).trim());
            int[] reference = ints(arg, 2);
            if (reference != null) {
                picture = picture.reference(reference[0], reference[1]);
                continue;
            }
            CgUiImage.Repeat[] repeat = repeatPair(arg);
            if (repeat == null) return null;
            picture = picture.repeat(repeat[0], repeat[1]);
        }
        return picture;
    }

    /** {@code border-image-repeat}'s one or two keywords, the second defaulting to the first; null for neither. */
    @Nullable
    static CgUiImage.Repeat[] repeatPair(@Nullable String raw) {
        if (raw == null) return null;
        String[] words = raw.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 0 || words.length > 2) return null;
        CgUiImage.Repeat x = CgUiImage.Repeat.parse(words[0]);
        if (x == null) return null;
        CgUiImage.Repeat y = words.length == 2 ? CgUiImage.Repeat.parse(words[1]) : x;
        return y == null ? null : new CgUiImage.Repeat[]{x, y};
    }

    /** {@code asset("sheet", "element")}. */
    @Nullable
    static CgUiImage parseAsset(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 2) return null;
        String sheet = unquote(parts.get(0).trim()), element = unquote(parts.get(1).trim());
        if (sheet.isEmpty() || element.isEmpty()) return null;
        return new CgUiImage.Sheet(sheet, element);
    }

    /** {@code paint(name)}: the painter registered under {@code name}, looked up when it draws. */
    @Nullable
    static CgUiImage parsePaint(String args) {
        String name = unquote(args.trim());
        if (name.isEmpty() || name.contains(",")) return null;
        return new CgUiImage.Painted(name, new CgUiPainter.ByName(name));
    }

    /** {@code cross-fade(<a> <p>%, <b>)}: {@code a} at {@code p}, {@code b} at the rest. */
    @Nullable
    static CgUiImage parseCrossFade(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 2) return null;
        String first = parts.get(0).trim();
        int space = first.lastIndexOf(' ');
        if (space <= 0 || !first.endsWith("%")) return null;
        Float percent = number(first.substring(space + 1, first.length() - 1));
        if (percent == null || percent < 0f || percent > 100f) return null;
        CgUiImage from = layer(first.substring(0, space).trim()), to = layer(parts.get(1).trim());
        if (from == null || to == null) return null;
        return new CgUiImage.CrossFade(from, to, 1f - percent / 100f);
    }

    // ── Tokens ────────────────────────────────────────────────────────────────

    @Nullable
    private static Float number(String raw) {
        try {
            return Float.parseFloat(raw.endsWith("px") ? raw.substring(0, raw.length() - 2) : raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Exactly {@code count} integers separated by spaces, or null. */
    @Nullable
    private static int[] ints(String raw, int count) {
        String[] tokens = raw.trim().split("\\s+");
        if (tokens.length != count) return null;
        int[] out = new int[count];
        try {
            for (int i = 0; i < count; i++) out[i] = Integer.parseInt(tokens[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
