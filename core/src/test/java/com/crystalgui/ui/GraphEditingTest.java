package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.graph.port.PortType;
import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.ui.elements.graph.GraphCommands;
import com.crystalgui.ui.input.keymap.KeyStroke;

/**
 * P6.2.4 — selection, marquee, move-many and delete.
 *
 * <h3>What is actually being asserted</h3>
 * <p>Each of these is a <b>convention</b> rather than a derivable answer, which is why the plan for this
 * item is mostly research. So the tests pin the conventions: that a marquee selects what it
 * <em>touches</em>, that Shift adds and Alt subtracts, that pressing an already-selected node does not
 * collapse the selection out from under a drag, that forty moved nodes are one undo step, and that
 * selection itself never enters the history.</p>
 *
 * <p>The first of those is the one that reads as broken fastest if it is wrong, and the third is the one
 * a naive implementation always gets wrong.</p>
 */
public class GraphEditingTest extends UiTestBase {

    private static final PortType VEC3 = new BasicPortType("vec3", 3);

    private UIWindow window;
    private GraphView graph;
    private int modifiers;

    @Before
    public void setUp() {
        modifiers = 0;
        TestPlatformService.get().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return modifiers; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return ""; }
            @Override public void setClipboard(String text) { }
        });

        graph = new GraphView();
        graph.layout(l -> l.width(360).height(300));

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(graph);

        window = new UIWindow(Ui.of(root));
        window.setUiScale(2f);
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 800);
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private GraphNode node(String title, float x, float y) {
        GraphNode n = new GraphNode(title);
        n.addOutput(VEC3, "Out");
        graph.addNode(n, x, y);
        frame();
        return n;
    }

    /** A world point as physical pointer coordinates, through the engine's own matrix. */
    private Vector2f physicalOfWorld(float worldX, float worldY) {
        Vector2f local = graph.worldToViewport(worldX, worldY);
        return Transform2D.apply(graph.getRuntimeCache().localToWorld.get(), local.x(), local.y());
    }

    private Vector2f physicalCenterOf(UIElement element) {
        var cache = element.getRuntimeCache();
        return Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
    }

    private void press(Vector2f p) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private void moveTo(Vector2f p) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, -1, false, 0f, -1L));
    }

    private void release(Vector2f p) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
    }

    /** Drags a rubber band between two WORLD points. */
    private void marquee(float x0, float y0, float x1, float y1) {
        press(physicalOfWorld(x0, y0));
        frame();
        moveTo(physicalOfWorld((x0 + x1) / 2f, (y0 + y1) / 2f));
        frame();
        moveTo(physicalOfWorld(x1, y1));
        frame();
        release(physicalOfWorld(x1, y1));
        frame();
    }

    // ── The marquee ─────────────────────────────────────────────────────────

    /**
     * <b>A node is selected by being touched, not by being enclosed.</b>
     *
     * <p>The decision the plan records: no vendor documents which rule they use, and enclose-only makes a
     * node bigger than the viewport unselectable at any zoom a user would be working at.</p>
     */
    @Test
    public void theMarqueeSelectsWhatItTouches() {
        GraphNode a = node("A", 20f, 20f);   // 168 wide by default, so 20..188
        GraphNode far = node("Far", 400f, 400f);
        frame();

        // A band that clips A's top-left corner only, and comes nowhere near Far.
        marquee(10f, 10f, 40f, 40f);

        assertTrue("touched is selected", graph.getSelection().contains(a));
        assertFalse(graph.getSelection().contains(far));
        assertEquals(1, graph.selectedNodes().size());
    }

    @Test
    public void aPlainMarqueeReplacesTheSelection() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 220f);
        graph.getSelection().selectOnly(b);
        frame();

        marquee(10f, 10f, 40f, 40f);

        assertTrue(graph.getSelection().contains(a));
        assertFalse("a plain band replaces rather than adds", graph.getSelection().contains(b));
    }

    /** Shift adds, Alt subtracts — the convention Blender, Unreal and Figma all share. */
    @Test
    public void shiftAddsAndAltSubtracts() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 220f);
        graph.getSelection().selectOnly(b);
        frame();

        modifiers = CgModifiers.SHIFT;
        marquee(10f, 10f, 40f, 40f);
        assertTrue("shift keeps what was there", graph.getSelection().contains(b));
        assertTrue("and adds what the band touched", graph.getSelection().contains(a));

        modifiers = CgModifiers.ALT;
        marquee(10f, 10f, 40f, 40f);
        assertFalse("alt takes it back out", graph.getSelection().contains(a));
        assertTrue(graph.getSelection().contains(b));
    }

    /** A band drawn over nothing clears the selection — clicking empty space is how everyone deselects. */
    @Test
    public void pressingEmptyCanvasClearsTheSelection() {
        GraphNode a = node("A", 20f, 20f);
        graph.getSelection().selectOnly(a);
        frame();

        press(physicalOfWorld(300f, 260f));
        frame();
        release(physicalOfWorld(300f, 260f));
        frame();

        assertTrue(graph.getSelection().isEmpty());
    }

    // ── The press rule ──────────────────────────────────────────────────────

    /**
     * <b>Pressing one of several selected nodes must not collapse the selection.</b>
     *
     * <p>The gesture is "click one of the five I selected and drag them all", and the naive rule — a
     * press selects only what it hit — breaks it silently: four nodes deselect and the drag moves one.
     * This is the single most common thing to get wrong in a graph editor.</p>
     */
    @Test
    public void pressingAnAlreadySelectedNodeKeepsTheSelection() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 220f);
        graph.getSelection().replaceWith(java.util.List.of(a, b));
        frame();

        press(physicalCenterOf(a.titleBar()));
        frame();

        assertEquals("both must still be selected", 2, graph.selectedNodes().size());
        release(physicalCenterOf(a.titleBar()));
        frame();
    }

    @Test
    public void pressingAnUnselectedNodeReplacesTheSelection() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 220f);
        graph.getSelection().selectOnly(a);
        frame();

        press(physicalCenterOf(b.titleBar()));
        frame();

        assertEquals(1, graph.selectedNodes().size());
        assertTrue(graph.getSelection().contains(b));
    }

    /**
     * <b>A node you touch comes to the front and stays there.</b>
     *
     * <p>The first version keyed stacking off {@code :checked}, which raised a node while it was selected
     * and dropped it back the instant something else was — so a node deliberately brought forward sank
     * behind its neighbour again as soon as you clicked away. Stacking is interaction history, not
     * selection state, which is why the second half of this test matters more than the first.</p>
     *
     * <p>Asserted through the hit-tester, because "what is on top is what you can click" is the property
     * that sharing one sort order between paint and hit-testing actually buys.</p>
     */
    @Test
    public void aTouchedNodeRisesAndStaysRisen() {
        GraphNode under = node("Under", 40f, 40f);
        GraphNode over = node("Over", 60f, 60f);   // added later, so it starts on top
        GraphNode elsewhere = node("Elsewhere", 260f, 40f);
        frame();

        // Derived from `over`'s own (later, on-top) bounds rather than a hardcoded point: `graphnode`'s
        // width is content-fit (its floor dropped from a fixed 96px to 40px once no-preview nodes were
        // made to shrink to their real size — see the CSS), so a literal pixel guess for "well inside
        // both boxes" goes stale the moment either node's measured width changes. 5px in from `over`'s
        // own top-left corner is guaranteed inside `over`, and — since `over` sits only 20px down-right
        // of `under`'s own origin, well inside `under`'s floor in both axes — inside `under` too.
        var overBounds = graph.worldBoundsOf(over);
        Vector2f overlap = physicalOfWorld(overBounds.x() + 5f, overBounds.y() + 5f);
        assertTrue("the later node starts on top",
                isInside(window.getHoveredElement(overlap.x(), overlap.y()), over));

        press(physicalCenterOf(under.titleBar()));
        frame();
        release(physicalCenterOf(under.titleBar()));
        frame();
        assertTrue("pressing the buried node brings it forward",
                isInside(window.getHoveredElement(overlap.x(), overlap.y()), under));

        // Now click something else entirely: the raised node must NOT sink back.
        press(physicalCenterOf(elsewhere.titleBar()));
        frame();
        release(physicalCenterOf(elsewhere.titleBar()));
        frame();

        assertFalse("it is no longer selected", under.isSelected());
        assertTrue("but it must still be in front — raising is history, not a highlight",
                isInside(window.getHoveredElement(overlap.x(), overlap.y()), under));
    }

    private static boolean isInside(UIElement hit, GraphNode node) {
        for (UIElement e = hit; e != null; e = e.getParent()) {
            if (e == node) return true;
        }
        return false;
    }

    // ── Move-many ───────────────────────────────────────────────────────────

    /**
     * <b>Dragging one selected node moves the whole selection, as one undo step.</b>
     *
     * <p>Each node moves by the same delta from its own origin rather than tracking the pointer, so the
     * selection keeps its shape — and forty nodes is one Ctrl+Z, which is the case transactions exist
     * for.</p>
     */
    @Test
    public void draggingASelectedNodeMovesEveryOneOfThemAsOneStep() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 220f);
        graph.getSelection().replaceWith(java.util.List.of(a, b));
        frame();

        Vector2f grab = physicalCenterOf(a.titleBar());
        press(grab);
        frame();
        moveTo(new Vector2f(grab.x() + 60f, grab.y()));   // 60 physical = 30 logical = 30 world at 1x
        frame();
        release(new Vector2f(grab.x() + 60f, grab.y()));
        frame();

        assertEquals(50f, graph.worldBoundsOf(a).x(), 0.6f);
        assertEquals("the whole selection keeps its shape", 50f, graph.worldBoundsOf(b).x(), 0.6f);
        assertEquals("one drag, one undo step", 1, graph.undoStack().undoDepth());

        graph.undoStack().undo();
        frame();
        assertEquals(20f, graph.worldBoundsOf(a).x(), 0.6f);
        assertEquals(20f, graph.worldBoundsOf(b).x(), 0.6f);
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    /**
     * <b>Delete takes the nodes and their wires in one step, and undo brings back both.</b>
     *
     * <p>The wires are the part worth asserting: a node restored without them is a graph the user never
     * had, and it is the case that only works because the transaction unwinds in reverse — the node
     * comes back before the wires that attach to it.</p>
     */
    @Test
    public void deletingASelectionTakesItsWiresAndUndoRestoresBoth() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.getOutputPorts().get(0);
        NodePort in = b.addInput(VEC3, "A");
        frame();
        graph.connect(out, in);
        assertEquals(1, graph.getConnections().size());

        graph.getSelection().selectOnly(b);
        int removed = graph.deleteSelection();
        frame();

        assertEquals(1, removed);
        assertTrue("the wire went with it", graph.getConnections().isEmpty());
        assertEquals("and so did the node", 1, graph.nodes().size());
        assertTrue(graph.getSelection().isEmpty());

        graph.undoStack().undo();
        frame();

        assertEquals("the node is back", 2, graph.nodes().size());
        assertEquals("and so is its wire", 1, graph.getConnections().size());
        assertTrue(out.isConnected());
    }

    /**
     * <b>Deleting the node the pointer is over must not crash the next frame's hover diff.</b>
     *
     * <p>It did. The handler kept {@code lastFrameHover} pointing into the detached subtree, so the next
     * frame asked for the common ancestor of two elements in <em>different trees</em> — the walk never
     * converges, both chains run off the end, and it threw an NPE from inside the hover diff, which is
     * nowhere near the delete that caused it. Fixed at both ends: the handler now forgets a detached
     * element (as focus already did), and the traversal returns null instead of walking off a tree.</p>
     *
     * <p>No test caught this because every delete test here deleted a node the pointer had never been
     * over. The gallery found it on the first try.</p>
     */
    @Test
    public void deletingTheNodeUnderThePointerDoesNotBreakTheHoverDiff() {
        GraphNode a = node("A", 20f, 20f);
        frame();

        // Put the pointer on it and let a frame establish the hover.
        moveTo(physicalCenterOf(a.titleBar()));
        frame();

        graph.getSelection().selectOnly(a);
        graph.deleteSelection();

        // The frame after the delete is where it used to throw.
        frame();
        moveTo(physicalOfWorld(300f, 260f));
        frame();

        assertEquals(0, graph.nodes().size());
    }

    @Test
    public void deletingSeveralNodesIsOneUndoStep() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        GraphNode c = node("C", 20f, 220f);
        graph.getSelection().replaceWith(java.util.List.of(a, b, c));
        frame();

        graph.deleteSelection();
        frame();
        assertEquals(0, graph.nodes().size());
        assertEquals(1, graph.undoStack().undoDepth());

        graph.undoStack().undo();
        frame();
        assertEquals("all three come back together", 3, graph.nodes().size());
    }

    // ── Wires ───────────────────────────────────────────────────────────────

    /** A wire is painted, not laid out, so it is picked analytically rather than hit-tested. */
    @Test
    public void aWireCanBePickedAndDeleted() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 260f, 20f);
        NodePort out = a.getOutputPorts().get(0);
        NodePort in = b.addInput(VEC3, "A");
        frame();
        graph.connect(out, in);
        frame();

        // Halfway between the two dots, where the wire runs.
        Vector2f from = out.dotCenter(), to = in.dotCenter();
        float originX = graph.wireLayer().getRuntimeCache().getX();
        float originY = graph.wireLayer().getRuntimeCache().getY();
        var picked = graph.pickWire((from.x() + to.x()) / 2f - originX, (from.y() + to.y()) / 2f - originY);

        assertNotNull("a click on the wire should find it", picked);
        graph.getSelection().selectOnly(picked);
        assertEquals(1, graph.deleteSelection());
        assertTrue(graph.getConnections().isEmpty());
    }

    @Test
    public void pickingFindsNothingFarFromAnyWire() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 260f, 20f);
        graph.connect(a.getOutputPorts().get(0), b.addInput(VEC3, "A"));
        frame();

        assertNull(graph.pickWire(140f, 400f));
    }

    // ── Selection is not history ────────────────────────────────────────────

    /**
     * <b>Selecting is never an undo step.</b>
     *
     * <p>The majority choice, and a live disagreement: Blender records selection and is criticised for
     * it, Figma has a standing request for it as an option. Ours follows VS Code — selection is view
     * state, and an edit's undo restores the selection that edit applied to.</p>
     */
    @Test
    public void selectingIsNotUndoable() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 220f);
        frame();

        graph.getSelection().selectOnly(a);
        graph.getSelection().add(b);
        graph.selectAll();
        marquee(10f, 10f, 40f, 40f);

        assertFalse("none of that touched the document", graph.undoStack().canUndo());
    }

    @Test
    public void selectAllAndClearDoWhatTheySay() {
        node("A", 20f, 20f);
        node("B", 20f, 220f);
        frame();

        graph.selectAll();
        assertEquals(2, graph.selectedNodes().size());

        graph.clearSelection();
        assertTrue(graph.getSelection().isEmpty());
    }

    /**
     * <b>Every shipped chord actually parses.</b>
     *
     * <p>This exists because it did not, and nothing caught it: {@code GraphCommands} bound
     * {@code "Backspace"}, the key-name table is reflected from {@code CgKeyCodes} where the constant is
     * {@code KEY_BACK}, and {@code Keymap.bind} throws on an unknown name. Every test here drove the API
     * directly rather than installing the commands, so the first thing to find it was the gallery
     * failing to open at all.</p>
     *
     * <p>Installing both command sets is the whole test — {@code bind} throwing is the failure.</p>
     */
    @Test
    public void theShippedKeyBindingsAllParse() {
        // Nothing installs anything: constructing the GraphView registered both sets and bound the
        // graph's own chords on itself. If a spec failed to parse, that constructor threw.
        var commands = window.getCommands();
        assertNotNull(commands.get(GraphCommands.DELETE));
        assertNotNull(commands.get(UndoCommands.UNDO));

        // And the alias resolves to the same key the reflected name does.
        assertEquals(KeyStroke.parse("Back"),
                KeyStroke.parse("Backspace"));
    }

    private void pressKey(int key, int mods) {
        modifiers = mods;
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', key, true, false, System.currentTimeMillis()));
        modifiers = 0;
        frame();
    }

    /**
     * <b>Every bound key does its job when the pointer has been anywhere in the graph.</b>
     *
     * <p>This is the test the harness earned. Delete, Ctrl+A and Escape all did nothing in the gallery
     * while F worked, and the difference was <em>focus</em>: only pressing a node focused the graph, so
     * after clicking a wire or empty canvas the commands resolved no {@link GraphView} from the focused
     * element and disabled themselves. The widget looked perfectly alive and none of its keys worked.</p>
     *
     * <p>Driven through real keyboard events with focus established the way a user establishes it —
     * by pressing the canvas — because that is precisely the path that was broken.</p>
     */
    @Test
    public void theGraphKeysWorkAfterPressingTheCanvas() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 220f);
        frame();

        // Focus the way a user does: press empty canvas. This used to leave focus outside the graph.
        press(physicalOfWorld(320f, 260f));
        frame();
        release(physicalOfWorld(320f, 260f));
        frame();

        pressKey(CgKeyCodes.KEY_A, CgModifiers.CTRL);
        assertEquals("Ctrl+A selects all", 2, graph.selectedNodes().size());

        pressKey(CgKeyCodes.KEY_ESCAPE, 0);
        assertTrue("Escape clears", graph.getSelection().isEmpty());

        graph.getSelection().selectOnly(a);
        pressKey(CgKeyCodes.KEY_DELETE, 0);
        assertEquals("Delete removes the selection", 1, graph.nodes().size());

        pressKey(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("and Ctrl+Z brings it back", 2, graph.nodes().size());
    }

    /**
     * <b>Pressing the canvas focuses it without drawing a focus ring.</b>
     *
     * <p>The graph has to hold focus for any of its commands to resolve, but taking it through
     * {@code requestFocus} rings: that source is {@code PROGRAMMATIC}, which is exactly what
     * {@code :focus-visible} exists to ring. Every click on the canvas therefore outlined the whole
     * viewport. The pointer variant is the same carve-out a click already gets — you know where your
     * pointer is.</p>
     */
    @Test
    public void pressingTheCanvasFocusesItWithoutRinging() {
        node("A", 20f, 20f);
        frame();

        press(physicalOfWorld(320f, 260f));
        frame();

        assertTrue("the graph must hold focus, or none of its keys work", graph.isFocused());
        assertFalse("but a pointer press must not ring it", graph.isFocusVisible());
    }

    /** Selecting a wire and pressing Delete removes it — the exact sequence that did nothing. */
    @Test
    public void deleteRemovesASelectedWireFromTheKeyboard() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 260f, 20f);
        graph.connect(a.getOutputPorts().get(0), b.addInput(VEC3, "A"));
        frame();

        // Press on the wire, as a user would: it selects it AND focuses the graph.
        Vector2f from = a.getOutputPorts().get(0).dotCenter(), to = b.getInputPorts().get(0).dotCenter();
        float ox = graph.wireLayer().getRuntimeCache().getX();
        float oy = graph.wireLayer().getRuntimeCache().getY();
        Vector2f onWire = graph.worldToViewport((from.x() + to.x()) / 2f - ox, (from.y() + to.y()) / 2f - oy);
        Vector2f physical = Transform2D.apply(graph.getRuntimeCache().localToWorld.get(), onWire.x(), onWire.y());

        press(physical);
        frame();
        release(physical);
        frame();

        assertNotNull("the press should have selected the wire", graph.getSelection().wire());
        pressKey(CgKeyCodes.KEY_DELETE, 0);
        assertTrue("and Delete should remove it", graph.getConnections().isEmpty());
    }

    /**
     * <b>Framing never magnifies past 1:1.</b>
     *
     * <p>The literal fit for one small node in a large viewport is an eight-times blow-up that fills the
     * screen with a single box — which is what it did. Framing is for seeing something in context, not
     * for inspecting its pixels.</p>
     */
    @Test
    public void framingDoesNotMagnifyASingleNode() {
        GraphNode a = node("A", 20f, 20f);
        node("B", 600f, 400f);
        frame();

        graph.getSelection().selectOnly(a);
        graph.frameSelection(10f);
        frame();

        assertTrue("a single node must not be blown up", graph.getZoom() <= 1f);
        assertTrue("but it must be in view",
                graph.visibleWorldRect().intersects(graph.worldBoundsOf(a)));
    }

    /** Framing the selection puts it in view; framing nothing falls back to everything. */
    @Test
    public void framingTheSelectionBringsItIntoView() {
        node("A", 20f, 20f);
        GraphNode far = node("Far", 900f, 700f);
        frame();

        graph.getSelection().selectOnly(far);
        graph.frameSelection(10f);
        frame();

        assertTrue("the framed node must be inside the view",
                graph.visibleWorldRect().intersects(graph.worldBoundsOf(far)));
    }
}
