package com.crystalgui.mc.modern.platform.service;

import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.core.cursor.CursorArt;
import com.crystalgui.core.cursor.CursorBitmaps;
import com.crystalgui.core.cursor.CursorService;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Presents real OS cursors via GLFW, for the CSS {@code cursor} property the engine resolves.
 *
 * <p>The LWJGL3 counterpart of {@link Lwjgl2CursorService}, and mostly the easier of the two: GLFW ships
 * standard cursors, so most pictures are a table lookup needing no artwork. <b>Not entirely
 * bitmap-free</b> though — see {@link Cursor#SLIDE_ARROW} below.</p>
 *
 * <h3>It enumerates no keywords</h3>
 * <p>{@link CursorBitmaps#artFor} is the single keyword table for every platform. All this class holds
 * is {@link #STANDARD}: the pictures <em>GLFW itself</em> has a native for, keyed by
 * {@link CursorArt#name()}. A picture missing from it falls through to {@link CursorArt#draw()}, and one
 * with no bitmap either shows the system arrow — so adding a {@link Cursor} needs no edit here.</p>
 *
 * <h3>Standard first, artwork as the fallback</h3>
 * <p>Three shapes ask for a standard cursor that <b>GLFW only added in 3.4</b> — the two diagonal resizes
 * and the four-way move. LWJGL 3.3.1 does declare those constants (it bundles a GLFW 3.4 pre-release), so
 * they will usually work, but "usually" is doing real work in that sentence: a standard shape the loaded
 * native does not recognise comes back as {@code NULL} rather than throwing, and the cursor would simply
 * never appear. Rather than depend on which snapshot a given MC build shipped, {@link #create} tries the
 * standard cursor and falls back to the artwork when it comes back empty. That artwork is already there,
 * shared with mc1710, and costs nothing until it is needed.</p>
 *
 * <h3>GLFW images are top-down — no flip, unlike LWJGL2</h3>
 * <p>{@link Lwjgl2CursorService} has to mirror every bitmap vertically and measure the hotspot from the
 * bottom. GLFW takes rows top-to-bottom with the hotspot in the same space, which is already what
 * {@link CursorBitmaps} produces, so the conversion here is only ARGB ints to RGBA bytes. <b>Do not
 * copy the flip across from mc1710</b> — on a symmetric double-arrow a mirrored image is invisible, and
 * only the misplaced hotspot would eventually give it away.</p>
 */
public final class GlfwCursorService implements CursorService {

    /**
     * The pictures GLFW has a native for, by {@link CursorArt#name()}.
     *
     * <p><b>This is a statement about GLFW, not about the UI</b>, which is why it is the one table left
     * in a loader: nobody but this platform knows what its toolkit ships. Anything absent is drawn from
     * {@link CursorBitmaps} instead, so the map only ever needs an entry when GLFW <em>gains</em> a
     * shape — never when CrystalGUI does.</p>
     */
    private static final Map<String, Integer> STANDARD = new HashMap<>();

    static {
        STANDARD.put("horizontal-arrow", GLFW.GLFW_HRESIZE_CURSOR);
        STANDARD.put("vertical-arrow",   GLFW.GLFW_VRESIZE_CURSOR);
        STANDARD.put("text-beam",        GLFW.GLFW_IBEAM_CURSOR);
        STANDARD.put("pointing-hand",    GLFW.GLFW_HAND_CURSOR);
        STANDARD.put("crosshair",        GLFW.GLFW_CROSSHAIR_CURSOR);
        // GLFW 3.4 standards, with our own artwork behind them. @see the class javadoc.
        STANDARD.put("diagonal-nwse",    GLFW.GLFW_RESIZE_NWSE_CURSOR);
        STANDARD.put("diagonal-nesw",    GLFW.GLFW_RESIZE_NESW_CURSOR);
        STANDARD.put("four-way",         GLFW.GLFW_RESIZE_ALL_CURSOR);
    }

    /** Keyed by ART, so the keywords sharing one picture share one native handle. {@code NULL} is
     * cached too — "this platform cannot make it" is as stable an answer as a cursor. */
    private final Map<CursorArt, Long> cache = new HashMap<>();
    private boolean supported = true;

    @Override
    public void setCursor(Cursor cursor) {
        if (!supported) return;
        try {
            long window = Minecraft.getInstance().getWindow().getWindow();
            if (window == MemoryUtil.NULL) return;
            // NULL restores the system arrow — GLFW's own contract, and a better answer than a wrong
            // picture.
            GLFW.glfwSetCursor(window, resolve(cursor));
        } catch (RuntimeException e) {
            // One failure means this platform cannot do it; stop trying rather than throwing every time
            // the pointer crosses an element. A missing cursor is cosmetic, never functional.
            supported = false;
        }
    }

    /** @return the native cursor handle for {@code cursor}, or {@link MemoryUtil#NULL} for the arrow. */
    private long resolve(Cursor cursor) {
        CursorArt art = CursorBitmaps.artFor(cursor);
        if (art == null) return MemoryUtil.NULL;

        Long cached = cache.get(art);
        if (cached != null) return cached;

        long created = create(art);
        cache.put(art, created);
        return created;
    }

    /** GLFW's own cursor if it knows this picture, our artwork if not. @see GlfwCursorService */
    private static long create(CursorArt art) {
        Integer standard = STANDARD.get(art.name());
        if (standard != null) {
            long handle = GLFW.glfwCreateStandardCursor(standard);
            if (handle != MemoryUtil.NULL) return handle;
        }
        return createFromBitmap(art);
    }

    /**
     * Builds a native cursor from one of {@link CursorBitmaps}' ARGB images.
     *
     * <p>GLFW wants RGBA bytes in top-down rows, which is the order the bitmaps are already in — so the
     * only work is unpacking each {@code 0xAARRGGBB} int into four bytes.</p>
     */
    private static long createFromBitmap(CursorArt art) {
        int[] argb = art.draw();
        if (argb == null) return MemoryUtil.NULL;

        final int size = CursorBitmaps.SIZE;
        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
        for (int pixel : argb) {
            pixels.put((byte) ((pixel >> 16) & 0xFF));   // R
            pixels.put((byte) ((pixel >> 8) & 0xFF));    // G
            pixels.put((byte) (pixel & 0xFF));           // B
            pixels.put((byte) ((pixel >> 24) & 0xFF));   // A
        }
        pixels.flip();

        GLFWImage image = GLFWImage.malloc();
        try {
            image.set(size, size, pixels);
            return GLFW.glfwCreateCursor(image, art.hotspotX(), art.hotspotY());
        } finally {
            // The native cursor owns a copy by now; the descriptor is ours to free.
            image.free();
        }
    }
}
