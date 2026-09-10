package com.crystalgui.core.cursor;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgCursorService.Image;

/**
 * Shows a mouse cursor — the seam between whoever <em>decides</em> on one and whoever <em>presents</em> it.
 *
 * <pre>{@code
 * CursorService.setCursor(Cursor.POINTER);   // that is the whole API
 * }</pre>
 *
 * <p><b>Nothing registers anything.</b> This resolves the keyword to a picture through
 * {@link CursorBitmaps#artFor} and hands it to CrystalGraphics' {@code CgCursorService}, whose LWJGL2
 * and LWJGL3 adapters ship with CrystalGraphics and know nothing about this package. A host gets
 * working cursors without naming a cursor service, an adapter, or the module either lives in.
 *
 * <h3>Why this is a class and not an interface</h3>
 *
 * <p>It was both an interface and a {@code CgService} slot, from when presenting a cursor was the
 * loader's job and each one wrote its own adapter. It is not any more: the toolkit half moved to
 * CrystalGraphics, which leaves exactly one implementation of a one-method interface that nobody
 * filled. What survives the collapse is the part that was always CrystalGUI's — the {@code cursor}
 * CSS property, its inheritance, the {@code auto} rule, and {@link CursorBitmaps}' keyword table.
 *
 * <h3>Presenting one is toolkit-specific to an awkward degree — and that is CrystalGraphics' half</h3>
 *
 * <ul>
 *   <li><b>LWJGL3 / GLFW</b> has standard system cursors. A mapping table is most of the adapter.</li>
 *   <li><b>LWJGL2</b> (MC 1.7.10, and the harness) has <b>no standard cursors at all</b>:
 *       {@code Mouse.setNativeCursor} takes raw pixel data — bottom-up, unlike every other toolkit —
 *       which is what {@link CursorBitmaps} exists to supply.</li>
 * </ul>
 *
 * <p><b>The name is what crosses</b>, not the pixels: an adapter whose toolkit ships that shape
 * natively prefers its own and falls back to our artwork. So {@link CursorBitmaps#artFor} stays the
 * single keyword table and adding a {@link Cursor} needs no edit anywhere else.
 *
 * <p>With no CrystalGraphics adapter registered this is silently inert, which is correct for a
 * dedicated server, a headless test, or any host with no window.
 */
public final class CursorService {

    private CursorService() { }

    /**
     * One {@link Image} per keyword, built once.
     *
     * <p>Not an optimisation to skip: {@link CursorArt#draw()} rasterises the picture, and
     * {@link #setCursor} runs from hover handling — every time the pointer crosses an element. The
     * adapter caches native handles by name; this caches the pixels behind them.
     *
     * <p>Indexed by {@link Cursor#ordinal()} rather than held in a map, so two documents' frame
     * threads cannot corrupt it. A reference write is atomic, {@link #NO_ART} distinguishes "computed,
     * nothing to draw" from "not computed yet", and the worst a race costs is rasterising one picture
     * twice and storing the same answer.
     */
    private static final Image[] CACHE = new Image[Cursor.values().length];

    /** Stands in for "this keyword has no picture", which a null slot cannot say. */
    private static final Image NO_ART = Image.standard("");

    /**
     * Shows {@code cursor}.
     *
     * <p>Never receives {@link Cursor#AUTO}: the caller resolves that to a concrete value first, so
     * only something mappable or ignorable ever arrives.
     */
    public static void setCursor(Cursor cursor) {
        CgPlatform.get(CgCursorService.SERVICE).show(imageFor(cursor));
    }

    private static Image imageFor(Cursor cursor) {
        if (cursor == null) return null;

        Image cached = CACHE[cursor.ordinal()];
        if (cached != null) return cached == NO_ART ? null : cached;

        CursorArt art = CursorBitmaps.artFor(cursor);
        Image image = null;
        if (art != null) {
            int[] pixels = art.draw();
            // The name travels even when the pixels do: an adapter whose toolkit ships that shape
            // natively prefers its own, and only falls back to ours.
            image = pixels == null
                    ? Image.standard(art.name())
                    : Image.of(art.name(), pixels, CursorBitmaps.SIZE, CursorBitmaps.SIZE,
                               art.hotspotX(), art.hotspotY());
        }
        CACHE[cursor.ordinal()] = image == null ? NO_ART : image;
        return image;
    }
}
