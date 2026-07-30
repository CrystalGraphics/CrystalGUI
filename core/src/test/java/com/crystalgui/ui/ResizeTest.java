package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.ui.input.UIInputHandler;
import dev.vfyjxf.taffy.style.TaffyDimension;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The CSS {@code resize} property (CSS Basic User Interface L4) — a port, not an invention.
 *
 * <p>Ambient on any element, driven by the cascade, exactly as {@code overflow} makes any element a
 * scroll container. Nobody constructs a "Resizable" widget; you write {@code resize: both} and a
 * {@code __resizer__} handle appears.</p>
 *
 * <p>The assertion that matters most here is the <b>origin</b> one. The spec says a user resize writes
 * width/height into the style attribute "replacing existing property declaration(s), if any,
 * <em>without {@code !important}</em>" — so it goes in at {@link StyleOrigin#INLINE}. Every other
 * code-driven geometry write in this engine uses {@code IMPORTANT}, which makes this the single most
 * likely thing to be "tidied" into the wrong pipeline, at which point a user drag would silently
 * start outranking an author's {@code !important} rule.</p>
 */
public class ResizeTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root, panel;

    private void build(Resize mode) {
        root = new UIElement().layout(l -> l.width(400).height(400));
        // Out of flow, because that is the only case where a LEADING edge can resize: moving the origin
        // is how the opposite edge stays put, and `left`/`top` only place an absolutely positioned box.
        // On an in-flow element they are a relative offset that slides it over its neighbours — see
        // inFlowElementsGetNoLeadingHandles below.
        // Placed away from the corner on purpose. At the origin a leading edge has nowhere to grow —
        // the containing-block clamp stops it, correctly — so a fixture parked at (0,0) cannot exercise
        // leftward or upward resizing at all.
        panel = new UIElement().layout(l -> l.width(100).height(80)
                .positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                .left(120).top(100));
        panel.generalStyle(g -> g.resize(mode));
        root.addChild(panel);

        window = new UIWindow(Ui.of(root));
        window.init(800, 800); // uiScale 2
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame(); // firstFrameOver — input is dropped before a frame exists
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    /** Any handle, for existence checks. There are up to eight now — see {@link #handleOf}. */
    private UIElement anyHandleOf(UIElement target) {
        for (UIElement child : target.getChildren()) {
            if (child.hasClass("__resizer__")) return child;
        }
        return null;
    }

    /** A specific handle, by edge — {@code "bottom-right"}, {@code "left"}, and so on. Selecting
     * explicitly matters: iteration order is enum order, so "the first handle" is {@code top}, and a
     * test that grabbed it while meaning the corner would silently resize the wrong axis. */
    private UIElement handleOf(UIElement target, String edge) {
        for (UIElement child : target.getChildren()) {
            if (child.hasClass("__resizer-" + edge + "__")) return child;
        }
        return null;
    }

    private int handleCountOf(UIElement target) {
        int n = 0;
        for (UIElement child : target.getChildren()) if (child.hasClass("__resizer__")) n++;
        return n;
    }

    private void press(float x, float y) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    private void move(float x, float y) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
    }

    /** Grabs the handle and drags by a logical delta. Sizes the handle explicitly so the fixture does
     * not depend on default.css's chosen grabber size. */
    private void dragHandleBy(float dx, float dy) {
        dragHandleBy("bottom-right", dx, dy);
    }

    private void dragHandleBy(String edge, float dx, float dy) {
        UIElement handle = handleOf(panel, edge);
        assertNotNull("no `" + edge + "` handle on this panel", handle);
        // Sized explicitly so the fixture does not depend on default.css's chosen handle thickness.
        handle.layout(l -> l.width(10).height(10));
        settle();

        float hx = handle.getRuntimeCache().getX() + 5f;
        float hy = handle.getRuntimeCache().getY() + 5f;
        press(hx, hy);
        move(hx + dx, hy + dy);
        settle();
    }

    // ── The handle is cascade-driven ────────────────────────────────────────

    @Test
    public void noHandleUntilResizeIsSet() {
        build(Resize.NONE);
        assertNull("resize: none must cost nothing structurally", anyHandleOf(panel));
    }

    @Test
    public void settingResizeAddsTheHandleAndClearingItRemovesIt() {
        build(Resize.NONE);

        panel.generalStyle(g -> g.resize(Resize.BOTH));
        settle();
        assertNotNull("the handles appear from the cascade, not from constructing a widget",
                anyHandleOf(panel));

        panel.generalStyle(g -> g.resize(Resize.NONE));
        settle();
        assertNull(anyHandleOf(panel));
    }

    /** The handle is internal, so it is invisible to public traversal and to the codec — like every
     * other structural part in this engine. */
    @Test
    public void theHandleIsAnInternalChild() {
        build(Resize.BOTH);
        assertTrue(anyHandleOf(panel).isInternalUI());
    }

    /** Out of flow, or adding `resize:` to an element would visibly reflow its content. */
    @Test
    public void addingTheHandleDoesNotReflowTheContent() {
        build(Resize.NONE);
        panel.addChild(new UIElement().layout(l -> l.width(40).height(20)));
        settle();
        float contentYBefore = panel.getChildren().get(0).getRuntimeCache().getY();

        panel.generalStyle(g -> g.resize(Resize.BOTH));
        settle();

        assertEquals("the grabber must not take a slot in its parent's layout",
                contentYBefore, panel.getChildren().get(0).getRuntimeCache().getY(), 0.001f);
    }

    // ── Resizing ────────────────────────────────────────────────────────────

    @Test
    public void draggingTheHandleResizesBothAxes() {
        build(Resize.BOTH);

        dragHandleBy(30f, 20f);

        assertEquals(130f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(100f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    /** {@code horizontal} offers the two side edges and <b>no corners</b> — a corner would imply a
     * vertical resize the mode forbids. */
    @Test
    public void horizontalOnlyLeavesHeightAlone() {
        build(Resize.HORIZONTAL);

        assertNull("a corner handle would imply a vertical resize", handleOf(panel, "bottom-right"));
        dragHandleBy("right", 30f, 20f);

        assertEquals(130f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals("height must be untouched", 80f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    @Test
    public void verticalOnlyLeavesWidthAlone() {
        build(Resize.VERTICAL);

        assertNull("a corner handle would imply a horizontal resize", handleOf(panel, "bottom-right"));
        dragHandleBy("bottom", 30f, 20f);

        assertEquals("width must be untouched", 100f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(100f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    // ── Eight handles ───────────────────────────────────────────────────────

    /**
     * An in-flow element gets only the <b>trailing</b> handles — which is exactly the set CSS offers.
     *
     * <p>A leading handle has to move the origin so the opposite edge stays put, and on an in-flow box
     * {@code left}/{@code top} is a relative offset: it slides over the sibling above while everything
     * below carries on as though nothing moved. Reported from the harness as a panel eating its
     * neighbour when its top edge was dragged. CSS sidesteps this by offering one grabber at the
     * bottom-right and never moving the box; the eight handles are our extension, so it applies where it
     * is meaningful.</p>
     */
    @Test
    public void inFlowElementsGetNoLeadingHandles() {
        root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement flowPanel = new UIElement().layout(l -> l.width(100).height(80));
        flowPanel.generalStyle(g -> g.resize(Resize.BOTH));
        root.addChild(flowPanel);
        window = new UIWindow(Ui.of(root));
        window.init(800, 800);
        settle();

        assertNull("no top edge on an element that cannot move", handleOf(flowPanel, "top"));
        assertNull("nor a left one", handleOf(flowPanel, "left"));
        assertNotNull("but the trailing edges are still there", handleOf(flowPanel, "bottom"));
        assertNotNull(handleOf(flowPanel, "right"));
        assertEquals("right, bottom and the bottom-right corner — the set CSS itself offers",
                3, handleCountOf(flowPanel));
    }

    /** Becoming positioned later must grow the missing handles, since `resize` and `position` are
     * independent properties and either can be set first. */
    @Test
    public void becomingPositionedGrowsTheLeadingHandles() {
        root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement flowPanel = new UIElement().layout(l -> l.width(100).height(80));
        flowPanel.generalStyle(g -> g.resize(Resize.BOTH));
        root.addChild(flowPanel);
        window = new UIWindow(Ui.of(root));
        window.init(800, 800);
        settle();
        assertEquals(3, handleCountOf(flowPanel));

        flowPanel.layout(l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE));
        settle();
        settle();

        assertEquals("all eight once it can actually be placed", 8, handleCountOf(flowPanel));
    }

    /**
     * Which handles exist is a function of the axes the mode allows.
     *
     * <p>Eight is not a divergence from CSS UI 4: the spec says only that the UA "presents a
     * bidirectional resizing mechanism" and never prescribes a single corner grabber. Browsers ship
     * one because theirs is drawn in the scrollbar gutter with nowhere else to go.</p>
     */
    @Test
    public void theHandleSetFollowsTheResizableAxes() {
        build(Resize.BOTH);
        assertEquals("four edges and four corners", 8, handleCountOf(panel));

        build(Resize.HORIZONTAL);
        assertEquals("side edges only", 2, handleCountOf(panel));
        assertNotNull(handleOf(panel, "left"));
        assertNotNull(handleOf(panel, "right"));

        build(Resize.VERTICAL);
        assertEquals("top and bottom edges only", 2, handleCountOf(panel));
        assertNotNull(handleOf(panel, "top"));
        assertNotNull(handleOf(panel, "bottom"));

        build(Resize.NONE);
        assertEquals(0, handleCountOf(panel));
    }

    /** A trailing edge grows by the drag and leaves the origin alone. */
    @Test
    public void draggingTheRightEdgeGrowsWidthOnly() {
        build(Resize.BOTH);

        dragHandleBy("right", 40f, 0f);

        assertEquals(140f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals("height untouched by a side edge", 80f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    @Test
    public void draggingTheBottomEdgeGrowsHeightOnly() {
        build(Resize.BOTH);

        dragHandleBy("bottom", 0f, 25f);

        assertEquals("width untouched by a horizontal edge", 100f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(105f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    /**
     * <b>A leading edge is a move as well as a resize.</b> Dragging the left edge leftwards grows the
     * element <em>and</em> shifts its origin by the same amount, so the right edge stays put. This is
     * the case CSS's single bottom-right grabber exists to avoid ever needing.
     */
    @Test
    public void draggingTheLeftEdgeGrowsAwayFromAStationaryRightEdge() {
        build(Resize.BOTH);
        float rightEdgeBefore = panel.getRuntimeCache().getX() + panel.getRuntimeCache().getWidth();

        dragHandleBy("left", -30f, 0f);
        settle();

        assertEquals("width grows by the drag's negation", 130f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals("the opposite edge must not move", rightEdgeBefore,
                panel.getRuntimeCache().getX() + panel.getRuntimeCache().getWidth(), 0.5f);
    }

    @Test
    public void draggingTheTopEdgeGrowsAwayFromAStationaryBottomEdge() {
        build(Resize.BOTH);
        float bottomBefore = panel.getRuntimeCache().getY() + panel.getRuntimeCache().getHeight();

        dragHandleBy("top", 0f, -20f);
        settle();

        assertEquals(100f, panel.getRuntimeCache().getHeight(), 0.5f);
        assertEquals("the opposite edge must not move", bottomBefore,
                panel.getRuntimeCache().getY() + panel.getRuntimeCache().getHeight(), 0.5f);
    }

    /** A corner moves both axes at once, and a leading corner moves the origin on both. */
    @Test
    public void draggingTheTopLeftCornerResizesAndMovesBothAxes() {
        build(Resize.BOTH);
        float rightBefore = panel.getRuntimeCache().getX() + panel.getRuntimeCache().getWidth();
        float bottomBefore = panel.getRuntimeCache().getY() + panel.getRuntimeCache().getHeight();

        dragHandleBy("top-left", -20f, -10f);
        settle();

        assertEquals(120f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(90f, panel.getRuntimeCache().getHeight(), 0.5f);
        assertEquals(rightBefore, panel.getRuntimeCache().getX() + panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(bottomBefore, panel.getRuntimeCache().getY() + panel.getRuntimeCache().getHeight(), 0.5f);
    }

    /** Resizing accumulates from the size at grab time, not from the live box — reading the live box
     * each frame would compound the delta and the element would race away from the cursor. */
    @Test
    public void resizingIsRelativeToTheSizeAtGrabTimeNotCompounded() {
        build(Resize.BOTH);
        UIElement handle = handleOf(panel, "bottom-right");
        handle.layout(l -> l.width(10).height(10));
        settle();

        float hx = handle.getRuntimeCache().getX() + 5f;
        float hy = handle.getRuntimeCache().getY() + 5f;
        press(hx, hy);
        move(hx + 10f, hy + 10f);
        settle();
        move(hx + 20f, hy + 20f); // same drag, twice as far from the origin
        settle();

        assertEquals("total growth must equal the total drag, not the sum of per-frame deltas",
                120f, panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── Origin (the fidelity detail) ────────────────────────────────────────

    /**
     * <b>The one most likely to be broken by a well-meaning tidy-up.</b> Spec: the UA writes the
     * resulting size "without {@code !important}". At IMPORTANT origin a user drag would outrank an
     * author's {@code !important} rule, which the spec explicitly does not allow.
     */
    @Test
    public void theResultingSizeIsWrittenAtInlineOriginNotImportant() {
        build(Resize.BOTH);

        dragHandleBy(30f, 0f);

        // Asserting merely that an INLINE candidate EXISTS would be vacuous — build() already writes
        // one via layout(). It has to be the RESIZED value that sits there.
        assertTrue("the resized width must be the value at INLINE origin",
                panel.getStyle().containsCandidate(LayoutProperties.WIDTH,
                        slot -> slot.origin() == StyleOrigin.INLINE
                                && slot.value() != null
                                && Math.abs(((TaffyDimension) slot.value()).getValue() - 130f) < 0.5f));
        assertFalse("and nothing may land at IMPORTANT",
                panel.getStyle().containsCandidate(LayoutProperties.WIDTH,
                        slot -> slot.origin() == StyleOrigin.IMPORTANT));
    }

    /** The consequence of the above, stated as behaviour rather than as plumbing. */
    @Test
    public void anAuthorsImportantWidthStillBeatsAUserResize() {
        build(Resize.BOTH);
        StyleGroup.importantPipeline(panel.getStyle().getLayoutGroup(), l -> l.width(100));
        settle();

        dragHandleBy(60f, 0f);

        assertEquals("!important must survive a user resize", 100f,
                panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── Constraints ─────────────────────────────────────────────────────────

    /**
     * The spec's only constraints on a resize are {@code min-*}/{@code max-*}.
     *
     * <p>Taffy applies them regardless, so these two tests would pass even if the resizer wrote the raw
     * dragged size — which is what it used to do. It now re-applies the same bounds itself, not to
     * constrain the box but to <em>know</em> the size it will settle at, because a leading edge has to
     * derive its origin from that. See
     * {@link #shrinkingFromALeadingEdgeStopsMovingOnceTheSizeStops()}.</p>
     */
    @Test
    public void maxWidthConstrainsTheResize() {
        build(Resize.BOTH);
        panel.layout(l -> l.maxWidth(120));
        settle();

        dragHandleBy(200f, 0f);

        assertEquals("max-width must cap it", 120f, panel.getRuntimeCache().getWidth(), 0.5f);
    }

    @Test
    public void minWidthConstrainsTheResize() {
        build(Resize.BOTH);
        panel.getStyle().getLayoutGroup().set(LayoutProperties.MIN_WIDTH, TaffyDimension.length(60f));
        settle();

        dragHandleBy(-200f, 0f);

        assertEquals("min-width must floor it", 60f, panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── The box stays inside its containing block ───────────────────────

    /**
     * <b>A resize cannot push a box out through its containing block.</b>
     *
     * <p>Moving was clamped this way from the start and sizing was not, so a panel dragged into the
     * bottom-right corner — as far as it could be moved — could then be <em>grown</em> straight out
     * through the corner it had just been stopped at. Reported from the harness exactly that way.</p>
     *
     * <p>No spec covers this, for the same reason none covers the move clamp: the web has no draggable
     * window. It is the OS window-manager behaviour, and the two halves have to agree or the clamp
     * reads as arbitrary.</p>
     */
    @Test
    public void aTrailingResizeStopsAtTheContainingBlocksEdge() {
        build(Resize.BOTH);
        // Parked at the far corner of the 400x400 root: 300+100 and 320+80 land exactly on the edges.
        panel.layout(l -> l.left(300).top(320));
        settle();

        dragHandleBy("bottom-right", 500f, 500f);
        settle();

        assertEquals("nothing left to grow into on the right", 100f,
                panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals("nor below", 80f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    /** The leading counterpart: growing leftwards stops when the origin reaches the container's edge,
     * rather than carrying the box out through it into negative coordinates. */
    @Test
    public void aLeadingResizeStopsWhenItsOriginReachesTheEdge() {
        build(Resize.BOTH);
        panel.layout(l -> l.left(40).top(40));
        settle();

        dragHandleBy("left", -500f, 0f);
        settle();

        assertEquals("40 of travel available, so 100 + 40", 140f,
                panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals("and the origin lands on the edge, not past it", 0f,
                panel.getRuntimeCache().getX(), 0.5f);
    }

    /** In-flow elements are deliberately exempt: {@code left}/{@code top} are a relative nudge there, so
     * there is no origin to clamp — and they have no leading handles for the same reason. Growing one
     * past its parent is ordinary overflow, which CSS permits. */
    @Test
    public void anInFlowElementIsNotClampedToItsParent() {
        root = new UIElement().layout(l -> l.width(400).height(400));
        panel = new UIElement().layout(l -> l.width(100).height(80));
        panel.generalStyle(g -> g.resize(Resize.BOTH));
        root.addChild(panel);
        window = new UIWindow(Ui.of(root));
        window.init(800, 800);
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();

        dragHandleBy("right", 500f, 0f);
        settle();

        assertEquals("overflow is legal; only positioned boxes are clamped", 600f,
                panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── The origin follows the achieved size, not the pointer ─────────────

    /**
     * <b>Once the size stops shrinking, the element stops moving.</b>
     *
     * <p>The origin used to follow the raw pointer delta, so dragging a top edge downward shrank the
     * dialog to its {@code min-height} and then went on <em>towing it down the screen</em> — while the
     * mirror-image drag upward from the bottom correctly just stopped. The asymmetry is what gave it
     * away: only the leading edges move anything, so only they could diverge.</p>
     *
     * <p>Deriving the origin from the size actually achieved makes the two halves the same computation,
     * so they cannot come apart again.</p>
     */
    @Test
    public void shrinkingFromALeadingEdgeStopsMovingOnceTheSizeStops() {
        build(Resize.BOTH);
        panel.getStyle().getLayoutGroup().set(LayoutProperties.MIN_HEIGHT, TaffyDimension.length(40f));
        settle();

        // 80 tall against a floor of 40: only 40px of shrink exists, and the drag asks for 100.
        dragHandleBy("top", 0f, 100f);
        settle();

        assertEquals("floored at min-height", 40f, panel.getRuntimeCache().getHeight(), 0.5f);
        // Started at y=100 and shrank by the 40 that was available, so the top edge lands at 140 — not
        // at the 200 the raw 100px drag would have taken it to.
        assertEquals("the origin travels only as far as the box actually shrank",
                140f, panel.getRuntimeCache().getY(), 0.5f);
    }

    /** The trailing half of the same pair, stated so the symmetry is pinned rather than assumed. */
    @Test
    public void shrinkingFromATrailingEdgeLeavesTheOriginAlone() {
        build(Resize.BOTH);
        panel.getStyle().getLayoutGroup().set(LayoutProperties.MIN_HEIGHT, TaffyDimension.length(40f));
        settle();
        float yBefore = panel.getRuntimeCache().getY();

        dragHandleBy("bottom", 0f, -100f);
        settle();

        assertEquals(40f, panel.getRuntimeCache().getHeight(), 0.5f);
        assertEquals("a trailing edge never moves the box", yBefore,
                panel.getRuntimeCache().getY(), 0.5f);
    }

    /**
     * The origin is read from the live inset, not from a field this class wrote.
     *
     * <p>A field only knows about positions the resizer itself applied, so an element placed by the
     * cascade reported an origin of zero and the first leading drag teleported it to the corner before
     * resizing anything.</p>
     */
    @Test
    public void aLeadingDragRespectsAPositionSetByTheCascade() {
        build(Resize.BOTH);
        panel.layout(l -> l.left(150).top(100));
        settle();
        float rightBefore = panel.getRuntimeCache().getX() + panel.getRuntimeCache().getWidth();

        dragHandleBy("left", -20f, 0f);
        settle();

        assertEquals(120f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals("the right edge must stay put even though nothing here wrote `left` first",
                rightBefore, panel.getRuntimeCache().getX() + panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── Divergence from the spec, asserted so it stays deliberate ───────────

    /**
     * The spec restricts {@code resize} to scroll containers. We do not — that restriction is an
     * artifact of browsers drawing the grabber in the scrollbar corner, and we draw our own. A
     * resizable panel in a UI toolkit is very often not scrollable.
     */
    @Test
    public void resizeWorksOnANonScrollContainer() {
        build(Resize.BOTH);
        assertFalse("fixture must genuinely not be a scroll container", panel.isScrollContainer());

        dragHandleBy(30f, 0f);

        assertEquals(130f, panel.getRuntimeCache().getWidth(), 0.5f);
    }
}
