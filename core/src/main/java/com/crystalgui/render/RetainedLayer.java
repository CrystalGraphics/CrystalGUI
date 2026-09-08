package com.crystalgui.render;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;

/**
 * A flattened subtree kept between frames — Flutter's {@code RepaintBoundary}, Qt Quick's batch root.
 *
 * <p>Painting a layer is the expensive half of an {@code opacity} or a mask: a clear, a walk, every
 * draw under it, a composite. Only the composite has to happen again when nothing under it changed, and
 * a retained layer is what makes the difference expressible — the texture is still there, so the frame
 * pays for one quad.</p>
 *
 * <pre>{@code
 * RetainedLayer layer = ctx.retain(box, region, box.subtreeRevision());
 * if (layer != null && layer.isFresh()) {
 *     ctx.blitLayer(layer.fbo(), opacity, region);      // nothing under it changed
 *     return;
 * }
 * CgFrameBuffer target = layer != null ? ctx.beginLayerFbo(layer.fbo(), region) : ctx.beginLayerFbo(region);
 * // ...paint the subtree...
 * ctx.endLayerFbo();
 * if (layer != null) layer.painted();
 * ctx.blitLayer(target, opacity, region);
 * }</pre>
 *
 * <p><b>Null is the ordinary answer</b>, not a failure: a subtree that paints by hand, or one the
 * retention budget will not stretch to, is painted straight into a pooled target as before.</p>
 */
public final class RetainedLayer {

    private final CgFrameBuffer fbo;

    /** The texture this subtree was last flattened into. Owned by {@link CgUiPaintContext}. */
    public CgFrameBuffer fbo() {
        return fbo;
    }

    /** Where it sat when it was drawn — a layer that moved is redrawn, not slid. */
    LayerRegion region;

    /** The subtree revision {@link #fbo} holds. */
    long revision;

    /** The frame it was last asked for, for eviction. */
    long lastFrame;

    private boolean fresh;
    private long paintedRevision;

    RetainedLayer(CgFrameBuffer fbo, LayerRegion region, long revision) {
        this.fbo = fbo;
        this.region = region;
        this.revision = revision;
    }

    /** Whether {@link #fbo} already holds this frame's picture and can simply be composited. */
    public boolean isFresh() {
        return fresh;
    }

    void setFresh(boolean fresh, long revision) {
        this.fresh = fresh;
        this.paintedRevision = revision;
    }

    /** Records that the caller has just drawn this subtree into {@link #fbo}. */
    public void painted() {
        this.revision = paintedRevision;
        this.fresh = true;
    }
}
