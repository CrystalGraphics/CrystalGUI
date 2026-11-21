package com.crystalgui.style;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One CSS {@code <length-percentage>} token: {@code px}, {@code %} or {@code em}, and a bare {@code 0}.
 *
 * <p>Every length-valued property parses through here, so the units a sheet may write are decided once.
 * A non-zero number with no unit is not a length, exactly as in CSS: {@code gap: 6} is dropped with a
 * warning, and {@code gap: 6px} is meant.</p>
 *
 * <pre>{@code
 * CssLength.parse("12px");    // 12 PX
 * CssLength.parse("50%");     // 50 PERCENT -- the number as written, not a fraction
 * CssLength.parse("1.5em");   // 1.5 EM, resolved per element (see FontRelative)
 * CssLength.parse("0");       // 0 PX
 * CssLength.parse("6");       // null: a unit is required
 * CssLength.parse("auto");    // null: keywords are the caller's
 * }</pre>
 *
 * <p>Easy to get wrong: {@link #value} of a percentage is {@code 50} for {@code 50%}. A caller wanting
 * Taffy's 0–1 fraction calls {@link #fraction()}.</p>
 */
public record CssLength(float value, Unit unit) {

    public enum Unit { PX, PERCENT, EM }

    public static final CssLength ZERO = new CssLength(0f, Unit.PX);

    // A CSS <number> then its unit. Float.parseFloat alone also takes "10f", "10d", "NaN" and "0x1p3".
    private static final Pattern TOKEN =
            Pattern.compile("([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:e[+-]?\\d+)?)(px|%|em)?");

    /** The token as a length, or null when it is not one. */
    public static @Nullable CssLength parse(@Nullable String raw) {
        if (raw == null) return null;
        Matcher m = TOKEN.matcher(raw.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        float number = Float.parseFloat(m.group(1));
        String unit = m.group(2);
        if (unit == null) return number == 0f ? ZERO : null;
        return switch (unit) {
            case "px" -> new CssLength(number, Unit.PX);
            case "%" -> new CssLength(number, Unit.PERCENT);
            default -> new CssLength(number, Unit.EM);
        };
    }

    /** A percentage as Taffy's 0–1 fraction. */
    public float fraction() {
        return value / 100f;
    }

    /** {@code 12px}, {@code 50%}, {@code 1.5em}; whole numbers without a decimal point. */
    @Override
    public String toString() {
        String number = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        return number + switch (unit) {
            case PX -> "px";
            case PERCENT -> "%";
            case EM -> "em";
        };
    }
}
