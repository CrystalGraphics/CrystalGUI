package com.crystalgui.render.text;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgSystemFonts;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code font-family} entries as a browser reads them, with CrystalGUI's own font folder standing in
 * for the installed fonts so no result depends on the machine.
 */
public class FontFamilyCacheTest {

    private static final Path BUNDLED = Paths.get("src/main/resources/assets/crystalgui/ui/fonts");
    private static final int ELLIPSIS = 0x2026;

    @After
    public void systemFontsOff() {
        FontFamilyCache.useSystemFonts(null);
    }

    @Test
    public void anEntryIsAPathOrAName() {
        assertTrue(FontFamilyCache.isResourcePath("crystalgui:ui/fonts/JetBrainsMono-Regular.ttf"));
        assertTrue(FontFamilyCache.isResourcePath("C:/Windows/Fonts/arial.ttf"));
        assertTrue(FontFamilyCache.isResourcePath("Minecraft.otf"));
        assertFalse(FontFamilyCache.isResourcePath("Segoe UI"));
        assertFalse(FontFamilyCache.isResourcePath("monospace"));
    }

    @Test
    public void aFamilyNameResolvesToAnInstalledFace() {
        FontFamilyCache.useSystemFonts(CgSystemFonts.of(List.of(BUNDLED)));
        CgFontFamily family = FontFamilyCache.resolve(List.of("No Such Family", "jetbrains mono"), 14);
        assertTrue(family.getPrimaryFont().getLogicalName(),
                family.getPrimaryFont().getLogicalName().endsWith("JetBrainsMono-Regular.ttf"));
    }

    /** MinecraftRegular has no U+2026; with installed fonts to ask, the family still draws one. */
    @Test
    public void whatTheStackLacksComesFromTheInstalledFonts() {
        FontFamilyCache.useSystemFonts(CgSystemFonts.of(List.of(BUNDLED)));
        CgFontFamily family = FontFamilyCache.resolve(List.of("crystalgui:ui/fonts/MinecraftRegular.otf"), 16);
        assertFalse(family.getPrimarySource().canDisplayCodePoint(ELLIPSIS));
        assertTrue(family.resolveSourceForCodePoint(ELLIPSIS).canDisplayCodePoint(ELLIPSIS));
    }

    @Test
    public void withSystemFontsOffOnlyPathsResolve() {
        CgFontFamily family = FontFamilyCache.resolve(
                List.of("JetBrains Mono", "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf"), 12);
        assertTrue(family.getPrimaryFont().getLogicalName().endsWith("IBMPlexSans-Regular.ttf"));
        assertNull(family.getFallback());
    }

    /** A family with only a regular face gets no bold family: the layout synthesises bold from regular. */
    @Test
    public void boldIsSynthesisedWhenTheFamilyHasNoBoldFace() {
        FontFamilyCache.useSystemFonts(CgSystemFonts.of(List.of(BUNDLED)));
        CgFontFamilyGroup group = FontFamilyCache.resolveGroup(List.of("IBM Plex Sans"), 12);
        assertSame(group.resolve(CgFontStyle.REGULAR), group.resolve(CgFontStyle.BOLD));
        assertEquals(Set.of(CgFontStyle.REGULAR), group.byStyle().keySet());
    }
}
