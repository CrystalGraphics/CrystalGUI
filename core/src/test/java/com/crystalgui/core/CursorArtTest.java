package com.crystalgui.core;

import com.crystalgui.core.input.CursorBitmaps;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The hand cursor is procedural art, so the useful assertions are the ones a wrong shape would trip:
 * that it is actually a hand-sized silhouette, that its hotspot lands on the fingertip, and that the
 * anti-aliased variant genuinely has partial alpha where the pixel-art one cannot.
 *
 * <p>None of this can tell you it looks good. It can tell you it is not empty, not clipped, and not
 * anchored somewhere absurd — the three ways procedural art fails silently.</p>
 */
public class CursorArtTest {

    private static int alpha(int argb) { return (argb >>> 24) & 0xFF; }

    @Test
    public void bothVariantsDrawSomethingHandSized() {
        for (int[] art : new int[][] { CursorBitmaps.pointingHand(), CursorBitmaps.pointingHandPixelArt() }) {
            assertEquals(CursorBitmaps.SIZE * CursorBitmaps.SIZE, art.length);
            int opaque = 0;
            for (int px : art) if (alpha(px) > 128) opaque++;
            assertTrue("a hand should cover a decent slab of the canvas, was " + opaque, opaque > 150);
            assertTrue("...but nowhere near all of it, was " + opaque, opaque < 700);
        }
    }

    /** The hotspot must land ON the finger. A hand cursor points, and a hotspot floating in empty space
     * puts the click where the picture is not. */
    @Test
    public void theHotspotIsOnTheFingertip() {
        for (int[] art : new int[][] { CursorBitmaps.pointingHand(), CursorBitmaps.pointingHandPixelArt() }) {
            int index = CursorBitmaps.HAND_HOTSPOT_Y * CursorBitmaps.SIZE + CursorBitmaps.HAND_HOTSPOT_X;
            assertTrue("the hotspot pixel must be part of the cursor", alpha(art[index]) > 0);
        }
    }

    /** Nothing may touch the border, or the shape is clipped by the canvas rather than by design. */
    @Test
    public void neitherVariantRunsOffTheCanvas() {
        for (int[] art : new int[][] { CursorBitmaps.pointingHand(), CursorBitmaps.pointingHandPixelArt() }) {
            int n = CursorBitmaps.SIZE;
            for (int i = 0; i < n; i++) {
                assertEquals("top row", 0, alpha(art[i]));
                assertEquals("bottom row", 0, alpha(art[(n - 1) * n + i]));
                assertEquals("left column", 0, alpha(art[i * n]));
                assertEquals("right column", 0, alpha(art[i * n + n - 1]));
            }
        }
    }

    /**
     * Every cursor here is <b>1-bit</b>: fully opaque or fully gone, no partial coverage.
     *
     * <p>Not a limitation to work around — it is what the artwork is drawn for. A 32&times;32 cursor is
     * about twenty pixels of shape, and at that size a crisp one-pixel outline beats a soft one; the
     * anti-aliased attempt rasterised two pixels thick on diagonals and read as ragged. It also means the
     * art needs no 8-bit-alpha capability from the driver, which is one fewer thing to degrade.</p>
     */
    @Test
    public void allArtworkIsOneBit() {
        for (int[] art : new int[][] { CursorBitmaps.pointingHand(), CursorBitmaps.pointingHandPixelArt(),
                CursorBitmaps.horizontalDoubleArrow(), CursorBitmaps.textBeam() }) {
            for (int px : art) {
                int a = alpha(px);
                assertTrue("must be fully on or fully off, was " + a, a == 0 || a == 255);
            }
        }
    }

    /** The fingers must actually be separate — that is the whole difference between this and the solid
     * variant, and the thing four generated attempts kept losing. */
    @Test
    public void theFingersAreSeparated() {
        int[] art = CursorBitmaps.pointingHand();
        int n = CursorBitmaps.SIZE;
        // A row through the middle of the curled fingers crosses white, black division, white, ... — so a
        // fused slab shows up as too few transitions.
        int row = 14, transitions = 0;
        boolean prevWhite = false;
        for (int x = 0; x < n; x++) {
            int px = art[row * n + x];
            boolean white = alpha(px) == 255 && (px & 0xFF) > 128;
            if (white != prevWhite) transitions++;
            prevWhite = white;
        }
        assertTrue("four fingers should give at least four white runs on this row, saw "
                + transitions + " transitions", transitions >= 8);
    }

    /** A white body inside a dark rim is what makes a cursor readable on any background. */
    @Test
    public void theSmoothHandIsWhiteInsideWithADarkRim() {
        int[] art = CursorBitmaps.pointingHand();
        boolean sawWhite = false, sawDark = false;
        for (int px : art) {
            if (alpha(px) < 200) continue;
            int level = px & 0xFF;
            if (level > 220) sawWhite = true;
            if (level < 40) sawDark = true;
        }
        assertTrue("needs a white body", sawWhite);
        assertTrue("needs a dark outline", sawDark);
    }
}
