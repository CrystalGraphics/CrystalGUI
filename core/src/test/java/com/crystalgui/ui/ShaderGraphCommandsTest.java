package com.crystalgui.ui;

import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.graph.shader.ShaderGraphEditor;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.editor.EditorCommands;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.graph.GraphCommands;
import com.crystalgui.ui.elements.graph.GraphView;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A widget's own keys belong to the widget, not to whoever puts it on screen.</b>
 *
 * <p>{@link GraphCommands} and {@link UndoCommands} were installed by one harness scene and by nothing
 * else. So the assembled shader graph in the dock took focus, drew a selection, and answered no key at
 * all — no Delete, no Space, no F, no Ctrl+Z. Nothing failed and nothing logged; the commands were simply
 * never registered, which is indistinguishable from a broken widget.</p>
 *
 * <p>That is the third time the same shape of defect landed here — {@code graph.css} was the first. A
 * requirement every consumer has to remember is a requirement that gets forgotten. So the widget installs
 * them, the way {@code TextEditor} always has with {@link EditorCommands}.</p>
 *
 * <p><b>An application's commands are still the application's.</b> The dock, the palette and Save stay in
 * {@code CrystalEditor.install} — those are choices about what a product offers, not about what a graph
 * <em>is</em>.</p>
 */
public class ShaderGraphCommandsTest extends UiTestBase {

    private UIWindow window;

    private UIWindow windowOver(UIElement content) {
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.addChild(content);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.init(1600, 1000);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
        return window;
    }

    /** A bare graph, with no shader editor and no host doing anything on its behalf. */
    private GraphView bareGraph() {
        GraphView graph = new GraphView();
        graph.layout(l -> l.width(600).height(400));
        windowOver(graph);
        return graph;
    }

    @Test(timeout = 15_000)
    public void aBareGraphRegistersItsOwnCommands() {
        bareGraph();
        assertTrue("graph.delete was never registered -- the graph answers no keys",
                window.getCommands().contains(GraphCommands.DELETE));
        assertTrue(window.getCommands().contains(GraphCommands.CREATE_NODE));
        assertTrue(window.getCommands().contains(GraphCommands.FRAME_ALL));
    }

    /**
     * <b>Ctrl+Z too — and under {@code edit.undo}, not an invented {@code graph.undo}.</b>
     *
     * <p>{@code UndoScope.nearest} walks outward from the focused element and finds this view's own stack,
     * so one command id serves every history in the window. A second id for the same concept would put two
     * Undo entries in the palette with nothing to say which the keystroke ran.</p>
     */
    @Test(timeout = 15_000)
    public void aBareGraphBindsUndoUnderTheSharedId() {
        GraphView graph = bareGraph();
        assertTrue("edit.undo was never registered -- Ctrl+Z does nothing in a graph",
                window.getCommands().contains(UndoCommands.UNDO));
        assertNotNull("Ctrl+Z is not bound on the graph",
                graph.keymap().chordFor(UndoCommands.UNDO));
        assertNotNull("Ctrl+Shift+Z / Ctrl+Y is not bound on the graph",
                graph.keymap().chordFor(UndoCommands.REDO));
    }

    /**
     * <b>Bound on the graph, not on the window root.</b>
     *
     * <p>The defaults include bare {@code A}, {@code F}, {@code Space} and {@code Backspace}. A keymap
     * resolves from the focused element outward, so at the root those would fire while typing into any
     * file open beside the graph in a dock — and every assertion above would still pass.</p>
     */
    @Test(timeout = 15_000)
    public void theBareLetterKeysAreScopedToTheGraphRatherThanTheWindow() {
        GraphView graph = bareGraph();
        assertNotNull("Delete is not bound on the graph at all",
                graph.keymap().chordFor(GraphCommands.DELETE));
        assertNull("bare 'A' is bound at the WINDOW root -- it would frame the graph while typing "
                        + "into any text editor sharing the window",
                window.ui.rootElement.keymap().chordFor(GraphCommands.FRAME_ALL));
    }

    /** The assembled editor inherits all of it, because its graph is an ordinary {@link GraphView}. */
    @Test(timeout = 15_000)
    public void theAssembledShaderEditorGetsThemThroughItsGraph() {
        // Left EMPTY on purpose: attaching previews for real nodes starts CgPreviewRenderer, which wants
        // a GL context. Commands do not care how many nodes exist.
        ShaderGraphEditor editor = new ShaderGraphEditor();
        windowOver(editor);

        assertTrue(window.getCommands().contains(GraphCommands.DELETE));
        assertTrue(window.getCommands().contains(UndoCommands.UNDO));
        assertNotNull(editor.graph().keymap().chordFor(GraphCommands.DELETE));
        assertNotNull(editor.graph().keymap().chordFor(UndoCommands.UNDO));
    }

    /**
     * The precedent this follows, asserted so it cannot quietly stop being true.
     *
     * <p>{@code TextEditor} has always installed its own commands and bound {@code edit.undo} on itself.
     * If that ever moves back out to the host, the rule this file exists to state has been broken from the
     * other end.</p>
     */
    @Test(timeout = 15_000)
    public void aTextEditorStillInstallsItsOwnTheSameWay() {
        TextEditor editor = new TextEditor("hello");
        editor.layout(l -> l.width(400).height(300));
        windowOver(editor);

        assertTrue(window.getCommands().contains(EditorCommands.PREFIX + "deleteLines"));
        assertTrue(window.getCommands().contains(UndoCommands.UNDO));
        assertNotNull("Ctrl+Z is not bound on the text editor",
                editor.keymap().chordFor(UndoCommands.UNDO));
    }
}
