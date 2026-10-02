package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgGraphTexture;

import java.util.Arrays;

/**
 * A box the compositor moves, drawn into a texture of its own -- DWM's redirection surface (render-graph G7). The
 * frame draws the texture under the box's node, so a move or a fade is the node's value and leaves the texture as it
 * is. A window is the box it is for.
 *
 * <pre>{@code
 * Surface surface = ctx.surface(window, region);                 // keyed by what it is for
 * if (surface == null) { ... paint into the target as any box ... }
 * if (!surface.holds(box, box.innerRevision(), x, y, region, epoch, stacking)) {   // x, y: the region in the node
 *     ctx.beginSurface(surface, region, !surface.drewAt(box, x, y, region));
 *     ... paint the box and its subtree at rest, at opacity 1 ...
 *     ctx.endSurface();
 *     surface.drew(box, box.innerRevision(), x, y, region, epoch, stacking);
 * }
 * ctx.blitLayer(surface.target(), opacity, region, fades);
 * }</pre>
 *
 * <p>Owned by the paint context that made it, which frees it when nothing asks for it for a while -- unless what it is
 * for is an {@link Owner} that keeps it. Null is the answer inside a copy drawn elsewhere
 * ({@link CgUiPaintContext#withoutRetention}), where the box draws as any other.</p>
 *
 * <p><b>Its picture</b> is what a thumbnail, a preview or the switcher draws ({@link #drawPicture}): the window as it
 * last painted, at rest, a minimised one included.</p>
 *
 * <p><b>Damage</b> (render-graph G7.3): a surface walked again executes only what changed in it. Each piece of drawing
 * in it is a {@link Tenant} that {@link #damage}s its old and new place when it drew anew or moved, and one the walk no
 * longer reaches is told to {@link Tenant#vacate}; the paint context cuts the surface's passes to the union.</p>
 */
public final class Surface {

    private final CgGraphTexture target;

    /** The frame it was last asked for, for freeing. */
    long lastFrame;

    /** Check mode: where the box is painted again whole, to compare with this. @see CgUiPaintContext#DAMAGE_CHECK */
    CgGraphTexture checkTarget;

    /** Freed by its context: it holds nothing any more. */
    boolean released;

    /**
     * What a surface is keyed by, told of it each time it is drawn, and asked whether to keep it while nothing paints
     * it -- a minimised window, whose preview still shows its picture.
     *
     * <pre>{@code
     * class WindowFrame extends UIElement implements Surface.Owner {
     *     public void surface(Surface surface) { this.surface = surface; }
     *     public boolean keepsSurface() { return state == WindowState.HIDDEN; }
     * }
     * }</pre>
     */
    public interface Owner {
        /** The surface just drawn or composited for it. */
        void surface(Surface surface);

        /** Whether to keep its surface while it is not drawn: false lets it go after a few seconds unasked for. */
        boolean keepsSurface();
    }

    /** The box's border box in the texture's pixels, top-down, and its size in the box's own units. */
    private float pictureX0, pictureY0, pictureX1, pictureY1, pictureWidth, pictureHeight;

    /** A piece of drawing kept in a surface, told when a walk no longer reaches it so it can damage where it was. */
    public interface Tenant {
        /** The walk at {@code frame} did not reach {@code part}: damage its old place in {@code surface}, if it was here. */
        void vacate(Surface surface, int part, long frame);
    }

    /** This walk's damage in the surface's pixels, top-down, and whether all of it is damaged. */
    private float damageX0, damageY0, damageX1, damageY1;
    private boolean full;
    private long walkFrame;
    /** Who the walk reached, and who the last one did. */
    private Tenant[] visited = new Tenant[64], held = new Tenant[64];
    private int[] visitedParts = new int[64], heldParts = new int[64];
    private int visitedCount, heldCount;

    /** What its picture was drawn for: the box, its inner revision, its region in its node, the epochs. */
    private Object box;
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
     * Whether it already holds {@code box}'s picture: drawn at {@code revision} (the box's inner revision), over
     * {@code region} placed at {@code (nodeX, nodeY)} in the box's node, under the replay and stacking epochs given.
     * Where it does, composite it and draw nothing. A window shown again is a new box over the same surface.
     */
    public boolean holds(Object box, long revision, int nodeX, int nodeY, LayerRegion region, long epoch,
                         long stacking) {
        return this.box == box && this.revision == revision && this.nodeX == nodeX && this.nodeY == nodeY
                && width == region.width() && height == region.height() && this.epoch == epoch
                && this.stacking == stacking;
    }

    /** Why {@link #holds} answers false, as a trace counter's name: what keeps a surface from being kept. */
    public String missed(Object box, long revision, int nodeX, int nodeY, LayerRegion region, long epoch,
                         long stacking) {
        if (this.revision == -1L) return "surface-miss-new";
        if (this.box != box) return "surface-miss-box";
        if (this.revision != revision) return "surface-miss-revision";
        if (this.nodeX != nodeX || this.nodeY != nodeY || width != region.width() || height != region.height()) {
            return "surface-miss-region";
        }
        if (this.epoch != epoch) return "surface-miss-epoch";
        return "surface-miss-stacking";
    }

    /** Records that the box was just drawn into it, as {@link #holds} names. */
    public void drew(Object box, long revision, int nodeX, int nodeY, LayerRegion region, long epoch, long stacking) {
        this.box = box;
        this.revision = revision;
        this.nodeX = nodeX;
        this.nodeY = nodeY;
        width = region.width();
        height = region.height();
        this.epoch = epoch;
        this.stacking = stacking;
    }

    /**
     * Whether its picture was drawn for {@code box} over this same region of the node: a moved region moves everything
     * in it, and another box's pieces are not this one's.
     */
    public boolean drewAt(Object box, int nodeX, int nodeY, LayerRegion region) {
        return revision != -1L && this.box == box && this.nodeX == nodeX && this.nodeY == nodeY
                && width == region.width() && height == region.height();
    }

    /**
     * Where the box's border box lies in the texture, {@code x0, y0, x1, y1} top-down, and its size in the box's own
     * units: what {@link #drawPicture} draws. Set with each walk.
     */
    public void pictured(float x0, float y0, float x1, float y1, float width, float height) {
        pictureX0 = x0;
        pictureY0 = y0;
        pictureX1 = x1;
        pictureY1 = y1;
        pictureWidth = width;
        pictureHeight = height;
    }

    /** Whether it holds a picture to draw: drawn at least once, and not freed. */
    public boolean hasPicture() {
        return !released && revision != -1L && pictureX1 > pictureX0 && pictureY1 > pictureY0;
    }

    /** The box's width when its picture was drawn, in its own units: what a thumbnail fits to. */
    public float pictureWidth() {
        return pictureWidth;
    }

    /** @see #pictureWidth */
    public float pictureHeight() {
        return pictureHeight;
    }

    /**
     * Draws the box's picture -- its border box, without what it paints past it -- into {@code (x, y, width, height)}
     * through the context's pose, tinted by its colour. Nothing when it has none.
     *
     * <pre>{@code
     * Surface surface = window.surface();
     * if (surface != null) surface.drawPicture(ctx, 0f, 0f, box.width(), box.height());
     * }</pre>
     */
    public void drawPicture(CgUiPaintContext ctx, float x, float y, float width, float height) {
        if (!hasPicture()) return;
        float w = target.getWidth(), h = target.getHeight();
        ctx.drawLayer(target, x, y, width, height, pictureX0 / w, 1f - pictureY0 / h, pictureX1 / w,
                1f - pictureY1 / h);
    }

    /** Starts a walk at {@code frame}, damaged everywhere with {@code full}. */
    void beginWalk(long frame, boolean full) {
        walkFrame = frame;
        this.full = full;
        damageX0 = damageY0 = Float.MAX_VALUE;
        damageX1 = damageY1 = -Float.MAX_VALUE;
        visitedCount = 0;
    }

    /** Marks {@code (x0, y0)-(x1, y1)}, the surface's pixels top-down, as drawn again this walk. */
    public void damage(float x0, float y0, float x1, float y1) {
        if (x1 <= x0 || y1 <= y0) return;
        damageX0 = Math.min(damageX0, x0);
        damageY0 = Math.min(damageY0, y0);
        damageX1 = Math.max(damageX1, x1);
        damageY1 = Math.max(damageY1, y1);
    }

    /** Records that this walk reached {@code tenant}'s {@code part}. */
    public void visit(Tenant tenant, int part) {
        if (visitedCount == visited.length) {
            visited = Arrays.copyOf(visited, visitedCount * 2);
            visitedParts = Arrays.copyOf(visitedParts, visitedCount * 2);
        }
        visited[visitedCount] = tenant;
        visitedParts[visitedCount++] = part;
    }

    /** Ends the walk: everything the last one reached and this one did not vacates. */
    void endWalk() {
        for (int i = 0; i < heldCount; i++) held[i].vacate(this, heldParts[i], walkFrame);
        Arrays.fill(held, 0, heldCount, null);
        Tenant[] tenants = held;
        int[] parts = heldParts;
        held = visited;
        heldParts = visitedParts;
        heldCount = visitedCount;
        visited = tenants;
        visitedParts = parts;
        visitedCount = 0;
    }

    /** Whether the walk damaged all of it. */
    boolean fullyDamaged() {
        return full;
    }

    /** This walk's damage, {@code x0, y0, x1, y1} top-down, into {@code out}; false when nothing was damaged. */
    boolean damage(float[] out) {
        if (damageX1 <= damageX0 || damageY1 <= damageY0) return false;
        out[0] = damageX0;
        out[1] = damageY0;
        out[2] = damageX1;
        out[3] = damageY1;
        return true;
    }

    /** Forgets what it holds: the next {@link #holds} answers false. */
    public void invalidate() {
        revision = -1L;
    }
}
