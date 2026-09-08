package com.crystalgui.render;

/**
 * Where a layer goes and how big it is, in its target's physical pixels.
 *
 * <p>A layer used to be the size of the display whatever it held, so the clear, the draw and the
 * composite were all full-screen for a 20×20 element at {@code opacity: 0.9}. A region is the answer
 * to all three at once: the offscreen is allocated for it, cleared over it, and composited back at it.
 * It is Skia's {@code saveLayer} bounds argument, which exists for exactly this reason.</p>
 *
 * <p><b>Integer, and outward-rounded.</b> Ink bounds are floats and a partly-covered pixel is a
 * covered pixel; a region that rounded to nearest would drop the last fraction of an antialiased edge,
 * which is visible precisely on the shapes this engine draws analytically.</p>
 *
 * <p>Coordinates are the TARGET's, not the screen's: a layer inside another layer is positioned within
 * the enclosing one, because that is where it is composited back to.</p>
 */
public record LayerRegion(int x, int y, int width, int height) {

    /** Whether this covers anything at all. */
    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    /** The same size, at the origin — what an inner layer of an enclosing one occupies. */
    public LayerRegion atOrigin() {
        return x == 0 && y == 0 ? this : new LayerRegion(0, 0, width, height);
    }
}
