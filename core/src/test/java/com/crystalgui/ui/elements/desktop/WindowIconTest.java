package com.crystalgui.ui.elements.desktop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The tile every taskbar entry, hover preview and switcher tile draws a window's icon with.
 *
 * <p>What is worth pinning is the CONTRACT, not the picture: a window with an icon wears a palette tile
 * keyed on the icon name, one without wears its initial on the neutral tile, and a tile that is shown
 * something else swaps its class rather than accumulating — a recycled element wearing two hues is the
 * {@code filetype-*} bug from the explorer, one widget over.</p>
 */
public class WindowIconTest {

    @Test
    public void anIconWearsAPaletteTileAndNoLetter() {
        WindowIcon tile = new WindowIcon().show("crystalgui:code", "Crystal Editor");
        String cls = tile.tileClass();
        assertTrue("expected a palette class, got " + cls,
                cls != null && cls.startsWith(WindowIcon.TILE_CLASS_PREFIX) && !cls.equals(WindowIcon.MONO_TILE_CLASS));
        assertTrue(tile.hasClass(cls));
        assertEquals("a glyph tile shows no monogram", "", tile.monogram());
    }

    @Test
    public void theSameIconAlwaysGetsTheSameTile() {
        // Windows' "these are the same application" reading: two windows declaring one icon share a hue,
        // wherever each is drawn — the strip, the preview and the switcher all go through this.
        WindowIcon inStrip = new WindowIcon().show("crystalgui:code", "Geometry");
        WindowIcon inPreview = new WindowIcon().show("crystalgui:code", "Crystal Editor");
        assertEquals(inStrip.tileClass(), inPreview.tileClass());
        assertEquals(WindowIcon.paletteIndexOf("crystalgui:code"), WindowIcon.paletteIndexOf("crystalgui:code"));
        int index = WindowIcon.paletteIndexOf("crystalgui:code");
        assertTrue("index within the sheet's palette", index >= 1 && index <= WindowIcon.PALETTE_SIZE);
    }

    @Test
    public void noIconMeansTheTitlesInitialOnTheNeutralTile() {
        WindowIcon tile = new WindowIcon().show(null, "  taskbar designer");
        assertEquals(WindowIcon.MONO_TILE_CLASS, tile.tileClass());
        assertEquals("upper-cased, whitespace skipped", "T", tile.monogram());
        assertEquals("", new WindowIcon().show(null, "   ").monogram());
        assertEquals("", new WindowIcon().show(null, null).monogram());
    }

    @Test
    public void showingSomethingElseSwapsTheTileRatherThanAddingOne() {
        WindowIcon tile = new WindowIcon().show("crystalgui:code", "A");
        String first = tile.tileClass();
        tile.show(null, "Beta");
        assertEquals(WindowIcon.MONO_TILE_CLASS, tile.tileClass());
        assertFalse("the old palette class must come off", tile.hasClass(first));
        assertEquals("B", tile.monogram());

        tile.show("crystalgui:code", "A");
        assertEquals(first, tile.tileClass());
        assertFalse(tile.hasClass(WindowIcon.MONO_TILE_CLASS));
        assertEquals("back to a glyph, the letter goes", "", tile.monogram());
    }

    @Test
    public void anUnknownIconFallsBackToTheMonogram() {
        // A name that resolves to no file is what a window from a pack that has not shipped its icons
        // looks like; it must read as a window, not as a blank slot.
        WindowIcon tile = new WindowIcon().show("crystalgui:no-such-icon", "Zed");
        assertEquals(WindowIcon.MONO_TILE_CLASS, tile.tileClass());
        assertEquals("Z", tile.monogram());
    }
}
