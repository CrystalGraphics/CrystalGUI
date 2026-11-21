package com.crystalgui.style.property.visual;

import com.crystalgui.style.CssLength;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Where a fitted drawable sits within its layer box when it doesn't exactly fill it — a keyword
 * subset of CSS {@code object-position}.
 *
 * <p>Inert under {@link DrawableFit#FILL} (nothing left over to distribute). Relevant for
 * {@code CONTAIN}/{@code NONE} (drawable smaller than the box) and {@code COVER} (larger — the
 * factors then choose which part overflows).</p>
 */
public enum DrawableAlign {
    TOP_LEFT(0f, 0f),
    TOP(0.5f, 0f),
    TOP_RIGHT(1f, 0f),
    LEFT(0f, 0.5f),
    /** CSS's own {@code object-position} default. */
    CENTER(0.5f, 0.5f),
    RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM(0.5f, 1f),
    BOTTOM_RIGHT(1f, 1f);

    private final float xFactor;
    private final float yFactor;

    DrawableAlign(float xFactor, float yFactor) {
        this.xFactor = xFactor;
        this.yFactor = yFactor;
    }

    /** Fraction of the horizontal leftover space placed before the drawable. */
    public float xFactor() {
        return xFactor;
    }

    /** Fraction of the vertical leftover space placed before the drawable. */
    public float yFactor() {
        return yFactor;
    }

    /** As CSS writes it: {@code left top}, {@code center}, {@code right center}. */
    public static String write(DrawableAlign align) {
        String x = align.xFactor == 0f ? "left" : align.xFactor == 1f ? "right" : "center";
        String y = align.yFactor == 0f ? "top" : align.yFactor == 1f ? "bottom" : "center";
        return x.equals(y) ? x : x + " " + y;
    }

    /** One of the nine at these factors, or null. */
    static @Nullable DrawableAlign at(float x, float y) {
        for (DrawableAlign a : values()) if (a.xFactor == x && a.yFactor == y) return a;
        return null;
    }

    /**
     * CSS {@code <position>}, 1–2 keywords or percentages, either order for keywords.
     *
     * <pre>
     *   overlay-position: center;         mask-position: left top;
     *   overlay-position: right;          mask-position: 100% 0%;
     *   overlay-position: bottom left;
     * </pre>
     *
     * <p>A subset: each axis is {@code 0%}, {@code 50%} or {@code 100%}, the nine places this enum has.
     * Other percentages and lengths do not parse.</p>
     */
    public static final class Value extends StyleValue<DrawableAlign> {

        public Value(String raw) {
            super(raw);
        }

        @Override
        protected @Nullable DrawableAlign doCompute(String raw) {
            List<String> t = CssParsingUtil.splitTopLevelSpaces(raw.toLowerCase(Locale.ROOT));
            if (t.isEmpty() || t.size() > 2) return null;
            boolean reversed = isVertical(t.get(0)) || (t.size() == 2 && isHorizontal(t.get(1)));
            String rx = reversed ? (t.size() == 2 ? t.get(1) : "center") : t.get(0);
            String ry = reversed ? t.get(0) : (t.size() == 2 ? t.get(1) : "center");
            float x = factor(rx, "left", "right"), y = factor(ry, "top", "bottom");
            return Float.isNaN(x) || Float.isNaN(y) ? null : at(x, y);
        }

        private static boolean isVertical(String t) {
            return t.equals("top") || t.equals("bottom");
        }

        private static boolean isHorizontal(String t) {
            return t.equals("left") || t.equals("right");
        }

        private static float factor(String token, String start, String end) {
            if (token.equals(start)) return 0f;
            if (token.equals(end)) return 1f;
            if (token.equals("center")) return 0.5f;
            CssLength length = CssLength.parse(token);
            if (length == null || (length.unit() == CssLength.Unit.EM)) return Float.NaN;
            if (length.unit() == CssLength.Unit.PX && length.value() != 0f) return Float.NaN;
            return length.fraction();
        }
    }
}
