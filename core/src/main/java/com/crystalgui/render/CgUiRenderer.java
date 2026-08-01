package com.crystalgui.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.gl.render.CgQuadRenderer;

/**
 * Thin wrapper over {@link CgQuadRenderer} — CrystalGUI's whole box-model quad path.
 *
 * <h3>Why instanced quads rather than a vertex stream</h3>
 * <p>This used to wrap {@code CgBatchRenderer}, writing four transformed vertices per quad through a
 * {@code PoseStack}-aware {@code CgVertexConsumer}. {@link CgQuadRenderer} expresses the same thing as
 * one per-instance record — {@code origin} plus the two edge vectors {@code right}/{@code up} — living
 * in a class-wide SSBO/TBO, with a single shared unit-quad mesh drawn instanced.</p>
 *
 * <p>The pose is no longer applied per vertex on the CPU here: {@link CgQuadRenderer.Quad#pose} bakes
 * it at submit time by running {@code origin} through {@code transformPosition} and {@code right}/
 * {@code up} through {@code transformDirection}. That is three transforms per quad instead of four,
 * and it stays correct under the full affine {@code transform:} can produce — rotation, scale and skew
 * all act on the two edge vectors exactly as they would on the four corners.</p>
 *
 * <p>It also puts UI quads on the same path CrystalGraphics' own {@code CgTextRenderer} already uses,
 * so text and box-model geometry no longer reach the GPU through two different renderers.</p>
 *
 * <h3>Material binding is not ours to do</h3>
 * <p>{@link CgQuadRenderer#useMaterial} owns bind/unbind and must be called before any
 * {@code submit()} — {@code submit()} throws otherwise, since that call is the renderer's only
 * confirmation that its instance buffer is actually attached to the bound material. Callers therefore
 * go through {@link #useMaterial(CgMaterial)} and never call {@code material.bind()} themselves.</p>
 */
public final class CgUiRenderer {

    private final CgQuadRenderer renderer;
    private final CgVectorRenderer vectorRenderer;
    private final CgUiPaintContext ctx;

    CgUiRenderer(CgUiPaintContext ctx) {
        this.renderer = CgQuadRenderer.create();
        this.vectorRenderer = CgVectorRenderer.create();
        this.ctx = ctx;
    }

    void begin() {
        renderer.begin();
        vectorRenderer.begin();
    }

    void end() {
        renderer.end();
        vectorRenderer.end();
    }

    /**
     * Flushes whatever is queued on either path — the only point at which anything is uploaded or
     * drawn. {@code submit()} alone never touches the GPU.
     *
     * <p>Flushing both is safe in either order because <b>at most one path ever holds pending work</b>:
     * {@link CgUiPaintContext} flushes the outgoing path when it switches between quads and curves,
     * which it must do anyway to swap the bound material. An empty flush is a cheap early return.</p>
     */
    public void flush() {
        renderer.flush();
        vectorRenderer.flush();
    }

    /**
     * Flushes only the quad path. Called by {@link CgUiPaintContext} when switching to curves —
     * the quads must reach the GPU while <em>their</em> material is still bound.
     */
    void flushQuads() {
        renderer.flush();
    }

    /** Flushes only the curve path, for the same reason in the other direction. */
    void flushCurves() {
        vectorRenderer.flush();
    }

    /**
     * Binds {@code material} and marks it as the owner of subsequent {@link #quad()} submissions.
     *
     * <p>Must be called again every frame, even for the same material — other rendering sharing this
     * GL context can rebind a different program between two of our frames, so "bound once" is not
     * "still bound". Switching materials auto-flushes whatever was queued for the previous one, since
     * those instances were computed against its shader.</p>
     */
    public void useMaterial(CgMaterial material) {
        renderer.useMaterial(material);
    }

    /**
     * The curve-path equivalent of {@link #useMaterial(CgMaterial)}.
     *
     * <p>Separate because {@link CgVectorRenderer} has its own instance buffer and its own
     * bind/unbind bookkeeping — a curve material and a quad material are never interchangeable, and
     * a single {@code useMaterial} taking both would silently let a caller submit quads against
     * {@code gui_curve.shader}, which declares no {@code QUAD_DATA} and would draw nothing.</p>
     */
    public void useCurveMaterial(CgMaterial material) {
        vectorRenderer.useMaterial(material);
    }

    /**
     * Starts a quad, <b>with this context's pose already applied</b>.
     *
     * <p>The single place the {@code PoseStack} is bound to a quad. {@code CgQuadRenderer.quad()}
     * resets its scratch instance — including setting {@code pose} back to {@code null} — so the pose
     * is re-applied here on every call rather than being sticky. Callers therefore keep passing plain
     * logical-space coordinates and cannot forget the transform; forgetting it would draw at
     * unscaled, untranslated screen coordinates, which looks like a layout bug rather than a missing
     * matrix.</p>
     *
     * <p>CPU-side only — {@code submit()} queues, {@link #flush()} draws.</p>
     *
     * <h3>Positions are written unrounded, deliberately</h3>
     * <p>Do not add a snap-to-whole-pixels here. An earlier version of this path (the old
     * {@code VertexWriter}) rounded each transformed vertex, which is what made a 1-logical-pixel
     * border render 1px on one widget and 2px on its neighbour at a fractional {@code uiScale} —
     * adjacent elements sit at different fractional offsets, so each independently rounds a
     * different way. Measured on Ore's tab rail: at scale 2 every border run is exactly 2px, at 1.5
     * they scatter between 1 and 2 with no pattern.</p>
     *
     * <p>Neither reference implementation snaps at this level. Minecraft 1.21's
     * {@code BufferBuilder.addVertex} writes raw floats, {@code POSITION} is non-normalised
     * {@code FLOAT}×3, and none of its GUI vertex shaders contains a rounding intrinsic — vanilla's
     * pixel alignment falls out of its GUI API being int-only in logical space over an integer
     * {@code guiScale}, never an explicit round.</p>
     *
     * <p>CrystalGUI already has the snap that matters, at the root origin:
     * {@code UIWindow.resolveRootPlacement} rounds {@code leftPos}/{@code topPos}. That one keeps an
     * unzoomed UI aligned and stays. A second snap here only destroys sub-pixel placement.</p>
     */
    public CgQuadRenderer.Quad quad() {
        if (!ctx.isFrameActive()) throw new IllegalStateException("Cannot submit quads outside beginFrame()/endFrame()");

        return renderer.quad().pose(ctx.getPoseStack().last().pose());
    }

    /**
     * Starts a Bézier stroke, <b>with this context's pose already applied</b> — the curve-path twin
     * of {@link #quad()}, and the single place the {@code PoseStack} is bound to a curve.
     *
     * <p>Same reasoning throughout: {@code CgVectorRenderer.curve()} resets its scratch instance
     * (clearing {@code pose} back to {@code null}), so the pose is re-applied on every call rather
     * than being sticky, and callers keep passing plain logical-space coordinates.</p>
     *
     * <p><b>Stroke widths follow the pose's scale.</b> {@code CgVectorRenderer.Curve#submit} bakes the
     * pose into the control points and scales {@code width}/{@code feather} by its uniform scale, so
     * a width passed here is in the same logical units as the coordinates — a 2px stroke stays 2
     * logical px at any {@code uiScale}, exactly as a 2px border does. That is the behaviour a caller
     * expects and the reason widths are not passed pre-scaled.</p>
     *
     * <p>Positions are written unrounded, for the identical reason spelled out on {@link #quad()} —
     * and more so here, since a curve's antialiased edge is derived from a continuous distance field
     * and snapping its control points would quantise the whole stroke, not just its extremities.</p>
     *
     * <p>CPU-side only — {@code submit()} queues, {@link #flush()} draws.</p>
     */
    public CgVectorRenderer.Curve curve() {
        if (!ctx.isFrameActive()) throw new IllegalStateException("Cannot submit curves outside beginFrame()/endFrame()");

        return vectorRenderer.curve().pose(ctx.getPoseStack().last().pose());
    }

    /**
     * Starts a filled triangle, <b>with this context's pose already applied</b> — the fill-mode
     * twin of {@link #curve()}. Shares the same {@code CgVectorRenderer} and the same curve material,
     * so submitting a triangle does <em>not</em> switch material paths any differently than
     * submitting a curve does — see {@code CgUiPaintContext.beginCurvePath()}.
     *
     * <p>Same pose/scale/unrounded-coordinates conventions as {@link #curve()}: corner radius and
     * feather scale with the pose exactly as a stroke width would, since both are distances in the
     * same space as the points.</p>
     */
    public CgVectorRenderer.Triangle triangle() {
        if (!ctx.isFrameActive()) throw new IllegalStateException("Cannot submit triangles outside beginFrame()/endFrame()");

        return vectorRenderer.triangle().pose(ctx.getPoseStack().last().pose());
    }

    /**
     * Releases what this renderer owns.
     *
     * <p>Called by {@link CgUiPaintContext#destroy()}. {@link CgQuadRenderer#delete()} only unbinds
     * the material it left bound — the shared unit-quad mesh and instance buffer are class-wide and
     * registry-owned, so they deliberately outlive any one renderer.</p>
     */
    void delete() {
        renderer.delete();
        vectorRenderer.delete();
    }
}
