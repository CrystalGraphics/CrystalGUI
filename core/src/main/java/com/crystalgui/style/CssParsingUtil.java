package com.crystalgui.style;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small shared helpers for parsing CSS-shaped style values. */
public final class CssParsingUtil {

    /**
     * An identifier followed by one non-nested paren group, or any run of non-whitespace.
     *
     * <p>The first alternative winning is the whole point: it keeps {@code cubic-bezier(0.4, 0, 0.2, 1)}
     * and {@code translate(10px, 5px)} as single tokens despite the spaces inside them.</p>
     */
    private static final Pattern FUNCTION_OR_WORD = Pattern.compile("[^\\s()]+\\([^()]*\\)|\\S+");

    private CssParsingUtil() {
    }

    /**
     * Splits a space-separated value into tokens, treating a whole {@code name(...)} call as one token
     * even when its arguments contain spaces.
     *
     * <pre>
     *   "translate(10px, 5px) scale(2) rotate(45deg)"  -> ["translate(10px, 5px)", "scale(2)", "rotate(45deg)"]
     *   "opacity 200ms cubic-bezier(0.4, 0, 0.2, 1)"   -> ["opacity", "200ms", "cubic-bezier(0.4, 0, 0.2, 1)"]
     * </pre>
     *
     * <p>Nested calls are NOT supported — the paren group may not itself contain parens, so a
     * {@code translate(calc(...))} would tokenise wrongly. Nothing in this engine's grammar nests
     * today; if that changes this needs a depth counter rather than a regex.</p>
     */
    public static List<String> splitFunctionList(String raw) {
        List<String> tokens = new ArrayList<>();
        Matcher m = FUNCTION_OR_WORD.matcher(raw);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    /**
     * Splits {@code raw} on top-level commas only — commas nested inside {@code func(...)} calls
     * (e.g. {@code cubic-bezier(a,b,c,d)}, {@code sprite(path, 1 2 3 4, ...)}) don't split entries.
     */
    public static List<String> splitTopLevelCommas(String raw) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(raw.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(raw.substring(start));
        return parts;
    }
}
