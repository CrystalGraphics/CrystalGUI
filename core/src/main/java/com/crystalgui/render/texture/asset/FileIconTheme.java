package com.crystalgui.render.texture.asset;

import com.crystalgraphics.util.io.CgIO;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.CgUiSvg;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Which icon a file gets — VS Code's file-icon-theme model, ported.
 *
 * <h3>A theme is JSON, so a resource pack ships one by shipping a file</h3>
 *
 * <p>Same contract {@link CgUiSpriteRegistry} already establishes: one JSON per theme at
 * {@code assets/{namespace}/ui/icons/{path}.json}, no registration call, and an override is a pack
 * shipping the same path. The values are icon names resolved through the same
 * {@code assets/{ns}/ui/icons/{name}.svg} rule {@code icon()} uses in CSS.</p>
 *
 * <pre>{@code
 * {
 *   "file": "crystalgui:file-text",
 *   "folder": "crystalgui:folder",
 *   "folderExpanded": "crystalgui:folder",
 *   "fileExtensions": { "java": "crystalgui:code", "png": "crystalgui:image" },
 *   "fileNames":      { "package.json": "crystalgui:package" },
 *   "folderNames":    { "src": "crystalgui:folder" }
 * }
 * }</pre>
 *
 * <h3>Resolution order is VS Code's, and the extension match is longest-first</h3>
 *
 * <p>Exact file name, then extension, then the generic default. The order matters — {@code package.json}
 * has to beat {@code json} — and so does trying the <b>longest</b> extension first: a file named
 * {@code types.d.ts} should match {@code d.ts} rather than {@code ts}, and matching on "everything after
 * the last dot" makes that impossible to express at all.</p>
 *
 * <h3>Colour is not in here, deliberately</h3>
 *
 * <p>A theme states which icon, never what colour. Colour comes from the cascade, through the class
 * {@link #classFor} returns — {@code .filetype-java { color: #E76F00; }} — for the same reason
 * {@code NodePort} reads its wire colour back out of the dot's computed {@code border-color} instead of
 * holding a palette in Java. It also buys real expressiveness: half a dozen languages share the one
 * {@code code} icon and can still each have their own colour, which keying colour to the icon could not
 * do.</p>
 *
 * <p>The class keys on what was <b>matched</b>, not on what it resolved to, and it comes from here rather
 * than from the caller so that the two agree about {@code d.ts}.</p>
 */
public final class FileIconTheme {

    private static final Gson GSON = new Gson();
    private static final ConcurrentHashMap<String, FileIconTheme> CACHE = new ConcurrentHashMap<>();

    /** The theme a resource pack overrides by shipping {@code assets/{ns}/ui/icons/default.json}. */
    public static final String DEFAULT_PATH = "crystalgui:default";

    private final String fileIcon;
    private final String folderIcon;
    private final String folderExpandedIcon;
    private final Map<String, String> fileExtensions;
    private final Map<String, String> fileNames;
    private final Map<String, String> folderNames;

    private FileIconTheme(String fileIcon, String folderIcon, String folderExpandedIcon,
                          Map<String, String> fileExtensions, Map<String, String> fileNames,
                          Map<String, String> folderNames) {
        this.fileIcon = fileIcon;
        this.folderIcon = folderIcon;
        this.folderExpandedIcon = folderExpandedIcon;
        this.fileExtensions = fileExtensions;
        this.fileNames = fileNames;
        this.folderNames = folderNames;
    }

    /** An empty theme — every lookup falls through to null. What a missing or malformed file resolves to. */
    private static final FileIconTheme EMPTY = new FileIconTheme(
            null, null, null, Map.of(), Map.of(), Map.of());

    public static FileIconTheme getDefault() {
        return of(DEFAULT_PATH);
    }

    /** Loads and caches a theme. Never null — a missing file yields a theme that resolves nothing. */
    public static FileIconTheme of(String themePath) {
        return CACHE.computeIfAbsent(themePath, FileIconTheme::load);
    }

    /** Drops every cached theme. Not wired to resource reload yet, same as {@code SvgDocument.of}. */
    public static void invalidateCache() {
        CACHE.clear();
        DARK_VARIANTS.clear();
    }

    // ── Light and dark drawings ─────────────────────────────────────────────────────────────────────

    /**
     * Which of an icon's two drawings to use.
     *
     * <p>JetBrains ships each icon twice — {@code java.svg} and {@code java_dark.svg} — because an icon
     * carries its own palette (that is the whole reason {@code filetypes.css} does not tint it), so it
     * cannot be recoloured for a dark background the way a monochrome glyph can. The two files are
     * genuinely different drawings, not one drawing inverted.</p>
     */
    public enum Variant {

        LIGHT(""),
        DARK("_dark");

        private final String suffix;

        Variant(String suffix) {
            this.suffix = suffix;
        }

        /** Appended to an icon name to reach this variant's file. Empty for {@link #LIGHT}. */
        public String suffix() {
            return suffix;
        }
    }

    /**
     * Which drawing of each icon is in use — <b>driven by the active theme's {@code @kind}</b>.
     *
     * <p>This field carried a note saying it was provisional because "there is no editor-theme concept
     * yet, so there is nothing for an icon variant to be a property <em>of</em>". Themes have landed, and
     * {@code UiThemeManager} now sets this on every swap. It stays a <em>static</em> rather than becoming
     * a field on the theme, because that note's other half still holds: the consumers are static
     * utilities ({@link #withVariant}, {@link #classFor}) reached from row-binding code that has no theme
     * in hand, and threading one through them would be plumbing in service of a purity nothing needs.
     * One global switch remains the honest representation of "the whole application is light or dark".</p>
     *
     * <p>{@code volatile} because a theme switch does not happen on the render thread and a stale read
     * would show one row's icon from the old variant.</p>
     *
     * <p><b>A change here needs the rows rebound to be seen</b> — the icon name is chosen in {@code bind},
     * not by the cascade, so restyling alone will not repaint it. {@code WorkbenchSettings.apply} refreshes
     * the file tree immediately after applying the theme, which is what makes the swap visible.</p>
     */
    private static volatile Variant variant = Variant.DARK;

    /** Icon name → whether that name's file is actually on disk; see {@link #withVariant}. */
    private static final ConcurrentHashMap<String, Boolean> DARK_VARIANTS = new ConcurrentHashMap<>();

    public static Variant getVariant() {
        return variant;
    }

    public static void setVariant(Variant newVariant) {
        variant = Objects.requireNonNull(newVariant, "variant");
    }

    /**
     * An icon name with the active variant applied — {@code "…/java"} to {@code "…/java_dark"}.
     *
     * <h3>Why a suffix convention rather than VS Code's {@code light} override block</h3>
     *
     * <p>VS Code's file-icon themes carry a whole second copy of the mapping under a {@code light} key,
     * because its icons are referenced by arbitrary {@code iconDefinitions} ids with no naming rule — so
     * there is no way to <em>derive</em> one variant's id from the other's. JetBrains' assets do have that
     * rule, which lets the same thing be said once per theme instead of once per entry. Sixty duplicated
     * lines is not a neutral cost: it is sixty chances for the two halves to disagree, and a theme that
     * maps {@code .yaml} in one block and forgets it in the other is broken only in one colour scheme.</p>
     *
     * <p><b>Falls back rather than failing.</b> Not every icon has a dark drawing — a shape that reads on
     * both backgrounds ships once — so a missing file means "this one is variant-neutral", which is a
     * legitimate thing for an icon to be and not an authoring error to warn about. The probe is cached
     * per name because it answers a question about the jar, which does not change while it is running.</p>
     */
    @Nullable
    public static String withVariant(@Nullable String iconName) {
        if (iconName == null || variant == Variant.LIGHT) return iconName;
        String candidate = iconName + variant.suffix();
        return DARK_VARIANTS.computeIfAbsent(candidate, FileIconTheme::resourceExists)
                ? candidate : iconName;
    }

    private static boolean resourceExists(String iconName) {
        try (InputStream stream = CgIO.openStream(toResourcePath(iconName))) {
            return stream != null;
        } catch (IOException unreadable) {
            return false;
        }
    }

    // ── Lookup ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The icon name for a file or folder, or null when the theme has nothing for it <i>and</i> no default.
     *
     * @param name      the last path segment — {@code "Main.java"}, not the whole path
     * @param directory whether this is a folder
     * @param expanded  a folder that is open, so a theme can show a different icon. Ignored for files
     */
    @Nullable
    public String iconFor(String name, boolean directory, boolean expanded) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (directory) {
            String named = folderNames.get(key);
            if (named != null) return named;
            // folderExpanded falls back to folder rather than to nothing: a theme that only draws one
            // folder icon is the common case, and requiring both would make it state the same value twice.
            return expanded && folderExpandedIcon != null ? folderExpandedIcon : folderIcon;
        }
        String named = fileNames.get(key);
        if (named != null) return named;
        String extension = matchExtension(key, fileExtensions);
        return extension != null ? fileExtensions.get(extension) : fileIcon;
    }

    /**
     * Every distinct icon this theme can produce, in no particular order.
     *
     * <p>Exists so a test can walk a theme rather than restate it. A hand-written list of "the icons we
     * ship" is two copies of the same fact, and the copy in the test is the one that goes stale — the
     * theme grows an entry, nothing checks the file exists, and the first anyone hears of it is a blank
     * row in a file tree.</p>
     */
    public Set<String> iconNames() {
        Set<String> out = new LinkedHashSet<>();
        if (fileIcon != null) out.add(fileIcon);
        if (folderIcon != null) out.add(folderIcon);
        if (folderExpandedIcon != null) out.add(folderExpandedIcon);
        out.addAll(fileExtensions.values());
        out.addAll(fileNames.values());
        out.addAll(folderNames.values());
        return out;
    }

    /**
     * The icon as a drawable, ready for {@code style().general().overlay(...)}. Null when unresolvable.
     *
     * <p>The active {@link Variant} is applied here rather than in {@link #iconFor}, so a caller asking
     * which icon a theme <em>maps</em> to gets the theme's own answer while a caller asking for something
     * to draw gets the drawing that suits the background. Every consumer in the engine goes through this
     * one, which is why the variant needs no plumbing through {@code ProjectFileTree}.</p>
     */
    @Nullable
    public CgUiSvg drawableFor(String name, boolean directory, boolean expanded) {
        String icon = withVariant(iconFor(name, directory, expanded));
        return icon == null ? null : CgUiSvg.of(toResourcePath(icon));
    }

    /**
     * The CSS class a row carries so a stylesheet can colour it — {@code "filetype-java"}.
     *
     * <p>Keyed on the match, not the icon, so two languages sharing one glyph keep separate colours. A
     * file with no recognised extension gets {@code filetype-file}, which is a hook rather than an
     * absence — a theme wanting one colour for "everything else" needs something to name.</p>
     */
    public String classFor(String name, boolean directory) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (directory) return "filetype-folder";
        if (fileNames.containsKey(key)) return "filetype-" + sanitise(key);
        String extension = matchExtension(key, fileExtensions);
        return extension != null ? "filetype-" + sanitise(extension) : "filetype-file";
    }

    /**
     * The longest registered extension this name ends with, or null.
     *
     * <p>Walks the dots left to right, so {@code types.d.ts} tries {@code d.ts} before {@code ts}. The
     * leading dot is required — a file literally named {@code ts} is not a TypeScript file, and matching
     * on a bare suffix would say it was.</p>
     */
    @Nullable
    private static String matchExtension(String name, Map<String, String> extensions) {
        int at = name.indexOf('.');
        while (at >= 0 && at + 1 < name.length()) {
            String candidate = name.substring(at + 1);
            if (extensions.containsKey(candidate)) return candidate;
            at = name.indexOf('.', at + 1);
        }
        return null;
    }

    /** Everything that is not a letter or digit becomes a dash, so the result is a legal class token. */
    private static String sanitise(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : '-');
        }
        return out.toString();
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────────────

    private static FileIconTheme load(String themePath) {
        String json = CgIO.loadSource(toThemeResourcePath(themePath));
        if (json == null) {
            CrystalGuiCore.LOGGER.warn("FileIconTheme: no theme at '{}'", themePath);
            return EMPTY;
        }
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            return new FileIconTheme(
                    optString(root, "file"),
                    optString(root, "folder"),
                    optString(root, "folderExpanded"),
                    optMap(root, "fileExtensions"),
                    optMap(root, "fileNames"),
                    optMap(root, "folderNames"));
        } catch (RuntimeException malformed) {
            CrystalGuiCore.LOGGER.warn("FileIconTheme: failed to parse '{}': {}",
                    themePath, malformed.getMessage());
            return EMPTY;
        }
    }

    /**
     * {@code "ns:name"} to {@code "ns:ui/icons/name.svg"} — the one definition of where an icon lives.
     *
     * <p>Shared with {@code TextureValue}'s {@code icon()} keyword rather than duplicated, so a stylesheet
     * and a theme can never disagree about the path a name resolves to. Namespace defaults to
     * {@code crystalgui}, matching {@link CgUiSpriteRegistry}.</p>
     */
    public static String toResourcePath(String iconName) {
        int colon = iconName.indexOf(':');
        String namespace = colon < 0 ? "crystalgui" : iconName.substring(0, colon);
        String path = colon < 0 ? iconName : iconName.substring(colon + 1);
        return namespace + ":ui/icons/" + path + ".svg";
    }

    private static String toThemeResourcePath(String themePath) {
        int colon = themePath.indexOf(':');
        String namespace = colon < 0 ? "crystalgui" : themePath.substring(0, colon);
        String path = colon < 0 ? themePath : themePath.substring(colon + 1);
        return namespace + ":ui/icons/" + path + ".json";
    }

    @Nullable
    private static String optString(JsonObject root, String field) {
        return root.has(field) ? root.get(field).getAsString() : null;
    }

    /**
     * A theme's map, with comma-grouped keys expanded.
     *
     * <h3>One line may register many keys</h3>
     *
     * <p>{@code "png, jpg, jpeg, gif": "…/image"} registers four extensions against one icon, and is
     * exactly equivalent to writing four entries. Nine image formats and seven archive formats share one
     * glyph each, so the ungrouped form spends thirty lines saying "and this one too" — and a reader
     * scanning for what a theme covers has to hold thirty lines in their head to learn there are two
     * groups. VS Code's own themes have this shape and no such shorthand, which is why theirs run to
     * several hundred lines.</p>
     *
     * <p>Splitting happens at <b>load</b>, so nothing downstream knows it exists: {@link #iconFor} still
     * sees one key per extension, and a theme written the long way behaves identically. Grouping is
     * therefore a convenience for whoever writes the file and not a concept the engine carries.</p>
     *
     * <p>Whitespace around a member is trimmed, so the separator can be {@code ", "} and stay readable.
     * An empty member — a trailing comma, a doubled one — is skipped rather than registered as the empty
     * key, which would otherwise match every name with a leading dot.</p>
     */
    private static Map<String, String> optMap(JsonObject root, String field) {
        if (!root.has(field)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : root.getAsJsonObject(field).entrySet()) {
            String icon = entry.getValue().getAsString();
            for (String key : entry.getKey().split(",")) {
                String trimmed = key.trim();
                if (trimmed.isEmpty()) continue;
                // Lower-cased on the way IN, so every lookup can lower-case once and compare directly
                // rather than scanning. File systems disagree about case and users disagree with both.
                out.put(trimmed.toLowerCase(Locale.ROOT), icon);
            }
        }
        return new HashMap<>(out);
    }
}
