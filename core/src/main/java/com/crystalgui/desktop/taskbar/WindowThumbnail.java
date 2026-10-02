package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowIcon;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

/**
 * A picture of a window, drawn at whatever size this element happens to be: the window's surface.
 *
 * <pre>{@code
 * WindowThumbnail thumbnail = new WindowThumbnail().setFrame(window);
 * panel.append(thumbnail);
 * thumbnail.syncSize();          // per frame: the box takes the window's shape, fitted into the sheet's maximum
 * }</pre>
 *
 * <h3>It draws the window's surface</h3>
 *
 * <p>DWM hands a taskbar a thumbnail by letting it draw the window's redirection surface, and so does this
 * (render-graph G7): a window is drawn into a texture of its own, {@link WindowFrame#surface()}, and the thumbnail
 * draws that texture's border box scaled into its own. It is live -- a window whose editor scrolls shows it scrolling,
 * a frame behind at most -- and costs one textured quad, where a second layout of the window and a second walk of it
 * used to be the price.</p>
 *
 * <p><b>A minimised window keeps its surface</b> ({@link WindowFrame#keepsSurface}): hiding detaches it, so nothing
 * paints it, and the picture stays the one it last painted, at rest -- the flight that minimised it was its
 * composite's, never its surface's. A window that has never painted has no picture, and shows {@link #placeholder}.</p>
 *
 * <h3>The BOX is the window fitted into a maximum, rather than the picture letterboxed inside a fixed one</h3>
 *
 * <p>Windows' model: the taskbar asks for a thumbnail no larger than a maximum on <b>each axis</b> and the window
 * answers with its own shape scaled to fit. So both of a thumbnail's dimensions vary, and nothing is letterboxed.
 * The sheet gives the maximum as a square, and {@link #syncSize} does the fitting; the fit-and-centre in
 * {@link #paintDecoration} is the fallback for a box something else has constrained.</p>
 */
public class WindowThumbnail extends UIElement {

    /**
     * Its own kind, and every concrete node needs one.
     *
     * <p>No shipped rule names this tag — the sheet keys on the classes — but a subclass that
     * declares none INHERITS {@code UIElement.NAME}, so it would report {@code element} and match
     * every bare {@code element} rule there ever is. That is the {@code ToolWindowFrame} trap
     * from the other side, and {@code NodeKindsCoverageTest} is what makes it a compile-time
     * question rather than an unstyled widget somebody reports.</p>
     */
    public static final Name NAME = Name.of("windowthumbnail");

    /** On the element, so a theme can letterbox, border or round the picture. */
    public static final String THUMBNAIL_CLASS = "__thumbnail__";

    @Nullable
    private WindowFrame frame;

    /** On the icon tile drawn in place of a picture. @see #placeholder */
    public static final String PLACEHOLDER_CLASS = "__placeholder__";

    /**
     * What is drawn when there is NO picture — the window's icon tile, large, centred on the letterbox
     * colour. Windows' own answer for a window it has no bitmap of.
     *
     * <p>The preview used to COLLAPSE its thumbnail for this case, on the argument that an empty box
     * reads as a window that renders nothing. It cost two things. A header-only panel read as broken
     * ("minimised windows have no previews"), because a window restored HIDDEN at startup has never been
     * painted and so has no photograph, and that is now the ordinary way a session opens. And the
     * collapse made {@link #fittedSize} answer null for such a window, which the preview's placement
     * treats as "not measured yet" — so a panel moved onto that entry deferred its placement every frame
     * for good, and the hover logic behind that wait never ran again. A pictureless window now has a
     * SHAPE like any other, so nothing downstream needs a special case for it.</p>
     *
     * <p>Built in the constructor and shown or hidden, never added later — the taffyChildIndex rule.</p>
     */
    private final WindowIcon placeholder = new WindowIcon();
    private boolean placeholderShown;

    /** The card a pictureless window fits to, as a ratio: landscape, like most windows. */
    private static final float PLACEHOLDER_ASPECT_W = 5f;
    private static final float PLACEHOLDER_ASPECT_H = 3f;

    public WindowThumbnail() {
        super(NAME);
        addClass(THUMBNAIL_CLASS);
        // NOTHING IN HERE IS INTERACTIVE: a click belongs to the preview panel around it, which activates the window.
        setHitTest(false);
        placeholder.addClass(PLACEHOLDER_CLASS);
        placeholder.setDisplayed(false);
        append(placeholder);
    }

    /** The window this shows, or null for none. */
    public WindowThumbnail setFrame(@Nullable WindowFrame frame) {
        this.frame = frame;
        placeholder.show(frame == null ? null : frame.iconName(), frame == null ? null : frame.getTitle());
        syncSize();
        return this;
    }

    /** Whether the placeholder tile is what is on show — for a test, which cannot see the paint. */
    public boolean isShowingPlaceholder() {
        return placeholderShown;
    }

    /**
     * Shows the tile exactly when there is no picture. Per frame, from {@link #syncSize}: a window can
     * be minimised while its own preview is up, and a photograph can arrive on the next paint.
     */
    private void syncPlaceholder() {
        boolean show = frame != null && !hasPicture();
        if (show == placeholderShown) return;
        placeholderShown = show;
        placeholder.setDisplayed(show);
    }

    /** The size currently written, so an unchanged one writes nothing. */
    private float appliedWidth = Float.NaN;
    private float appliedHeight = Float.NaN;

    /**
     * The box the sheet gave this element before anything was written to it — the MAXIMUM a thumbnail
     * may be on either axis.
     *
     * <p>Captured from the first measurement rather than read out of the cascade, because a
     * {@code TaffyDimension} does not give its pixels back and the alternative is naming the number in
     * Java, where the sheet should own it. Safe because it is only ever read before this class has
     * written a size: after that the measurement is the FITTED size and no longer the bound.</p>
     */
    private float maxWidth = Float.NaN;
    private float maxHeight = Float.NaN;

    /**
     * Gives the box the window's own proportions, so the picture fills it instead of letterboxing.
     *
     * <p>Idempotent and cheap enough to call per frame, which is what a preview does: a window can be
     * resized while its own preview is up, and the box should follow it.</p>
     *
     * <h4>Windows' own model: a MAX BOX, and the window fitted inside it on BOTH axes</h4>
     *
     * <p>The taskbar sends {@code WM_DWMSENDICONICTHUMBNAIL} carrying a maximum x and a maximum y, and
     * the window answers with a bitmap no larger than that; the user-facing knob, {@code MaxThumbSizePx},
     * is a single maximum rather than a width or a height. So a tall window comes back full-height and
     * narrow, a wide one full-width and short, and <b>both dimensions vary from window to window</b> —
     * which is why Windows' previews are visibly different sizes and never letterboxed.</p>
     *
     * <p>Fixing the height and deriving only the width, which is what this did first, is a different
     * model and produces a different bug: it makes every thumbnail the same height, so the panel's
     * proportions come from the window while its size does not, and a tall window ends up with a sliver
     * of a panel. The box is fitted into the sheet's square instead, and BOTH sizes are written.</p>
     *
     * <h4>Explicit sizes, and not {@code aspect-ratio}, which is the obvious way and does not work</h4>
     *
     * <p>Taffy will happily derive a width from a definite height and a ratio — the box comes out the
     * right shape. What it does not do is count that derived width as the item's contribution to its
     * PARENT's intrinsic size, so the panel went on sizing to its header and a wide thumbnail simply
     * overflowed it. A definite size is counted.</p>
     *
     * @return whether the size changed, so a caller placing the panel knows to measure it again
     */
    public boolean syncSize() {
        // Display, not sizing, so it is not held off with the size: a morph that is holding the box at
        // the old window's shape must still stop drawing a picture the new window does not have.
        syncPlaceholder();
        if (sizingSuppressed) return false;
        float[] fitted = fittedSize();
        if (fitted == null) return false;
        if (Math.abs(fitted[0] - appliedWidth) < 0.5f && Math.abs(fitted[1] - appliedHeight) < 0.5f) {
            return false;
        }
        appliedWidth = fitted[0];
        appliedHeight = fitted[1];
        applySize(fitted[0], fitted[1]);
        return true;
    }

    /**
     * Stops {@link #syncSize} writing, so a transition can drive the box instead.
     *
     * <p>A preview MORPHS its thumbnail when it moves from one entry to another, and the two writers
     * would otherwise fight every frame: the animation writing the intermediate size and this writing
     * the destination straight back over it.</p>
     */
    public void setSizingSuppressed(boolean suppressed) {
        this.sizingSuppressed = suppressed;
    }

    private boolean sizingSuppressed;

    /**
     * Forgets what was last written, so the next {@link #syncSize} writes whatever it fits to.
     *
     * <p>For a morph CANCELLED part-way: the animation writes the same INLINE slot as {@link #applySize}
     * without telling this class, so after a cancel the box holds an intermediate size while the record
     * here still says the morph's start. A later window that happens to fit to that recorded size would
     * then be skipped as "unchanged" and drawn in a box of the wrong shape.</p>
     */
    public void forgetApplied() {
        appliedWidth = Float.NaN;
        appliedHeight = Float.NaN;
    }

    /** Writes the box, at INLINE so a transition writing the same slot can take over from it. */
    public void applySize(float width, float height) {
        appliedWidth = width;
        appliedHeight = height;
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.width(width).height(height));
    }

    /**
     * The window's shape scaled to fit the maximum, or null when there is nothing to measure yet.
     *
     * <p>Separate from applying it, so a transition can ask where the box is GOING without the box
     * jumping there.</p>
     */
    @Nullable
    public float[] fittedSize() {
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
            // NO PICTURE STILL HAS A SHAPE: the placeholder card. Answering null here is what made a
            // pictureless window stall the preview's placement for good. @see #placeholder
            sourceWidth = PLACEHOLDER_ASPECT_W;
            sourceHeight = PLACEHOLDER_ASPECT_H;
        } else {
            return null;
        }
        if (sourceWidth <= 0f || sourceHeight <= 0f) return null;

        if (Float.isNaN(maxWidth)) {
            Box box = box();
            if (box == null || box.width() <= 0f || box.height() <= 0f) return null;
            maxWidth = box.width();
            maxHeight = box.height();
        }

        float scale = Math.min(maxWidth / sourceWidth, maxHeight / sourceHeight);
        return new float[] { sourceWidth * scale, sourceHeight * scale };
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

    /** @see #liveBox */
    private boolean hasLive() {
        return liveBox() != null;
    }

    /**
     * Whether there is anything to draw: a window on screen, whose surface comes with its first paint, or one that
     * kept its picture when it was minimised.
     */
    public boolean hasPicture() {
        return hasLive() || (frame != null && frame.surface() != null);
    }

    /** Draws the window's surface, fitted and centred, clipped to this box. */
    @Override
    public void paintDecoration(CgUiPaintContext ctx, Box box) {
        super.paintDecoration(ctx, box);
        Surface picture = frame == null ? null : frame.surface();
        if (picture == null || box.width() <= 0f || box.height() <= 0f) return;
        float sourceWidth = picture.pictureWidth(), sourceHeight = picture.pictureHeight();
        if (sourceWidth <= 0f || sourceHeight <= 0f) return;
        float scale = Math.min(box.width() / sourceWidth, box.height() / sourceHeight);
        float width = sourceWidth * scale, height = sourceHeight * scale;
        ctx.pushScissor(0f, 0f, box.width(), box.height());
        try {
            picture.drawPicture(ctx, (box.width() - width) / 2f, (box.height() - height) / 2f, width, height);
        } finally {
            ctx.popScissor();
        }
    }
}
