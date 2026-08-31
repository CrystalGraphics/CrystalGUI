package com.crystalgui.widget.graph;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UINode;
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
        List<UINode> widgets = List.of(canvas, graph);
        for (UINode widget : widgets) {
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
        for (UINode widget : List.of(canvas, graph, node)) {
            Box box = boxOf(widget);
            if (box == null) offenders.add(widget.getClass().getSimpleName() + ": no box");
            else if (!(box.width() > 0f) || !(box.height() > 0f)) {
                offenders.add(widget.getClass().getSimpleName()
                        + ": measured " + box.width() + "x" + box.height());
            }
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }
}
