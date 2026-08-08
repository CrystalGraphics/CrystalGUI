package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.graph.GraphView;
import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.12 — a floating panel over a canvas, and the gestures that must leave it alone.
 *
 * <h3>The failure this exists for, because the symptom points nowhere near the cause</h3>
 * <p>A press on the Main Preview's resize handle started a resize drag correctly — and then <b>bubbled on
 * to {@code GraphView}, which started a marquee drag of its own with pointer capture.</b>
 * {@code UIDragController} cancels a live drag when a second one begins, so the resize was torn down a
 * microsecond after it started. The handle looked completely dead and the press "released immediately",
 * with nothing in the resize code wrong at all.</p>
 *
 * <p>The canvas already excluded promoted children and nodes from its background gestures for exactly
 * this class of reason. An overlay is neither, so it needed the third carve-out — and this test is what
 * says so, since every part of it works in isolation.</p>
 */
public class CanvasOverlayTest extends UiTestBase {

    private static final float VIEW = 300f;

    private UIWindow window;
    private GraphView graph;
    private UIElement overlay;

    @Before
    public void setUp() {
        graph = new GraphView();
        graph.layout(l -> l.width(VIEW).height(VIEW));

        overlay = new UIElement().layout(l -> l.width(80f).height(60f).left(10f).top(10f));
        graph.addOverlay(overlay);

        UIElement root = new UIElement().layout(l -> l.width(VIEW).height(VIEW));
        root.addChild(graph);

        window = new UIWindow(Ui.of(root));
        // The user-agent sheet, because it is what gives a MainPreviewPanel its size. Without it the
        // panel lays out at 0x0 and every pointer test silently misses — a press lands on the canvas
        // instead and the assertion fails for a reason that has nothing to do with what it is testing.
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        window.init((int) VIEW, (int) VIEW);
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private Vector2f physicalCentreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        return Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() / 2f, cache.getY() + cache.getHeight() / 2f);
    }

    private void press(Vector2f at) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, 0, true, 0f, 1L));
        frame();
    }

    private void scroll(Vector2f at, float notches) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, -1, false, notches, -1L));
        frame();
    }

    // ── The overlay exists and is where it says ─────────────────────────────

    @Test
    public void anOverlayIsHitTestableOverTheCanvas() {
        Vector2f at = physicalCentreOf(overlay);
        assertSame("the overlay must be what the pointer finds, not the canvas under it",
                overlay, window.getHoveredElement(at.x(), at.y()));
    }

    /** It is in the viewport, not on the plane — so panning must not carry it away. */
    @Test
    public void anOverlayDoesNotMoveWhenTheCanvasPans() {
        float before = overlay.getRuntimeCache().getX();
        graph.setPan(120f, 80f);
        frame();
        assertEquals("an overlay is viewport-fixed", before, overlay.getRuntimeCache().getX(), 0.01f);
    }

    // ── ...and the canvas leaves it alone ───────────────────────────────────

    /**
     * <b>A press on an overlay must not start a marquee.</b>
     *
     * <p>The marquee takes pointer capture, which is what cancelled the overlay's own drag. Asserting on
     * the drag controller rather than on the marquee's visibility is deliberate: capture is the part that
     * does the damage, and a marquee that is merely invisible would still have taken it.</p>
     */
    @Test
    public void aPressOnAnOverlayDoesNotStartACanvasDrag() {
        press(physicalCentreOf(overlay));
        assertFalse("the canvas claimed a press that was not its own",
                window.getInputHandler().getDragController().isDragging());
    }

    /** The control: a press on bare canvas still does start one, so the guard is not a blanket off-switch. */
    @Test
    public void aPressOnBareCanvasStillStartsAMarquee() {
        var cache = graph.getRuntimeCache();
        Vector2f empty = Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() - 20f, cache.getY() + cache.getHeight() - 20f);
        press(empty);
        assertTrue("the marquee is the canvas's own gesture and must survive the carve-out",
                window.getInputHandler().getDragController().isDragging());
    }

    /**
     * The wheel over an overlay is the overlay's.
     *
     * <p>The main preview zooms its own camera on the wheel, so a canvas that also zoomed would move two
     * things at once for one gesture.</p>
     */
    @Test
    public void theWheelOverAnOverlayDoesNotZoomTheCanvas() {
        float before = graph.getZoom();
        scroll(physicalCentreOf(overlay), 1f);
        assertEquals("the canvas zoomed under a panel that owns its own wheel",
                before, graph.getZoom(), 0.0001f);
    }

    /**
     * <b>A press on a leading resize edge must not move the panel.</b>
     *
     * <p>The Main Preview is anchored by {@code right}/{@code bottom}, so its {@code left} inset is
     * {@code auto}. {@code UIResizer} reads {@code resizeOriginLeft()} as the origin a leading edge
     * measures from, and that answered <b>0</b> for an {@code auto} inset — so pressing the top or left
     * edge wrote {@code left: 0; top: 0} and threw the panel into the canvas's corner, on the press,
     * before the pointer had moved.</p>
     *
     * <p>Reported as "clicking the main preview sometimes teleports it to the top left", and the
     * "sometimes" is the tell: only the three leading handles do it, and they are a few pixels wide.</p>
     *
     * <p>Asserted with a <b>zero-delta</b> press rather than a real drag, because that is the shape of the
     * bug: nothing about the resize arithmetic is wrong, the origin it starts from is.</p>
     */
    @Test
    public void aPressOnALeadingResizeEdgeLeavesTheOverlayWhereItIs() {
        var panel = new com.crystalgui.graph.shader.MainPreviewPanel(
                new com.crystalgui.graph.GraphDocument(),
                com.crystalgraphics.shadergraph.CgShaderNodeRegistry.builtins(),
                new com.crystalgraphics.shadergraph.CgMasterNode());
        graph.addOverlay(panel);
        frame();

        float beforeX = panel.getRuntimeCache().getX();
        float beforeY = panel.getRuntimeCache().getY();
        assertTrue("the panel must start away from the corner or this asserts nothing; x=" + beforeX,
                beforeX > graph.getRuntimeCache().getX() + 10f);

        UIElement topLeft = panel.getChildren().stream()
                .filter(c -> c.hasClass("__resizer-top-left__"))
                .findFirst().orElseThrow(() -> new AssertionError("no leading handle on a resizable panel"));
        press(physicalCentreOf(topLeft));
        frame();

        assertEquals("a press with no movement moved the panel horizontally",
                beforeX, panel.getRuntimeCache().getX(), 0.5f);
        assertEquals("a press with no movement moved the panel vertically",
                beforeY, panel.getRuntimeCache().getY(), 0.5f);
    }

    /**
     * An overlay cannot be dragged out of the canvas.
     *
     * <p>Worse than untidy: the viewport is {@code overflow: hidden}, so a panel dragged past the edge
     * does not end up somewhere awkward — it is simply <b>gone</b>, with no edge left to grab it back by.
     * {@code UIResizer} clamps a resize against {@code resizeContainingBlock()} for the same reason, and a
     * move is the half that was missing.</p>
     */
    @Test
    public void anOverlayCannotBeDraggedOutOfTheCanvas() {
        var panel = new com.crystalgui.graph.shader.MainPreviewPanel(
                new com.crystalgui.graph.GraphDocument(),
                com.crystalgraphics.shadergraph.CgShaderNodeRegistry.builtins(),
                new com.crystalgraphics.shadergraph.CgMasterNode());
        graph.addOverlay(panel);
        frame();

        // Grab the header and haul it far past the bottom-right corner.
        UIElement header = panel.getChildren().stream()
                .filter(c -> c.hasClass(com.crystalgui.graph.shader.MainPreviewPanel.HEAD_CLASS))
                .findFirst().orElseThrow();
        Vector2f grab = physicalCentreOf(header);
        press(grab);
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(grab.x() + 4000), Math.round(grab.y() + 4000), 0, 0, -1, false, 0f, -1L));
        frame();

        float right = panel.getRuntimeCache().getX() + panel.getRuntimeCache().getWidth();
        float bottom = panel.getRuntimeCache().getY() + panel.getRuntimeCache().getHeight();
        float canvasRight = graph.getRuntimeCache().getX() + graph.getRuntimeCache().getWidth();
        float canvasBottom = graph.getRuntimeCache().getY() + graph.getRuntimeCache().getHeight();

        assertTrue("dragged out past the right edge: " + right + " > " + canvasRight,
                right <= canvasRight + 0.5f);
        assertTrue("dragged out past the bottom edge: " + bottom + " > " + canvasBottom,
                bottom <= canvasBottom + 0.5f);
    }

    /**
     * <b>An orbit ends when the button that started it is released — whichever button that was.</b>
     *
     * <p>{@code startDrag} without an explicit button assumes the left one, so a middle-drag was never
     * told its button came up. The implicit capture release still fires, which leaves a live drag with no
     * button held: the preview kept rotating with every mouse move until another click happened to end
     * it, i.e. a free-rotate mode nobody asked for.</p>
     *
     * <p>This engine already records the identical failure for {@code CanvasView}'s middle-button pan —
     * "a drag with no button held keeps eating every mouse move, and the canvas slides around on its
     * own". Same mistake, one widget later, which is why it is worth a test rather than a comment.</p>
     */
    @Test
    public void aMiddleButtonOrbitEndsOnTheMiddleRelease() {
        var panel = new com.crystalgui.graph.shader.MainPreviewPanel(
                new com.crystalgui.graph.GraphDocument(),
                com.crystalgraphics.shadergraph.CgShaderNodeRegistry.builtins(),
                new com.crystalgraphics.shadergraph.CgMasterNode());
        graph.addOverlay(panel);
        frame();

        UIElement surface = panel.getChildren().stream()
                .filter(c -> c.hasClass(com.crystalgui.graph.shader.MainPreviewPanel.SURFACE_CLASS))
                .findFirst().orElseThrow();
        Vector2f at = physicalCentreOf(surface);

        final int middle = com.crystalgraphics.platform.input.CgMouseCodes.MIDDLE_BUTTON;
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, middle, true, 0f, 1L));
        frame();
        assertTrue("a middle press on the preview should start an orbit",
                window.getInputHandler().getDragController().isDragging());

        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, middle, false, 0f, 2L));
        frame();
        assertFalse("releasing the SAME button must end it — otherwise the preview free-rotates",
                window.getInputHandler().getDragController().isDragging());
    }

    @Test
    public void theWheelOverBareCanvasStillZooms() {
        float before = graph.getZoom();
        var cache = graph.getRuntimeCache();
        scroll(Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() - 20f, cache.getY() + cache.getHeight() - 20f), 1f);
        assertNotEquals(before, graph.getZoom(), 0.0001f);
    }
}
