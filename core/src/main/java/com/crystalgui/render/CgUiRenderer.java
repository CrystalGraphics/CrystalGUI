package com.crystalgui.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgCurveBatch;
import com.crystalgraphics.render.canvas.CgQuadBatch;

/**
 * The paint context's quads and curves on its {@link CgCanvas}: each item started with the context's current
 * material, state and hand-bound texture, its pose, clip entry and nodes. A run of items that share those is one draw.
 *
 * <h3>Positions are written unrounded, deliberately</h3>
 * <p>Snapping each transformed corner made a 1-logical-pixel border 1px on one widget and 2px on its neighbour at a
 * fractional {@code uiScale}. Neither Minecraft's GUI nor Blink snaps here; the one snap that matters is the root
 * origin's ({@code UIWindow.resolveRootPlacement}).</p>
 */
public final class CgUiRenderer {

    private final CgUiPaintContext ctx;
    private final CgCanvas canvas;

    private CgMaterial quadMaterial;
    private CgRenderState quadState;
    private CgTexture quadTexture;
    private CgMaterial curveMaterial;

    CgUiRenderer(CgUiPaintContext ctx, CgCanvas canvas) {
        this.ctx = ctx;
        this.canvas = canvas;
    }

    /** Ends the open run: into the recording while the context records, else drawn now. */
    public void flush() {
        canvas.flush();
    }

    /** The material later {@link #quad()}s draw with, under its own state; drops the hand-bound texture. */
    public void useMaterial(CgMaterial material) {
        useMaterial(material, null);
    }

    /** As {@link #useMaterial(CgMaterial)}, under {@code state} instead of the material's. */
    void useMaterial(CgMaterial material, CgRenderState state) {
        quadMaterial = material;
        quadState = state;
        quadTexture = null;
    }

    /** Unit 0 for later quads, over the material's sampler there; null gives it back to the material. */
    void bindTexture(int unit, CgTexture texture) {
        if (unit != 0) throw new IllegalArgumentException("only unit 0 is bound by hand: " + unit);
        quadTexture = texture;
    }

    /** The material later curves, triangles and cells draw with. */
    public void useCurveMaterial(CgMaterial material) {
        curveMaterial = material;
    }

    /**
     * A quad with the context's pose, clip and nodes applied. Never call {@code .pose(...)} on it: that drops
     * {@code uiScale} and the element's transform.
     */
    public CgQuadBatch.Quad quad() {
        if (!ctx.isFrameActive()) throw new IllegalStateException("Cannot submit quads outside beginFrame()/endFrame()");
        if (quadMaterial == null) throw new IllegalStateException("no quad material: useMaterial first");
        CgQuadBatch.Quad quad = canvas.quad(quadMaterial).state(quadState);
        if (quadTexture != null) quad.texture(0, quadTexture);
        return quad.pose(ctx.getPoseStack().last().pose()).clip(ctx.clipEntry()).node(ctx.spatialNode(), ctx.effectNode());
    }

    /** A Bézier stroke, posed as {@link #quad()}; its width scales with the pose, so it is in logical pixels. */
    public CgCurveBatch.Curve curve() {
        return canvas.curve(curveMaterial()).pose(ctx.getPoseStack().last().pose()).clip(ctx.clipEntry())
                .node(ctx.spatialNode(), ctx.effectNode());
    }

    /** A filled triangle, posed as {@link #curve()}: its corner radius and feather scale with the pose. */
    public CgCurveBatch.Triangle triangle() {
        return canvas.triangle(curveMaterial()).pose(ctx.getPoseStack().last().pose()).clip(ctx.clipEntry())
                .node(ctx.spatialNode(), ctx.effectNode());
    }

    /** A cell of a tessellated fill, posed as {@link #curve()}; coverage is exact area in device pixels. */
    public CgCurveBatch.Cell filledCell() {
        return canvas.cell(curveMaterial()).pose(ctx.getPoseStack().last().pose()).clip(ctx.clipEntry())
                .node(ctx.spatialNode(), ctx.effectNode());
    }

    private CgMaterial curveMaterial() {
        if (!ctx.isFrameActive()) throw new IllegalStateException("Cannot submit curves outside beginFrame()/endFrame()");
        if (curveMaterial == null) throw new IllegalStateException("no curve material: useCurveMaterial first");
        return curveMaterial;
    }
}
