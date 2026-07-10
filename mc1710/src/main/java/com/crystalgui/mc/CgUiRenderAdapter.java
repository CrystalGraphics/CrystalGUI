package com.crystalgui.mc;

import com.crystalgui.ui.UIContainer;
import org.joml.Matrix4f;

/**
 * Stateless render adapter for hosting a CrystalGUI {@link UIContainer} in
 * any OpenGL surface (Minecraft drawScreen, overlay, HUD, etc.).
 *
 * <p>Provides layout computation and draw-list rendering in a single call.
 * The caller controls when and where rendering happens — this adapter does
 * not own the container or manage any GL state beyond what
 * {@link UIContainer#render(Matrix4f)} touches internally.</p>
 *
 * <p>A reusable {@link Matrix4f} is held to avoid per-frame allocation.
 * This means the adapter is <em>not</em> thread-safe (which is fine —
 * rendering is single-threaded on the LWJGL thread).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CgUiRenderAdapter renderer = new CgUiRenderAdapter();
 *
 * // In any render callback (GuiScreen.drawScreen, overlay, HUD, etc.):
 * renderer.renderContainer(container, guiWidth, guiHeight);
 * }</pre>
 */
public final class CgUiRenderAdapter {

    private final Matrix4f orthoProjection = new Matrix4f();

    /**
     * Computes layout for the container at the given surface dimensions,
     * then renders it using an orthographic projection.
     *
     * <p>The width/height should be in the same coordinate space that the
     * container's elements were laid out in (typically GUI-scaled pixels
     * for Minecraft, or raw pixels for the harness).</p>
     *
     * @param container the UI container to render (must have a document attached)
     * @param width     surface width in layout units
     * @param height    surface height in layout units
     */
    public void renderContainer(UIContainer container, float width, float height) {
        container.computeLayout(width, height);
        orthoProjection.setOrtho(0, width, height, 0, -1, 1);
        container.render(orthoProjection);
    }

    /**
     * Renders the container with a caller-supplied projection matrix.
     * Layout is computed at the given dimensions, then rendering uses the
     * provided projection. For non-standard projections or nested viewports.
     *
     * @param container  the UI container to render
     * @param width      surface width for layout computation
     * @param height     surface height for layout computation
     * @param projection caller-owned projection matrix
     */
    public void renderContainer(UIContainer container, float width, float height, Matrix4f projection) {
        container.computeLayout(width, height);
        container.render(projection);
    }
}
