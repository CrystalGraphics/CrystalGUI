package com.crystalgui.style.transition;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.easing.CubicBezier;
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

    private static final Pattern TIME = Pattern.compile("(?i)^(-?\\d+(?:\\.\\d+)?)(ms|s)$");
    private static final Pattern CUBIC_BEZIER = Pattern.compile(
            "(?i)^cubic-bezier\\(\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*\\)$");

    /**
     * Parses the full {@code transition} value: comma-separated entries. Comma-splitting is
     * paren-aware, so commas inside {@code cubic-bezier(...)} don't split entries.
     */
    public static List<TransitionSpec> parse(String raw) {
        List<TransitionSpec> specs = new ArrayList<>();
        // `none` IS THE WHOLE VALUE, and it is what makes an entry animation possible at all.
        //
        // CSS's `transition-property: none`. Every animation that enters from somewhere has to SNAP to
        // where it comes from and then ease to rest — and without a suppression, applying the start
        // state is itself a change, so the engine eases TOWARDS it and the thing animates backwards.
        // The web's recipe is exactly this: a class that carries `transition: none` plus the start
        // values, removed a frame later so the resting rule and its transition take over.
        //
        // An empty list rather than a flag, because that is already the shape everything downstream
        // reads: `tryStart` looks for a spec naming the property and declines when there is none.
        if ("none".equalsIgnoreCase(raw.trim())) return specs;
        for (String entry : CssParsingUtil.splitTopLevelCommas(raw)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            specs.add(parseEntry(trimmed));
        }
        return specs;
    }

    private static TransitionSpec parseEntry(String entry) {
        // Shared with `transform`'s function-list parsing — a cubic-bezier(...) call and a
        // translate(...) call tokenise by the same rule.
        List<String> tokens = CssParsingUtil.splitFunctionList(entry);

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

    /**
     * A transition list as the CSS its own {@link #parse} reads back.
     *
     * <pre>{@code
     * "opacity 240ms ease, display 240ms ease"
     * }</pre>
     *
     * <p>Beside the parser, so the two spellings of one grammar cannot drift apart — the same reason
     * {@link com.crystalgui.style.property.StyleProperty#write} sits beside its {@code ValueParser}.</p>
     */
    public static String write(List<TransitionSpec> specs) {
        // `none` IS the empty list, and the parser reads it back as one. An empty string would too, but
        // nothing should write an empty declaration into a sheet or onto a clipboard.
        if (specs.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (TransitionSpec spec : specs) {
            if (out.length() > 0) out.append(", ");
            out.append(spec.propertyNameOrAll())
                    .append(' ').append(millis(spec.durationNanos()));
            // ONLY WHEN THERE IS ONE: a bare `0ms` delay in the middle would be read as the delay it is,
            // which is the same value -- but it is noise in something a person reads.
            if (spec.delayNanos() != 0L) out.append(' ').append(millis(spec.delayNanos()));
            out.append(' ').append(timingFunction(spec.easing()));
        }
        return out.toString();
    }

    private static String millis(long nanos) {
        return (nanos / 1_000_000L) + "ms";
    }

    /** The keyword or {@code cubic-bezier(...)} that {@code parseTimingFunction} would answer with. */
    private static String timingFunction(Easing easing) {
        if (easing == ProgressFunctions.Premade.LINEAR) return "linear";
        if (easing instanceof CubicBezier bezier) {
            return "cubic-bezier(" + bezier.getX1() + ", " + bezier.getY1() + ", "
                    + bezier.getX2() + ", " + bezier.getY2() + ")";
        }
        throw new IllegalStateException("No CSS spelling for easing " + easing.getClass().getSimpleName()
                + " — transition-timing-function can only write the keywords and cubic-bezier().");
    }
}
