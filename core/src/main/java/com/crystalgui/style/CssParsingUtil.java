package com.crystalgui.style;

import java.util.ArrayList;
import java.util.List;

/** Small shared helpers for parsing CSS-shaped style values. */
public final class CssParsingUtil {

    private CssParsingUtil() {
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
