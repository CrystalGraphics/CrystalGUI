package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgGraphTexture;

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
 *     ctx.blitLayer(layer.target(), opacity, region);   // nothing under it changed
 *     return;
 * }
 * CgGraphTexture target = layer != null ? ctx.beginLayerFbo(layer.target(), region) : ctx.beginLayerFbo(region);
 * // ...paint the subtree...
 * ctx.endLayerFbo();
 * if (layer != null) layer.painted();
 * ctx.blitLayer(target, opacity, region);
 * }</pre>
 *
 * <p><b>Null is the ordinary answer</b>, not a failure: a subtree that paints by hand, or one the
 * retention budget will not stretch to, is painted into a texture the executor lends for the frame.</p>
 */
public final class RetainedLayer {

    private final CgGraphTexture target;

    /** The texture this subtree was last flattened into: made when a frame executes, owned by the paint context. */
    public CgGraphTexture target() {
        return target;
    }

    /** Where it sat when it was drawn — a layer that moved is redrawn, not slid. */
    LayerRegion region;

    /** The subtree revision {@link #target} holds. */
    long revision;

    /** The frame it was last asked for, for eviction. */
    long lastFrame;

    private boolean fresh;
    private long paintedRevision;

    RetainedLayer(CgGraphTexture target, LayerRegion region, long revision) {
        this.target = target;
        this.region = region;
        this.revision = revision;
    }

    /** Whether {@link #target} already holds this frame's picture and can simply be composited. */
    public boolean isFresh() {
        return fresh;
    }

    void setFresh(boolean fresh, long revision) {
        this.fresh = fresh;
        this.paintedRevision = revision;
    }

    /** Records that the caller has just drawn this subtree into {@link #target}. */
    public void painted() {
        this.revision = paintedRevision;
        this.fresh = true;
    }
}
