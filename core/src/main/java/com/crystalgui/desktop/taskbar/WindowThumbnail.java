package com.crystalgui.desktop.taskbar;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowIcon;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * A live picture of a window: the window's surface, scaled into this element's box.
 *
 * <pre>{@code
 * // A box of the window's own shape, fitted into the sheet's size, as a switcher tile's
 * WindowThumbnail tile = new WindowThumbnail().setFrame(window);
 * tile.syncSize();          // per frame: follows a window resized while it is shown
 *
 * // The sheet's height for every window and a width that follows it, as a taskbar preview's
 * WindowThumbnail cell = new WindowThumbnail().setHeightFixed(true).setFrame(window);
 * }</pre>
 *
 * <p>It draws {@link WindowFrame#surface()}, the texture the window is composited from, so it costs one quad and
 * shows the window a frame behind at most. A minimised window keeps its surface ({@link WindowFrame#keepsSurface}),
 * so its picture is the one it last painted; a window that has never painted shows its icon tile instead
 * ({@link #isShowingPlaceholder}).</p>
 *
 * <ul>
 *   <li>The picture is drawn on whole device pixels at a fixed device size, so a thumbnail that moves samples
 *       the same texels every frame. Off the pixel grid, a 4x minified window shimmers as it slides.</li>
 *   <li>Its origin is snapped only once the panel stops moving: snapped while the panel slides, the picture steps
 *       a pixel at a time against the smoothly moving glass, most visibly in the slide's slow tail.</li>
 *   <li>The box's size is written inline: a sheet rule sets the maximum, read once from the first layout, and
 *       nothing else may size the box. A sheet {@code transition} on {@code width} animates the box while the
 *       picture is drawn at its final size, centred and clipped, so a widening box never rescales it.</li>
 * </ul>
 */
public class WindowThumbnail extends UIElement {

    /** Its own kind: a subclass declaring none would report {@code element} and match every bare rule. */
    public static final Name NAME = Name.of("windowthumbnail");

    /** On the element, so a theme can letterbox, border or round the picture. */
    public static final String THUMBNAIL_CLASS = "__thumbnail__";

    /** On the icon tile drawn in place of a picture. @see #isShowingPlaceholder */
    public static final String PLACEHOLDER_CLASS = "__placeholder__";

    @Nullable
    private WindowFrame frame;

    /** Built here and shown or hidden, never added later: the taffyChildIndex rule. */
    private final WindowIcon placeholder = new WindowIcon();
    private boolean placeholderShown;

    /** Whether the box keeps the sheet's height and fits only its width to the window. */
    private boolean heightFixed;

    /** The picture's size, the window fitted into the maximum: what is drawn, whatever the box is mid-way to. */
    private float fitWidth = Float.NaN;
    private float fitHeight = Float.NaN;

    /** The shape a pictureless window fits to: landscape, like most windows. */
    private static final float PLACEHOLDER_ASPECT_W = 5f;
    private static final float PLACEHOLDER_ASPECT_H = 3f;

    /** The size last written, so an unchanged one writes nothing. */
    private float appliedWidth = Float.NaN;
    private float appliedHeight = Float.NaN;

    /** The window fading out, its picture's size, and the fade's start and length. @see #setFrame(WindowFrame, long) */
    @Nullable
    private WindowFrame fadeFrom;
    private float fadeFromWidth, fadeFromHeight;
    private long fadeStart, fadeNanos;

    /** The sheet's box, read before anything is written: the fitted mode's maximum on each axis. */
    private float maxWidth = Float.NaN;
    /** The share of the sheet's size the picture is held to: below 1 when a row of cards would outrun the screen. */
    private float squeeze = 1f;
    private float maxHeight = Float.NaN;

    public WindowThumbnail() {
        super(NAME);
        addClass(THUMBNAIL_CLASS);
        // A click belongs to the panel around it.
        setHitTest(false);
        placeholder.addClass(PLACEHOLDER_CLASS);
        placeholder.setDisplayed(false);
        append(placeholder);
    }

    /**
     * Whether the box keeps the sheet's height for every window and fits only its width (Windows' taskbar
     * previews), rather than taking the window's shape on both axes (the default, its switcher tiles). A window
     * wider than the sheet's width is fitted to it and centred vertically.
     */
    public WindowThumbnail setHeightFixed(boolean heightFixed) {
        this.heightFixed = heightFixed;
        return this;
    }

    /** A window with glass is drawn into a surface only while something wants its picture: this does while in the tree. */
    @Override
    protected void connected() {
        super.connected();
        if (frame != null) frame.wantPicture(this, true);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        if (frame != null) frame.wantPicture(this, false);
    }

    /** The window this shows, or null for none. */
    public WindowThumbnail setFrame(@Nullable WindowFrame frame) {
        return setFrame(frame, 0L);
    }

    /**
     * Shows another window, cross-fading from the one shown over {@code fadeNanos}: the old picture fades out at
     * its own size while the new one fades in at its own. Zero, or no old picture, switches at once.
     *
     * <pre>{@code
     * thumbnail.setFrame(next, 150_000_000L);   // a preview moving to another entry, over its slide
     * }</pre>
     */
    public WindowThumbnail setFrame(@Nullable WindowFrame frame, long fadeNanos) {
        fadeFrom = null;
        if (fadeNanos > 0L && this.frame != null && this.frame != frame && this.frame.surface() != null
                && !Float.isNaN(fitWidth)) {
            fadeFrom = this.frame;
            fadeFromWidth = fitWidth;
            fadeFromHeight = fitHeight;
            fadeStart = System.nanoTime();
            this.fadeNanos = fadeNanos;
            repaint();
        }
        if (this.frame != null && this.frame != frame) this.frame.wantPicture(this, false);
        this.frame = frame;
        if (frame != null && document() != null) frame.wantPicture(this, true);
        placeholder.show(frame == null ? null : frame.iconName(), frame == null ? null : frame.iconTitle());
        syncSize();
        return this;
    }

    /** Whether the icon tile is on show in place of a picture. */
    public boolean isShowingPlaceholder() {
        return placeholderShown;
    }

    private void syncPlaceholder() {
        boolean show = frame != null && !hasPicture();
        if (show == placeholderShown) return;
        placeholderShown = show;
        placeholder.setDisplayed(show);
    }

    /**
     * Brings the box and the placeholder up to date with the window. Cheap and idempotent, so call it per frame
     * while shown: a window can be resized or minimised under its own thumbnail.
     *
     * @return whether the box's size changed, so a caller placing a panel around it measures again
     */
    public boolean syncSize() {
        syncPlaceholder();
        if (!fit()) return false;
        // Height fixed: never narrower than square, so a tall window's panel keeps room for its header.
        float width = heightFixed ? Math.max(fitWidth, Math.min(maxWidth, maxHeight) * squeeze) : fitWidth;
        float height = heightFixed ? maxHeight * squeeze : fitHeight;
        if (Math.abs(width - appliedWidth) < 0.5f && Math.abs(height - appliedHeight) < 0.5f) return false;
        appliedWidth = width;
        appliedHeight = height;
        // Explicit sizes, not aspect-ratio: Taffy does not count a ratio-derived width toward the parent's size.
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.width(width).height(height));
        return true;
    }

    /**
     * Holds the picture to {@code share} of the sheet's size, aspect kept: how a row of many cards fits the screen.
     * Takes effect on the next {@link #syncSize}.
     */
    public void setSqueeze(float share) {
        this.squeeze = share;
    }

    /** The width {@link #syncSize} last wrote, as it would be unsqueezed; NaN before the first. */
    public float naturalWidth() {
        return appliedWidth / squeeze;
    }

    /**
     * The width {@link #syncSize} last wrote: where a box under a width transition is going, before its layout
     * gets there. NaN before the first.
     */
    public float targetWidth() {
        return appliedWidth;
    }

    /** Fits the window's shape into the sheet's maximum, into {@link #fitWidth}; false while there is nothing to measure. */
    private boolean fit() {
        float sourceWidth;
        float sourceHeight;
        Box live = liveBox();
        Surface picture = frame == null ? null : frame.surface();
        if (live != null) {
            sourceWidth = live.width();
            sourceHeight = live.height();
        } else if (picture != null) {
            sourceWidth = picture.pictureWidth();
            sourceHeight = picture.pictureHeight();
        } else if (frame != null) {
            // A pictureless window still has a shape, or the panel around it could never be placed.
            sourceWidth = PLACEHOLDER_ASPECT_W;
            sourceHeight = PLACEHOLDER_ASPECT_H;
        } else {
            return false;
        }
        if (sourceWidth <= 0f || sourceHeight <= 0f) return false;

        if (Float.isNaN(maxWidth)) {
            Box box = box();
            if (box == null || box.width() <= 0f || box.height() <= 0f) return false;
            maxWidth = box.width();
            maxHeight = box.height();
        }

        float scale = Math.min(maxWidth * squeeze / sourceWidth, maxHeight * squeeze / sourceHeight);
        fitWidth = sourceWidth * scale;
        fitHeight = sourceHeight * scale;
        return true;
    }

    /** The window's box while it is on screen; null for a minimised one, which is detached. */
    @Nullable
    private Box liveBox() {
        if (frame == null || frame.state() != WindowState.VISIBLE || frame.parent() == null) {
            return null;
        }
        Box src = frame.box();
        return src != null && src.width() > 0f && src.height() > 0f ? src : null;
    }

    /** Whether there is anything to draw: a window on screen, or one that kept its picture when minimised. */
    public boolean hasPicture() {
        return liveBox() != null || (frame != null && frame.surface() != null);
    }

    /** Painted every frame of a cross-fade: the tree sees no change in it. */
    @Override
    public boolean paintsDynamically() {
        return fadeFrom != null || super.paintsDynamically();
    }

    /** The two pictures' rects in this box during a cross-fade, {@code x, y, width, height, device width, device height}: scratch, per paint. */
    private final float[] oldRect = new float[6], newRect = new float[6];

    /**
     * Draws the window's surface at its fitted size, centred on whole device pixels, clipped to this box.
     *
     * <p>A cross-fade draws the old picture OPAQUE where the new one will cover it and fading only where it will
     * not, then the new one fading in over both: two pictures each part-transparent let the panel's glass through
     * their overlap and washed it out.</p>
     */
    @Override
    public void paintDecoration(CgUiPaintContext ctx, Box box) {
        super.paintDecoration(ctx, box);
        if (box.width() <= 0f || box.height() <= 0f) return;
        notePose(ctx);
        Surface picture = frame == null ? null : frame.surface();
        boolean hasNew = false;
        if (picture != null && picture.pictureWidth() > 0f && picture.pictureHeight() > 0f) {
            if (Float.isNaN(fitWidth)) {
                float scale = Math.min(box.width() / picture.pictureWidth(), box.height() / picture.pictureHeight());
                place(ctx, box, picture.pictureWidth() * scale, picture.pictureHeight() * scale, newRect, !moving);
            } else {
                place(ctx, box, fitWidth, fitHeight, newRect, !moving);
            }
            hasNew = true;
        }
        float in = 1f;
        if (fadeFrom != null) {
            in = (float) CgEasings.OUT_QUAD.ease(System.nanoTime() - fadeStart, fadeNanos);
            Surface old = fadeFrom.surface();
            if (in >= 1f || old == null) {
                fadeFrom = null;
                in = 1f;
            } else {
                place(ctx, box, fadeFromWidth, fadeFromHeight, oldRect, !moving);
                drawOld(ctx, box, old, hasNew, 1f - in);
            }
        }
        if (hasNew) draw(ctx, box, picture, newRect, in, 0f, 0f, box.width(), box.height());
        // The filtered copy is rebuilt about every 100 ms while the window changes: paint again to pick it up.
        if (picture != null && picture.thumbnailStale()) repaint();
    }

    /** The old picture: opaque under the new one's rect, fading by {@code out} in the bands outside it. */
    private void drawOld(CgUiPaintContext ctx, Box box, Surface old, boolean hasNew, float out) {
        float ox0 = oldRect[0], oy0 = oldRect[1], ox1 = ox0 + oldRect[2], oy1 = oy0 + oldRect[3];
        float ix0 = hasNew ? Math.max(ox0, newRect[0]) : 0f, iy0 = hasNew ? Math.max(oy0, newRect[1]) : 0f;
        float ix1 = hasNew ? Math.min(ox1, newRect[0] + newRect[2]) : 0f;
        float iy1 = hasNew ? Math.min(oy1, newRect[1] + newRect[3]) : 0f;
        if (ix1 <= ix0 || iy1 <= iy0) {
            draw(ctx, box, old, oldRect, out, ox0, oy0, ox1, oy1);
            return;
        }
        draw(ctx, box, old, oldRect, 1f, ix0, iy0, ix1, iy1);
        draw(ctx, box, old, oldRect, out, ox0, oy0, ox1, iy0);
        draw(ctx, box, old, oldRect, out, ox0, iy1, ox1, oy1);
        draw(ctx, box, old, oldRect, out, ox0, iy0, ix0, iy1);
        draw(ctx, box, old, oldRect, out, ix1, iy0, ox1, iy1);
    }

    /** The pose's translation at the last paint, and whether it differed from the one before: the panel is moving. */
    private float poseX = Float.NaN, poseY = Float.NaN;
    private boolean moving;

    /** Notes whether the panel moved since the last paint; a moving one is painted again next frame, to settle. */
    private void notePose(CgUiPaintContext ctx) {
        Matrix4f m = ctx.targetPose();
        moving = !Float.isNaN(poseX) && (Math.abs(m.m30() - poseX) > 1e-3f || Math.abs(m.m31() - poseY) > 1e-3f);
        poseX = m.m30();
        poseY = m.m31();
        // THE FRAME AFTER THE LAST MOVE paints once more, on the grid, or a picture that stopped mid-pixel stays there.
        if (moving) repaint();
    }

    /** {@code width x height} centred in the box, into {@code out}: its origin on whole device pixels if {@code snap}. */
    private static void place(CgUiPaintContext ctx, Box box, float width, float height, float[] out, boolean snap) {
        float x = (box.width() - width) / 2f, y = (box.height() - height) / 2f;
        if (ctx.isPoseAxisAligned()) {
            // The origin snapped and the size rounded once, in device pixels: snapping both edges instead lets
            // the width flip a pixel as the box slides, which changes the scale and the texels sampled.
            Matrix4f m = ctx.targetPose();
            float sx = Math.abs(m.m00()), sy = Math.abs(m.m11());
            if (sx > 1e-6f && sy > 1e-6f) {
                if (snap) {
                    x = ctx.snapXToDevicePixel(x);
                    y = ctx.snapYToDevicePixel(y);
                }
                width = Math.max(1f, Math.round(width * sx)) / sx;
                height = Math.max(1f, Math.round(height * sy)) / sy;
            }
        }
        out[0] = x;
        out[1] = y;
        out[2] = width;
        out[3] = height;
        // The size in device pixels, for the filtered copy; 0 where the pose is not axis-aligned.
        Matrix4f pose = ctx.targetPose();
        boolean flat = ctx.isPoseAxisAligned();
        out[4] = flat ? Math.max(1f, Math.round(width * Math.abs(pose.m00()))) : 0f;
        out[5] = flat ? Math.max(1f, Math.round(height * Math.abs(pose.m11()))) : 0f;
    }

    /** {@code picture} into {@code rect} at {@code opacity}, clipped to {@code (x0, y0)-(x1, y1)} and the box. */
    private static void draw(CgUiPaintContext ctx, Box box, Surface picture, float[] rect, float opacity,
                             float x0, float y0, float x1, float y1) {
        x0 = Math.max(x0, 0f);
        y0 = Math.max(y0, 0f);
        x1 = Math.min(x1, box.width());
        y1 = Math.min(y1, box.height());
        if (x1 <= x0 || y1 <= y0 || opacity <= 0f) return;
        // Layer opacity, never the tint's alpha: the surface is premultiplied, so an alpha-only tint keeps the
        // full colour while covering less, and a cross-fade's overlap comes out brighter than either picture.
        float previous = ctx.pushLayerOpacity(opacity);
        ctx.pushScissor(x0, y0, x1 - x0, y1 - y0);
        try {
            picture.drawThumbnail(ctx, rect[0], rect[1], rect[2], rect[3], (int) rect[4], (int) rect[5]);
        } finally {
            ctx.popScissor();
            ctx.popLayerOpacity(previous);
        }
    }
}
