package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.ui.input.FocusPolicy;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.testsupport.TestPlatformService;

/**
 * P6.1.9 — how undo reaches the DOM.
 *
 * <h3>What is actually being asserted</h3>
 * <p>{@code UndoStackTest} proves the mechanism in isolation. What this pins is the <b>wiring</b>: that a
 * real Ctrl+Z travels keystroke → keymap → command → the nearest {@link UndoScope} outward from focus,
 * and lands in <em>that document's</em> history rather than a global one. Every link in that chain is a
 * place the whole thing can be silently dead while every unit of it passes.</p>
 */
public class UndoWiringTest extends UiTestBase {

    private static final PortType VEC3 = new BasicPortType("vec3", 3);

    private UIWindow window;
    private UIElement root;
    private GraphView graph;

    @Before
    public void setUp() {
        graph = new GraphView();
        graph.layout(l -> l.width(300).height(240));

        root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(graph);

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        // Registration only — the chords are declared on the commands, so there is nothing to bind and
        // no element to bind it on. (A GraphView registers these itself; stated here anyway, because
        // this test is about undo rather than about the graph.)
        UndoCommands.register();
        window.init(800, 800);
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private void pressKey(int key, int modifiers) {
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', key, true, false, System.currentTimeMillis()));
    }

    /** The stub input service reports modifiers, so a chord has to be delivered through it. */
    private void pressWithCtrl(int key) {
        TestPlatformService.get().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return CgModifiers.CTRL; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return ""; }
            @Override public void setClipboard(String text) { }
        });
        pressKey(key, CgModifiers.CTRL);
    }

    private GraphNode node(String title, float x, float y) {
        GraphNode n = new GraphNode(title);
        graph.addNode(n, x, y);
        return n;
    }

    // ── The chain ───────────────────────────────────────────────────────────

    /**
     * <b>Ctrl+Z, as a keystroke, undoes a connection.</b>
     *
     * <p>Keystroke → keymap (root scope) → {@code edit.undo} → nearest {@code UndoScope} from the focused
     * element → the graph's own stack. Nothing in that chain is exercised by testing the stack alone.</p>
     */
    @Test
    public void ctrlZFromTheKeyboardUndoesAGraphEdit() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 180f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort in = b.addInput(VEC3, "A");
        frame();

        graph.connect(out, in);
        assertEquals(1, graph.getConnections().size());

        window.getInputHandler().requestFocus(a);   // focus decides which document Ctrl+Z reaches
        frame();
        pressWithCtrl(CgKeyCodes.KEY_Z);
        frame();

        assertTrue("the wire should be gone", graph.getConnections().isEmpty());
        assertFalse(out.isConnected());

        pressWithCtrl(CgKeyCodes.KEY_Y);            // the other redo, bound to the same command
        frame();
        assertEquals("redo puts it back", 1, graph.getConnections().size());
    }

    /**
     * <b>The nearest scope wins, and there is no global stack.</b>
     *
     * <p>Two graphs in one window are two documents. Undoing with focus inside one must not reach into
     * the other — the property that makes this per document rather than per window, and the one that is
     * impossible to retrofit once something has assumed otherwise.</p>
     */
    @Test
    public void undoReachesTheFocusedDocumentOnly() {
        GraphView other = new GraphView();
        other.layout(l -> l.width(300).height(120));
        root.addChild(other);

        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 180f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort in = b.addInput(VEC3, "A");

        GraphNode c = new GraphNode("C");
        GraphNode d = new GraphNode("D");
        other.addNode(c, 20f, 20f);
        other.addNode(d, 180f, 20f);
        NodePort otherOut = c.addOutput(VEC3, "Out");
        NodePort otherIn = d.addInput(VEC3, "A");
        frame();

        graph.connect(out, in);
        other.connect(otherOut, otherIn);

        window.getInputHandler().requestFocus(c);   // focus is in the OTHER graph
        frame();
        pressWithCtrl(CgKeyCodes.KEY_Z);
        frame();

        assertTrue("the focused document lost its wire", other.getConnections().isEmpty());
        assertEquals("and the other one kept its own", 1, graph.getConnections().size());
    }

    /** With focus outside any document, there is nothing to undo — and the command says so rather than
     * guessing at a stack. */
    @Test
    public void withNoScopeInScopeTheCommandIsDisabled() {
        UIElement outside = new UIElement().layout(l -> l.width(10).height(10));
        outside.setFocusPolicy(FocusPolicy.FOCUSABLE);
        root.addChild(outside);
        frame();

        assertNull(UndoScope.nearest(outside));
        var undo = window.getCommands().get(UndoCommands.UNDO);
        assertNotNull(undo);
        assertFalse(undo.isEnabled(new CommandContext(outside, null)));
    }

    /** Enablement is one mechanism with three consumers — the keystroke, a menu item and the palette all
     * read this same answer, so a greyed-out Edit ▸ Undo needs no separate bookkeeping. */
    @Test
    public void theCommandIsEnabledExactlyWhenThereIsSomethingToUndo() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 180f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort in = b.addInput(VEC3, "A");
        frame();

        var context = new CommandContext(a, null);
        var undo = window.getCommands().get(UndoCommands.UNDO);
        assertFalse("nothing has happened yet", undo.isEnabled(context));

        graph.connect(out, in);
        assertTrue(undo.isEnabled(context));
    }

    /** A node's move is one step for the whole drag, recorded at the end — not one per frame. */
    @Test
    public void aNodeMoveIsOneUndoStep() {
        GraphNode a = node("A", 40f, 40f);
        a.addOutput(VEC3, "Out");
        frame();

        UndoStack history = graph.undoStack();
        graph.moveNode(a, 100f, 90f);              // the drag writes position directly...
        graph.recordMove(a, 40f, 40f, 100f, 90f);  // ...and records once, on release
        frame();

        assertEquals(1, history.undoDepth());
        assertEquals("move", history.undoLabel());

        history.undo();
        frame();
        assertEquals(40f, graph.worldBoundsOf(a).x(), 0.6f);
    }

    /** Pan, zoom and selection are view state: Ctrl+Z after a long editing session must not scroll. */
    @Test
    public void viewStateIsNotUndoable() {
        GraphNode a = node("A", 20f, 20f);
        a.addOutput(VEC3, "Out");
        frame();

        graph.setZoom(2.5f);
        graph.setPan(-40f, -30f);
        graph.selectNode(a, false);
        a.setCollapsed(true);

        assertFalse("none of that is a document change", graph.undoStack().canUndo());
    }
}
