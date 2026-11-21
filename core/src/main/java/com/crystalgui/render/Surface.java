package com.crystalgui.render;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.canvas.CgKeptLayer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;

/**
 * A box the compositor moves, drawn into a picture kept across frames: a {@link CgKeptLayer} walked when the box's
 * key changes and composited as it is otherwise, so a move or a fade is the node's value. DWM's redirection surface; a
 * window is the box it is for.
 *
 * <pre>{@code
 * Surface surface = ctx.surface(window, x0, y0, x1, y1);        // null: paint into the target as any box
 * try (CgKeptLayer.Walk walk = surface.walk(ctx, box, box.innerRevision(), epoch, stacking, x0, y0, x1, y1)) {
 *     if (walk.draws()) ... paint the box and its subtree at rest ...
 * }                                                              // composited here
 * }</pre>
 *
 * <p>Held by the paint context that made it, which forgets it when nothing asks for it for a while, unless what it
 * is for is an {@link Owner} that keeps it. Null is the answer inside a copy drawn elsewhere
 * ({@link CgUiPaintContext#withoutRetention}).</p>
 *
 * <p><b>Its picture</b> is what a thumbnail, a preview or the switcher draws ({@link #drawPicture}): the window's border
 * box as it last painted, a minimised one included.</p>
 */
public final class Surface {

    private static final CgVar.Bool DAMAGE = CgVar.bool("crystalgui.paint.damage", true, CgVar.DEV,
            "Executes only what changed in a surface walked again. Off: the whole surface executes.");

    /**
     * What a surface is keyed by, told of it each time it is drawn, and asked whether to keep it while nothing paints
     * it: a minimised window, whose preview still shows its picture.
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

        /**
         * Whether something needs its picture now. A box with glass in it is drawn straight into the frame, so only
         * while this is true is it also drawn into a surface, with its glass flat.
         */
        default boolean wantsPicture() {
            return false;
        }

        /**
         * The largest width in device pixels its surface will need, or 0 for no promise. A new surface is made this
         * wide at once, so growing toward it never remakes the texture mid-animation.
         */
        default int reserveWidth() {
            return 0;
        }

        /** @see #reserveWidth */
        default int reserveHeight() {
            return 0;
        }
    }

    final CgKeptLayer layer = new CgKeptLayer();

    /** The frame it was last asked for, for forgetting. */
    long lastFrame;

    /** What its picture was drawn for: the box, its inner revision, its place in its node, the epochs. */
    private Object box;
    private long revision = -1L, epoch, stacking;
    private float x0, y0, x1, y1;
    /** Where the last walk's picture landed, in the node's device pixels. */
    private int pictureX, pictureY;
    /** Its last walk drew text below the glyph tier it asked for: walked again until the glyphs land. */
    private boolean walkAgain;
    /** What the kept layer is walked at: moved whenever the key above changes. */
    private long walked;

    /** The box's border box in the picture's pixels, top-down, and its size in the box's own units. */
    private float pictureX0, pictureY0, pictureX1, pictureY1, pictureWidth, pictureHeight;

    Surface() {
    }

    /**
     * Walks it over {@code (x0, y0)-(x1, y1)} in the canvas's units now, composited when the walk closes: drawn when
     * anything of its key changed (the box, its inner revision, its place, the replay and stacking epochs), held
     * otherwise. A box tree passes its subtree's ink in the node's device pixels, under the identity transform.
     */
    public CgKeptLayer.Walk walk(CgUiPaintContext ctx, Object box, long revision, long epoch, long stacking, float x0,
                                 float y0, float x1, float y1) {
        boolean holds = !walkAgain && this.box == box && this.revision == revision && this.epoch == epoch
                && this.stacking == stacking && this.x0 == x0 && this.y0 == y0 && this.x1 == x1 && this.y1 == y1;
        if (!holds) {
            CgTrace.add(UiTrace.FRAME, missed(box, revision, epoch, stacking, x0, y0, x1, y1), 1);
            walked++;
            walkAgain = false;
            this.box = box;
            this.revision = revision;
            this.epoch = epoch;
            this.stacking = stacking;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
        CgKeptLayer.Walk walk = ctx.canvas().kept(layer, x0, y0, x1 - x0, y1 - y0, walked);
        pictureX = walk.x();
        pictureY = walk.y();
        CgTrace.add(UiTrace.FRAME, walk.draws() ? "surfaces-painted" : "surfaces-kept", 1);
        if (walk.draws() && !DAMAGE.get()) walk.damage(x0, y0, x1 - x0, y1 - y0);
        return walk;
    }

    /**
     * Its whole picture where its last walk put it, under the canvas's transform now: a turned box's composite, its
     * walk composited nowhere.
     */
    public void composite(CgUiPaintContext ctx) {
        if (!layer.hasPicture()) return;
        ctx.canvas().picture(layer, pictureX, pictureY, layer.pictureWidth(), layer.pictureHeight());
    }

    /** Why a walk could not hold the picture, as a trace counter's name. */
    private String missed(Object box, long revision, long epoch, long stacking, float x0, float y0, float x1, float y1) {
        if (this.revision == -1L) return "surface-miss-new";
        if (this.box != box) return "surface-miss-box";
        if (walkAgain) return "surface-miss-degraded";
        if (this.revision != revision) return "surface-miss-revision";
        if (this.x0 != x0 || this.y0 != y0 || this.x1 != x1 || this.y1 != y1) return "surface-miss-region";
        if (this.epoch != epoch) return "surface-miss-epoch";
        return "surface-miss-stacking";
    }

    /**
     * Where the box's border box lies in the picture, {@code x0, y0, x1, y1} in its device pixels from its top-left,
     * and its size in the box's own units: what {@link #drawPicture} draws. Set with each walk that draws.
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
        return layer.hasPicture() && pictureX1 > pictureX0 && pictureY1 > pictureY0;
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
     * Draws the box's picture (its border box, without what it paints past it) into {@code (x, y, width, height)} on
     * the context's canvas. Nothing when it has none.
     *
     * <pre>{@code
     * Surface surface = window.surface();
     * if (surface != null) surface.drawPicture(ctx, 0f, 0f, box.width(), box.height());
     * }</pre>
     */
    public void drawPicture(CgUiPaintContext ctx, float x, float y, float width, float height) {
        if (!hasPicture()) return;
        ctx.canvas().picture(layer, pictureX0, pictureY0, pictureX1 - pictureX0, pictureY1 - pictureY0, x, y, width,
                height);
    }

    /**
     * Draws the box's picture as {@link #drawPicture} does, filtered down to the rect's device size first: each pixel
     * the average of the texels it covers, where one bilinear tap of a picture four or five times larger flickers.
     *
     * <pre>{@code
     * surface.drawThumbnail(ctx, x, y, w, h);
     * if (surface.thumbnailStale()) element.repaint();   // it trails the picture: asked again soon
     * }</pre>
     *
     * <ul>
     *   <li>The filtered copy is kept and filtered again about every 100 ms while the picture changes.</li>
     *   <li>A picture under one and a half times the size asked for, or smaller on either axis, draws as
     *       {@link #drawPicture} does.</li>
     * </ul>
     */
    public void drawThumbnail(CgUiPaintContext ctx, float x, float y, float width, float height) {
        if (!hasPicture()) return;
        ctx.canvas().thumbnail(layer, pictureX0, pictureY0, pictureX1 - pictureX0, pictureY1 - pictureY0, x, y, width,
                height);
    }

    /** Whether the kept thumbnail trails the picture, so its owner asks to be painted again. */
    public boolean thumbnailStale() {
        return layer.thumbnailStale();
    }

    /**
     * Walks it again next frame whatever its key says, its picture standing: what was drawn below the glyph tier it
     * asked for is painted again there, and the rest replays.
     */
    public void walkAgain() {
        walkAgain = true;
    }

    /** Forgets what it holds: the next walk draws it whole. */
    public void invalidate() {
        revision = -1L;
        layer.invalidate();
    }
}
