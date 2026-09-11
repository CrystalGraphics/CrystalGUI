package com.crystalgui.render.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@code font-family} stack and a pixel size into a {@link CgFontFamily}, cached.
 *
 * <p>An entry is one of three things, as in a browser:</p>
 * <pre>{@code
 * font-family: "crystalgui:ui/fonts/JetBrainsMono-Regular.ttf",   // a resource path: has ':' or '/', or a font extension
 *              "Cascadia Code",                                    // an installed family, by name
 *              monospace;                                          // a generic family: what this platform means by it
 * }</pre>
 *
 * <p>The first entry that loads is the primary and the rest supply glyphs it lacks. Anything none
 * of them covers comes from the installed fonts, per script, so 日本語 and العربية draw under a
 * Latin-only stack the way they do in any application — see {@link CgSystemFonts}.</p>
 *
 * <ul>
 *   <li>Cached by {@code (stack, targetPx)}; {@code UIText} relies on getting the same instance back.</li>
 *   <li>{@code -Dcrystalgui.font.systemFonts=false} turns off names, generic families and the implicit
 *       fallback, leaving resource paths. The tests run that way, so no result depends on the machine.</li>
 *   <li>A stack where nothing loads draws in the platform's sans-serif; with system fonts off it throws.</li>
 *   <li>A generic is recognised quoted or not — the parser drops quotes — so {@code "serif"} is the
 *       generic, not a font named serif.</li>
 * </ul>
 */
public final class FontFamilyCache {

    private static final Map<String, CgFontFamily> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CgFontFamilyGroup> GROUP_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Optional<CgFont>> RESOURCE_FONTS = new ConcurrentHashMap<>();

    private static volatile CgSystemFonts systemFonts =
            Boolean.parseBoolean(System.getProperty("crystalgui.font.systemFonts", "true")) ? CgSystemFonts.get() : null;

    private static volatile Locale locale = Locale.getDefault();

    private FontFamilyCache() {
    }

    /**
     * Where family names, generic families and the implicit fallback come from; {@code null} turns
     * all three off. Clears the cache; a family resolved before keeps the fonts it had.
     *
     * <pre>{@code
     * FontFamilyCache.useSystemFonts(CgSystemFonts.of(List.of(fontsDirectory)));   // a test's own "installed" fonts
     * }</pre>
     */
    public static void useSystemFonts(@Nullable CgSystemFonts fonts) {
        systemFonts = fonts;
        CACHE.clear();
        GROUP_CACHE.clear();
    }

    /**
     * The language the player reads; {@code DesktopHost} pushes the game's here every frame. It decides
     * how a Han character nothing in the stack covers is drawn — Japanese, Simplified, Traditional or
     * Korean — when its own text does not say. A change clears the cache; the same locale again is free.
     *
     * <pre>{@code
     * FontFamilyCache.useLocale(Locale.forLanguageTag("ja-JP"));
     * }</pre>
     */
    public static void useLocale(@Nullable Locale newLocale) {
        if (newLocale == null || newLocale.equals(locale)) {
            return;
        }
        locale = newLocale;
        CACHE.clear();
        GROUP_CACHE.clear();
    }

    /** The family for {@code stack} at {@code targetPx}; throws only when no font at all can be found. */
    public static CgFontFamily resolve(List<String> stack, int targetPx) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("stack must not be null/empty");
        }
        return CACHE.computeIfAbsent(key(stack, targetPx), ignored -> build(stack, targetPx, CgFontStyle.REGULAR));
    }

    /**
     * The same stack as a {@link CgFontFamilyGroup}, for styled text. Bold and italic use a real face
     * when the primary is an installed family that has one — loaded the first time a span is bold or
     * italic — and are synthesised otherwise: a resource path names one face, and the grammar has no
     * way to name its bold.
     *
     * <p>Cached under {@link #resolve}'s key — {@code UIText} memoises its shaped paragraph on reference
     * equality, so a group rebuilt per call would re-shape every frame.</p>
     */
    public static CgFontFamilyGroup resolveGroup(List<String> stack, int targetPx) {
        CgFontFamily regular = resolve(stack, targetPx);
        return GROUP_CACHE.computeIfAbsent(key(stack, targetPx), ignored -> group(stack, targetPx, regular));
    }

    private static CgFontFamilyGroup group(List<String> stack, int targetPx, CgFontFamily regular) {
        boolean primaryIsResource = stack.contains(regular.getPrimaryFont().getLogicalName());
        if (systemFonts == null || primaryIsResource) {
            return CgFontFamilyGroup.ofRegular(regular);
        }
        return CgFontFamilyGroup.lazy(regular, style -> {
            CgFontFamily styled = build(stack, targetPx, style);
            // The same primary instance means the family has no such face; synthesis answers then.
            return styled.getPrimaryFont() != regular.getPrimaryFont() ? styled : null;
        });
    }

    private static CgFontFamily build(List<String> stack, int targetPx, CgFontStyle style) {
        CgSystemFonts fonts = systemFonts;
        List<CgFont> loaded = new ArrayList<>();
        Set<CgFontKey> keys = new HashSet<>();
        for (String entry : stack) {
            CgFont font = load(entry, targetPx, style, fonts);
            if (font == null) {
                // A missing asset has warned already; an absent installed family is ordinary.
                CrystalGuiCore.LOGGER.debug("FontFamilyCache: font-family entry did not load, trying the next: {}", entry);
            } else if (keys.add(font.getKey())) {
                loaded.add(font);
            }
        }
        if (loaded.isEmpty() && fonts != null) {
            CgSystemFontFace sans = fonts.generic(CgGenericFamily.SANS_SERIF, style);
            if (sans != null) {
                loaded.add(fonts.load(sans, style, targetPx));
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("FontFamilyCache: no font-family entry could be loaded: " + stack);
        }
        CgFontFamily family = CgFontFamily.of(loaded.get(0), loaded.subList(1, loaded.size()).toArray(new CgFont[0]));
        return fonts == null ? family : family.withFallback(fonts.fallback(locale));
    }

    private static CgFont load(String entry, int targetPx, CgFontStyle style, @Nullable CgSystemFonts fonts) {
        if (isResourcePath(entry)) {
            return loadResource(entry, targetPx);
        }
        if (fonts == null) {
            return null;
        }
        CgGenericFamily generic = CgGenericFamily.fromCss(entry);
        CgSystemFontFace face = generic != null ? fonts.generic(generic, style) : fonts.find(entry, style);
        if (face == null) {
            return null;
        }
        try {
            return fonts.load(face, style, targetPx);
        } catch (RuntimeException e) {
            CrystalGuiCore.LOGGER.warn("FontFamilyCache: installed font '{}' failed to load: {}", face, e.getMessage());
            return null;
        }
    }

    /** A path to a font file rather than a family name: {@code crystalgui:ui/fonts/x.ttf}, {@code C:/x.ttf}, {@code x.otf}. */
    static boolean isResourcePath(String entry) {
        String lower = entry.toLowerCase(Locale.ROOT);
        return entry.indexOf(':') >= 0 || entry.indexOf('/') >= 0 || entry.indexOf('\\') >= 0
                || lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc") || lower.endsWith(".otc");
    }

    private static CgFont loadResource(String path, int targetPx) {
        return RESOURCE_FONTS.computeIfAbsent(targetPx + "@" + path,
                ignored -> Optional.ofNullable(readResource(path, targetPx))).orElse(null);
    }

    private static CgFont readResource(String path, int targetPx) {
        InputStream in = CgIO.openStream(path);
        if (in == null) {
            CrystalGuiCore.LOGGER.warn("FontFamilyCache: font asset not found: {}", path);
            return null;
        }
        try {
            return CgFont.load(readAllBytes(in), path, CgFontStyle.REGULAR, targetPx);
        } catch (IOException e) {
            CrystalGuiCore.LOGGER.warn("FontFamilyCache: failed to read font asset '{}': {}", path, e.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // a read-only stream failing to close changes nothing about what was read
            }
        }
    }

    private static String key(List<String> stack, int targetPx) {
        return targetPx + "@" + String.join(",", stack);
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
