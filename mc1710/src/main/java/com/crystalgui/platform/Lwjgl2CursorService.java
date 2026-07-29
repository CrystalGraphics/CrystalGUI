package com.crystalgui.platform;

import com.crystalgui.core.input.CursorBitmaps;
import com.crystalgui.core.input.UICursorService;
import com.crystalgui.style.property.visual.Cursor;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Mouse;

import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.Map;

/**
 * Presents real OS cursors via LWJGL2, for the CSS {@code cursor} property the engine resolves.
 *
 * <p>Lives in the loader because {@code core/} may not import LWJGL at all — the import guard fails
 * the build on it. Only the artwork is shared: {@link CursorBitmaps} is pure pixel maths and lives in
 * {@code core/}, so this adapter is the entire per-loader cost. An LWJGL3/GLFW loader has an easier job
 * still, since <code>glfwCreateStandardCursor</code> covers the whole resize set and needs no bitmaps.</p>
 *
 * <h3>⚠️ Not compiled by this build</h3>
 * <p>{@code mc1710} is commented out of {@code settings.gradle.kts} and has no CrystalGUI integration
 * yet — this file has therefore <b>never been compiled or run</b>. It is a byte-for-byte sibling of the
 * harness copy, which <em>is</em> built and verified, so the logic is exercised; what is unverified here
 * is only that this module can resolve {@code com.crystalgui.core} and LWJGL2 at all. Wiring it up
 * means re-including the module and adding those dependencies, then registering the service:</p>
 *
 * <pre>{@code CrystalGuiCore.setCursorService(new Lwjgl2CursorService()); }</pre>
 *
 * <p>Register it after the display exists — the capability query needs one.</p>
 *
 * <h3>Three LWJGL2 details that are easy to get wrong</h3>
 * <ol>
 *   <li><b>Images are bottom-up, and the hotspot's Y is measured from the bottom.</b> Every drawing
 *       function in {@link CursorBitmaps} works top-down like the rest of this codebase, so the flip
 *       happens here, once, in {@link #toCursor}. Getting this wrong produces a vertically mirrored
 *       cursor whose hotspot is at the wrong end — and for a symmetric double-arrow the mirroring is
 *       invisible, so only the hotspot would give it away.</li>
 *   <li><b>Cursors must be a size the platform accepts</b>, which in practice means 32×32 on Windows.
 *       {@code Cursor.getMinCursorSize()}/{@code getMaxCursorSize()} are checked rather than assumed,
 *       and a platform outside that range gets the system arrow instead of a crash.</li>
 *   <li><b>Native cursors need the capability bit.</b> Without
 *       {@code CURSOR_ONE_BIT_TRANSPARENCY} there is no cursor support at all, and creating one
 *       throws.</li>
 * </ol>
 *
 * <p>Cursors are created lazily and cached <b>by shape</b> (see {@link Shape}), so the eighteen mapped
 * CSS keywords share six native objects rather than allocating one each. Anything without artwork maps
 * to {@code null}, which is LWJGL2's way of restoring the system arrow — a better answer than a wrong
 * picture, and the right one for most of the keyword set.</p>
 */
public final class Lwjgl2CursorService implements UICursorService {

    /**
     * The distinct pictures, as opposed to the CSS keywords that ask for them.
     *
     * <p><b>Caching by shape rather than by keyword is the point.</b> Eighteen keywords map onto these
     * six — {@code ew-resize}, {@code col-resize}, {@code e-resize} and {@code w-resize} all want the
     * same horizontal arrow. Keying the cache on {@link Cursor} instead allocated a separate native
     * cursor object per keyword: seventeen natives for six images, each one a real OS handle.</p>
     */
    private enum Shape {
        HORIZONTAL_ARROW(CursorBitmaps::horizontalDoubleArrow),
        VERTICAL_ARROW(CursorBitmaps::verticalDoubleArrow),
        DIAGONAL_NWSE(CursorBitmaps::diagonalNwseArrow),
        DIAGONAL_NESW(CursorBitmaps::diagonalNeswArrow),
        FOUR_WAY(CursorBitmaps::fourWayArrow),
        TEXT_BEAM(CursorBitmaps::textBeam);

        private final java.util.function.Supplier<int[]> draw;

        Shape(java.util.function.Supplier<int[]> draw) {
            this.draw = draw;
        }
    }

    /** Keyed by shape, so identical pictures share one native object. Null values are cached too —
     * "this platform cannot draw it" is as stable an answer as a cursor. */
    private final Map<Shape, org.lwjgl.input.Cursor> cache = new EnumMap<>(Shape.class);
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
        Shape shape = shapeFor(cursor);
        if (shape == null) return null; // no artwork — the system arrow beats a wrong picture
        if (cache.containsKey(shape)) return cache.get(shape);

        org.lwjgl.input.Cursor created = null;
        if (canCreateCursors()) {
            try {
                created = toCursor(shape.draw.get());
            } catch (LWJGLException e) {
                created = null;
            }
        }
        cache.put(shape, created);
        return created;
    }

    /**
     * Which CSS keywords we have artwork for. Everything else falls through to the system arrow, which
     * is a better answer than a wrong shape — and that is most of the set, deliberately: {@link Cursor}
     * ports all of CSS's keywords while only a handful matter to this UI.
     */
    private static Shape shapeFor(Cursor cursor) {
        switch (cursor) {
            case EW_RESIZE: case COL_RESIZE: case E_RESIZE: case W_RESIZE:
                return Shape.HORIZONTAL_ARROW;
            case NS_RESIZE: case ROW_RESIZE: case N_RESIZE: case S_RESIZE:
                return Shape.VERTICAL_ARROW;
            case NWSE_RESIZE: case NW_RESIZE: case SE_RESIZE:
                return Shape.DIAGONAL_NWSE;
            case NESW_RESIZE: case NE_RESIZE: case SW_RESIZE:
                return Shape.DIAGONAL_NESW;
            case MOVE: case ALL_SCROLL: case GRABBING:
                return Shape.FOUR_WAY;
            case TEXT:
                return Shape.TEXT_BEAM;
            default:
                return null;
        }
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
     */
    private org.lwjgl.input.Cursor toCursor(int[] topDown) throws LWJGLException {
        final int size = CursorBitmaps.SIZE;
        IntBuffer pixels = org.lwjgl.BufferUtils.createIntBuffer(size * size);
        for (int y = size - 1; y >= 0; y--) {
            for (int x = 0; x < size; x++) {
                pixels.put(topDown[y * size + x]);
            }
        }
        pixels.flip();

        // Y from the bottom, since that is the space the image is now in.
        int hotspotY = size - 1 - CursorBitmaps.HOTSPOT;
        return new org.lwjgl.input.Cursor(size, size, CursorBitmaps.HOTSPOT, hotspotY, 1, pixels, null);
    }
}
