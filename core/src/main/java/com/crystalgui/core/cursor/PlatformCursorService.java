package com.crystalgui.core.cursor;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgCursorImage;
import com.crystalgraphics.platform.service.CgCursorService;

import java.util.EnumMap;
import java.util.Map;

/**
 * The default {@link CursorService}: turns a keyword into a picture and hands it to whatever
 * CrystalGraphics adapter the loader registered.
 *
 * <p><b>Nothing installs this and no host names it.</b> It is {@link CursorService#SERVICE}'s
 * absent-value, so a loader that registers no cursor service of its own still gets working cursors
 * the moment CrystalGraphics has a {@code CgCursorService} — which its own LWJGL modules provide.
 * That is the whole point: the keyword vocabulary is this engine's and the native handle is the
 * toolkit's, and neither side has to know the other exists.
 *
 * <p>With no CrystalGraphics adapter registered this is silently inert, which is correct for a
 * dedicated server, a headless test, or any host with no window.
 */
final class PlatformCursorService implements CursorService {

    /**
     * One {@link CgCursorImage} per keyword, built once.
     *
     * <p>Not an optimisation to skip: {@link CursorArt#draw()} rasterises the picture, and
     * {@code setCursor} is called from hover handling — every time the pointer crosses an element.
     * The adapter caches native handles by name; this caches the pixels behind them.
     */
    private final Map<Cursor, CgCursorImage> images = new EnumMap<>(Cursor.class);

    @Override
    public void setCursor(Cursor cursor) {
        CgPlatform.get(CgCursorService.SERVICE).show(imageFor(cursor));
    }

    private CgCursorImage imageFor(Cursor cursor) {
        if (cursor == null) return null;
        // computeIfAbsent is not used: a keyword with no art maps to null, which it cannot store,
        // so every miss would rasterise again.
        if (images.containsKey(cursor)) return images.get(cursor);

        CursorArt art = CursorBitmaps.artFor(cursor);
        CgCursorImage image = null;
        if (art != null) {
            int[] pixels = art.draw();
            // The name travels even when the pixels do: an adapter whose toolkit ships that shape
            // natively prefers its own, and only falls back to ours.
            image = pixels == null
                    ? CgCursorImage.standard(art.name())
                    : CgCursorImage.of(art.name(), pixels, CursorBitmaps.SIZE, CursorBitmaps.SIZE,
                                       art.hotspotX(), art.hotspotY());
        }
        images.put(cursor, image);
        return image;
    }
}
