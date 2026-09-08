package com.crystalgui.core.cursor;

import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * One distinct cursor <em>picture</em>, as opposed to the {@link Cursor} keywords that ask for it.
 *
 * <p>Instances are shared: {@code ew-resize}, {@code col-resize}, {@code e-resize} and {@code w-resize}
 * all map to the one horizontal arrow. A platform adapter caches its native cursor objects keyed on
 * this rather than on the keyword, so those four keywords cost one OS handle between them.</p>
 *
 * <pre>{@code
 * CursorArt art = CursorBitmaps.artFor(cursor);
 * if (art == null) return null;                       // nothing to show — the system arrow
 * if (cache.containsKey(art)) return cache.get(art);  // by ART, never by keyword
 * int[] argb = art.draw();                            // null when the shape is standard-only
 * }</pre>
 *
 * <h3>{@link #name} is what a platform matches its own native set against</h3>
 *
 * <p>A toolkit with system cursors — GLFW has most of them — wants to use its own rather than our
 * bitmap, and it looks that up by name. Matching on the name and not the keyword is what makes the
 * table in {@link CursorBitmaps} the <b>only</b> place a cursor is enumerated: an adapter that has never
 * heard of a name falls through to {@link #draw()}, so adding a {@link Cursor} needs no edit in any
 * loader. The keyword→picture table used to be copied into every adapter and the copies drifted —
 * {@code slide-arrow} reached the LWJGL2 ones and not GLFW, {@code crosshair} the reverse.</p>
 *
 * @param name     stable identifier for the picture, matched by a platform against its native set
 * @param art      draws it, or {@code null} for a shape only a native can present — see {@link #draw()}
 * @param hotspotX the click point within the image, from the left
 * @param hotspotY the click point within the image, <b>from the top</b>; an adapter whose images are
 *                 bottom-up (LWJGL2's are) flips it
 */
public record CursorArt(String name, @Nullable Supplier<int[]> art, int hotspotX, int hotspotY) {

    /** A symmetric shape — its hotspot is the bitmap's centre, which is all but one of them. */
    public static CursorArt centred(String name, Supplier<int[]> art) {
        return new CursorArt(name, art, CursorBitmaps.HOTSPOT, CursorBitmaps.HOTSPOT);
    }

    /**
     * A shape with no bitmap, presentable only by a platform holding a native of this name.
     *
     * <p>{@code crosshair} is one: GLFW ships a good one and duplicating it in pixels would be worse
     * than what the OS provides. An adapter without it shows the system arrow, which is the documented
     * outcome for any cursor a platform cannot present.</p>
     */
    public static CursorArt standardOnly(String name) {
        return new CursorArt(name, null, CursorBitmaps.HOTSPOT, CursorBitmaps.HOTSPOT);
    }

    /**
     * The picture, as top-left-origin ARGB of {@link CursorBitmaps#SIZE}², or {@code null} when this
     * shape has no bitmap.
     *
     * <p>Drawn on every call — an adapter caches the native object it builds, not this array.</p>
     */
    public @Nullable int[] draw() {
        return art == null ? null : art.get();
    }
}
