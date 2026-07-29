package com.crystalgui.core.input;

/**
 * Procedurally drawn cursor bitmaps, 32×32 ARGB.
 *
 * <p><b>Lives in {@code core/} because it contains no platform code at all</b> -- it is integer pixel
 * maths, and the import guard is satisfied trivially. That matters: every LWJGL2 loader (the harness,
 * MC 1.7.10) needs these exact shapes, so keeping them here leaves each loader with only a ~90-line
 * adapter to duplicate instead of the artwork as well. The engine owning its cursor artwork is the
 * same split as it owning {@code default.css}: the pictures are ours, presenting them is the
 * platform's.
 *
 *
 * <p>Generated rather than shipped as PNGs. The shapes are a handful of arrows built from straight
 * runs and triangles — a few dozen lines of drawing code against binary assets that would need
 * authoring, packing, loading, and a hotspot table maintained alongside them. Doing it in code also
 * keeps the hotspot next to the geometry that defines it, which is the thing most likely to drift.</p>
 *
 * <p>Every shape is drawn as a <b>white body with a black outline</b>, for the same reason every OS
 * cursor is: a single-colour cursor disappears against a UI of that colour. The outline is generated
 * from the body rather than drawn separately, so the two can never disagree.</p>
 *
 * <p><b>Origin is top-left here.</b> LWJGL2 wants cursor images bottom-up with the hotspot measured
 * from the bottom, and that conversion happens once, in {@code Lwjgl2CursorService} — not in these
 * functions, which would otherwise all have to be read upside-down.</p>
 */
public final class CursorBitmaps {

    public static final int SIZE = 32;
    /** Centre pixel — the hotspot for every symmetric shape here. */
    public static final int HOTSPOT = SIZE / 2;

    private static final int TRANSPARENT = 0x00000000;
    private static final int BODY = 0xFFFFFFFF;
    private static final int OUTLINE = 0xFF000000;

    private CursorBitmaps() {
    }

    /** ↔ — a horizontal double-headed arrow. {@code ew-resize}, and SplitView's divider. */
    public static int[] horizontalDoubleArrow() {
        boolean[] body = new boolean[SIZE * SIZE];
        shaft(body, true);
        arrowHead(body, 4, HOTSPOT, +1, true);
        arrowHead(body, SIZE - 5, HOTSPOT, -1, true);
        return outline(body);
    }

    /** ↕ — {@code ns-resize}. */
    public static int[] verticalDoubleArrow() {
        boolean[] body = new boolean[SIZE * SIZE];
        shaft(body, false);
        arrowHead(body, HOTSPOT, 4, +1, false);
        arrowHead(body, HOTSPOT, SIZE - 5, -1, false);
        return outline(body);
    }

    /** ↖↘ — {@code nwse-resize}. */
    public static int[] diagonalNwseArrow() {
        return diagonal(true);
    }

    /** ↗↙ — {@code nesw-resize}. */
    public static int[] diagonalNeswArrow() {
        return diagonal(false);
    }

    /** ✛ — a four-way arrow. {@code move}, for a dialog's title bar. */
    public static int[] fourWayArrow() {
        boolean[] body = new boolean[SIZE * SIZE];
        shaft(body, true);
        shaft(body, false);
        arrowHead(body, 4, HOTSPOT, +1, true);
        arrowHead(body, SIZE - 5, HOTSPOT, -1, true);
        arrowHead(body, HOTSPOT, 4, +1, false);
        arrowHead(body, HOTSPOT, SIZE - 5, -1, false);
        return outline(body);
    }

    /** An I-beam. {@code text} — what {@code cursor: auto} resolves to over an editable element. */
    public static int[] textBeam() {
        boolean[] body = new boolean[SIZE * SIZE];
        for (int y = 6; y < SIZE - 6; y++) plot(body, HOTSPOT, y);
        for (int x = HOTSPOT - 3; x <= HOTSPOT + 3; x++) {
            plot(body, x, 6);
            plot(body, x, SIZE - 7);
        }
        return outline(body);
    }

    // ── Drawing primitives ──────────────────────────────────────────────────

    /** A 2px-thick run through the centre, along one axis. */
    private static void shaft(boolean[] body, boolean horizontal) {
        for (int i = 5; i < SIZE - 5; i++) {
            if (horizontal) {
                plot(body, i, HOTSPOT - 1);
                plot(body, i, HOTSPOT);
            } else {
                plot(body, HOTSPOT - 1, i);
                plot(body, HOTSPOT, i);
            }
        }
    }

    /**
     * A solid triangular head at {@code (tipX, tipY)} opening in {@code dir} along one axis.
     *
     * <p>Widening by one pixel per step back from the tip is what gives the stepped, pixel-art look
     * of the shapes it imitates — no anti-aliasing, which a 1-bit-transparency cursor could not
     * display anyway.</p>
     */
    private static void arrowHead(boolean[] body, int tipX, int tipY, int dir, boolean horizontal) {
        for (int step = 0; step < 6; step++) {
            for (int spread = -step; spread <= step; spread++) {
                if (horizontal) plot(body, tipX + dir * step, tipY + spread);
                else plot(body, tipX + spread, tipY + dir * step);
            }
        }
    }

    /** A double-headed arrow along one of the two diagonals. */
    private static int[] diagonal(boolean nwse) {
        boolean[] body = new boolean[SIZE * SIZE];
        for (int i = -9; i <= 9; i++) {
            int x = HOTSPOT + i;
            int y = nwse ? HOTSPOT + i : HOTSPOT - i;
            plot(body, x, y);
            plot(body, x + 1, y);
        }
        // Heads are drawn as small solid corners rather than rotated triangles: at this resolution a
        // rotated triangle rasterises to something lumpier than the corner it is meant to suggest.
        diagonalHead(body, nwse ? HOTSPOT - 10 : HOTSPOT + 10, HOTSPOT - 10, nwse ? +1 : -1, +1);
        diagonalHead(body, nwse ? HOTSPOT + 10 : HOTSPOT - 10, HOTSPOT + 10, nwse ? -1 : +1, -1);
        return outline(body);
    }

    private static void diagonalHead(boolean[] body, int x, int y, int dirX, int dirY) {
        for (int i = 0; i < 7; i++) {
            plot(body, x + dirX * i, y);
            plot(body, x, y + dirY * i);
        }
    }

    private static void plot(boolean[] body, int x, int y) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
        body[y * SIZE + x] = true;
    }

    /**
     * Turns a boolean body mask into ARGB, adding a black outline in every pixel adjacent to the body.
     *
     * <p>Deriving the outline from the body is the point: hand-drawing both would let them fall out of
     * step the first time a shape changed, and the failure mode — a cursor with a gap in its
     * silhouette — is subtle enough to survive review.</p>
     */
    private static int[] outline(boolean[] body) {
        int[] argb = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = y * SIZE + x;
                if (body[i]) {
                    argb[i] = BODY;
                } else if (adjacentToBody(body, x, y)) {
                    argb[i] = OUTLINE;
                } else {
                    argb[i] = TRANSPARENT;
                }
            }
        }
        return argb;
    }

    private static boolean adjacentToBody(boolean[] body, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE) continue;
                if (body[ny * SIZE + nx]) return true;
            }
        }
        return false;
    }
}
