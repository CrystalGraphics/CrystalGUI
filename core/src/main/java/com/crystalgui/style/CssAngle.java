package com.crystalgui.style;

import javax.annotation.Nullable;

/**
 * CSS {@code <angle>} parsing — {@code deg}, {@code rad}, {@code turn}, {@code grad} — normalised to
 * radians, which is what JOML's rotation methods take.
 *
 * <p>The engine's only angle-valued syntax is {@code transform}'s {@code rotate()}/{@code skew()}, and
 * before those there was no angle unit anywhere in CrystalGUI at all. Kept as a separate utility rather
 * than folded into the transform parser so a future {@code linear-gradient(45deg, ...)} or
 * {@code rotate} property reuses one definition instead of growing a second, subtly different one.</p>
 *
 * <h3>Sign</h3>
 * <p>Positive is clockwise, matching both CSS and this engine's coordinate system (Y grows downward,
 * so a positive Z rotation visually turns clockwise).</p>
 *
 * <h3>Bare numbers</h3>
 * <p>Unlike {@code LengthPercent}, a bare number is <b>not</b> accepted as an implicit unit — CSS
 * requires a unit on every non-zero angle, and silently reading {@code rotate(45)} as 45 degrees would
 * hide a real authoring error. The single exception is a literal zero, which CSS does allow unitless.</p>
 */
public final class CssAngle {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final float GRAD_TO_RAD = (float) (Math.PI / 200.0);
    private static final float TURN_TO_RAD = (float) (Math.PI * 2.0);

    private CssAngle() {
    }

    /**
     * @return the angle in radians, or {@code null} if {@code rawValue} is not a valid CSS angle.
     *         Callers treat {@code null} as "this declaration is malformed" rather than as a zero.
     */
    public static @Nullable Float parse(String rawValue) {
        if (rawValue == null) return null;
        String trimmed = rawValue.trim().toLowerCase();
        if (trimmed.isEmpty()) return null;

        try {
            if (trimmed.endsWith("deg")) {
                return number(trimmed, 3) * DEG_TO_RAD;
            }
            if (trimmed.endsWith("grad")) {
                return number(trimmed, 4) * GRAD_TO_RAD;
            }
            if (trimmed.endsWith("turn")) {
                return number(trimmed, 4) * TURN_TO_RAD;
            }
            if (trimmed.endsWith("rad")) {
                return number(trimmed, 3);
            }
            // CSS permits a unitless zero, and only a zero.
            float bare = Float.parseFloat(trimmed);
            return bare == 0f ? Float.valueOf(0f) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses the numeric part left of a {@code suffixLength}-character unit. */
    private static float number(String trimmed, int suffixLength) {
        return Float.parseFloat(trimmed.substring(0, trimmed.length() - suffixLength).trim());
    }

    /** Formats {@code radians} back as a {@code deg} value — the round-trip for serialization/debug. */
    public static String toDegreesString(float radians) {
        return (radians / DEG_TO_RAD) + "deg";
    }
}
