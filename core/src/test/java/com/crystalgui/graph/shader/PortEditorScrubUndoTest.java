package com.crystalgui.graph.shader;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.shadergraph.CgBuiltinShaderNodes;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodeFieldBinder;
import com.crystalgui.ui.elements.graph.NodeWidgetFactory;
import org.joml.Vector2f;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Scrubbing a port's inline editor must be undoable — <b>and reachable</b> by Ctrl+Z.
 *
 * <h3>The distinction this test exists to make</h3>
 * <p>{@code ScrubUndoTest} already proves a scrub records exactly one {@code SetNodeFieldEdit}. That was
 * true here too, and undo still did nothing: commands resolve their stack by walking
 * {@link UndoScope#nearest} <b>outward from the focused element</b>, and a scrub changed a value without
 * focusing anything. With focus null there is no scope, so the entry sat on a stack no command could
 * find.</p>
 *
 * <p>It presented as "undo does not work on scrubbing" and depended entirely on what had been clicked
 * beforehand — select a node first and it worked, which is why deleting-then-undoing looked healthy. So
 * asserting the edit exists is not enough; this asserts it can be <em>reached</em>.</p>
 */
public class PortEditorScrubUndoTest extends UiTestBase {

    private GraphView view;
    private UIWindow window;
    private GraphDocument document;

    /** Far enough in that the editor, which floats to the LEFT of its port, is on-screen. */
    private static final float NODE_X = 260f, NODE_Y = 160f;

    /** What the stubbed input service reports — the resolver reads live modifier state. */
    private int modifiers;

    private GraphNode openWithMultiply() {
        com.crystalgui.testsupport.TestPlatformService.install().input(
                new com.crystalgraphics.platform.service.CgInputService() {
                    @Override public int getCurrentModifiers() { return modifiers; }
                    @Override public int translateKeyboardCodes(int c) { return c; }
                    @Override public boolean isKeyDown(int c) { return false; }
                    @Override public int translateMouseCodes(int c) { return c; }
                    @Override public boolean isMouseDown(int c) { return false; }
                    @Override public int howManyMouseButtons() { return 3; }
                    @Override public String getClipboard() { return ""; }
                    @Override public void setClipboard(String text) { }
                });
        view = new GraphView();
        view.layout(l -> l.width(600).height(400));
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        root.addChild(view);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        CgShaderNodeRegistry shaderNodes = new CgShaderNodeRegistry();
        CgBuiltinShaderNodes.registerAll(shaderNodes);
        NodeTypeRegistry library = ShaderGraphBridge.asNodeLibrary(shaderNodes);
        view.setNodeLibrary(library, NodeWidgetFactory.of(library).build(),
                ShaderGraphBridge.GLSL_PROMOTION);
        ShaderPortArity.install(view, () -> { });
        document = view.getDocument();

        NodeType type = library.get(CgBuiltinShaderNodes.MULTIPLY.id());
        NodeData data = document.addNode(type.create(NODE_X, NODE_Y));
        GraphNode node = view.getNodeFactory().create(type, data);
        view.addNode(node, NODE_X, NODE_Y);
        NodeFieldBinder.attach(node, type, document, view.undoStack(), null);
        frame();
        frame();
        return node;
    }

    @Test
    public void aScrubOnAPortEditorIsRecordedAndReachableByUndo() {
        openWithMultiply();
        UndoStack stack = view.undoStack();
        assertEquals("nothing recorded yet", 0, stack.undoDepth());

        UIElement handle = findScrubHandle(window.ui.rootElement);
        assertNotNull("the port's inline editor must offer a scrub handle", handle);

        scrub(handle, 60f);

        assertEquals("a scrub is one undo step", 1, stack.undoDepth());

        // The half that was missing. A command walks outward from whatever is focused; with focus null
        // it finds no scope at all and Ctrl+Z is inert however healthy the stack is.
        UIElement focused = window.getInputHandler().getFocusedElement();
        assertNotNull("scrubbing must leave focus somewhere, or undo has nowhere to resolve from",
                focused);
        assertSame("and that somewhere must resolve to THIS graph's history",
                stack, UndoScope.nearest(focused));
    }

    /**
     * <b>The end-to-end path: scrub, then actually press Ctrl+Z.</b>
     *
     * <p>Everything else here asserts a piece — the entry exists, the scope resolves. This drives the
     * keystroke through the real resolver and command, which is the only thing the user ever does, and is
     * what the earlier tests could not have caught: they called {@code undo.undo()} directly and so proved
     * the stack worked while saying nothing about whether the key reached it.</p>
     */
    @Test
    public void ctrlZAfterAScrubUndoesTheScrub() {
        GraphNode node = openWithMultiply();
        String field = "A";
        String before = document.node(node.getNodeId()).properties().get(field);

        UIElement handle = findScrubHandle(window.ui.rootElement);
        assertNotNull(handle);
        scrub(handle, 60f);

        String scrubbed = document.node(node.getNodeId()).properties().get(field);
        assertNotEquals("the scrub must have changed something to undo", before, scrubbed);

        pressCtrlZ();

        assertEquals("Ctrl+Z must put the scrubbed value back",
                before, document.node(node.getNodeId()).properties().get(field));
    }

    /**
     * <b>Undo has to be VISIBLE, not merely recorded.</b>
     *
     * <p>This is the failure every earlier test in this file walked straight past. The stack was correct
     * all along — one entry per scrub, on the right stack, reachable by Ctrl+Z. What was missing is that
     * an {@code Edit} mutates the document <em>directly</em>, and nothing carried the result back to the
     * control displaying the old value or re-ran the recompile hook. So undo changed the document and
     * nothing on screen moved: the box kept showing the undone number, the shader never rebuilt, and it
     * read as "Ctrl+Z undid some earlier action instead".</p>
     *
     * <p>Asserting the control's own value is the whole point — asserting the document would have passed
     * throughout the bug.</p>
     */
    @Test
    public void undoingAScrubUpdatesTheEditorAndNotJustTheDocument() {
        GraphNode node = openWithMultiply();
        UIElement handle = findScrubHandle(window.ui.rootElement);
        assertNotNull(handle);

        com.crystalgui.ui.elements.config.control.NumberControl control = ownerOf(handle);
        assertNotNull("the handle must belong to a NumberControl", control);
        double before = control.getValue();

        scrub(handle, 60f);
        assertNotEquals("the scrub must move the control", before, control.getValue(), 1e-6);

        pressCtrlZ();

        assertEquals("the CONTROL must show the restored value, not only the document",
                before, control.getValue(), 1e-6);
    }

    /** The NumberControl a scrub handle drives. */
    private com.crystalgui.ui.elements.config.control.NumberControl ownerOf(UIElement handle) {
        for (UIElement e = handle; e != null; e = e.getParent()) {
            for (UIElement sibling : e.getParent() == null
                    ? java.util.List.<UIElement>of() : e.getParent().getChildren()) {
                if (sibling instanceof com.crystalgui.ui.elements.config.control.NumberControl n) return n;
            }
        }
        return null;
    }

    /** A press that never travels still focuses, so a click and a drag agree about what is selected. */
    @Test
    public void aPressWithoutADragStillFocusesTheField() {
        openWithMultiply();
        UIElement handle = findScrubHandle(window.ui.rootElement);
        assertNotNull(handle);

        Vector2f at = centreOf(handle);
        press(at, 0f);
        release(at, 0f);

        assertNotNull(window.getInputHandler().getFocusedElement());
        assertEquals("a click is not an edit", 0, view.undoStack().undoDepth());
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private void scrub(UIElement handle, float distance) {
        Vector2f at = centreOf(handle);
        press(at, 0f);
        for (float dx = 10f; dx <= distance; dx += 10f) {
            move(at, dx);
        }
        release(at, distance);
    }

    private UIElement findScrubHandle(UIElement from) {
        if (from.hasClass(com.crystalgui.ui.elements.config.control.NumberControl.SCRUB_HANDLE_CLASS)) {
            return from;
        }
        for (UIElement child : from.getChildren()) {
            UIElement found = findScrubHandle(child);
            if (found != null) return found;
        }
        return null;
    }

    /** A real Mod+Z, through the keymap resolver and the edit.undo command. */
    private void pressCtrlZ() {
        modifiers = com.crystalgraphics.platform.input.CgModifiers.CTRL;
        window.getInputHandler().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                ' ', com.crystalgraphics.platform.input.CgKeyCodes.KEY_Z, true, false, 3L));
        frame();
        modifiers = 0;
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** Physical centre, through the element's own transform — getX() is not screen space. */
    private Vector2f centreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        return Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() / 2f, cache.getY() + cache.getHeight() / 2f);
    }

    private void press(Vector2f at, float dx) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x() + dx), Math.round(at.y()), 0, 0, 0, true, 0f, 1L));
        frame();
    }

    private void move(Vector2f at, float dx) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x() + dx), Math.round(at.y()), 0, 0, -1, false, 0f, -1L));
        frame();
    }

    private void release(Vector2f at, float dx) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x() + dx), Math.round(at.y()), 0, 0, 0, false, 0f, 2L));
        frame();
    }
}
