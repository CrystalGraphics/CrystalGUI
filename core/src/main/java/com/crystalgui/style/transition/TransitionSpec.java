package com.crystalgui.style.transition;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.easing.Easing;
import com.crystalgui.style.easing.ProgressFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code <property-name|all> <duration>[ms|s] [<delay>[ms|s]] [timing-function]} entry, parsed
 * from a {@code transition} shorthand value. {@code timing-function} is either a keyword
 * ({@code linear|ease|ease-in|ease-out|ease-in-out}) or a {@code cubic-bezier(a,b,c,d)} call with
 * arbitrary control points — no {@code steps()} support (not in the underlying easing library).
 */
public record TransitionSpec(String propertyNameOrAll, long durationNanos, long delayNanos, Easing easing) {

    public static final String ALL = "all";

    private static final Pattern TOKEN = Pattern.compile("[^\\s()]+\\([^()]*\\)|\\S+");
    private static final Pattern TIME = Pattern.compile("(?i)^(-?\\d+(?:\\.\\d+)?)(ms|s)$");
    private static final Pattern CUBIC_BEZIER = Pattern.compile(
            "(?i)^cubic-bezier\\(\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*\\)$");

    /**
     * Parses the full {@code transition} value: comma-separated entries. Comma-splitting is
     * paren-aware, so commas inside {@code cubic-bezier(...)} don't split entries.
     */
    public static List<TransitionSpec> parse(String raw) {
        List<TransitionSpec> specs = new ArrayList<>();
        for (String entry : CssParsingUtil.splitTopLevelCommas(raw)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            specs.add(parseEntry(trimmed));
        }
        return specs;
    }

    private static TransitionSpec parseEntry(String entry) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(entry);
        while (m.find()) tokens.add(m.group());

        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Empty transition entry");
        }
        String propertyName = tokens.get(0);
        if (tokens.size() < 2) {
            throw new IllegalArgumentException("Transition entry '" + entry + "' is missing a duration");
        }

        long duration = parseTime(tokens.get(1), entry);
        long delay = 0;
        Easing easing = parseTimingFunction("ease"); // CSS default transition-timing-function

        int next = 2;
        if (next < tokens.size() && TIME.matcher(tokens.get(next)).matches()) {
            delay = parseTime(tokens.get(next), entry);
            next++;
        }
        if (next < tokens.size()) {
            easing = parseTimingFunction(tokens.get(next));
            next++;
        }
        if (next != tokens.size()) {
            throw new IllegalArgumentException("Unexpected trailing tokens in transition entry '" + entry + "'");
        }

        return new TransitionSpec(propertyName, duration, delay, easing);
    }

    private static long parseTime(String token, String entry) {
        Matcher m = TIME.matcher(token);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Expected a duration like '200ms' or '0.3s' in transition entry '" + entry + "', got '" + token + "'");
        }
        double value = Double.parseDouble(m.group(1));
        boolean seconds = m.group(2).equalsIgnoreCase("s");
        double millis = seconds ? value * 1000.0 : value;
        return Math.round(millis * 1_000_000.0);
    }

    private static Easing parseTimingFunction(String token) {
        Matcher bezier = CUBIC_BEZIER.matcher(token);
        if (bezier.matches()) {
            return ProgressFunctions.cubicBezier(
                    Double.parseDouble(bezier.group(1)),
                    Double.parseDouble(bezier.group(2)),
                    Double.parseDouble(bezier.group(3)),
                    Double.parseDouble(bezier.group(4)));
        }
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "linear" -> ProgressFunctions.Premade.LINEAR;
            case "ease" -> ProgressFunctions.cubicBezier(0.25, 0.1, 0.25, 1.0);
            case "ease-in" -> ProgressFunctions.cubicBezier(0.42, 0.0, 1.0, 1.0);
            case "ease-out" -> ProgressFunctions.cubicBezier(0.0, 0.0, 0.58, 1.0);
            case "ease-in-out" -> ProgressFunctions.cubicBezier(0.42, 0.0, 0.58, 1.0);
            default -> throw new IllegalArgumentException("Unknown transition-timing-function '" + token + "'");
        };
    }
}
