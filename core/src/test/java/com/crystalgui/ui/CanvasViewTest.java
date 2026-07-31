package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.canvas.CanvasView;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.elements.canvas.WorldRect;
import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.2.2 — the pan/zoom canvas.
 *
 * <h3>What is actually being asserted</h3>
 * <p>A canvas that pans and zooms is easy to write and easy to get subtly, invisibly wrong: the view
 * moves, the nodes move with it, and the <em>clicks</em> land somewhere else. So the load-bearing test
 * here is not "pan changes pan" — it is {@link #whereTheMathsSaysItIsIsWhereTheEngineDrawsIt()}, which
 * takes a world coordinate through the widget's own conversion, turns it into a physical pointer
 * position, and asks the real hit-tester what is underneath. That pins the widget's arithmetic against
 * the engine's transform chain rather than against itself.</p>
 */
public class CanvasViewTest extends UiTestBase {

    private static final float VIEW = 200f;   // logical size of the canvas box
    private static final float UI_SCALE = 2f;

    private UIWindow window;
    private CanvasView canvas;

    /** What the stub input service reports for Space — the modifier that turns a left-drag into a pan. */
    private boolean spaceHeld;

    @Before
    public void setUp() {
        spaceHeld = false;
        TestPlatformService.get().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) {
                return localKeyCode == com.crystalgraphics.platform.input.CgKeyCodes.KEY_SPACE && spaceHeld;
            }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return ""; }
            @Override public void setClipboard(String text) { }
        });

        canvas = new CanvasView();
        canvas.layout(l -> l.width(VIEW).height(VIEW));

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(canvas);

        window = new UIWindow(Ui.of(root));
        window.setUiScale(UI_SCALE);
        window.init(800, 800); // 400x400 logical — the root fits exactly, so there is no centring offset
        frame();
    }

    /** One frame minus the painting, including the ticker pass the culler runs on. */
    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private UIElement node(float worldX, float worldY, float w, float h) {
        UIElement node = new UIElement().layout(l -> l.width(w).height(h));
        canvas.addNode(node, worldX, worldY);
        frame();
        return node;
    }

    // ── Physical-pixel plumbing, so the tests can speak in world coordinates ──

    /** World point -> physical pointer position, through the widget's own conversion and then the
     * engine's real matrix — never through a hand-rolled {@code * uiScale}, which would only be
     * testing the test. */
    private Vector2f physicalOf(float worldX, float worldY) {
        Vector2f local = canvas.worldToViewport(worldX, worldY);
        return Transform2D.apply(canvas.getRuntimeCache().localToWorld.get(), local.x(), local.y());
    }

    private void mouseTo(float physX, float physY) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(Math.round(physX), Math.round(physY), 0, 0, -1, false, 0f, -1L));
    }

    private void press(float physX, float physY, int button) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(Math.round(physX), Math.round(physY), 0, 0, button, true, 0f, 1L));
    }

    private void release(float physX, float physY, int button) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(Math.round(physX), Math.round(physY), 0, 0, button, false, 0f, 2L));
    }

    private void wheel(float physX, float physY, float notches) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(Math.round(physX), Math.round(physY), 0, 0, -1, false, notches, -1L));
    }

    // ── The property the widget exists for ──────────────────────────────────

    /**
     * <b>The click lands where the picture is.</b>
     *
     * <p>The widget's conversion says a world point is at a particular physical pixel; the engine's
     * own hit-tester — the one that inverts {@code localToWorld}, which rendering also derives its
     * pose from — is asked what is under that pixel. If the two ever disagree the canvas looks
     * perfect and is unusable, which is the failure this whole class is arranged around.</p>
     */
    @Test
    public void whereTheMathsSaysItIsIsWhereTheEngineDrawsIt() {
        UIElement node = node(120f, 80f, 40f, 20f);

        canvas.setZoom(1.7f);
        canvas.setPan(-90f, -40f);
        frame();

        Vector2f centre = physicalOf(140f, 90f); // the node's world centre
        assertSame("hit test disagrees with worldToViewport at " + centre,
                node, window.getHoveredElement(centre.x(), centre.y()));
    }

    /** Zoom about the pointer: whatever was under the cursor is still under the cursor afterwards. */
    @Test
    public void zoomAboutTheCursorPinsTheWorldPointUnderIt() {
        node(0f, 0f, 500f, 500f);
        Vector2f cursor = physicalOf(70f, 55f);

        Vector2f before = canvas.screenToWorld(cursor.x(), cursor.y());
        canvas.zoomAt(2.5f, cursor.x(), cursor.y());
        frame();
        Vector2f after = canvas.screenToWorld(cursor.x(), cursor.y());

        assertEquals(2.5f, canvas.getZoom(), 1e-4f);
        assertEquals(before.x(), after.x(), 1e-3f);
        assertEquals(before.y(), after.y(), 1e-3f);
    }

    /** And the same through the real wheel path, which is the only bit a user touches. */
    @Test
    public void theWheelZoomsAboutTheCursor() {
        node(0f, 0f, 500f, 500f);
        Vector2f cursor = physicalOf(60f, 60f);
        mouseTo(cursor.x(), cursor.y());
        frame();

        Vector2f before = canvas.screenToWorld(cursor.x(), cursor.y());
        wheel(cursor.x(), cursor.y(), -1f);
        frame();

        assertTrue("a wheel-up notch must zoom in", canvas.getZoom() > 1f);
        Vector2f after = canvas.screenToWorld(cursor.x(), cursor.y());
        assertEquals(before.x(), after.x(), 1e-3f);
        assertEquals(before.y(), after.y(), 1e-3f);
    }

    /**
     * <b>Which way the wheel zooms, pinned against the engine's own convention rather than against
     * this widget's code.</b>
     *
     * <p>A positive notch means the wheel rolled <em>down</em> here — the only thing that says so is
     * {@code ScrollerView}, which grows {@code scrollTop} by a positive delta. So the same notch that
     * scrolls a list downwards must zoom the canvas <em>out</em>. This shipped inverted: nothing
     * failed, because a test written from the implementation agrees with the implementation, and it
     * was caught by the first person to touch a wheel. Asserting both scrollers here is the point —
     * if the platform's sign ever flips, these two fail together instead of drifting apart.</p>
     */
    @Test
    public void aPositiveNotchScrollsDownAndThereforeZoomsOut() {
        ScrollerView list = new ScrollerView();
        list.layout(l -> l.width(60).height(60));
        for (int i = 0; i < 10; i++) list.addChild(new UIElement().layout(l -> l.width(60).height(30)));
        canvas.content().addChild(list);
        frame();

        float scrollBefore = list.getTargetScrollTop();
        var handler = window.getInputHandler();
        handler.sendInputEvent(list, new MouseEvent.Scroll(list, handler.pointerPosition(), 1f));
        frame();

        assertTrue("reference: a positive notch scrolls a list DOWN", list.getTargetScrollTop() > scrollBefore);

        // Clear of the list: it sits at world (0,0)-(60,60) and legitimately eats the wheel it can
        // use, so aiming there tests the scroller a second time instead of the canvas.
        Vector2f cursor = physicalOf(150f, 150f);
        wheel(cursor.x(), cursor.y(), 1f);
        frame();
        assertTrue("so the same notch must zoom OUT, not in", canvas.getZoom() < 1f);
    }

    /**
     * <b>{@code transform-origin} is the widget's, not the theme's.</b>
     *
     * <p>It defaults to 50% — the element's centre — and every conversion here assumes the plane
     * scales about its top-left, so a theme setting it would silently offset the whole canvas by half
     * a viewport, scaled. The canvas writes it at IMPORTANT for exactly this reason; this asserts the
     * cascade actually holds that line.</p>
     */
    @Test
    public void aThemeCannotMoveTheZoomPivot() {
        UIElement node = node(120f, 80f, 40f, 20f);
        canvas.content().getStyle().getGeneralGroup()
                .transformOrigin(LengthPercent.percent(0.5f), LengthPercent.percent(0.5f));
        canvas.setZoom(2f);
        canvas.centerOnWorld(140f, 90f); // at 2x the node is off-screen otherwise, and clipped out of the hit test
        frame();

        Vector2f centre = physicalOf(140f, 90f);
        assertSame(node, window.getHoveredElement(centre.x(), centre.y()));
    }

    // ── Gestures ────────────────────────────────────────────────────────────

    @Test
    public void middleDragPansOneForOneInLogicalPixels() {
        Vector2f start = physicalOf(50f, 50f);
        press(start.x(), start.y(), com.crystalgraphics.platform.input.CgMouseCodes.MIDDLE_BUTTON);
        frame();
        assertTrue("the middle button must start a pan", canvas.isPanning());

        // 30 logical px right, 20 down — at uiScale 2 that is 60x40 physical.
        mouseTo(start.x() + 60f, start.y() + 40f);
        frame();

        assertEquals(30f, canvas.getPanX(), 0.5f);
        assertEquals(20f, canvas.getPanY(), 0.5f);

        release(start.x() + 60f, start.y() + 40f, com.crystalgraphics.platform.input.CgMouseCodes.MIDDLE_BUTTON);
        frame();
        assertFalse(canvas.isPanning());
    }

    /** Space+left pans — the escape hatch for a mouse with no usable middle button. */
    @Test
    public void spaceTurnsALeftDragIntoAPan() {
        spaceHeld = true;
        Vector2f start = physicalOf(50f, 50f);
        press(start.x(), start.y(), com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON);
        frame();
        mouseTo(start.x() + 40f, start.y());
        frame();

        assertTrue(canvas.isPanning());
        assertEquals(20f, canvas.getPanX(), 0.5f);
    }

    /**
     * A bare left-drag does <b>not</b> pan, and that is a reservation rather than an omission: it is
     * the marquee-select gesture 6.2.4 needs, and handing it to panning now would take it back later.
     */
    @Test
    public void aBareLeftDragIsLeftForTheMarquee() {
        Vector2f start = physicalOf(50f, 50f);
        press(start.x(), start.y(), com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON);
        frame();
        mouseTo(start.x() + 60f, start.y() + 60f);
        frame();

        assertFalse(canvas.isPanning());
        assertEquals(0f, canvas.getPanX(), 1e-4f);
    }

    /** A pan gesture beats whatever is under the cursor — it is captured, not bubbled. */
    @Test
    public void panningWinsOverTheNodeUnderTheCursor() {
        UIElement node = node(10f, 10f, 100f, 100f);
        final int[] seen = {0};
        node.onMouseDown.attachListener((el, e) -> seen[0]++, false, true);

        Vector2f over = physicalOf(60f, 60f);
        press(over.x(), over.y(), com.crystalgraphics.platform.input.CgMouseCodes.MIDDLE_BUTTON);
        frame();

        assertTrue(canvas.isPanning());
        assertEquals("the node must not see a press that started a pan", 0, seen[0]);
    }

    // ── Culling ─────────────────────────────────────────────────────────────

    @Test
    public void offScreenNodesAreCulledAndComeBackWhenPannedTo() {
        UIElement near = node(10f, 10f, 50f, 50f);
        UIElement far = node(900f, 10f, 50f, 50f);

        assertFalse(canvas.isCulled(near));
        assertTrue("a node 900 world units right of a 200px viewport is not visible",
                canvas.isCulled(far));

        canvas.setPan(-880f, 0f);
        frame();

        assertTrue(canvas.isCulled(near));
        assertFalse("panning to a node must bring it back", canvas.isCulled(far));
    }

    /**
     * <b>Culling skips paint, not layout.</b>
     *
     * <p>{@code display: none} would collapse a culled node's layout — and its layout rect is the
     * input the cull decision is computed from, so it could never be un-culled without a cache of
     * where it used to be. This asserts the culled node still measures, which is what keeps the
     * decision self-correcting.</p>
     */
    @Test
    public void aCulledNodeKeepsItsLayout() {
        UIElement far = node(900f, 10f, 50f, 40f);
        assertTrue(canvas.isCulled(far));

        assertEquals(50f, far.getRuntimeCache().getWidth(), 1e-3f);
        assertEquals(40f, far.getRuntimeCache().getHeight(), 1e-3f);
        assertEquals(0f, far.getStyle().getGeneralGroup().opacity(), 1e-4f);
    }

    /** Turning culling off must hand back the opacity it took — including a node's own. */
    @Test
    public void unCullingRestoresTheCallersOwnOpacity() {
        UIElement far = node(900f, 10f, 50f, 40f);
        far.generalStyle(g -> g.opacity(0.4f));
        frame();
        assertTrue(canvas.isCulled(far));
        assertEquals("culling outranks the caller while it holds", 0f,
                far.getStyle().getGeneralGroup().opacity(), 1e-4f);

        canvas.setCullingEnabled(false);
        frame();
        assertFalse(canvas.isCulled(far));
        assertEquals(0.4f, far.getStyle().getGeneralGroup().opacity(), 1e-4f);
    }

    /** A node that leaves the plane while culled must not carry the forced opacity away with it. */
    @Test
    public void aRemovedNodeIsReleasedFromCulling() {
        UIElement far = node(900f, 10f, 50f, 40f);
        assertTrue(canvas.isCulled(far));

        canvas.content().removeChild(far);
        frame();

        assertFalse(canvas.isCulled(far));
        assertEquals(1f, far.getStyle().getGeneralGroup().opacity(), 1e-4f);
    }

    // ── Framing ─────────────────────────────────────────────────────────────

    @Test
    public void fitToContentFramesEveryNode() {
        node(0f, 0f, 100f, 100f);
        node(600f, 400f, 100f, 100f);

        canvas.fitToContent(10f);
        frame();

        WorldRect visible = canvas.visibleWorldRect();
        WorldRect bounds = canvas.contentBounds();
        assertNotNull(bounds);
        assertTrue("everything must be inside the view after a fit: " + bounds + " in " + visible,
                visible.contains(bounds));
        assertTrue("and it should be a tight fit, not a distant one",
                visible.width() < bounds.width() * 2f);
    }

    @Test
    public void fitOnAnEmptyCanvasChangesNothing() {
        canvas.setZoom(1.5f);
        canvas.fitToContent();
        assertEquals(1.5f, canvas.getZoom(), 1e-4f);
    }

    @Test
    public void zoomIsClampedToTheDeclaredRange() {
        canvas.setZoomRange(0.5f, 2f);
        canvas.setZoom(99f);
        assertEquals(2f, canvas.getZoom(), 1e-4f);
        canvas.setZoom(0.001f);
        assertEquals(0.5f, canvas.getZoom(), 1e-4f);
    }

    // ── Structure ───────────────────────────────────────────────────────────

    /** A child of the viewport would sit outside the transform and stay nailed to the screen while
     * everything else panned — so it is refused, as with every other composite here. */
    @Test
    public void theViewportRefusesPublicChildren() {
        assertFalse(canvas.acceptsPublicChildren());
        try {
            canvas.addChild(new UIElement());
            fail("addChild on the viewport must throw");
        } catch (RuntimeException expected) {
            // the composite-widget convention
        }
    }

    @Test
    public void theViewChangeSignalFiresOncePerChange() {
        int[] fired = {0};
        canvas.onViewChanged.connect(() -> fired[0]++);

        canvas.setPan(10f, 10f);
        canvas.setPan(10f, 10f);   // unchanged — must not fire
        canvas.setZoom(2f);

        assertEquals(2, fired[0]);
    }
}
