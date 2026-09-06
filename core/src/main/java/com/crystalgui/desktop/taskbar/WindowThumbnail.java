package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowIcon;
import com.crystalgui.desktop.window.WindowSnapshot;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.box.Box;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

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
 * <p><b>On this engine that is a real second BOX, and the mirroring flag is gone.</b> The old engine
 * drew the subtree twice against one cached {@code localToWorld} per element, so the copy overwrote the
 * original's idea of where it lived and the real window stopped being clickable where it was drawn —
 * which is why {@code CgUiPaintContext.mirrored} existed and why it had to be a counter rather than a
 * boolean. {@link com.crystalgui.ui.box.BoxTree#mirror} lays the subtree out a second time under this
 * element, so each copy has its own matrices and its own place in the hit order, and the node is never
 * told it is drawn twice. Nothing here paints: the painter walks the mirror like any other box.</p>
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
 * in {@link #syncMirror} is then a no-op in the ordinary case and stays as the honest fallback for a box
 * that something else has constrained.</p>
 *
 * <h3>A minimised window is a PHOTOGRAPH instead</h3>
 *
 * <p>Hiding is DETACHING in CrystalOS — the point being that a hidden window genuinely stops: no layout,
 * no paint, no input — so a minimised window has no boxes to mirror and there is nothing to draw live.
 * {@link WindowSnapshot} covers that case with a picture taken on the way out, which is what DWM does too,
 * and its javadoc carries the argument for why keeping one does not fight the freeze contract: a texture
 * is not a window and cannot run.</p>
 *
 * <p>So there are two paths here and only one of them mirrors. A live window is a subtree laid out
 * again; a minimised one is already a texture, drawn in {@link #paintDecoration} because a texture has
 * no boxes.</p>
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
        // NOTHING IN HERE IS INTERACTIVE. The picture is a picture: a click belongs to the preview panel
        // around it, which activates the window. Leaving it hittable would also mean the MIRRORED subtree
        // competed for hits, which is the other half of what `mirrored` exists to prevent.
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
        Box live = liveBox();
        if (live != null) {
            sourceWidth = live.width();
            sourceHeight = live.height();
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
            Box box = box();
            if (box == null || box.width() <= 0f || box.height() <= 0f) return null;
            maxWidth = box.width();
            maxHeight = box.height();
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
     * Whether there is anything at all to draw — live, or a photograph taken before it was minimised.
     *
     * <p>Exposed so a preview can collapse rather than reserve a picture-sized hole that will stay
     * empty, which is what a window that has never been on screen leaves.</p>
     */
    public boolean hasPicture() {
        return hasLive() || (frame != null && frame.snapshot().isValid());
    }

    // ── The mirror ──────────────────────────────────────────────────────────────────────────────

    /** The window's second layout, hosted here, or null while there is nothing live to mirror. */
    @Nullable
    private Box mirror;

    /** What {@link #mirror} was built for, so a changed window takes a new one. */
    @Nullable
    private WindowFrame mirrored;

    /**
     * Keeps the mirror in step with the window and the box — from a post-layout hook, per frame.
     *
     * <p><b>Nothing here draws.</b> The old engine composed a pose by hand and re-walked the frame's
     * subtree inside {@code ctx.mirrored(...)}; this asks the box tree for a second layout and sets a
     * transform on its root, and the painter reaches it like any other box. The three things the hand
     * version had to get right — the scissor, the origin, and the mirroring guard — are the box tree's
     * now: a mirror root is hosted HERE, so it is clipped by this element's own {@code overflow} and
     * positioned in this element's space, and it has matrices of its own so hit-testing was never
     * confused in the first place.</p>
     *
     * <p>Post-layout because every input is a measured box: this element's, for the maximum, and the
     * window's, for the shape. Before layout both are the previous frame's, and on the frame a preview
     * first opens both are absent.</p>
     */
    private void syncMirror() {
        Box self = box();
        Box live = liveBox();
        if (self == null || live == null || self.width() <= 0f || self.height() <= 0f) {
            dropMirror();
            return;
        }
        UIDocument document = document();
        if (document == null) {
            dropMirror();
            return;
        }
        if (mirror == null || mirrored != frame) {
            dropMirror();
            mirror = document.boxes().mirror(frame, self);
            mirrored = frame;
        }
        // FIT AND CENTRE, as a transform on the copy. A `left`/`top` would move the ORIGINAL too --
        // a mirror shares its subtree's nodes and therefore its styles, which is exactly why
        // BoxTree.mirror tells a caller to transform the returned box instead.
        float scale = Math.min(self.width() / live.width(), self.height() / live.height());
        float width = live.width() * scale;
        float height = live.height() * scale;
        // TRANSLATE THEN SCALE, in that order and never the other. A transform list applies
        // LEFT TO RIGHT as matrix multiplication, so `scale then translate` would scale the offset too
        // and put the picture at a fraction of where it belongs -- the ordering `SvgTransform.parse`
        // once got backwards and that Transform's own javadoc states.
        mirror.setTransform(Transform.of(
                Transform.Op.translate(LengthPercent.px((self.width() - width) / 2f),
                        LengthPercent.px((self.height() - height) / 2f)),
                Transform.Op.scale(scale, scale)));
    }

    private void dropMirror() {
        if (mirror == null) return;
        UIDocument document = document();
        if (document != null) document.boxes().unmirror(mirror);
        mirror = null;
        mirrored = null;
    }

    @Override
    protected void connected() {
        super.connected();
        UIDocument document = document();
        if (document == null) return;
        document.animation().afterLayout(this, delta -> {
            syncMirror();
            return true;
        });
    }

    @Override
    protected void disconnected() {
        // A MIRROR IS A LAYOUT, so it has to go when this leaves the tree -- the hook is dropped for us
        // by ownership, and the boxes it made are not.
        dropMirror();
        super.disconnected();
    }

    /**
     * Draws the PHOTOGRAPH — the only thing here that is painted rather than laid out.
     *
     * <p>A minimised window is detached and has no boxes, so there is nothing to mirror and
     * {@link WindowSnapshot} is a texture. It is clipped to this box all the same: the picture is
     * fitted inside it, so in principle nothing can escape, but the live path clips and an asymmetry
     * between two paths that are supposed to look identical is the kind of thing only ever noticed as
     * a symptom somewhere else.</p>
     */
    @Override
    public void paintDecoration(CgUiPaintContext ctx, Box box) {
        super.paintDecoration(ctx, box);
        if (frame == null || hasLive()) return;
        if (box.width() <= 0f || box.height() <= 0f) return;

        WindowSnapshot photograph = frame.snapshot();
        if (!photograph.isValid()) return;

        float sourceWidth = photograph.capturedWidth();
        float sourceHeight = photograph.capturedHeight();
        if (sourceWidth <= 0f || sourceHeight <= 0f) return;

        float scale = Math.min(box.width() / sourceWidth, box.height() / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        // IN THIS BOX'S OWN SPACE. The painter draws every box with the pose set from its own
        // localToWorld, so a decoration starts at (0,0) rather than at an absolute layout coordinate --
        // the same origin change `toLocal` made, on the paint side.
        float left = (box.width() - width) / 2f;
        float top = (box.height() - height) / 2f;

        ctx.pushScissor(0f, 0f, box.width(), box.height());
        try {
            photograph.draw(ctx, left, top, width, height);
        } finally {
            ctx.popScissor();
        }
    }
}
