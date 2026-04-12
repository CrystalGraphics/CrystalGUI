package com.crystalgui.core.render;

import com.crystalgui.ui.CgUIDrawable;
import io.github.somehussar.crystalgraphics.gl.render.CgBufferSource;
import io.github.somehussar.crystalgraphics.gl.render.CgDynamicTextureRenderLayer;
import io.github.somehussar.crystalgraphics.gl.render.CgRenderLayer;

import lombok.Getter;
import org.joml.Matrix4f;

/**
 * UI-side render context wrapping a {@link CgBufferSource} with typed layer
 * accessors and an integrated {@link ScissorStack}.
 *
 * <p>This is the primary object passed through the UI draw traversal.
 * {@link com.crystalgui.ui.UIContainer} creates and owns a {@code CgUiRenderContext};
 * individual {@link CgUIDrawable} elements paint into it via the
 * ergonomic layer getters ({@link #solid()}, {@link #panel()}, etc.).</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #begin(Matrix4f)} — opens all layers with the ortho projection</li>
 *   <li>UI traversal paints into layers via typed getters</li>
 *   <li>{@link #end()} — flushes remaining geometry and disables scissor</li>
 * </ol>
 *
 * <h3>Scissor invariant (plan §15.6)</h3>
 * <p>{@link #pushScissor(int, int, int, int)} and {@link #popScissor()} always
 * flush <strong>all</strong> dirty layers before changing scissor state, ensuring
 * that already-queued geometry renders with the correct clip region.</p>
 */
public final class CgUIRenderContext {

    /**
     * -- GETTER --
     *  Returns the underlying buffer source.
     *  <p>Prefer the typed accessors (
     * , 
     * , etc.)
     *  for normal UI painting. Direct source access is available for advanced
     *  use cases such as layer-specific flush or custom layer lookup.</p>
     *
     * @return the backing {@link CgBufferSource}
     */
    @Getter
    private final CgBufferSource source;
    private final ScissorStack scissor;

    /**
     * Creates a new render context backed by the given buffer source.
     *
     * @param source the ordered layer collection; must contain layers registered
     *               under the standard {@link CgUILayers} keys
     */
    public CgUIRenderContext(CgBufferSource source) {
        this.source = source;
        this.scissor = new ScissorStack();
    }

    // ── Frame lifecycle ─────────────────────────────────────────────────

    /**
     * Opens a new UI render frame: begins all layers and resets the scissor stack.
     *
     * @param projection the orthographic projection matrix for this frame
     */
    public void begin(Matrix4f projection) {
        source.begin(projection);
        scissor.reset();
    }

    /**
     * Ends the UI render frame: disables scissor and ends all layers
     * (which flushes any remaining dirty geometry).
     */
    public void end() {
        scissor.disable();
        source.end();
    }

    // ── Typed layer accessors ───────────────────────────────────────────

    /** Returns the solid-colour fill layer. */
    public CgRenderLayer solid() {
        return source.get(CgUILayers.SOLID);
    }

    /** Returns the textured panel / nine-slice layer. */
    public CgRenderLayer panel() {
        return source.get(CgUILayers.PANEL);
    }

    /** Returns the SDF-rounded-rect shader layer. */
    public CgRenderLayer rounded() {
        return source.get(CgUILayers.ROUNDED);
    }

    /** Returns the additive overlay layer. */
    public CgRenderLayer overlay() {
        return source.get(CgUILayers.OVERLAY);
    }

    /** Returns the MSDF text layer (dynamic texture). */
    public CgDynamicTextureRenderLayer text() {
        return source.get(CgUILayers.TEXT);
    }

    // ── Scissor stack ───────────────────────────────────────────────────

    /**
     * Pushes a scissor rectangle after flushing all dirty layers.
     *
     * <p>The flush-before-scissor guarantee ensures no queued geometry
     * renders with the wrong clip region.</p>
     *
     * @param x x-origin (screen pixels)
     * @param y y-origin (screen pixels)
     * @param w width (pixels)
     * @param h height (pixels)
     */
    public void pushScissor(int x, int y, int w, int h) {
        source.flushAll();
        scissor.push(x, y, w, h);
    }

    /**
     * Pops the current scissor rectangle after flushing all dirty layers.
     */
    public void popScissor() {
        source.flushAll();
        scissor.pop();
    }

    /**
     * Flushes all dirty layers immediately without ending the frame.
     *
     * <p>Useful for mid-frame synchronisation points (e.g. before reading
     * back the framebuffer or switching render targets).</p>
     */
    public void flushAll() {
        source.flushAll();
    }

    /**
     * Returns the scissor stack for direct inspection (e.g. depth queries).
     *
     * <p>Prefer {@link #pushScissor(int, int, int, int)} / {@link #popScissor()}
     * for normal clip operations — those methods enforce the flush invariant.</p>
     *
     * @return the backing {@link ScissorStack}
     */
    public ScissorStack getScissorStack() {
        return scissor;
    }
}
