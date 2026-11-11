package com.crystalgui.render;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.loading.task.CgLoadStep;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What painting loads before the first frame, each off the render thread: the default font, then the shipped icons and
 * the stylesheets' glyphs. A load task's step, waiting a frame at a time; the GPU half is the desktop's rehearsal.
 *
 * <pre>{@code
 * plan.task("crystalgui:paint").render(UiPaintWarmup.preload()).start();   // CgUiLifecycle.onLoad
 * }</pre>
 *
 * <ul>
 *   <li>A failure is logged and the step finishes: whatever did not load loads where it is first drawn.</li>
 *   <li>The glyphs are waited for {@link #GLYPH_WAIT_NANOS} at most, so a font that never settles cannot hold the
 *       loading screen.</li>
 * </ul>
 */
public final class UiPaintWarmup implements CgLoadStep {

    /**
     * The {@code uiScale} glyphs are warmed at: the host's (Minecraft sets 2). A glyph is keyed by the size it is
     * rasterised at, {@code font-size * uiScale}, so a warm at another scale fills entries no draw looks up.
     */
    static final float UI_SCALE = 2f;

    /** How long a warm waits for the sheets' families at most, so a font that never settles cannot hold the loading screen. */
    static final long GLYPH_WAIT_NANOS = 5_000_000_000L;

    /**
     * The face anything drawing text without the cascade gets: {@code StylePropertyRegistry.FONT_FAMILY}'s order, so
     * no widget differs from the rest with nothing in a sheet to explain it. The first that loads wins.
     */
    private static final String[] DEFAULT_FONT_STACK = {"crystalgui:ui/fonts/IBMPlexSans-Regular.ttf"};

    @Nullable
    private static volatile CgFont defaultFont;

    @Nullable
    private CgJob<CgFont> font;
    @Nullable
    private CgJob<?> glyphs;
    private long glyphsDeadline;

    private UiPaintWarmup() {}

    /** A fresh step: the font, then the icons and glyphs. One per load. */
    public static UiPaintWarmup preload() {
        return new UiPaintWarmup();
    }

    @Override
    public boolean step(CgLoadTask task) {
        if (font == null) {
            font = CgJob.lane(CgJobLane.BACKGROUND).named("cgui default font").run(UiPaintWarmup::defaultFont);
        }
        if (!font.isDone()) {
            task.nextFrame();
            return false;
        }
        if (glyphsDeadline == 0L) {
            preloadIcons();
            glyphs = warmGlyphs(UI_SCALE);
            glyphsDeadline = System.nanoTime() + GLYPH_WAIT_NANOS;
            task.progress(0.5f);
        }
        if (glyphs != null && !glyphs.isDone() && System.nanoTime() <= glyphsDeadline) {
            task.nextFrame();
            return false;
        }
        return true;
    }

    /** The default font, read once for every context: on a worker by the warm, else by the first context made. */
    static CgFont defaultFont() {
        CgFont f = defaultFont;
        if (f != null) return f;
        synchronized (UiPaintWarmup.class) {
            if (defaultFont == null) defaultFont = loadDefaultFont();
            return defaultFont;
        }
    }

    private static CgFont loadDefaultFont() {
        // Walks the stack, so a face can be named before it ships.
        for (String candidate : DEFAULT_FONT_STACK) {
            CgFont loaded = tryLoadFont(candidate);
            if (loaded != null) return loaded;
        }
        throw new IllegalStateException("no default font asset could be loaded: " + Arrays.toString(DEFAULT_FONT_STACK));
    }

    /** Null when the asset is absent; a corrupt one throws. */
    @Nullable
    private static CgFont tryLoadFont(String asset) {
        InputStream in = CgIO.openStream(asset);
        if (in == null) return null;
        try (InputStream stream = in) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = stream.read(buf)) != -1; ) bytes.write(buf, 0, n);
            return CgFont.load(bytes.toByteArray(), asset, CgFontStyle.REGULAR, 16);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read default font asset: " + asset, e);
        }
    }

    /**
     * Parses the file-icon theme's icons on a worker, so the first frame drawing one does not. Parsing touches no GPU,
     * so this removes the cost rather than moving it; an icon not parsed in time parses where it draws. The chrome
     * marks named only by stylesheets stay lazy: listing them here would copy a fact the sheets own.
     */
    static void preloadIcons() {
        CgJob.executor(CgJobLane.LATENCY).execute(() -> {
            try {
                Set<String> paths = new LinkedHashSet<>();
                for (String name : FileIconTheme.getDefault().iconNames()) {
                    paths.add(FileIconTheme.toResourcePath(FileIconTheme.withVariant(name)));
                }
                SvgDocument.preload(paths);
            } catch (RuntimeException | LinkageError broken) {
                CrystalGuiCore.LOGGER.warn("icon preload failed; icons parse on demand", broken);
            }
        });
    }

    /**
     * Rasterises printable ASCII of every face the stylesheets name, at every size they name, on a worker: a first
     * frame otherwise makes each glyph it draws synchronously. Faces and sizes are read from
     * {@link StyleSheet#DEFAULT}, never listed here, and warmed at {@code size * uiScale}, the size a draw looks up.
     *
     * @return the job, or null when it could not start: glyphs rasterise on demand
     */
    @Nullable
    static CgJob<?> warmGlyphs(float uiScale) {
        try {
            Set<List<String>> families = new LinkedHashSet<>();
            Set<Integer> cssSizes = new LinkedHashSet<>();
            // The cascade's own defaults, in force without a rule restating them.
            families.add(StylePropertyRegistry.FONT_FAMILY.initialValue);
            cssSizes.add(Math.round(StylePropertyRegistry.FONT_SIZE.initialValue));
            for (StyleRule rule : StyleSheet.DEFAULT.getRules()) {
                for (StyleRule.Declaration declaration : rule.declarations()) {
                    // The property first: compute() is lazy, and forcing every declaration would parse the sheet twice.
                    if (declaration.property() == StylePropertyRegistry.FONT_FAMILY) {
                        if (declaration.value().compute() instanceof List<?> stack && !stack.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            List<String> names = (List<String>) stack;
                            families.add(names);
                        }
                    } else if (declaration.property() == StylePropertyRegistry.FONT_SIZE) {
                        if (declaration.value().compute() instanceof Number size && Math.round(size.floatValue()) > 0) {
                            cssSizes.add(Math.round(size.floatValue()));
                        }
                    }
                }
            }
            int[] effective = new int[cssSizes.size()];
            int next = 0;
            for (int cssPx : cssSizes) effective[next++] = Math.round(cssPx * uiScale);

            // Resolving a family opens its files in FreeType, at every size: the cache is keyed by size.
            return CgJob.lane(CgJobLane.BACKGROUND).named("cgui glyph warm").run(() -> {
                try {
                    for (List<String> stack : families) {
                        CgFontFamily family = null;
                        for (int cssPx : cssSizes) {
                            FontFamilyCache.resolveGroup(stack, cssPx);
                            family = FontFamilyCache.resolve(stack, cssPx);
                        }
                        if (family != null) CgFontRegistry.get().warmAscii(family.getPrimaryFont(), effective);
                    }
                } catch (RuntimeException | LinkageError broken) {
                    CrystalGuiCore.LOGGER.warn("glyph warm failed; glyphs rasterise on demand", broken);
                }
            });
        } catch (RuntimeException | LinkageError broken) {
            CrystalGuiCore.LOGGER.warn("glyph warm failed; glyphs rasterise on demand", broken);
            return null;
        }
    }
}
