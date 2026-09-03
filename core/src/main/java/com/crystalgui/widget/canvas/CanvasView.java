package com.crystalgui.widget.canvas;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.box.Box;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.event.MouseEvent;
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
 *       {@link UIElement#toLocal} speak. {@link #getPanX()} is in these units.</li>
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
public class CanvasView extends UIElement {

    /**
     * This widget's kind.
     *
     * <p>Declared here rather than in a vocabulary class, and declared AT ALL because a subclass
     * inherits its parent's kind unless it is given its own: without this, CanvasView reports
     * {@code crystalgui:element} (or its supertype's) and every rule the sheets write for
     * {@code canvasview} matches nothing at all — no background, no border, an unstyled widget that
     * reads as one that was never built.</p>
     */
    public static final Name NAME = Name.of("canvasview");

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
        this(NAME);
    }

    /**
     * For a subclass with a kind of its own.
     *
     * <p>M6.1's rule, paid for eleven times in sixteen widgets: a subclass inherits its parent's kind
     * unless it is GIVEN one, so {@code GraphView} without this reports {@code canvasview} and every
     * {@code graphview} rule in {@code graph.css} matches nothing.</p>
     */
    protected CanvasView(Name name) {
        super(name);
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
        StyleGroup.inlinePipeline(content.getStyle().getGeneralGroup(),
                g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f)));

        append(content);
        applyView();

        // CAPTURE, so a pan gesture wins over whatever is under the cursor. A node that handled the
        // press first would swallow it, and space-drag would work everywhere except over the nodes —
        // i.e. everywhere except where you actually want to grab.
        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!panEnabled || !isEnabled()) return;
            if (isBackgroundGestureExempt(((UIElement) event.getTarget()))) return;
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
            if (isBackgroundGestureExempt(((UIElement) event.getTarget()))) return;
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

    /**
     * Adds a panel that floats <b>over</b> the canvas and does not pan or zoom with it.
     *
     * <h3>This is the case {@link #acceptsPublicChildren()} refuses, turned into a feature</h3>
     * <p>That method's note says a child of the viewport "would sit outside the transform and stay nailed
     * to the screen while everything else panned" — which is exactly right, and exactly what a floating
     * inspector, minimap or shader preview wants. Refusing it wholesale left a caller with only two
     * options: put the panel on the plane, where it drifts off-screen the moment you pan, or put it
     * outside the canvas entirely, where it stops being <em>over</em> the graph at all.</p>
     *
     * <p>The overlay is an internal child, so it stays out of {@link #content()}, out of node iteration,
     * and out of anything that treats the plane's children as the document.</p>
     */
    /** Floating panels that sit over the canvas and are not part of it. @see #addOverlay */
    private final java.util.Set<UIElement> overlays = new java.util.HashSet<>();

    public CanvasView addOverlay(UIElement panel) {
        StyleGroup.defaultPipeline(panel.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
        overlays.add(panel);
        append(panel);
        raiseOnPress(panel);
        return this;
    }

    /**
     * The next {@code z-index} a pressed overlay takes. Monotonic, so "most recently used is on top" is a
     * total order and never needs the others rewritten.
     */
    private int nextOverlayZ = 1;

    /**
     * The overlay currently on top, so a repeated press on it is free.
     *
     * <p>The identity, not the number. Comparing the panel's {@code z-index} against the counter looks
     * equivalent and is not: every overlay starts at the initial {@code z-index} of 0, so on the very
     * first press <em>every</em> panel matches "already frontmost" and nothing is ever raised at all.</p>
     */
    @Nullable
    private UIElement frontmostOverlay;

    /**
     * Clicking an overlay brings it to the front — every window manager's oldest rule, and the one thing
     * two overlapping panels cannot be used without.
     *
     * <h3>Why this is here and not in each panel</h3>
     *
     * <p>Because it is a property of <em>being</em> an overlay, not of being a preview or a blackboard.
     * Two panels each implementing it would need to agree on a shared counter, which is this class's to
     * own — and a third overlay would silently not participate, which reads as the new panel being broken
     * rather than as a rule it never opted into.</p>
     *
     * <h3>CAPTURE phase, deliberately</h3>
     *
     * <p>Capture runs root→target <b>before</b> the target's own handlers, so this sees the press whatever
     * the panel does with it afterwards. Both current overlays call {@code stopPropagation()} — the Main
     * Preview's surface starts an orbit drag, the title bars start a move — so a bubble-phase listener
     * would be reached by exactly the presses that need it least: the ones on dead space.</p>
     *
     * <p>It also raises on a press rather than on a click, which is what makes a drag start on top instead
     * of sliding under the other panel for the duration of the gesture.</p>
     */
    private void raiseOnPress(UIElement panel) {
        panel.onMouseDown.attachListener((element, event) -> {
            // Already frontmost: nothing to write, and writing anyway would burn a z-index per press.
            if (frontmostOverlay == panel) return;
            frontmostOverlay = panel;
            StyleGroup.inlinePipeline(panel.getStyle().getGeneralGroup(), g -> g.zIndex(nextOverlayZ++));
        }, true, false);
    }

    /**
     * Whether a press or wheel landed inside a floating overlay. @see #addOverlay
     *
     * <p><b>Separate from {@link #isInsidePromotedChild} because an overlay is neither promoted nor a
     * node</b>, and every background gesture this class and its subclasses run has to exclude all three.
     * Missing this one is not subtle in its effect but is very subtle in its symptom: a press on an
     * overlay's resize handle starts a resize drag, then bubbles on to the marquee handler, which starts
     * a drag <em>of its own</em> with pointer capture — and {@code UIDragController} cancels the first to
     * make room. The handle looks completely dead, and the press "releases immediately", because the
     * gesture that would have finished it was torn down a microsecond after it began.</p>
     *
     * <p>Membership rather than a structural test: an overlay is an internal child, and so are the
     * canvas's own resize handles and anything else the engine hangs there, so "internal child of this"
     * would claim things that are genuinely the canvas's.</p>
     */
    protected boolean isInsideOverlay(@Nullable UIElement target) {
        if (overlays.isEmpty()) return false;
        for (UIElement element = target; element != null && element != this; element = element.parentElement()) {
            if (overlays.contains(element)) return true;
        }
        return false;
    }

    /** Whether a background gesture should ignore this target entirely — promoted, or a floating panel. */
    protected boolean isBackgroundGestureExempt(@Nullable UIElement target) {
        return isInsidePromotedChild(target) || isInsideOverlay(target);
    }

    /** Adds {@code node} to the plane at a world position. Absolute positioning is what makes a node
     * placeable at all; a flow child would be laid out by the plane instead. */
    public CanvasView addNode(UIElement node, float worldX, float worldY) {
        StyleGroup.defaultPipeline(node.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
        moveNode(node, worldX, worldY);
        content.append(node);
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
        Box cache = box();
        float targetX = cache.x() + cache.width() * 0.5f;
        float targetY = cache.y() + cache.height() * 0.5f;
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
        Box cache = box();
        float viewW = cache.width(), viewH = cache.height();
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
        for (UIElement child : content.children()) {
            WorldRect rect = worldBoundsOf(child);
            union = union == null ? rect : union.union(rect);
        }
        return union;
    }

    /**
     * A node's own rect in world space, or an EMPTY rect at the origin when it has no box yet.
     *
     * <p>{@code box()} is nullable on this engine where {@code getRuntimeCache()} always answered, and
     * a node added this frame has not been laid out — the culling pass asks about every child of the
     * plane on every tick, including one added a moment ago, so this is reached on the ordinary path
     * rather than an exotic one. An empty rect is the right answer rather than a convenient one: it
     * intersects nothing, so a node nobody has measured is culled until it has a size, which is what
     * a zero-area node should be.</p>
     */
    public WorldRect worldBoundsOf(UIElement node) {
        Box cache = node.box();
        if (cache == null) return new WorldRect(0f, 0f, 0f, 0f);
        // A NODE IS A CHILD OF THE PLANE, so `x()` -- the offset from its host's border-box origin --
        // IS its world position already: the plane's pan and zoom live in its TRANSFORM, which `x()`
        // does not carry. The old engine's accessor accumulated through every ancestor, so it had to
        // subtract the plane's own origin to get here; doing that now subtracts it a second time.
        // Invisible while the plane sits at the canvas's own origin, which it does until a sheet gives
        // `canvasview` a padding.
        return new WorldRect(cache.x(), cache.y(), cache.width(), cache.height());
    }

    /**
     * The slice of world space the viewport currently shows, or an empty one before first layout.
     *
     * <p>Empty rather than infinite: an unmeasured viewport shows NOTHING, and answering "everything"
     * would un-cull the whole plane for one frame on every attach.</p>
     */
    public WorldRect visibleWorldRect() {
        Box cache = box();
        if (cache == null) return new WorldRect(0f, 0f, 0f, 0f);
        // THE VIEWPORT'S OWN TOP-LEFT IS (0, 0) IN ITS OWN SPACE, so the world point it shows is
        // just the pan undone. `cache.x()` here is this CANVAS's offset inside ITS parent, which is
        // not a canvas-relative quantity at all -- on the old engine that accessor was absolute and
        // the two terms cancelled; here they do not, and the error is however far down the page the
        // canvas happens to sit. This rect is what culls both nodes and WIRES, so a wrong one hides
        // things that are plainly on screen.
        return new WorldRect(-panX / zoom, -panY / zoom, cache.width() / zoom, cache.height() / zoom);
    }

    // ── Coordinates ─────────────────────────────────────────────────────────

    /**
     * Physical pointer position → world coordinates.
     *
     * <p>Routed through {@link UIElement#toLocal}, so it stays correct under {@code uiScale},
     * an ancestor transform, and an ancestor's scroll offset — none of which this widget knows
     * about.</p>
     */
    public Vector2f screenToWorld(float rawX, float rawY) {
        // `toLocal` puts THIS box's origin at zero (M6.1), so the plane's own offset is already out
        // of the answer and only the pan and zoom remain.
        Vector2f local = toLocal(rawX, rawY);
        return new Vector2f((local.x() - panX) / zoom, (local.y() - panY) / zoom);
    }

    /**
     * World coordinates → the engine's logical space — the same frame {@code RuntimeCache.getX()} and
     * {@link UIElement#toLocal} report in, <b>not</b> physical pixels.
     */
    public Vector2f worldToViewport(float worldX, float worldY) {
        return new Vector2f(contentOriginX() + panX + zoom * worldX,
                contentOriginY() + panY + zoom * worldY);
    }

    /**
     * The exact inverse of {@link #worldToViewport}: a point in the engine's logical space — what
     * {@link UIElement#toLocal} returns and what a {@code DragListener} on this canvas reports —
     * back into world coordinates.
     *
     * <p>Distinct from {@link #screenToWorld}, which starts from <em>physical</em> pointer pixels. Both
     * exist because both starting points are real: a raw event carries physical, and anything already
     * converted (a drag delta, a layout position) carries logical. Collapsing them would mean one caller
     * silently applying {@code uiScale} twice, which looks correct at a scale of 1.</p>
     */
    public Vector2f viewportToWorld(float localX, float localY) {
        // A VIEWPORT COORDINATE STARTS AT ZERO, so only the pan and zoom come off. The plane's own
        // origin used to be subtracted here because the old engine's accessors were absolute and this
        // method was handed one; `toLocal` puts a box's own origin at zero now, and its caller is
        // `screenToWorld` or a drag callback -- both of which already answer in this space.
        return new Vector2f((localX - panX) / zoom, (localY - panY) / zoom);
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
        for (UIElement child : content.children()) {
            if (cullExempt.contains(child)) continue;
            applyCulled(child, !view.intersects(worldBoundsOf(child)));
        }
        // A node removed from the plane while culled would otherwise keep its forced opacity — and
        // stay invisible after being re-parented somewhere else entirely.
        culled.removeIf(node -> {
            if (node.parent() == content) return false;
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
            node.getStyle().getGeneralGroup().set(StyleOrigin.INLINE, StylePropertyRegistry.OPACITY, 0f);
        } else {
            culled.remove(node);
            clearCullOpacity(node);
        }
    }

    /** Removes only <em>our</em> candidate — a caller's own {@code opacity} at any other origin, and
     * a theme's, survive being culled and uncalled. */
    private void clearCullOpacity(UIElement node) {
        node.getStyle().getGeneralGroup().set(StyleOrigin.INLINE, StylePropertyRegistry.OPACITY, null);
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
        for (UIElement element = target; element != null && element != this; element = element.parentElement()) {
            if (document().isPromoted(element)) return true;
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

    /** Takes the RAW pointer position — {@code Drag} converts to local space itself. */
    private void beginPan(float rawX, float rawY, int panButton) {
        UIDocument window = document();
        if (window == null) return;
        final float startPanX = panX, startPanY = panY;
        setPanning(true);
        // The button is declared so the drag ends when THAT button is released — the controller
        // assumes the left one otherwise, and a middle-drag would never stop.
        Drag.start(this, rawX, rawY, panButton, null, 0f,
                new Drag.Listener() {
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
        StyleGroup.inlinePipeline(content.getStyle().getGeneralGroup(), g -> g.transform(view));
    }

    private float contentOriginX() {
        Box origin = content.box();
        return origin == null ? 0f : origin.x();
    }

    private float contentOriginY() {
        Box origin = content.box();
        return origin == null ? 0f : origin.y();
    }

    /**
     * Geometry that can only be settled once layout has run.
     *
     * <p>{@code onLayoutChanged()} on the old engine; there is no such override here, because layout
     * is ONE pass with no feedback into it. A post-layout hook may move a box and read a box and may
     * not add one — a structural change would need a second pass, and there is not one.</p>
     */
    private void onLayoutSettled() {
        ensureTicking();
        updateCulling();
    }

    private void ensureTicking() {
        if (ticking || !cullingEnabled) return;
        UIDocument window = document();
        if (window == null) return;
        document().animation().every(this, this::tickFrame);
        ticking = true;
    }

        public boolean tickFrame(float deltaSeconds) {
        if (!cullingEnabled) {
            ticking = false;
            return false;
        }
        updateCulling();
        return true;
    }
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            onLayoutSettled();
            return true;
        });
    }

}
