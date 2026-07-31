package com.crystalgui.ui.elements.canvas;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIDragController;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Getter;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * A pan-and-zoom viewport: an unbounded plane of content viewed through a fixed window.
 *
 * <p>The substrate every node graph sits on, and deliberately not graph-specific — nodes, ports and
 * wires are 6.2.3's business. What lives here is the viewport contract: a world coordinate system,
 * a view onto it, and the input gestures that move that view.</p>
 *
 * <h3>Zoom is a {@code transform}, which is why this is small</h3>
 * <p>CSS {@code transform} is layout-free by construction — Taffy never sees it — so scaling the
 * whole plane cannot reflow anything, and {@code UITransform.applyTo} is the single definition shared
 * by the render {@code PoseStack} and the hit-test chain, so clicks follow the picture with no code
 * here at all. That is the whole reason this widget is a few hundred lines rather than a coordinate
 * system of its own: the engine already had the hard half.</p>
 *
 * <h3>Structure</h3>
 * <pre>
 * CanvasView            overflow: hidden — the fixed window, and the element gestures are read on
 *   └── content         {@code __content__}, absolutely positioned, carries translate+scale
 *         └── nodes     the caller's elements, placed in world coordinates
 * </pre>
 * <p>Nodes go through {@link #content()} or {@link #addNode}; {@code addChild} on the canvas itself
 * throws, per the composite-widget convention — a child added to the viewport would sit outside the
 * transform and refuse to pan.</p>
 *
 * <h3>Three coordinate spaces, and mixing them is the classic bug</h3>
 * <ul>
 *   <li><b>World</b> — where nodes live. Unaffected by pan or zoom. What you author.</li>
 *   <li><b>Logical</b> — the engine's layout space, what {@code RuntimeCache.getX()} and
 *       {@link UIElement#screenToLocal} speak. {@link #getPanX()} is in these units.</li>
 *   <li><b>Physical</b> — raw pointer pixels, as delivered by {@code MouseEvent.getPosition()}.
 *       Differs from logical by {@code uiScale}.</li>
 * </ul>
 * <p>{@link #screenToWorld} goes physical → world in one call and is what a caller doing pointer
 * maths should reach for; {@link #worldToViewport} is the inverse half, into logical space.</p>
 *
 * <h3>The transform is {@code translate(pan) scale(zoom)}, in that order</h3>
 * <p>So {@code pan} is measured <em>after</em> zoom — it is a screen-space offset, not a world one.
 * That is what makes a pan drag a plain addition: a pointer that moved 30 logical px moves the view
 * 30 px whatever the zoom, which is exactly what a hand-drag should do, with no division to get
 * subtly wrong at the extremes. {@link #centerOnWorld} exists for the times you genuinely want to
 * think in world units.</p>
 */
public class CanvasView extends UIElement implements UIFrameTicker {

    /** The transformed plane. Themes target it to paint a grid — a background on this element is in
     * world space and therefore scales and slides with the view for free. */
    public static final String CONTENT_CLASS = "__content__";

    /** On the canvas itself while a pan drag is live, so {@code cursor: grabbing} is a stylesheet's
     * decision rather than a hard-coded one. */
    public static final String PANNING_CLASS = "__panning__";

    private final UIElement content = new UIElement();

    @Getter
    private float panX, panY;

    @Getter
    private float zoom = 1f;

    @Getter
    private float minZoom = 0.1f, maxZoom = 8f;

    /**
     * Multiplier per wheel notch. <b>Multiplicative, not additive</b>: zooming out from 1.0 in steps
     * of 0.1 reaches zero in ten notches and negative on the eleventh, and long before that each
     * notch is a wildly different proportion of the current scale. A constant ratio makes every notch
     * feel identical and cannot cross zero.
     */
    @Getter
    private float zoomStep = 1.1f;

    @Getter
    private boolean panEnabled = true, zoomEnabled = true;

    @Getter
    private boolean cullingEnabled = true;

    /** World-space slack around the viewport before a node is culled. */
    @Getter
    private float cullMargin = 0f;

    @Getter
    private boolean panning;

    /** Not a bookkeeping duplicate of the style: this is what {@link #isCulled} answers, and it keeps
     * the un-cull path from writing to every node on every tick. */
    private final Set<UIElement> culled = new HashSet<>();

    /** @see #setCullExempt(UIElement, boolean) */
    private final Set<UIElement> cullExempt = new HashSet<>();

    private boolean ticking;

    /** Fires after any change to pan or zoom, from any source. Zero-arg because a listener that cares
     * reads {@link #getZoom()}/{@link #getPanX()} — passing three floats would just be them. */
    public final Signal.Action onViewChanged = new Signal.Action();

    public CanvasView() {
        // The viewport clips. Without it the plane paints over its neighbours, which looks like a
        // z-order bug and is really a missing overflow.
        StyleGroup.defaultPipeline(getStyle().getGeneralGroup(), g -> g.overflow(Overflow.HIDDEN));

        content.addClass(CONTENT_CLASS);
        // Absolute so the plane is not a flex item of the viewport: it must be free to be any size and
        // to sit at the origin regardless of the viewport's own flex settings. 100%/100% only matters
        // for percentage-sized nodes — everything else here is measured from the live layout.
        StyleGroup.defaultPipeline(content.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f).widthPercent(100f).heightPercent(100f));

        // LOAD-BEARING, and written at IMPORTANT so a theme cannot quietly break the widget's maths:
        // transform-origin defaults to 50% (the element's centre, as in CSS), which would make the
        // scale pivot on a point that moves whenever the viewport resizes. Every conversion below
        // assumes the plane scales about its own top-left.
        StyleGroup.importantPipeline(content.getStyle().getGeneralGroup(),
                g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f)));

        addInternalChild(content);
        applyView();

        // CAPTURE, so a pan gesture wins over whatever is under the cursor. A node that handled the
        // press first would swallow it, and space-drag would work everywhere except over the nodes —
        // i.e. everywhere except where you actually want to grab.
        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!panEnabled || !isEnabled()) return;
            if (isInsidePromotedChild(event.getTarget())) return;
            if (!isPanTrigger(event)) return;
            event.stopPropagation();
            beginPan(event.getPosition().x(), event.getPosition().y(), event.getButtonId());
        }, true, false);

        // Bubble, not capture: a scroller *inside* the canvas keeps its own wheel. Zooming out from
        // under a list the user was scrolling is the worse failure of the two.
        this.events.getGroup(MouseEvent.Scroll.class).attachListener((el, event) -> {
            if (!zoomEnabled || !isEnabled()) return;
            // A wheel over a popup we own is the popup's, even when the popup declined it. A ScrollerView
            // only claims the wheel while it actually scrolls -- deliberately, so a list at its end chains
            // outward -- so a menu whose list is short or already at the bottom hands the wheel straight
            // to this handler, and the graph zooms under an open menu. That is never what was meant.
            if (isInsidePromotedChild(event.getTarget())) return;
            float notches = event.getScroll();
            if (notches == 0f) return;
            // NEGATED, and the sign is not guessable: in this engine a POSITIVE notch means the wheel
            // rolled DOWN. The only source of truth for that is ScrollerView, which does
            // `setScrollTop(before + delta)` — scrollTop grows as you scroll down. So positive must
            // zoom OUT. Taking the sign at face value gives a canvas that zooms in when you scroll
            // down, which is wrong in a way every user notices in the first second and no test
            // catches, because a test that asserts what the code does agrees with it.
            zoomAt(zoom * (float) Math.pow(zoomStep, -notches),
                    event.getPosition().x(), event.getPosition().y());
            // Consumed whether or not the zoom actually moved. At the clamp the alternative is for the
            // wheel to fall through and scroll an ancestor, so holding the wheel at max zoom would
            // start dragging the whole panel out from under the canvas.
            event.stopPropagation();
        }, false, true);
    }

    /**
     * The transformed plane nodes live on.
     *
     * <p>Handed out rather than proxied: it is an ordinary {@link UIElement}, and everything a caller
     * might want to do to a container — {@code querySelector}, {@code clearAllChildren}, a background
     * — should keep working rather than needing a forwarding method each.</p>
     */
    public UIElement content() {
        return content;
    }

    /** Structure, not content — a child of the viewport would sit outside the transform and stay
     * nailed to the screen while everything else panned. @see #content() */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** Adds {@code node} to the plane at a world position. Absolute positioning is what makes a node
     * placeable at all; a flow child would be laid out by the plane instead. */
    public CanvasView addNode(UIElement node, float worldX, float worldY) {
        StyleGroup.defaultPipeline(node.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
        moveNode(node, worldX, worldY);
        content.addChild(node);
        return this;
    }

    /** Moves an already-added node. Written at INLINE, so a caller's own later {@code layout()} call
     * and a stylesheet still compete through the normal cascade. */
    public CanvasView moveNode(UIElement node, float worldX, float worldY) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> l.left(worldX).top(worldY));
        return this;
    }

    // ── The view ────────────────────────────────────────────────────────────

    public CanvasView setZoom(float value) {
        return setView(panX, panY, value);
    }

    public CanvasView setPan(float x, float y) {
        return setView(x, y, zoom);
    }

    public CanvasView panBy(float dx, float dy) {
        return setView(panX + dx, panY + dy, zoom);
    }

    /**
     * Zooms while keeping the world point currently under {@code (rawX, rawY)} nailed to that same
     * screen position — the only zoom a pointer-driven canvas should have.
     *
     * @param rawX physical pointer x, straight from {@code MouseEvent.getPosition()}
     */
    public CanvasView zoomAt(float newZoom, float rawX, float rawY) {
        float clamped = clampZoom(newZoom);
        if (clamped == zoom) return this;
        Vector2f world = screenToWorld(rawX, rawY);
        // local = origin + pan + zoom·world must not move, so pan absorbs the change in scale.
        return setView(panX + world.x() * (zoom - clamped),
                panY + world.y() * (zoom - clamped), clamped);
    }

    /** Puts a world point at the centre of the viewport, at the current zoom. */
    public CanvasView centerOnWorld(float worldX, float worldY) {
        var cache = getRuntimeCache();
        float targetX = cache.getX() + cache.getWidth() * 0.5f;
        float targetY = cache.getY() + cache.getHeight() * 0.5f;
        return setView(targetX - contentOriginX() - zoom * worldX,
                targetY - contentOriginY() - zoom * worldY, zoom);
    }

    /** @see #fitToContent(float) */
    public CanvasView fitToContent() {
        return fitToContent(0f);
    }

    /**
     * Frames every node, with {@code padding} world units of margin.
     *
     * <p>No-op when the plane is empty or the viewport has not been laid out yet — a "fit" against a
     * zero-size viewport resolves to zoom 0, which is a blank canvas the caller cannot recover from
     * by eye.</p>
     */
    public CanvasView fitToContent(float padding) {
        WorldRect bounds = contentBounds();
        var cache = getRuntimeCache();
        float viewW = cache.getWidth(), viewH = cache.getHeight();
        if (bounds == null || viewW <= 0f || viewH <= 0f) return this;

        WorldRect padded = bounds.expand(padding);
        float fitZoom = clampZoom(Math.min(viewW / Math.max(1e-4f, padded.width()),
                viewH / Math.max(1e-4f, padded.height())));
        setView(panX, panY, fitZoom);
        return centerOnWorld(padded.centerX(), padded.centerY());
    }

    /** The union of every node's world rect, or {@code null} when the plane is empty. */
    @Nullable
    public WorldRect contentBounds() {
        WorldRect union = null;
        for (UIElement child : content.getChildren()) {
            WorldRect rect = worldBoundsOf(child);
            union = union == null ? rect : union.union(rect);
        }
        return union;
    }

    /** A node's own rect in world space. */
    public WorldRect worldBoundsOf(UIElement node) {
        var cache = node.getRuntimeCache();
        // getX() accumulates through parents but NOT through the plane's transform — that is applied
        // afterwards, in localToWorld — so subtracting the plane's origin lands in world units with
        // no division by zoom.
        return new WorldRect(cache.getX() - contentOriginX(), cache.getY() - contentOriginY(),
                cache.getWidth(), cache.getHeight());
    }

    /** The slice of world space the viewport currently shows. */
    public WorldRect visibleWorldRect() {
        var cache = getRuntimeCache();
        float x = (cache.getX() - contentOriginX() - panX) / zoom;
        float y = (cache.getY() - contentOriginY() - panY) / zoom;
        return new WorldRect(x, y, cache.getWidth() / zoom, cache.getHeight() / zoom);
    }

    // ── Coordinates ─────────────────────────────────────────────────────────

    /**
     * Physical pointer position → world coordinates.
     *
     * <p>Routed through {@link UIElement#screenToLocal}, so it stays correct under {@code uiScale},
     * an ancestor transform, and an ancestor's scroll offset — none of which this widget knows
     * about.</p>
     */
    public Vector2f screenToWorld(float rawX, float rawY) {
        Vector2f local = screenToLocal(rawX, rawY);
        return new Vector2f((local.x() - contentOriginX() - panX) / zoom,
                (local.y() - contentOriginY() - panY) / zoom);
    }

    /**
     * World coordinates → the engine's logical space — the same frame {@code RuntimeCache.getX()} and
     * {@link UIElement#screenToLocal} report in, <b>not</b> physical pixels.
     */
    public Vector2f worldToViewport(float worldX, float worldY) {
        return new Vector2f(contentOriginX() + panX + zoom * worldX,
                contentOriginY() + panY + zoom * worldY);
    }

    /**
     * The exact inverse of {@link #worldToViewport}: a point in the engine's logical space — what
     * {@link UIElement#screenToLocal} returns and what a {@code DragListener} on this canvas reports —
     * back into world coordinates.
     *
     * <p>Distinct from {@link #screenToWorld}, which starts from <em>physical</em> pointer pixels. Both
     * exist because both starting points are real: a raw event carries physical, and anything already
     * converted (a drag delta, a layout position) carries logical. Collapsing them would mean one caller
     * silently applying {@code uiScale} twice, which looks correct at a scale of 1.</p>
     */
    public Vector2f viewportToWorld(float localX, float localY) {
        return new Vector2f((localX - contentOriginX() - panX) / zoom,
                (localY - contentOriginY() - panY) / zoom);
    }

    // ── Culling ─────────────────────────────────────────────────────────────

    /**
     * Whether off-screen nodes stop painting. On by default — an unbounded plane is the case this
     * widget exists for, and a viewport showing twenty of two thousand nodes should cost twenty.
     */
    public CanvasView setCullingEnabled(boolean enabled) {
        if (this.cullingEnabled == enabled) return this;
        this.cullingEnabled = enabled;
        if (enabled) {
            ensureTicking();
        } else {
            for (UIElement node : Set.copyOf(culled)) applyCulled(node, false);
        }
        return this;
    }

    public CanvasView setCullMargin(float worldUnits) {
        this.cullMargin = Math.max(0f, worldUnits);
        return this;
    }

    public boolean isCulled(UIElement node) {
        return culled.contains(node);
    }

    /**
     * Exempts an element from culling entirely — for a <b>painter</b> rather than a node.
     *
     * <p>Culling asks an element's box where it is, which is the right question for a node and the
     * wrong one for something that draws across the whole plane: a wire layer's box says nothing about
     * where its wires are, so left cullable it disappears the moment the view leaves its own origin,
     * taking everything it draws with it. An exempt element is expected to cull its own drawing, where
     * it knows what it is drawing.</p>
     *
     * <p>Deliberately a set on the canvas rather than a flag on {@code UIElement}: it is a fact about
     * this canvas's relationship with that child, not a property of the element. The same shape as
     * {@code setScrollExempt}, which is on the element only because scrolling is an ambient capability
     * of every element and culling is not.</p>
     */
    public CanvasView setCullExempt(UIElement node, boolean exempt) {
        if (exempt) {
            cullExempt.add(node);
            // It may already be culled from a previous pass; the exemption has to undo that or it stays
            // invisible forever, which is the bug this method exists to prevent.
            applyCulled(node, false);
        } else {
            cullExempt.remove(node);
        }
        return this;
    }

    public boolean isCullExempt(UIElement node) {
        return cullExempt.contains(node);
    }

    public int culledCount() {
        return culled.size();
    }

    /**
     * Re-evaluates every node against the visible rect.
     *
     * <p>Runs from the frame ticker rather than only when the view changes, because a node can move
     * without the view moving — a dragged node, a relayout, a node added off-screen — and the canvas
     * gets no notification of a child's layout change. The work is one AABB test per node, which is
     * cheaper by orders of magnitude than the per-element material bind it avoids.</p>
     */
    public void updateCulling() {
        if (!cullingEnabled) return;
        WorldRect view = visibleWorldRect().expand(cullMargin);
        for (UIElement child : content.getChildren()) {
            if (cullExempt.contains(child)) continue;
            applyCulled(child, !view.intersects(worldBoundsOf(child)));
        }
        // A node removed from the plane while culled would otherwise keep its forced opacity — and
        // stay invisible after being re-parented somewhere else entirely.
        culled.removeIf(node -> {
            if (node.getParent() == content) return false;
            clearCullOpacity(node);
            return true;
        });
    }

    /**
     * Culls by <b>skipping paint, not layout</b>: {@code opacity: 0} at IMPORTANT origin, which
     * {@code drawSubtree} early-returns on.
     *
     * <p>{@code display: none} is the obvious choice and is the wrong one here, for a reason that is
     * not obvious until it bites. A culled node's layout would collapse to nothing — and its layout
     * rect is precisely the input the cull decision is computed from, so the node could never be
     * un-culled without a cache of where it used to be, which then goes stale the moment anything
     * moves it. Keeping layout live makes the decision self-correcting every tick. It also costs no
     * relayout as nodes cross the viewport edge, which panning does constantly.</p>
     *
     * <p>What is genuinely given up is layout cost for off-screen nodes. That is the smaller half:
     * layout recomputes only when dirty, while paint happens every single frame.</p>
     */
    private void applyCulled(UIElement node, boolean cull) {
        if (cull == culled.contains(node)) return;
        if (cull) {
            culled.add(node);
            node.getStyle().getGeneralGroup().set(StyleOrigin.IMPORTANT, StylePropertyRegistry.OPACITY, 0f);
        } else {
            culled.remove(node);
            clearCullOpacity(node);
        }
    }

    /** Removes only <em>our</em> candidate — a caller's own {@code opacity} at any other origin, and
     * a theme's, survive being culled and uncalled. */
    private void clearCullOpacity(UIElement node) {
        node.getStyle().getGeneralGroup().set(StyleOrigin.IMPORTANT, StylePropertyRegistry.OPACITY, null);
    }

    // ── Gestures ────────────────────────────────────────────────────────────

    public CanvasView setPanEnabled(boolean enabled) {
        this.panEnabled = enabled;
        return this;
    }

    public CanvasView setZoomEnabled(boolean enabled) {
        this.zoomEnabled = enabled;
        return this;
    }

    public CanvasView setZoomRange(float min, float max) {
        this.minZoom = Math.max(1e-4f, Math.min(min, max));
        this.maxZoom = Math.max(this.minZoom, max);
        return setZoom(zoom);
    }

    public CanvasView setZoomStep(float step) {
        this.zoomStep = Math.max(1.0001f, step);
        return this;
    }

    /**
     * Whether a press or wheel landed inside a subtree that is only our <em>DOM</em> child — a popup we
     * own but that is drawn in the top layer.
     *
     * <p>A promoted subtree is visually not inside its parent, so a parent that acts on background
     * gestures must not count one. Everything a canvas does on the background — pan, zoom, marquee — has
     * to ask this first, and each of them was found the same way: by a popup that looked dead because the
     * canvas underneath had taken its input.</p>
     */
    protected boolean isInsidePromotedChild(@Nullable UIElement target) {
        for (UIElement element = target; element != null && element != this; element = element.getParent()) {
            if (element.isInTopLayer()) return true;
        }
        return false;
    }

    /**
     * Middle-drag, or left-drag with Space held.
     *
     * <p>Left-drag on empty space is deliberately <b>not</b> a pan: it is the marquee gesture every
     * graph editor spends it on, and 6.2.4 needs it. Space-drag is the escape hatch for a mouse with
     * no usable middle button, and is what Figma, Blender and Photoshop all settled on.</p>
     */
    protected boolean isPanTrigger(MouseEvent.Down event) {
        int button = event.getButtonId();
        if (button == CgMouseCodes.MIDDLE_BUTTON) return true;
        return button == CgMouseCodes.LEFT_BUTTON && isSpaceHeld();
    }

    private static boolean isSpaceHeld() {
        var input = CgPlatform.input();
        return input != null && input.isKeyDown(CgKeyCodes.KEY_SPACE);
    }

    /** Takes the RAW pointer position — {@code UIDragController} converts to local space itself. */
    private void beginPan(float rawX, float rawY, int panButton) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        final float startPanX = panX, startPanY = panY;
        setPanning(true);
        // The button is declared so the drag ends when THAT button is released — the controller
        // assumes the left one otherwise, and a middle-drag would never stop.
        window.getInputHandler().getDragController().startDrag(this, rawX, rawY, panButton,
                new UIDragController.DragListener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        // The drag source is the VIEWPORT, never the plane — and that is not a
                        // detail. Every coordinate a DragListener receives is converted through the
                        // source's own transform, so panning the plane while dragging from it would
                        // move the very frame the delta is measured in: the view would accelerate
                        // away under the cursor instead of following it.
                        setView(startPanX + dx, startPanY + dy, zoom);
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        setPanning(false);
                    }

                    @Override
                    public void onDragCancel() {
                        setPanning(false);
                    }
                });
    }

    private void setPanning(boolean value) {
        if (this.panning == value) return;
        this.panning = value;
        if (value) addClass(PANNING_CLASS);
        else removeClass(PANNING_CLASS);
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private CanvasView setView(float newPanX, float newPanY, float newZoom) {
        float z = clampZoom(newZoom);
        if (z == zoom && newPanX == panX && newPanY == panY) return this;
        this.panX = newPanX;
        this.panY = newPanY;
        this.zoom = z;
        applyView();
        onViewChanged.emit();
        return this;
    }

    private float clampZoom(float value) {
        if (Float.isNaN(value)) return zoom;
        return Math.max(minZoom, Math.min(maxZoom, value));
    }

    /**
     * Pushes the view onto the plane as a {@code transform}, at IMPORTANT origin.
     *
     * <p>The same reasoning as {@code Scroller}'s thumb and {@code UIText}'s measured height: this is
     * runtime state, not appearance, and a stylesheet that overrode it would freeze the view. It
     * still writes through the cascade rather than around it, so a transition on {@code transform}
     * declared by a theme is honoured — and {@code replaceOrPutCandidate} no-ops on an unchanged
     * value, so a pan drag that reports the same position twice costs nothing.</p>
     */
    private void applyView() {
        UITransform view = UITransform.of(
                UITransform.Op.translate(LengthPercent.px(panX), LengthPercent.px(panY)),
                UITransform.Op.scale(zoom, zoom));
        StyleGroup.importantPipeline(content.getStyle().getGeneralGroup(), g -> g.transform(view));
    }

    private float contentOriginX() {
        return content.getRuntimeCache().getX();
    }

    private float contentOriginY() {
        return content.getRuntimeCache().getY();
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        ensureTicking();
        updateCulling();
    }

    private void ensureTicking() {
        if (ticking || !cullingEnabled) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        window.registerTicker(this);
        ticking = true;
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (!cullingEnabled) {
            ticking = false;
            return false;
        }
        updateCulling();
        return true;
    }
}
