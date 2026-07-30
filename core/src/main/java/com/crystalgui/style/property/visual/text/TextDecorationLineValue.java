package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.property.StyleValue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses CSS {@code text-decoration-line}: {@code none}, or any combination of {@code underline},
 * {@code overline} and {@code line-through} separated by whitespace.
 *
 * <p>Multi-keyword rather than single-valued because CSS genuinely allows
 * {@code text-decoration-line: underline overline}, and a spell-checker squiggle plus a strikethrough on
 * the same range is a real editor case rather than a hypothetical one.</p>
 *
 * <p>An unrecognised keyword throws, which {@link StyleValue#compute()} turns into a warning and a
 * {@code null} — so the declaration is skipped and the cascade falls through to whatever was underneath,
 * rather than a typo silently meaning "no decoration".</p>
 */
public final class TextDecorationLineValue extends StyleValue<Set<TextDecorationLine>> {

    public TextDecorationLineValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Set<TextDecorationLine> doCompute(String raw) {
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.equals("none")) return Collections.emptySet();

        EnumSet<TextDecorationLine> lines = EnumSet.noneOf(TextDecorationLine.class);
        for (String token : trimmed.split("\\s+")) {
            if (token.isEmpty()) continue;
            lines.add(TextDecorationLine.valueOf(token.replace('-', '_').toUpperCase(Locale.ROOT)));
        }
        return Collections.unmodifiableSet(lines);
    }
}
