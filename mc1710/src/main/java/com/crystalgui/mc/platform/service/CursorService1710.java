package com.crystalgui.mc.platform.service;

import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.core.cursor.CursorArt;
import com.crystalgui.core.cursor.CursorBitmaps;
import com.crystalgui.core.cursor.CursorService;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Mouse;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Presents real OS cursors via LWJGL2, for the CSS {@code cursor} property the engine resolves.
 *
 * <p>Lives in the loader because {@code core/} may not import LWJGL — only the artwork is shared, and
 * {@link CursorBitmaps} is pure pixel maths, so this adapter is the entire per-loader cost. An
 * LWJGL3/GLFW loader has an easier job still, since <code>glfwCreateStandardCursor</code> covers the
 * whole resize set and needs no bitmaps.</p> *
 * <p><b>It enumerates no keywords.</b> {@link CursorBitmaps#artFor} is the single table for every
 * platform, so a cursor added to {@link Cursor} reaches this adapter with no edit here — which is what
 * {@link CursorArt} exists for.</p>
 *
 * <p><b>Cursors are created lazily</b>, on the first {@link #setCursor} that needs one, so constructing
 * this service before the LWJGL display exists is fine — the capability query is deferred with them.</p>
 *
 * <h3>Three LWJGL2 details that are easy to get wrong</h3>
 * <ol>
 *   <li><b>Images are bottom-up, and the hotspot's Y is measured from the bottom.</b> Every drawing
 *       function in {@link CursorBitmaps} works top-down like the rest of this codebase, so the flip
 *       happens here, once, in {@link #toCursor}. Getting this wrong produces a vertically mirrored
 *       cursor whose hotspot is at the wrong end — and for a symmetric double-arrow the mirroring is
 *       invisible, so only the hotspot would give it away.</li>
 *   <li><b>Cursors must be a size the platform accepts</b>, which in practice means 32×32 on Windows.
 *       {@code getMinCursorSize()}/{@code getMaxCursorSize()} are checked rather than assumed, and a
 *       platform outside that range gets the system arrow instead of a crash.</li>
 *   <li><b>Native cursors need the capability bit.</b> Without {@code CURSOR_ONE_BIT_TRANSPARENCY}
 *       there is no cursor support at all, and creating one throws.</li>
 * </ol>
 */
public final class CursorService1710 implements CursorService {

    /** Keyed by ART, so the keywords sharing one picture share one native object. Null values are
     * cached too — "this platform cannot draw it" is as stable an answer as a cursor. */
    private final Map<CursorArt, org.lwjgl.input.Cursor> cache = new HashMap<>();
    private boolean supported = true;
    /** Tri-state: null until first asked, since the capability query needs a live display. */
    private Boolean cursorsAvailable;

    @Override
    public void setCursor(Cursor cursor) {
        if (!supported) return;
        try {
            Mouse.setNativeCursor(resolve(cursor));
        } catch (LWJGLException | RuntimeException e) {
            // One failure means this platform cannot do it; stop trying rather than throwing every
            // time the pointer crosses an element. A missing cursor is cosmetic.
            supported = false;
        }
    }

    /** @return the native cursor for {@code cursor}, or {@code null} to use the system arrow. */
    private org.lwjgl.input.Cursor resolve(Cursor cursor) {
        CursorArt art = CursorBitmaps.artFor(cursor);
        // No picture, or one only a native could present: the system arrow beats a wrong shape.
        if (art == null) return null;
        if (cache.containsKey(art)) return cache.get(art);

        org.lwjgl.input.Cursor created = null;
        int[] pixels = canCreateCursors() ? art.draw() : null;
        if (pixels != null) {
            try {
                created = toCursor(pixels, art.hotspotX(), art.hotspotY());
            } catch (LWJGLException e) {
                created = null;
            }
        }
        cache.put(art, created);
        return created;
    }

    /** Queried once. The capability bit and the size limits cannot change for the life of a display,
     * and the query is not free enough to repeat on every cache miss. */
    private boolean canCreateCursors() {
        if (cursorsAvailable == null) {
            cursorsAvailable = (org.lwjgl.input.Cursor.getCapabilities()
                    & org.lwjgl.input.Cursor.CURSOR_ONE_BIT_TRANSPARENCY) != 0
                    && CursorBitmaps.SIZE >= org.lwjgl.input.Cursor.getMinCursorSize()
                    && CursorBitmaps.SIZE <= org.lwjgl.input.Cursor.getMaxCursorSize();
        }
        return cursorsAvailable;
    }

    /**
     * Converts a top-down ARGB bitmap into an LWJGL2 cursor.
     *
     * <p>The vertical flip and the hotspot conversion are the whole reason this is a separate method:
     * they are one line each and both are silently wrong if omitted.</p>
     *
     * <p>The hotspot comes from the {@link CursorArt} rather than from {@link CursorBitmaps#HOTSPOT},
     * because it is not the centre for every shape — a pointing hand's is its fingertip. Reading the
     * constant here would put the click point half a cursor away from where the picture says it is.</p>
     */
    private org.lwjgl.input.Cursor toCursor(int[] topDown, int hotspotX, int hotspotY) throws LWJGLException {
        final int size = CursorBitmaps.SIZE;
        IntBuffer pixels = BufferUtils.createIntBuffer(size * size);
        for (int y = size - 1; y >= 0; y--) {
            for (int x = 0; x < size; x++) {
                pixels.put(topDown[y * size + x]);
            }
        }
        pixels.flip();

        // Y from the bottom, since that is the space the image is now in.
        int flippedHotspotY = size - 1 - hotspotY;
        return new org.lwjgl.input.Cursor(size, size, hotspotX, flippedHotspotY, 1, pixels, null);
    }
}
