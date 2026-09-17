package com.crystalgui.style;

import java.util.regex.Pattern;

/**
 * Block comments in a CSS value — where a switched-off declaration or layer is kept.
 *
 * <pre>{@code
 * CssComments.strip("#000 0 1px 2px /* , #F00 0 0 4px *}{@code /");   // "#000 0 1px 2px " — what the cascade reads
 * CssComments.has("/* #FFF *}{@code /");                             // true
 * }</pre>
 *
 * <p>Block comments only: a value can hold {@code //}, as a URL does, and a sheet's line comments are stripped
 * before a value is ever cut out of it.</p>
 */
public final class CssComments {

    private static final Pattern BLOCK = Pattern.compile("(?s)/\\*.*?\\*/");

    private CssComments() {
    }

    public static String strip(String css) {
        return BLOCK.matcher(css).replaceAll("");
    }

    public static boolean has(String css) {
        return css.contains("/*");
    }
}
