package com.crystalgui.widget.graph;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.WorldRect;
import org.joml.Vector2f;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.widget.canvas.CanvasView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M6.4's canvas and graph on the new engine.
 *
 * <p>Six tests, every one of them an invariant row this batch owns — and every one a rule the old
 * engine learned by getting it wrong, so each is worth re-asserting against machinery that has
 * changed underneath it. Two in particular could only be got wrong again here: {@code toLocal}'s
 * origin moved at M6.1, and a pan drag is the one gesture in the engine whose SOURCE travels.</p>
 */
public class GraphBatchPortTest extends UiDocumentTestBase {

    private static final PortType FLOAT = new BasicPortType("float", 1);

    private CanvasView canvas() {
        CanvasView view = new CanvasView();
        layout(view, l -> l.width(600f).height(400f));
        document.append(view);
        frame();
        frame();
        return view;
    }

    private GraphView graph() {
        GraphView view = new GraphView();
        layout(view, l -> l.width(600f).height(400f));
        document.append(view);
        frame();
        frame();
        return view;
    }

    /**
     * <b>A pan drag's source is the VIEWPORT, never the transformed plane.</b>
     *
     * <p>Every drag coordinate is converted through the source's own transform, so panning the plane
     * you are dragging FROM moves the frame the delta is measured in — the view accelerates away from
     * the cursor instead of following it. The old engine records this; the new one can reach it by a
     * second route, because {@code toLocal} now puts the box's own origin at zero, which is what made
     * a dialog rubber-band against the pointer at 6.2.</p>
     *
     * <p><b>Five steps, not one.</b> One step passes against both bugs: the first frame's delta is
     * correct either way and the error compounds only once the source has moved.</p>
     */
    @Test
    public void aPanDragTracksThePointerOneForOne() {
        withDefaultStyles();
        CanvasView view = canvas();
        Box box = boxOf(view);
        assertNotNull("the canvas has no box", box);
        float px = box.worldX() + 300f, py = box.worldY() + 200f;

        press(px, py, CgMouseCodes.MIDDLE_BUTTON);
        frame();
        float startPanX = view.getPanX(), startPanY = view.getPanY();

        for (int step = 1; step <= 5; step++) {
            move(px + step * 20f, py);
            frame();
            float panned = view.getPanX() - startPanX;
            assertEquals("after " + (step * 20) + "px of pointer travel the view panned " + panned,
                    step * 20f, panned, 0.5f);
        }
        release(px + 100f, py, CgMouseCodes.MIDDLE_BUTTON);
        frame();
        assertEquals("a horizontal pan moved the view vertically",
                startPanY, view.getPanY(), 0.5f);
    }

    /**
     * <b>A POSITIVE scroll notch means the wheel rolled DOWN, so it zooms OUT.</b>
     *
     * <p>{@code ScrollerView} is the engine's one statement of the sign — it does
     * {@code setScrollTop(before + delta)} — and the old {@code CanvasView} shipped zooming the wrong
     * way because a test written from the implementation agrees with the implementation. Asserted
     * against a direction the engine already fixes rather than against a remembered convention.</p>
     */
    @Test
    public void aPositiveWheelNotchZoomsOut() {
        withDefaultStyles();
        CanvasView view = canvas();
        Box box = boxOf(view);
        assertNotNull(box);
        move(box.worldX() + 300f, box.worldY() + 200f);
        frame();
        float before = view.getZoom();

        wheel(1f);
        frame();
        assertTrue("a positive notch is the wheel rolling DOWN and must zoom OUT -- "
                        + before + " -> " + view.getZoom(),
                view.getZoom() < before);

        wheel(-1f);
        wheel(-1f);
        frame();
        assertTrue("and a negative notch has to come back the other way",
                view.getZoom() > before);
    }

    /**
     * <b>The plane's {@code transform-origin} is pinned to {@code 0 0}.</b>
     *
     * <p>It defaults to 50%, so every world↔screen conversion would be off by half a viewport times
     * the zoom — and it would look plausible, because the picture stays internally consistent. The
     * check is the round trip at a zoom other than 1: a wrong origin cancels out at 1.0 and at the
     * exact centre, which is where a fixture written without thinking about it would land.</p>
     */
    @Test
    public void theWorldRoundTripSurvivesZoom() {
        withDefaultStyles();
        CanvasView view = canvas();
        view.setZoom(2.5f).setPan(37f, -19f);
        frame();
        frame();

        Box box = boxOf(view);
        assertNotNull(box);
        // A CORNER, not the centre: at 50% origin the centre is a fixed point and every error there
        // is exactly zero.
        float rawX = box.worldX() + 40f, rawY = box.worldY() + 25f;
        var world = view.screenToWorld(rawX, rawY);
        var back = view.worldToViewport(world.x, world.y);

        assertEquals("x did not survive the round trip at zoom 2.5", 40f, back.x, 0.5f);
        assertEquals("y did not survive the round trip at zoom 2.5", 25f, back.y, 0.5f);
    }

    /**
     * <b>An input port takes ONE edge, an output MANY — so connecting to an occupied input replaces.</b>
     *
     * <p>Refusing it looks like correct validation and makes rewiring take two gestures. The displaced
     * edge must leave through the same {@code disconnect} a manual one does, or undo will not know it
     * happened — which is why this asserts on the CONNECTION COUNT of the displaced source as well as
     * on the input: an implementation that dropped the edge without disconnecting satisfies the
     * input's count perfectly.</p>
     */
    @Test
    public void connectingToAnOccupiedInputReplaces() {
        withDefaultStyles();
        GraphView view = graph();
        GraphNode a = new GraphNode("A");
        GraphNode b = new GraphNode("B");
        GraphNode c = new GraphNode("C");
        view.addNode(a, 0f, 0f);
        view.addNode(b, 200f, 0f);
        view.addNode(c, 0f, 120f);
        NodePort outA = a.addOutput(FLOAT, "Out");
        NodePort outC = c.addOutput(FLOAT, "Out");
        NodePort in = b.addInput(FLOAT, "In");
        frame();

        view.connect(outA, in);
        frame();
        assertEquals("the first wire did not land", 1, in.getConnectionCount());
        assertEquals(1, outA.getConnectionCount());

        view.connect(outC, in);
        frame();
        assertEquals("an input may hold exactly one edge", 1, in.getConnectionCount());
        assertEquals("the displaced edge has to leave through disconnect, or undo cannot see it",
                0, outA.getConnectionCount());
        assertEquals("and the new one has to be attached", 1, outC.getConnectionCount());
    }

    /**
     * <b>A wire's colour is read back out of the CASCADE.</b>
     *
     * <p>{@code NodePort.typeColor()} returns the dot's computed {@code border-color}, which is how
     * {@code graph.css} keeps Unity's per-type palette in CSS instead of putting GLSL's type system
     * and its colours in Java. It is also the first thing in this batch to read a computed style back,
     * so it is the first that can silently answer the initial value instead.</p>
     *
     * <p>Asserted through a stylesheet rule rather than a default, with a control colour that no
     * initial value could be: the failure mode is "it answered white", and a fixture whose expected
     * colour IS white passes against it.</p>
     */
    @Test
    public void aPortsColourComesFromTheSheet() {
        // #RRGGBBAA IN A SHEET, ALPHA LAST -- and the engine's int is 0xAARRGGBB, which is the
        // documented trap this fixture walked straight into on its first run: written as #FF3C8CFF
        // (the int order) it parses as RGB(255,60,140) at full alpha and answers 0xFFFF3C8C, a
        // different colour that is still a perfectly plausible one.
        document.styles().addStylesheet(StyleSheet.parse(
                "nodeport.type-float .__dot__ { border-color: #3C8CFFFF; }"));
        GraphView view = graph();
        GraphNode node = new GraphNode("A");
        view.addNode(node, 0f, 0f);
        NodePort port = node.addInput(FLOAT, "In");
        frame();
        frame();

        assertEquals("the wire colour has to be read off the dot's computed border-color",
                0xFF3C8CFF, port.typeColor());
    }

    /**
     * Every widget in the batch lays out with content in it.
     *
     * <p>The smoke test each of the three batches before this one turned out to need, for the same
     * reason: a widget that measures {@code Nx0} is the failure this engine produces, and it looks
     * correct in every other observable.</p>
     */
    @Test
    public void every64WidgetLaysOutWithItsContent() {
        withDefaultStyles();
        CanvasView canvas = new CanvasView();
        GraphView graph = new GraphView();
        List<UIElement> widgets = List.of(canvas, graph);
        for (UIElement widget : widgets) {
            layout(widget, l -> l.width(400f).height(240f));
            document.append(widget);
        }
        GraphNode node = new GraphNode("Node");
        graph.addNode(node, 20f, 20f);
        node.addInput(FLOAT, "In");
        node.addOutput(FLOAT, "Out");
        StyleGroup.inlinePipeline(canvas.content().getStyle().getLayoutGroup(),
                l -> l.width(100f).height(100f));
        frame();
        frame();

        List<String> offenders = new ArrayList<>();
        for (UIElement widget : List.of(canvas, graph, node)) {
            Box box = boxOf(widget);
            if (box == null) offenders.add(widget.getClass().getSimpleName() + ": no box");
            else if (!(box.width() > 0f) || !(box.height() > 0f)) {
                offenders.add(widget.getClass().getSimpleName()
                        + ": measured " + box.width() + "x" + box.height());
            }
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    /**
     * <b>A graph is legible on the user-agent sheet alone, and a theme still wins.</b>
     *
     * <p>It was not. Counted across the whole UA sheet, {@code dialog} had 12 appearance declarations,
     * {@code textfield} 7, {@code button} and {@code menu} 6 each — and {@code graphnode},
     * {@code nodeport} and {@code graphview} had <b>zero between them</b>. So every other self-drawing
     * widget was visible with no theme loaded and the graph was not: laid out perfectly and painting
     * nothing, which reads as a broken widget rather than an unthemed one.</p>
     *
     * <p>Both halves matter and only the pair is worth asserting. A fallback that a theme could not
     * override would put the per-type palette in the wrong sheet; a fallback that never applies is the
     * bug it was written for, still there.</p>
     */
    @Test
    public void aGraphIsLegibleWithNoThemeAndAThemeStillWins() {
        withDefaultStyles();
        GraphView view = graph();
        GraphNode node = new GraphNode("A");
        view.addNode(node, 0f, 0f);
        NodePort port = node.addInput(FLOAT, "In");
        frame();
        frame();

        int bare = port.typeColor();
        assertTrue("a port dot has no colour at all without a theme -- the wire it feeds would be "
                        + "invisible too, since NodePort.typeColor() reads exactly this",
                (bare >>> 24) > 0);
        assertTrue("the node has no edge without a theme",
                (node.getStyle().getGeneralGroup().borderColor() >>> 24) > 0);

        // AND A THEME STILL WINS -- by REDECLARING THE PROPERTY, which is the only mechanism that
        // works across sheets. `var()` substitutes at parse time, so a token set in a later sheet
        // cannot reach an earlier sheet's declaration; the first version of this test asserted
        // exactly that and failed, which is the useful half of writing it. `crystalgui:graph` states
        // `nodeport.type-float .__dot__ { border-color: ... }` itself, at higher specificity and
        // later in order, so the per-type palette lands where the user-agent grey was.
        document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        frame();
        frame();

        int themed = port.typeColor();
        assertTrue("the theme did not reach the port dot: still " + Integer.toHexString(themed),
                themed != bare);
        assertEquals("a float port takes the theme's vec1 colour", 0xFF84E4E7, themed);
    }

    /**
     * <b>A wire's two endpoints are the two dots it connects, in the wire layer's own space.</b>
     *
     * <p>Reported as a graph with three connections in its model and no wires on screen — except one
     * short horizontal segment near the top-left of the plane, which was every wire in the graph drawn
     * on top of each other.</p>
     *
     * <p>{@code Box.x()} is the offset from the HOST's border-box origin, where the old engine's
     * {@code getRuntimeCache().getX()} accumulated through every ancestor and was absolute. So
     * {@code dotCenter()} returned a dot's few-pixel offset inside its own port row rather than its
     * position on the plane: every endpoint collapsed to nearly the same point, and the wires were
     * drawn perfectly, in the wrong place, with the model entirely correct. It is the same class of
     * error as {@code toLocal}'s moved origin at M6.1 — silent, and wrong by an amount that depends on
     * how deep in the tree the two boxes are.</p>
     *
     * <p><b>Asserted on the SEPARATION rather than on absolute positions</b>, which is the thing that
     * was broken and the thing a reader can check: two nodes 240px apart have dots about 240px apart,
     * whatever the plane's own origin happens to be.</p>
     */
    @Test
    public void aWireRunsBetweenTheTwoDotsItConnects() {
        withDefaultStyles();
        GraphView view = graph();
        GraphNode left = new GraphNode("Left");
        GraphNode right = new GraphNode("Right");
        view.addNode(left, 0f, 0f);
        view.addNode(right, 240f, 0f);
        NodePort out = left.addOutput(FLOAT, "Out");
        NodePort in = right.addInput(FLOAT, "In");
        frame();
        frame();
        view.connect(out, in);
        frame();
        frame();

        NodeWireLayer wires = view.wireLayer();
        Vector2f a = out.dotCenterIn(wires);
        Vector2f b = in.dotCenterIn(wires);

        assertTrue("the two endpoints collapsed onto each other: " + a + " and " + b
                        + " -- every wire in the graph would be one short segment",
                Math.abs(b.x() - a.x()) > 150f);
        assertTrue("the endpoints are not on the same row: " + a + " and " + b,
                Math.abs(b.y() - a.y()) < 40f);

        // AND THEY ARE THE DOTS' REAL POSITIONS, not merely far apart: the output dot is on the LEFT
        // node, so it has to sit left of the input's. A sign error would satisfy the separation above.
        assertTrue("the output dot is not left of the input dot: " + a + " then " + b, a.x() < b.x());
    }

    /**
     * <b>The visible world rect does not depend on where the canvas sits in the page.</b>
     *
     * <p>It did. {@code visibleWorldRect} read {@code box().x()} — this canvas's offset inside ITS
     * OWN parent — and subtracted the plane's origin from it, which cancelled on the old engine
     * because that accessor was absolute and does not cancel here. So a canvas half way down a
     * scrolling page reported a viewport hundreds of world units from the one it shows, and that rect
     * is what culls both the nodes and the WIRES: things plainly on screen were drawn and then thrown
     * away.</p>
     *
     * <p>Asserted by putting the same canvas at two different offsets and demanding the same answer,
     * which is the property that was broken. A fixture with one canvas at the top of an empty document
     * passes against the bug, because there the offset is zero.</p>
     */
    @Test
    public void theVisibleRectIsIndependentOfWhereTheCanvasSits() {
        withDefaultStyles();
        CanvasView top = new CanvasView();
        layout(top, l -> l.width(400f).height(200f));
        // A TALL SPACER between them, so the second canvas is a long way down its parent.
        UIElement spacer = new UIElement();
        layout(spacer, l -> l.width(400f).height(500f));
        CanvasView low = new CanvasView();
        layout(low, l -> l.width(400f).height(200f));
        document.append(top);
        document.append(spacer);
        document.append(low);
        frame();
        frame();

        assertTrue("the fixture did not actually separate them",
                boxOf(low).worldY() - boxOf(top).worldY() > 400f);

        WorldRect a = top.visibleWorldRect();
        WorldRect b = low.visibleWorldRect();
        assertEquals("two identical canvases report different world origins purely because one is "
                        + "further down the page: " + a.x() + " vs " + b.x(), a.x(), b.x(), 0.01f);
        assertEquals("...and in y: " + a.y() + " vs " + b.y(), a.y(), b.y(), 0.01f);

        // AND THE ANSWER IS THE PAN, which is what makes it a viewport rather than a constant.
        low.setPan(60f, -30f);
        frame();
        frame();
        assertEquals("panning right must move the visible world LEFT",
                -60f, low.visibleWorldRect().x(), 0.5f);
        assertEquals(30f, low.visibleWorldRect().y(), 0.5f);
    }

    /**
     * <b>The live wire's pointer end follows the pointer.</b>
     *
     * <p>Reported as a wire being dragged from a port to another port sitting a whole node's width
     * away from the cursor. The wire's START was correct — it had just been fixed — and its END was
     * the raw coordinate a {@code Drag} callback reports, which is relative to the drag's SOURCE and,
     * since M6.1, to that source's own origin. The old engine's {@code screenToLocal} did not subtract
     * the element's own position, so a listener on a port received what was near enough an absolute
     * layout coordinate to use as a plane one — which is what both this and the create-node menu did,
     * and what {@code NodeWireLayer.updatePending}'s javadoc still claimed.</p>
     *
     * <p>Asserted against the port the pointer is actually OVER, which is what the user sees and what
     * a drop would connect to. Asserting the raw number would test the arithmetic against itself.</p>
     */
    @Test
    public void theLiveWireEndsUnderThePointer() {
        withDefaultStyles();
        GraphView view = graph();
        GraphNode left = new GraphNode("Left");
        GraphNode right = new GraphNode("Right");
        view.addNode(left, 20f, 20f);
        view.addNode(right, 260f, 120f);
        NodePort out = left.addOutput(FLOAT, "Out");
        NodePort target = right.addInput(FLOAT, "In");
        frame();
        frame();

        Box from = boxOf(out);
        Box onto = boxOf(target);
        assertNotNull("the fixture has no ports to drag between", from);
        assertNotNull(onto);

        press(from.worldX() + from.width() / 2f, from.worldY() + from.height() / 2f);
        frame();
        // ONTO THE OTHER PORT, which is where the pointer visibly is.
        float px = onto.worldX() + onto.width() / 2f;
        float py = onto.worldY() + onto.height() / 2f;
        move(px, py);
        frame();

        Vector2f live = view.wireLayer().pendingEnd();
        // The POINTER's own position in the plane, which is what the wire has to reach -- not the
        // port's centre, since the press landed at the port's centre and the pointer is there.
        Vector2f expected = Box.originIn(onto, view.content().box())
                .add(onto.width() / 2f, onto.height() / 2f);
        assertEquals("the live wire ends " + live + " while the pointer is at " + expected,
                expected.x(), live.x(), 6f);
        assertEquals(expected.y(), live.y(), 6f);
        release(px, py);
    }

    /**
     * <b>A marquee drawn over a node selects it, wherever the view sits in the page.</b>
     *
     * <p>The third consumer of the same mistake, and the one whose symptom is a selection rather than
     * a mis-drawn line: the band's {@code left}/{@code top} subtracted this view's own offset inside
     * ITS parent from a coordinate that is already relative to this view. So a graph half way down a
     * page drew its band hundreds of pixels from the pointer and selected whatever happened to be
     * there.</p>
     *
     * <p>Driven with a SPACER above the view, because a graph at the top of an empty document has a
     * zero offset and passes against the bug — the same shape the cull-rect test needs.</p>
     */
    @Test
    public void aMarqueeSelectsWhatItIsDrawnOver() {
        withDefaultStyles();
        UIElement spacer = new UIElement();
        layout(spacer, l -> l.width(600f).height(220f));
        document.append(spacer);
        GraphView view = new GraphView();
        layout(view, l -> l.width(600f).height(400f));
        document.append(view);
        frame();
        frame();

        GraphNode node = new GraphNode("Target");
        view.addNode(node, 60f, 60f);
        frame();
        frame();

        Box target = boxOf(node);
        assertNotNull("the node has no box", target);
        assertTrue("the fixture did not push the view down the page", boxOf(view).worldY() > 200f);

        // A BAND FROM ABOVE-LEFT OF THE NODE TO BELOW-RIGHT OF IT, in surface pixels, over empty plane.
        press(target.worldX() - 24f, target.worldY() - 24f);
        frame();
        move(target.worldX() + target.width() + 24f, target.worldY() + target.height() + 24f);
        frame();

        assertTrue("a marquee drawn over the node did not select it",
                view.getSelection().nodes().contains(node));

        // AND THE BAND IS DRAWN WHERE THE POINTER IS. Selection alone cannot see this: it is computed
        // from the raw drag coordinates, while the band's left/top are written separately -- so the
        // band can sit hundreds of pixels away while the right nodes light up, which is precisely the
        // failure and precisely what a selection assertion passes against.
        Box band = boxOf(view.marqueeElement());
        assertNotNull("the marquee band has no box", band);
        assertEquals("the band's left edge is not at the pointer's press",
                target.worldX() - 24f, band.worldX(), 4f);
        assertEquals("the band's top edge is not at the pointer's press",
                target.worldY() - 24f, band.worldY(), 4f);
        release(target.worldX() + target.width() + 24f, target.worldY() + target.height() + 24f);
    }
}
