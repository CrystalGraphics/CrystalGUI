package com.crystalgui.style.property.visual.shadow;

import com.crystalgui.render.texture.ArgbMath;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.FontRelative;
import com.crystalgui.style.property.visual.color.ColorValue;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes CSS shadow values for every {@link ShadowGrammar}.
 *
 * <p>Ported from Blink's {@code css_parsing_utils::ConsumeShadow} and {@code ParseSingleShadow}
 * (BSD-3-Clause, see THIRD-PARTY.md). The colour and {@code inset} may each come before or after the
 * lengths; the lengths are contiguous; a second colour or a second {@code inset} fails; and whatever a
 * shadow leaves unconsumed fails the whole list.</p>
 *
 * <p>Two deliberate differences, both this engine's conventions rather than CSS's: a bare number is a
 * pixel length, as it is for every length property here, and there is no {@code calc()}.</p>
 */
final class ShadowParser {

    private static final int X = 0, Y = 1, BLUR = 2, SPREAD = 3;

    private ShadowParser() {
    }

    /** A parsed list whose {@code em} lengths are not yet pixels. */
    static final class Parsed {
        private final float[][] lengths;   // per shadow: x, y, blur, spread, as authored numbers
        private final boolean[][] em;      // per shadow: which of those are em multiples
        private final int[] argb;
        private final boolean[] currentColor;
        private final boolean[] inset;

        private Parsed(int count) {
            lengths = new float[count][4];
            em = new boolean[count][4];
            argb = new int[count];
            currentColor = new boolean[count];
            inset = new boolean[count];
        }

        boolean isFontRelative() {
            for (boolean[] row : em) for (boolean e : row) if (e) return true;
            return false;
        }

        ShadowList resolve(float fontSize) {
            int count = argb.length;
            if (count == 0) return ShadowList.NONE;
            Shadow[] out = new Shadow[count];
            for (int i = 0; i < count; i++) {
                float x = px(i, X, fontSize), y = px(i, Y, fontSize);
                float blur = px(i, BLUR, fontSize), spread = px(i, SPREAD, fontSize);
                out[i] = currentColor[i]
                        ? Shadow.currentColor(x, y, blur, spread, inset[i])
                        : Shadow.of(x, y, blur, spread, argb[i], inset[i]);
            }
            return ShadowList.of(out);
        }

        private float px(int shadow, int field, float fontSize) {
            float value = lengths[shadow][field];
            return em[shadow][field] ? value * fontSize : value;
        }
    }

    /** Null when {@code raw} is not a valid value for {@code grammar}. */
    @Nullable
    static Parsed parse(String raw, ShadowGrammar grammar) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;

        // ConsumeShadow: `none` alone, or a comma-separated list.
        if (value.equalsIgnoreCase("none")) return grammar.list ? new Parsed(0) : null;

        List<String> items = CssParsingUtil.splitTopLevelCommas(value);
        if (!grammar.list && items.size() != 1) return null;

        Parsed parsed = new Parsed(items.size());
        for (int i = 0; i < items.size(); i++) {
            List<String> tokens = CssParsingUtil.splitFunctionList(items.get(i).trim());
            if (tokens.isEmpty() || !parseSingle(tokens, grammar, parsed, i)) return null;
        }
        return parsed;
    }

    /** {@code ParseSingleShadow}, token for token. */
    private static boolean parseSingle(List<String> tokens, ShadowGrammar grammar, Parsed out, int index) {
        Cursor cursor = new Cursor(tokens);

        Color color = consumeColor(cursor);
        boolean inset = false;
        if (cursor.peekIs("inset")) {
            if (!grammar.insetAndSpread) return false;
            inset = true;
            cursor.advance();
            if (color == null) color = consumeColor(cursor);
        }

        if (!consumeLength(cursor, false, out, index, X)) return false;
        if (!consumeLength(cursor, false, out, index, Y)) return false;

        boolean blur = consumeLength(cursor, true, out, index, BLUR);
        if (blur && grammar.insetAndSpread) {
            consumeLength(cursor, grammar.spreadNonNegative, out, index, SPREAD);
        }

        if (!cursor.atEnd()) {
            if (color == null) color = consumeColor(cursor);
            if (cursor.peekIs("inset")) {
                if (!grammar.insetAndSpread || inset) return false;
                inset = true;
                cursor.advance();
                if (color == null) color = consumeColor(cursor);
            }
        }
        // ConsumeCommaSeparatedList requires each item to be consumed up to its comma.
        if (!cursor.atEnd()) return false;

        out.inset[index] = inset;
        if (color == null || color.current) {
            out.currentColor[index] = true;
        } else {
            out.argb[index] = color.argb;
        }
        return true;
    }

    private record Color(int argb, boolean current) {
    }

    /** {@code ConsumeColor}: a colour token, or nothing consumed. */
    @Nullable
    private static Color consumeColor(Cursor cursor) {
        if (cursor.atEnd()) return null;
        String token = cursor.peek();
        if (token.equalsIgnoreCase("currentcolor")) {
            cursor.advance();
            return new Color(0, true);
        }
        Integer argb = ColorValue.parseCssColor(token);
        if (argb == null) return null;
        cursor.advance();
        return new Color(argb, false);
    }

    /**
     * {@code ConsumeLength} with a value range: a length is consumed only when it is one and it is in
     * range, so {@code 10px 20px -30px} leaves the negative blur behind and fails as leftover input.
     */
    private static boolean consumeLength(Cursor cursor, boolean nonNegative, Parsed out, int index, int field) {
        if (cursor.atEnd()) return false;
        String token = cursor.peek().toLowerCase(Locale.ROOT);
        float value;
        boolean em = false;
        try {
            if (token.endsWith("em")) {
                float multiple = FontRelative.multipleIn(token);
                if (Float.isNaN(multiple)) return false;
                value = multiple;
                em = true;
            } else if (token.endsWith("px")) {
                value = Float.parseFloat(token.substring(0, token.length() - 2));
            } else if (isPlainNumber(token)) {
                value = Float.parseFloat(token);
            } else {
                return false;
            }
        } catch (NumberFormatException notALength) {
            return false;
        }
        if (!Float.isFinite(value) || (nonNegative && value < 0f)) return false;
        out.lengths[index][field] = value;
        out.em[index][field] = em;
        cursor.advance();
        return true;
    }

    /** Digits, a sign, a point and an exponent: what {@code Float.parseFloat} reads, minus its hex and NaN forms. */
    private static boolean isPlainNumber(String token) {
        if (token.isEmpty()) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e')) return false;
        }
        return Character.isDigit(token.charAt(token.length() - 1)) || token.endsWith(".");
    }

    private static final class Cursor {
        private final List<String> tokens;
        private int at;

        Cursor(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean atEnd() {
            return at >= tokens.size();
        }

        String peek() {
            return tokens.get(at);
        }

        boolean peekIs(String keyword) {
            return !atEnd() && tokens.get(at).equalsIgnoreCase(keyword);
        }

        void advance() {
            at++;
        }
    }

    // ── Writing ──────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code none}, or each shadow as {@code <color> <x> <y> <blur>}, then {@code <spread>} and
     * {@code inset} when present. The blur is always written, as a computed value is.
     *
     * <p>A colour mid-transition between a literal and {@code currentcolor} writes its literal part: no
     * spelling here holds the mix, and a transition is never what gets serialised.</p>
     */
    static String write(ShadowList list) {
        if (list.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            Shadow s = list.get(i);
            if (i > 0) out.append(", ");
            out.append(s.color().isCurrentColor() ? "currentcolor" : ArgbMath.toCss(s.color().literalArgb()));
            out.append(' ').append(px(s.x())).append(' ').append(px(s.y())).append(' ').append(px(s.blur()));
            if (s.spread() != 0f) out.append(' ').append(px(s.spread()));
            if (s.inset()) out.append(" inset");
        }
        return out.toString();
    }

    private static String px(float value) {
        if (value == (long) value) return (long) value + "px";
        return value + "px";
    }
}
