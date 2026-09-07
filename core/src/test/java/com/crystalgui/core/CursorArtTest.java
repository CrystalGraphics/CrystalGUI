package com.crystalgui.core;

import com.crystalgraphics.platform.input.CgCursorBitmaps;
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
        for (int[] art : new int[][] { CgCursorBitmaps.pointingHand(), CgCursorBitmaps.pointingHandPixelArt() }) {
            assertEquals(CgCursorBitmaps.SIZE * CgCursorBitmaps.SIZE, art.length);
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
        for (int[] art : new int[][] { CgCursorBitmaps.pointingHand(), CgCursorBitmaps.pointingHandPixelArt() }) {
            int index = CgCursorBitmaps.HAND_HOTSPOT_Y * CgCursorBitmaps.SIZE + CgCursorBitmaps.HAND_HOTSPOT_X;
            assertTrue("the hotspot pixel must be part of the cursor", alpha(art[index]) > 0);
        }
    }

    /** Nothing may touch the border, or the shape is clipped by the canvas rather than by design. */
    @Test
    public void neitherVariantRunsOffTheCanvas() {
        for (int[] art : new int[][] { CgCursorBitmaps.pointingHand(), CgCursorBitmaps.pointingHandPixelArt() }) {
            int n = CgCursorBitmaps.SIZE;
            for (int i = 0; i < n; i++) {
                assertEquals("top row", 0, alpha(art[i]));
                assertEquals("bottom row", 0, alpha(art[(n - 1) * n + i]));
                assertEquals("left column", 0, alpha(art[i * n]));
                assertEquals("right column", 0, alpha(art[i * n + n - 1]));
            }
        }
    }

    /**
     * Every <b>straight-edged</b> cursor is 1-bit: fully opaque or fully gone, no partial coverage.
     *
     * <p>Not a limitation to work around — it is what that artwork is drawn for. A 32&times;32 cursor is
     * about twenty pixels of shape, and at that size a crisp one-pixel outline beats a soft one; the
     * anti-aliased attempt rasterised two pixels thick on diagonals and read as ragged. It also means the
     * art needs no 8-bit-alpha capability from the driver, which is one fewer thing to degrade.</p>
     *
     * <p><b>The curved three are excluded, and the list here is the statement of that.</b> An arc has no
     * orientation that aliases cleanly, so a mask draws it as a chain of blocks — the rule is about the
     * kind of edge, not a blanket ban, and {@link #theCurvedCursorsAreSmooth} asserts the other half.</p>
     */
    @Test
    public void allArtworkIsOneBit() {
        for (int[] art : new int[][] { CgCursorBitmaps.pointingHand(), CgCursorBitmaps.pointingHandPixelArt(),
                CgCursorBitmaps.horizontalDoubleArrow(), CgCursorBitmaps.textBeam() }) {
            for (int px : art) {
                int a = alpha(px);
                assertTrue("must be fully on or fully off, was " + a, a == 0 || a == 255);
            }
        }
    }

    /**
     * <b>The curved three carry partial coverage, which is the whole reason they are drawn differently.</b>
     *
     * <p>The counterpart to {@link #allArtworkIsOneBit}: an arc rasterised as a mask reads as a staircase,
     * so the curves are built from signed distance fields instead. Asserted as "there are grey pixels"
     * because that is exactly what a well-meaning simplification back to a boolean body would remove, and
     * the result would still be a recognisable cursor — just a dated-looking one.</p>
     *
     * <p><b>Rotate is a hybrid and carries far fewer</b>: its arc is a field, but its two arrowheads are
     * the resize arrows' own mask, because a quarter turn ends on the axes and an axis-aligned head has
     * nothing but orientations that alias cleanly. So the bound is low enough to admit a short curve and
     * still fail a shape with no curve left in it at all.</p>
     */
    @Test
    public void theCurvedCursorsAreSmooth() {
        assertSmooth("rotate-ne", CgCursorBitmaps.rotateNe());
        assertSmooth("rotate-nw", CgCursorBitmaps.rotateNw());
        assertSmooth("rotate-se", CgCursorBitmaps.rotateSe());
        assertSmooth("rotate-sw", CgCursorBitmaps.rotateSw());
        assertSmooth("skew", CgCursorBitmaps.skew());
        assertSmooth("pivot", CgCursorBitmaps.pivot());
    }

    /** @see #theCurvedCursorsAreSmooth */
    private static void assertSmooth(String name, int[] art) {
        assertEquals(CgCursorBitmaps.SIZE * CgCursorBitmaps.SIZE, art.length);
        int opaque = 0;
        int partial = 0;
        for (int px : art) {
            int a = alpha(px);
            if (a == 255) opaque++;
            else if (a > 0) partial++;
        }
        assertTrue(name + ": nothing was drawn at all", opaque > 60);
        assertTrue(name + ": covers far too much of the canvas to be a cursor, was " + opaque,
                opaque < 700);
        assertTrue(name + ": no partial coverage — the curve has been flattened back to a mask, was "
                + partial, partial > 12);
    }

    /** Nothing may touch the border: a cursor clipped by its own canvas looks broken at the screen edge. */
    @Test
    public void theCurvedCursorsStayOnTheCanvas() {
        int n = CgCursorBitmaps.SIZE;
        for (int[] art : new int[][] {
                CgCursorBitmaps.rotateNe(), CgCursorBitmaps.rotateNw(),
                CgCursorBitmaps.rotateSe(), CgCursorBitmaps.rotateSw(),
                CgCursorBitmaps.skew(), CgCursorBitmaps.pivot() }) {
            for (int i = 0; i < n; i++) {
                assertEquals("top row", 0, alpha(art[i]));
                assertEquals("bottom row", 0, alpha(art[(n - 1) * n + i]));
                assertEquals("left column", 0, alpha(art[i * n]));
                assertEquals("right column", 0, alpha(art[i * n + n - 1]));
            }
        }
    }

    /** The fingers must actually be separate — that is the whole difference between this and the solid
     * variant, and the thing four generated attempts kept losing. */
    @Test
    public void theFingersAreSeparated() {
        int[] art = CgCursorBitmaps.pointingHand();
        int n = CgCursorBitmaps.SIZE;
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
        int[] art = CgCursorBitmaps.pointingHand();
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
