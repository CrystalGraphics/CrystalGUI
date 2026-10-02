package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgGraphTexture;

/**
 * A box the compositor moves, drawn into a texture of its own -- DWM's redirection surface (render-graph G7). The
 * frame draws the texture under the box's node, so a move or a fade is the node's value and leaves the texture as it
 * is. A window is the box it is for.
 *
 * <pre>{@code
 * Surface surface = ctx.surface(box, region);
 * if (surface == null) { ... paint into the target as any box ... }
 * if (!surface.holds(box.innerRevision(), x, y, region, epoch, stacking)) {   // x, y: the region's place in the node
 *     ctx.beginLayerFbo(surface.target(), region);
 *     ... paint the box and its subtree, at opacity 1 ...
 *     ctx.endLayerFbo();
 *     surface.drew(box.innerRevision(), x, y, region, epoch, stacking);
 * }
 * ctx.blitLayer(surface.target(), opacity, region, fades);
 * }</pre>
 *
 * <p>Owned by the paint context that made it, which frees it when nothing asks for it for a while. Null is the answer
 * inside a copy drawn elsewhere ({@link CgUiPaintContext#withoutRetention}), where the box draws as any other.</p>
 */
public final class Surface {

    private final CgGraphTexture target;

    /** The frame it was last asked for, for freeing. */
    long lastFrame;

    /** What its picture was drawn for: the box's inner revision, its region in its node, the epochs. */
    private long revision = -1L, epoch, stacking;
    private int nodeX, nodeY, width, height;

    Surface(CgGraphTexture target) {
        this.target = target;
    }

    /** The texture the box is drawn into, kept across frames. */
    public CgGraphTexture target() {
        return target;
    }

    /**
     * Whether it already holds the box's picture: drawn at {@code revision} (the box's inner revision), over
     * {@code region} placed at {@code (nodeX, nodeY)} in the box's node, under the replay and stacking epochs given.
     * Where it does, composite it and draw nothing.
     */
    public boolean holds(long revision, int nodeX, int nodeY, LayerRegion region, long epoch, long stacking) {
        return this.revision == revision && this.nodeX == nodeX && this.nodeY == nodeY && width == region.width()
                && height == region.height() && this.epoch == epoch && this.stacking == stacking;
    }

    /** Why {@link #holds} answers false, as a trace counter's name: what keeps a surface from being kept. */
    public String missed(long revision, int nodeX, int nodeY, LayerRegion region, long epoch, long stacking) {
        if (this.revision == -1L) return "surface-miss-new";
        if (this.revision != revision) return "surface-miss-revision";
        if (this.nodeX != nodeX || this.nodeY != nodeY || width != region.width() || height != region.height()) {
            return "surface-miss-region";
        }
        if (this.epoch != epoch) return "surface-miss-epoch";
        return "surface-miss-stacking";
    }

    /** Records that the box was just drawn into it, as {@link #holds} names. */
    public void drew(long revision, int nodeX, int nodeY, LayerRegion region, long epoch, long stacking) {
        this.revision = revision;
        this.nodeX = nodeX;
        this.nodeY = nodeY;
        width = region.width();
        height = region.height();
        this.epoch = epoch;
        this.stacking = stacking;
    }

    /** Forgets what it holds: the next {@link #holds} answers false. */
    public void invalidate() {
        revision = -1L;
    }
}
