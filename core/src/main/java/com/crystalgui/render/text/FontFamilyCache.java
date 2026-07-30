package com.crystalgui.render.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@code font-family} path list (see {@code GeneralGroup#fontFamily()}) plus a
 * concrete pixel size into a real {@link CgFontFamily} — a genuine primary+fallback chain built
 * via {@link CgFontFamily#of}, not a bare single {@link CgFont}, so {@code CgFontFamily}'s own
 * per-character fallback resolution (codepoint coverage across sources) actually has more than
 * one source to fall back to.
 *
 * <p>Cached by {@code (paths, targetPx)} — {@link CgFontFamily} requires every source in one
 * family to share a single target pixel size, so rebuilding the family on every paint call (or
 * every distinct font-size an element happens to use) would be wasteful; loading is the expensive
 * part (reads + parses font file bytes), not the family construction itself.</p>
 */
public final class FontFamilyCache {

    private static final Map<String, CgFontFamily> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CgFontFamilyGroup> GROUP_CACHE = new ConcurrentHashMap<>();

    private FontFamilyCache() {
    }

    /** Resolves (and caches) the {@link CgFontFamily} for {@code paths} at {@code targetPx}. Throws
     * if the primary path (first in the list) fails to load — a font-family with no usable primary
     * source can't render anything, matching this codebase's fail-fast convention. A fallback path
     * that fails to load is skipped with a warning rather than failing the whole family, since a
     * missing fallback still leaves a working (if less complete) family behind. */
    public static CgFontFamily resolve(List<String> paths, int targetPx) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("paths must not be null/empty");
        }
        String key = targetPx + "@" + String.join(",", paths);
        return CACHE.computeIfAbsent(key, ignored -> build(paths, targetPx));
    }

    /**
     * The same family wrapped as a {@link CgFontFamilyGroup} — what styled text needs, because
     * {@code bold}/{@code italic} select a <em>face</em> and a bare {@link CgFontFamily} has only one.
     *
     * <p>Built with {@link CgFontFamilyGroup#ofRegular}, so bold and italic are <b>synthesised</b> rather
     * than loaded: {@link #loadFont} loads every source as {@link CgFontStyle#REGULAR}, and CSS
     * {@code font-family} as this engine parses it is a list of asset paths with no way to say "and this
     * one is the bold face". Synthesis is the honest answer until that grammar exists — the backend
     * carries {@code syntheticBold}/{@code syntheticItalic} through to the glyph key for exactly this.</p>
     *
     * <p>Cached under the same {@code (paths, targetPx)} key as {@link #resolve}, which matters for more
     * than allocation: {@code UIText} memoises its shaped paragraph on <b>reference equality</b> of what
     * it resolved, so a group rebuilt per call would re-shape the text on every single frame.</p>
     */
    public static CgFontFamilyGroup resolveGroup(List<String> paths, int targetPx) {
        CgFontFamily family = resolve(paths, targetPx);
        return GROUP_CACHE.computeIfAbsent(targetPx + "@" + String.join(",", paths),
                ignored -> CgFontFamilyGroup.ofRegular(family));
    }

    private static CgFontFamily build(List<String> paths, int targetPx) {
        CgFont primary = loadFont(paths.get(0), targetPx);
        if (primary == null) {
            throw new IllegalStateException("FontFamilyCache: primary font-family source failed to load: " + paths.get(0));
        }

        List<CgFont> fallbacks = new ArrayList<>();
        for (int i = 1; i < paths.size(); i++) {
            CgFont fallback = loadFont(paths.get(i), targetPx);
            if (fallback != null) {
                fallbacks.add(fallback);
            } else {
                CrystalGuiCore.LOGGER.warn("FontFamilyCache: fallback font-family source failed to load, skipping: {}", paths.get(i));
            }
        }
        return CgFontFamily.of(primary, fallbacks.toArray(new CgFont[0]));
    }

    private static CgFont loadFont(String path, int targetPx) {
        InputStream in = CgIO.openStream(path);
        if (in == null) {
            CrystalGuiCore.LOGGER.warn("FontFamilyCache: font asset not found: {}", path);
            return null;
        }
        try {
            byte[] data = readAllBytes(in);
            return CgFont.load(data, path, CgFontStyle.REGULAR, targetPx);
        } catch (IOException e) {
            CrystalGuiCore.LOGGER.warn("FontFamilyCache: failed to read font asset '{}': {}", path, e.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing meaningful to do — the font either loaded successfully above or we're
                // already handling a failure; a close failure on a read-only stream isn't actionable.
            }
        }
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
