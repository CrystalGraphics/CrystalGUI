package com.crystalgui.core.settings;

import com.crystalgui.core.async.Published;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/**
 * What the user chose for the whole desktop, whichever application they chose it in: the theme and the editor scheme,
 * token overrides, the UI scale, the language and the keymap. One immutable value, published; the desktop's shell
 * persists it ({@link PreferencesStore}).
 *
 * <pre>{@code
 * // Anyone, any thread
 * float desktop = Preferences.current().desktopScale();      // a document's scale is UiScale's, not this
 *
 * // An application's settings page: writing a desktop-wide setting is writing the preference
 * settings.set(SettingsLayer.USER, WorkbenchSettings.UI_THEME, "Crystal Light");
 *
 * // Directly
 * Preferences.update(p -> p.withDesktopScale(1.5f).withZoom("crystalgui:editor", 1.25f));
 *
 * // A document hears every change on its own sequence
 * Preferences.PUBLISHED.changes().subscribe(() -> document.invalidateStyle());
 * }</pre>
 *
 * <p>It is the bottom of every document's settings chain: a {@link Settings} resolves a desktop-wide key
 * ({@link #declare}) from here and from no layer of its own, so a change made in one application is the value in
 * every other. Application preferences stay the application's, in its own document's settings.</p>
 *
 * <p>Easy to get wrong: a key is desktop-wide only once it is {@linkplain #declare declared}; an undeclared key
 * written to a {@code Settings} stays in that document's layer.</p>
 */
public final class Preferences {

    public static final String THEME = "appearance.theme";
    public static final String SCHEME = "appearance.editorScheme";
    /** The desktop's scale, multiplied with the screen's. */
    public static final String DESKTOP_SCALE = "appearance.scale";
    public static final String LOCALE = "appearance.locale";
    /** {@code appearance.zoom.<application id>}: one application's zoom, multiplied with the desktop's scale. */
    public static final String ZOOM_PREFIX = "appearance.zoom.";
    private static final String LEGACY_UI_SCALE = "appearance.uiScale";
    private static final String OVERRIDE_PREFIX = "appearance.token.";
    private static final String KEYMAP_PREFIX = "keymap.";
    private static final String NOTIFICATIONS_PREFIX = "notifications.";

    /** The published preferences. */
    public static final Published<Preferences> PUBLISHED = Published.of("preferences", new Preferences(Map.of()));

    private static final Set<String> DESKTOP_WIDE = ConcurrentHashMap.newKeySet();

    static {
        declare(THEME);
        declare(SCHEME);
        declare(DESKTOP_SCALE);
        declare(LOCALE);
    }

    private final Map<String, String> values;

    private Preferences(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** The preferences this thread reads: its frame's pin, else the latest. */
    public static Preferences current() {
        return PUBLISHED.current();
    }

    /** Builds and publishes the next preferences. Returning the same instance changes nothing. */
    public static Preferences update(UnaryOperator<Preferences> change) {
        Preferences before = PUBLISHED.latest().value();
        Preferences next = PUBLISHED.update(change);
        if (next != before) PreferencesStore.save(next);
        return next;
    }

    /** Makes {@code key} desktop-wide: every {@link Settings} resolves and writes it here. */
    public static void declare(String key) {
        DESKTOP_WIDE.add(key);
    }

    /** Whether {@code key} is a desktop-wide preference. */
    public static boolean isDesktopWide(String key) {
        return DESKTOP_WIDE.contains(key) || key.startsWith(OVERRIDE_PREFIX) || key.startsWith(KEYMAP_PREFIX)
                || key.startsWith(NOTIFICATIONS_PREFIX) || key.startsWith(ZOOM_PREFIX);
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Nullable
    public String raw(String key) {
        return values.get(key);
    }

    /** Every stored key and its text, in the order it was written. */
    public Map<String, String> asMap() {
        return values;
    }

    /** The theme setting's text (a display name), or null for the default. */
    @Nullable
    public String theme() {
        return values.get(THEME);
    }

    @Nullable
    public String scheme() {
        return values.get(SCHEME);
    }

    /** The user's own values for theme tokens, {@code --name} to CSS text. */
    public Map<String, String> tokenOverrides() {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(OVERRIDE_PREFIX)) out.put("--" + key.substring(OVERRIDE_PREFIX.length()), value);
        });
        return out;
    }

    /** The desktop's scale, on top of the screen's; 1 where the user never chose one. */
    public float desktopScale() {
        return parse(values.get(DESKTOP_SCALE), 1f);
    }

    /** {@code app}'s zoom, on top of the desktop's scale; 1 where it was never zoomed. */
    public float zoom(String app) {
        return parse(values.get(ZOOM_PREFIX + app), 1f);
    }

    /** Every application someone zoomed, to its zoom. */
    public Map<String, Float> zooms() {
        Map<String, Float> out = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(ZOOM_PREFIX)) out.put(key.substring(ZOOM_PREFIX.length()), parse(value, 1f));
        });
        return out;
    }

    private static float parse(@Nullable String held, float otherwise) {
        if (held == null) return otherwise;
        try {
            String number = held.trim();
            boolean percent = number.endsWith("%");
            float value = Float.parseFloat(percent ? number.substring(0, number.length() - 1).trim() : number);
            if (percent) value /= 100f;
            return value > 0f ? value : otherwise;
        } catch (NumberFormatException unreadable) {
            return otherwise;
        }
    }

    /** The language, or null while the platform's is in force. */
    @Nullable
    public Locale locale() {
        String held = values.get(LOCALE);
        return held == null ? null : Locale.forLanguageTag(held);
    }

    /** The user's chords per command id; a command listed here has these and none of its declared ones. */
    public Map<String, List<String>> keymap() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!key.startsWith(KEYMAP_PREFIX)) return;
            List<String> chords = new ArrayList<>();
            for (String chord : value.split(";")) {
                if (!chord.isBlank()) chords.add(chord.trim());
            }
            out.put(key.substring(KEYMAP_PREFIX.length()), chords);
        });
        return out;
    }

    // ── The next generation ─────────────────────────────────────────────────────────────────────

    /** These preferences with {@code key} set to {@code value}, or removed when null. This one when nothing moves. */
    public Preferences with(String key, @Nullable String value) {
        if (Objects.equals(values.get(key), value)) return this;
        Map<String, String> next = new LinkedHashMap<>(values);
        if (value == null) next.remove(key);
        else next.put(key, value);
        return new Preferences(next);
    }

    /** These preferences with exactly {@code all}: what a load from disk publishes. */
    public Preferences withAll(Map<String, String> all) {
        return values.equals(all) ? this : new Preferences(all);
    }

    /** The desktop's scale; 1 is stored as no entry. */
    public Preferences withDesktopScale(float scale) {
        return with(DESKTOP_SCALE, scale == 1f ? null : percent(scale));
    }

    /** {@code app}'s zoom; 1 is stored as no entry, so the file lists only the applications someone zoomed. */
    public Preferences withZoom(String app, float zoom) {
        return with(ZOOM_PREFIX + app, zoom == 1f ? null : percent(zoom));
    }

    /**
     * Before UI16 the one stored scale was real pixels per unit at lengths written for 2; it becomes the desktop's
     * scale at half the value. A load from disk passes through here.
     */
    static Map<String, String> migrated(Map<String, String> loaded) {
        String legacy = loaded.get(LEGACY_UI_SCALE);
        if (legacy == null) return loaded;
        Map<String, String> next = new LinkedHashMap<>(loaded);
        next.remove(LEGACY_UI_SCALE);
        float half = parse(legacy, 2f) / 2f;
        if (half != 1f && !next.containsKey(DESKTOP_SCALE)) next.put(DESKTOP_SCALE, percent(half));
        return next;
    }

    /** A scale as it is stored and shown, {@code "125%"}; read back by {@link #desktopScale} and {@link #zoom}. */
    public static String percent(float scale) {
        return Math.round(scale * 100f) + "%";
    }

    public Preferences withLocale(@Nullable Locale locale) {
        return with(LOCALE, locale == null ? null : locale.toLanguageTag());
    }

    public Preferences withTokenOverride(String token, @Nullable String css) {
        String name = token.startsWith("--") ? token.substring(2) : token;
        return with(OVERRIDE_PREFIX + name, css);
    }

    /** The user's chords for {@code commandId}; an empty list unbinds it, null returns it to its declared ones. */
    public Preferences withKeys(String commandId, @Nullable List<String> chords) {
        return with(KEYMAP_PREFIX + commandId, chords == null ? null : String.join(";", chords));
    }
}
