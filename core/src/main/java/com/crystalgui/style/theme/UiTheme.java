package com.crystalgui.style.theme;

import com.crystalgui.style.sheet.StyleSheet;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed theme or editor-colour-scheme file: header metadata, the raw variable table, and the CSS
 * source its override rules will be re-parsed from at apply time.
 *
 * <p>A theme file is ordinary CSS (see {@code plan/style-overhaul.md} §3.2): a {@code theme { }} block of
 * variable definitions by convention, plus optional real override rules for what variables cannot
 * express — an {@code asset()} sprite skin being the historical extreme. The metadata rides in a
 * fixed-shape header comment:</p>
 *
 * <pre>{@code
 * /* @theme  Crystal Dark
 *  * @id     crystalgui:crystal-dark
 *  * @kind   dark
 *  * @extends —                          (optional parent theme id)
 *  * @editor-scheme crystalgui:dark-plus (optional bundled-scheme suggestion)
 *  * @author crystalgui *␣/
 * }</pre>
 *
 * <p>A scheme file is the same shape opening with {@code @scheme <name>} instead of
 * {@code @theme <name>} — the first tag is what declares which of the two artifacts this is.</p>
 *
 * <h3>Validation is refusal, not degradation</h3>
 * <p>{@link #parse} throws on a malformed header <em>and on CSS whose rules would not survive</em>
 * (a broken selector — the {@code :focus-within} poison — throws out of the trial parse). A theme
 * arrives from outside eventually, and a half-installed one must degrade to "not offered", never to
 * a blank window; {@link ThemeRegistry} turns the throw into a logged refusal.</p>
 */
@Getter
@Accessors(fluent = true)
public final class UiTheme {

    /** What the file's first tag declared it to be — a UI theme or an editor colour scheme. */
    public enum Role { THEME, SCHEME }

    public enum Kind { DARK, LIGHT, HIGH_CONTRAST }

    private static final Pattern HEADER_TAG =
            Pattern.compile("@(editor-scheme|theme|scheme|id|kind|extends|author)[ \\t]+([^\\r\\n]+)");
    private static final Pattern ID_SHAPE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");

    private final String id;
    private final String displayName;
    private final Role role;
    private final Kind kind;
    /** Parent theme id ({@code @extends}), or {@code null} for a root theme. */
    @Nullable
    private final String parentId;
    /** The scheme this theme suggests ({@code @editor-scheme}) — offered, never forced. */
    @Nullable
    private final String editorScheme;
    @Nullable
    private final String author;
    /** The full CSS text — override rules are re-parsed from it at apply time, against the merged
     * variable table of the whole inheritance chain (a theme's own rules may use {@code var()}). */
    private final String source;
    /** This file's raw {@code --name: value} definitions, unresolved — resolution happens once, on
     * the merged chain, in {@code UiThemeManager.apply()}. */
    private final Map<String, String> variables;

    private UiTheme(String id, String displayName, Role role, Kind kind, @Nullable String parentId,
                    @Nullable String editorScheme, @Nullable String author, String source,
                    Map<String, String> variables) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
        this.kind = kind;
        this.parentId = parentId;
        this.editorScheme = editorScheme;
        this.author = author;
        this.source = source;
        this.variables = variables;
    }

    /**
     * Parses and validates a theme/scheme file. Throws {@link IllegalArgumentException} naming what
     * is wrong — the registry's refusal path; nothing else should catch this.
     */
    public static UiTheme parse(String source) {
        String themeName = null;
        String schemeName = null;
        String id = null;
        String kindText = null;
        String extendsText = null;
        String editorScheme = null;
        String author = null;

        Matcher tag = HEADER_TAG.matcher(source);
        while (tag.find()) {
            String value = cleanTagValue(tag.group(2));
            switch (tag.group(1)) {
                case "theme" -> themeName = value;
                case "scheme" -> schemeName = value;
                case "id" -> id = value;
                case "kind" -> kindText = value;
                case "extends" -> extendsText = value;
                case "editor-scheme" -> editorScheme = value;
                case "author" -> author = value;
            }
        }

        if (themeName == null && schemeName == null) {
            throw new IllegalArgumentException(
                    "missing '@theme <name>' or '@scheme <name>' header tag — the file declares neither artifact");
        }
        if (themeName != null && schemeName != null) {
            throw new IllegalArgumentException(
                    "both '@theme' and '@scheme' header tags present — a file is one artifact, not two");
        }
        Role role = themeName != null ? Role.THEME : Role.SCHEME;
        String displayName = themeName != null ? themeName : schemeName;
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("empty display name on the '@" +
                    (role == Role.THEME ? "theme" : "scheme") + "' tag");
        }

        if (id == null || !ID_SHAPE.matcher(id).matches()) {
            throw new IllegalArgumentException("missing or malformed '@id' — expected 'namespace:name', got '"
                    + id + "'");
        }

        if (kindText == null) {
            throw new IllegalArgumentException("missing '@kind' — expected dark, light, or high-contrast");
        }
        Kind kind = switch (kindText.toLowerCase(Locale.ROOT)) {
            case "dark" -> Kind.DARK;
            case "light" -> Kind.LIGHT;
            case "high-contrast" -> Kind.HIGH_CONTRAST;
            default -> throw new IllegalArgumentException(
                    "unknown '@kind " + kindText + "' — expected dark, light, or high-contrast");
        };

        String parentId = noneToNull(extendsText);
        if (parentId != null && !ID_SHAPE.matcher(parentId).matches()) {
            throw new IllegalArgumentException("malformed '@extends' — expected 'namespace:name' or '—', got '"
                    + parentId + "'");
        }

        // The poison check: parse the CSS now, with no external table, so a selector that would
        // throw does it HERE — at registration, where it becomes a refusal — rather than at apply,
        // where it would take the whole sheet down in front of the user.
        StyleSheet.parse(source, Map.of());

        return new UiTheme(id, displayName, role, kind, parentId, noneToNull(editorScheme), author,
                source, Map.copyOf(StyleSheet.variablesOf(source)));
    }

    /** Strips a trailing comment close and any {@code *}-decoration the tag value dragged along. */
    private static String cleanTagValue(String raw) {
        String value = raw.trim();
        if (value.endsWith("*/")) value = value.substring(0, value.length() - 2).trim();
        return value;
    }

    @Nullable
    private static String noneToNull(@Nullable String value) {
        if (value == null) return null;
        return switch (value) {
            case "", "—", "-", "none" -> null;
            default -> value;
        };
    }
}
