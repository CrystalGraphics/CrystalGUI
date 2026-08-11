package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.property.StyleValue;

import java.util.Locale;

/**
 * Parses CSS {@code font-weight}: the keywords {@code normal} and {@code bold}, or a number on the
 * 100–900 scale.
 *
 * <p>A custom value rather than a plain {@code EnumProperty} for one reason: the numeric form. Authors
 * write {@code font-weight: 600}, and an enum parser would reject it as an unknown keyword — a warning
 * for a declaration whose meaning is not in doubt. {@link FontWeight#ofNumeric} resolves it the way a
 * family with two faces resolves it, which is what this engine loads.</p>
 *
 * <p>Out-of-range numbers are refused rather than clamped. {@code font-weight: 1200} is a mistake, not
 * a request for something very bold, and clamping would hide it — an unrecognised value throws, which
 * {@link StyleValue#compute()} turns into a warning and a {@code null}, so the declaration is skipped
 * and the cascade falls through to whatever sat underneath it.</p>
 */
public final class FontWeightValue extends StyleValue<FontWeight> {

    public FontWeightValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected FontWeight doCompute(String raw) {
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) throw new IllegalArgumentException("font-weight: empty value");

        char first = trimmed.charAt(0);
        if (first >= '0' && first <= '9') {
            int numeric = Integer.parseInt(trimmed);
            if (numeric < 100 || numeric > 900) {
                throw new IllegalArgumentException("font-weight out of the 100-900 range: " + numeric);
            }
            return FontWeight.ofNumeric(numeric);
        }
        return FontWeight.valueOf(trimmed.toUpperCase(Locale.ROOT));
    }
}
