package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a {@code font-family} value: a comma-separated fallback stack of quoted asset paths, e.g.
 * {@code font-family: "crystalgraphics:fonts/A.ttf", "crystalgraphics:fonts/B.ttf";} — real font
 * fallback (primary tried first, each subsequent path tried for codepoints the previous can't
 * display), not just a single font reference. Resolving the list into an actual
 * {@code CgFontFamily} (via {@code CgFontFamily.of(primary, ...fallbacks)}) happens later, in
 * {@code FontFamilyCache} — this class only parses the raw path list.
 *
 * <p>Uses {@link CssParsingUtil#splitTopLevelCommas} — the same comma-splitting helper
 * {@code sprite()}/{@code asset()} already use in {@code TextureValue} — even though there's no
 * function-call wrapper here (unlike {@code image(...)}/{@code sprite(...)}), since a bare
 * comma-separated value list is real CSS's own actual {@code font-family:} grammar.</p>
 */
public class FontFamilyValue extends StyleValue<List<String>> {

    public FontFamilyValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable List<String> doCompute(String rawValue) {
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) return null;

        List<String> paths = new ArrayList<>();
        for (String part : CssParsingUtil.splitTopLevelCommas(trimmed)) {
            String path = unquote(part.trim());
            if (path.isEmpty()) return null;
            paths.add(path);
        }
        return paths.isEmpty() ? null : paths;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
