package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;

/**
 * A live picture of a window, drawn at whatever size this element happens to be.
 *
 * <h3>It re-draws the window rather than keeping a copy of it</h3>
 *
 * <p>Windows' DWM hands out a thumbnail by registering the window's composited surface and letting the
 * taskbar blit it; we have no per-window surface, so the equivalent is to paint the subtree a second time
 * under a different pose. It costs one extra subtree walk while a preview is open, and the fill is at
 * thumbnail size, so it is cheap in the only dimension that matters — but it is genuinely LIVE, which a
 * cached frame would not be. A window whose editor is scrolling shows it scrolling.</p>
 *
 * <p>The second draw goes through {@link CgUiPaintContext#mirrored}, and that is not optional: every
 * element reconciles its cached {@code localToWorld} against the pose it was drawn with, and hit-testing
 * walks exactly that cache. Without the mirror pass a preview would leave every element of the window
 * believing it lives in the thumbnail — and a preview is drawn later, so the copy would win and the real
 * window would stop being clickable where it is drawn.</p>
 *
 * <h3>The BOX is the window fitted into a maximum, rather than the picture letterboxed inside a fixed one</h3>
 *
 * <p>Windows' model, and it is worth stating precisely because the near-misses all look reasonable: the
 * taskbar asks for a thumbnail no larger than a maximum on <b>each axis</b> and the window answers with
 * its own shape scaled to fit. So both of a thumbnail's dimensions vary — a tall window comes back
 * full-height and narrow, a wide one full-width and short — and the panel is built around whatever came
 * back. Nothing is ever letterboxed, and no two previews need be the same size.</p>
 *
 * <p>The sheet gives the maximum as a square, and {@link #syncSize} does the fitting. The fit-and-centre
 * in {@link #paintOverlay} is then a no-op in the ordinary case and stays as the honest fallback for a
 * box that something else has constrained.</p>
 *
 * <h3>A minimised window is a PHOTOGRAPH instead</h3>
 *
 * <p>Hiding is DETACHING in CrystalOS — the point being that a hidden window genuinely stops: no layout,
 * no paint, no input — so a minimised window has no boxes to mirror and there is nothing to draw live.
 * {@link WindowSnapshot} covers that case with a picture taken on the way out, which is what DWM does too,
 * and its javadoc carries the argument for why keeping one does not fight the freeze contract: a texture
 * is not a window and cannot run.</p>
 *
 * <p>So there are two paths here and only one of them mirrors. A live window is a subtree drawn again; a
 * minimised one is already a texture, with no placement caches to protect and no second walk to pay for.</p>
 */
public class WindowThumbnail extends UIElement {

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
        addClass(THUMBNAIL_CLASS);
        // NOTHING IN HERE IS INTERACTIVE. The picture is a picture: a click belongs to the preview panel
        // around it, which activates the window. Leaving it hittable would also mean the MIRRORED subtree
        // competed for hits, which is the other half of what `mirrored` exists to prevent.
        setHitTest(false);
        placeholder.addClass(PLACEHOLDER_CLASS);
        placeholder.setDisplayed(false);
        addInternalChild(placeholder);
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
     * <h4>Explicit sizes, and not {@code aspect-rate}, which is the obvious way and does not work</h4>
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
        if (hasLive()) {
            var src = frame.getRuntimeCache();
            sourceWidth = src.getWidth();
            sourceHeight = src.getHeight();
        } else if (frame != null && frame.snapshot().isValid()) {
            sourceWidth = frame.snapshot().capturedWidth();
            sourceHeight = frame.snapshot().capturedHeight();
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
            var box = getRuntimeCache();
            if (box.getWidth() <= 0f || box.getHeight() <= 0f) return null;
            maxWidth = box.getWidth();
            maxHeight = box.getHeight();
        }

        float scale = Math.min(maxWidth / sourceWidth, maxHeight / sourceHeight);
        return new float[] { sourceWidth * scale, sourceHeight * scale };
    }

    /**
     * Whether the window can be drawn LIVE — false for a minimised one, which is detached.
     *
     * <p>The freeze contract working as intended: a hidden window has no layout, so there are no boxes
     * to mirror. {@link #hasPicture} is the question a caller usually wants, since a minimised window
     * still has a photograph.</p>
     */
    private boolean hasLive() {
        if (frame == null || frame.state() != WindowState.VISIBLE || frame.getParent() == null) {
            return false;
        }
        var src = frame.getRuntimeCache();
        return src.getWidth() > 0f && src.getHeight() > 0f;
    }

    /**
     * Whether there is anything at all to draw — live, or a photograph taken before it was minimised.
     *
     * <p>Exposed so a preview can collapse rather than reserve a picture-sized hole that will stay
     * empty, which is what a window that has never been on screen leaves.</p>
     */
    public boolean hasPicture() {
        return hasLive() || (frame != null && frame.snapshot().isValid());
    }

    @Override
    protected void paintOverlay(CgUiPaintContext ctx) {
        super.paintOverlay(ctx);
        if (frame == null) return;
        var box = getRuntimeCache();
        if (box.getWidth() <= 0f || box.getHeight() <= 0f) return;

        boolean live = hasLive();
        WindowSnapshot photograph = frame.snapshot();
        if (!live && !photograph.isValid()) return;

        float sourceWidth = live ? frame.getRuntimeCache().getWidth() : photograph.capturedWidth();
        float sourceHeight = live ? frame.getRuntimeCache().getHeight() : photograph.capturedHeight();
        if (sourceWidth <= 0f || sourceHeight <= 0f) return;

        float scale = Math.min(box.getWidth() / sourceWidth, box.getHeight() / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        float left = box.getX() + (box.getWidth() - width) / 2f;
        float top = box.getY() + (box.getHeight() - height) / 2f;

        // A MINIMISED WINDOW IS A PHOTOGRAPH, and it needs none of the mirroring below: it is already a
        // texture, so there is no subtree to draw and no placement cache to protect.
        //
        // It is clipped all the same. The picture is fitted inside this box, so in principle nothing can
        // escape it -- but the live path clips and this one did not, and an asymmetry between two paths
        // that are supposed to look identical is the kind of thing that is only ever noticed as a
        // symptom somewhere else.
        if (!live) {
            ctx.pushScissor(box.getX(), box.getY(), box.getWidth(), box.getHeight());
            try {
                photograph.draw(ctx, left, top, width, height);
            } finally {
                ctx.popScissor();
            }
            return;
        }
        var src = frame.getRuntimeCache();

        // CLIPPED TO THIS BOX FIRST. A window with anything out-of-flow in it -- a popup, a resizer, a
        // dropped-down menu -- draws outside its own rect, and in a thumbnail that would spill across the
        // preview panel and whatever is behind it.
        ctx.pushScissor(box.getX(), box.getY(), box.getWidth(), box.getHeight());
        ctx.getPoseStack().pushPose();
        var pose = ctx.getPoseStack().last().pose();
        // READ RIGHT TO LEFT: put the window's own origin at zero, scale it down, then move it to where
        // the picture goes. Elements draw at ABSOLUTE layout coordinates and the pose is what maps those
        // to the screen, so composing here is all it takes to draw the whole tree somewhere else.
        pose.translate(left, top, 0f);
        pose.scale(scale, scale, 1f);
        pose.translate(-src.getX(), -src.getY(), 0f);
        try {
            ctx.mirrored(() -> frame.drawSubtree(ctx));
        } finally {
            ctx.getPoseStack().popPose();
            ctx.popScissor();
        }
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
