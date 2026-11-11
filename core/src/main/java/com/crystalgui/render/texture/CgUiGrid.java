package com.crystalgui.render.texture;

import com.crystalgraphics.render.canvas.CgCanvasGrid;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgui.render.CgUiPaintContext;

/**
 * A ruled grid — {@code grid(<cell>, <colour>[, <line-width>])} as a drawable.
 *
 * <h3>One draw, evaluated per fragment</h3>
 *
 * <p>The whole grid is one quad through CrystalGraphics' {@link CgCanvasGrid}: the cell size and the line
 * width travel as material properties in pixels, and every fragment computes its own coverage from the
 * screen-space derivative of its position in cells. No texture, no tiling, no repeat count — a grid
 * over a 4K backdrop costs exactly what a grid over a thumbnail costs.</p>
 *
 * <h3>Why it is not a repeating sprite</h3>
 *
 * <p>The obvious implementation is a one-cell texture tiled by {@link CgUiSprite}, and it is what the
 * art this replaced actually did: a 817x800 PNG with the grid drawn into it, stretched to whatever
 * the screen was. That art is 825KB, is non-power-of-two, and smears its own lines at every size but
 * the one it was drawn for. A tiled sprite fixes the size but not the sampling: a line is a texel and
 * a texel under a pixel aliases, so the grid sparkles as anything moves and disappears when zoomed
 * out. The technique below has an analytic answer for both.</p>
 *
 * <h3>What the shader is faithful to</h3>
 *
 * <p><b>Ported from the "Pristine Grid" technique</b> — Ben Golus, <i>The Best Darn Grid Shader
 * (Yet)</i> (2023). The three corrections that make it production-grade over the usual
 * {@code smoothstep(fwidth(fract(uv)))}: derivatives measured per axis by length so a rotated grid is
 * still correct; a line never drawn thinner than a pixel but faded by the factor it was widened, so
 * its average brightness survives; and a clean dissolve to the grid's own density once a cell is
 * under a pixel, instead of moire. The reasoning for each is in the shader.</p>
 *
 * <p><b>Licence.</b> Golus's own gist carries no licence statement, so nothing is copied from it. The
 * implementation follows the CC0 Godot port and the MIT WebGPU one, both of the same published
 * technique; the credit is his. See {@code THIRD-PARTY.md}.</p>
 *
 * <h3>Corners</h3>
 *
 * <p>{@link CornerRadiusAware}, so a grid on a rounded element masks itself with the same SDF the
 * rounded wrap would have used — and the documented gap for a self-clipping background applies:
 * {@code border-width} is not stroked over it.</p>
 */
public final class CgUiGrid implements CgUiDrawable, CornerRadiusAware {

    /** Every grid's draw, on the frame thread; made at the first draw, since a server builds grids and draws none. */
    private static CgCanvasGrid gpu;

    private final float cellWidth;
    private final float cellHeight;
    private final float lineWidth;
    private final int argb;

    private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

    /**
     * @param cellWidth  horizontal cell pitch, in logical pixels
     * @param cellHeight vertical cell pitch, in logical pixels
     * @param lineWidth  line thickness in logical pixels — a line thinner than a device pixel is
     *                   drawn a pixel wide and faded rather than dropped
     * @param argb       the line colour, straight (not premultiplied) — {@link #draw} premultiplies
     */
    public CgUiGrid(float cellWidth, float cellHeight, float lineWidth, int argb) {
        // A zero or negative pitch is a division by zero one stage later, and the shader's own clamp
        // would only catch it after the properties had been uploaded. Refused here instead.
        this.cellWidth = Math.max(0.5f, cellWidth);
        this.cellHeight = Math.max(0.5f, cellHeight);
        this.lineWidth = Math.max(0f, lineWidth);
        this.argb = argb;
    }

    public float cellWidth() {
        return cellWidth;
    }

    public float cellHeight() {
        return cellHeight;
    }

    public float lineWidth() {
        return lineWidth;
    }

    public int argb() {
        return argb;
    }

    /**
     * {@code -1}: a grid has no size of its own, so {@code overlay-size} resolves it against the box.
     *
     * <p>The same answer a solid colour gives, and for the same reason — a grid is a field rather than
     * a picture, so there is no natural size for {@code contain} or {@code none} to honour.</p>
     */
    @Override
    public float intrinsicWidth() {
        return -1f;
    }

    @Override
    public float intrinsicHeight() {
        return -1f;
    }

    @Override
    public void setCornerRadii(float rxTL, float ryTL, float rxTR, float ryTR,
                               float rxBR, float ryBR, float rxBL, float ryBL) {
        this.rxTL = rxTL; this.ryTL = ryTL;
        this.rxTR = rxTR; this.ryTR = ryTR;
        this.rxBR = rxBR; this.ryBR = ryBR;
        this.rxBL = rxBL; this.ryBL = ryBL;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        if (lineWidth <= 0f) return;
        int tint = ctx.getColor();
        // A fully transparent tint or a fully transparent line is a draw call that writes nothing.
        if ((tint >>> 24) == 0 || (argb >>> 24) == 0) return;

        if (gpu == null) gpu = new CgCanvasGrid(CgCanvasMaterials.SCREEN);
        gpu.size(width, height).cell(cellWidth, cellHeight).line(lineWidth).color(argb)
                .radii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL).apply();
        ctx.quad(gpu.material()).at(x, y).size(width, height).color(tint).submit();
    }
}
