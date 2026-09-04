package com.crystalgui.app.shadergraph;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.texteditor.EditorCommands;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.graph.GraphCommands;
import com.crystalgui.widget.graph.GraphView;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.ui.input.keymap.Keymap;

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
 * {@code CrystalEditor.KIND}'s extension list — those are choices about what a product offers, not about what a graph
 * <em>is</em>.</p>
 */
public class ShaderGraphCommandsTest extends UiDocumentTestBase {


    private UIDocument windowOver(UIElement content) {
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(content);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        for (int i = 0; i < 4; i++) frame();
        return document;
    }

    /** A bare graph, with no shader editor and no host doing anything on its behalf. */
    private GraphView bareGraph() {
        GraphView graph = new GraphView();
        graph.layout(l -> l.width(600).height(400));
        windowOver(graph);
        return graph;
    }

    @Test
    public void aBareGraphRegistersItsOwnCommands() {
        bareGraph();
        assertTrue("graph.delete was never registered -- the graph answers no keys",
                document.getCommands().contains(GraphCommands.DELETE));
        assertTrue(document.getCommands().contains(GraphCommands.CREATE_NODE));
        assertTrue(document.getCommands().contains(GraphCommands.FRAME_ALL));
    }

    /**
     * <b>Ctrl+Z too — and under {@code edit.undo}, not an invented {@code graph.undo}.</b>
     *
     * <p>{@code UndoScope.nearest} walks outward from the focused element and finds this view's own stack,
     * so one command id serves every history in the document. A second id for the same concept would put two
     * Undo entries in the palette with nothing to say which the keystroke ran.</p>
     */
    @Test
    public void aBareGraphBindsUndoUnderTheSharedId() {
        GraphView graph = bareGraph();
        assertTrue("edit.undo was never registered -- Ctrl+Z does nothing in a graph",
                document.getCommands().contains(UndoCommands.UNDO));
        // The chord is declared with the command now, so it is reachable without any element holding
        // a binding -- which is what removed the last reason a widget needed a document to install one.
        assertNotNull("Ctrl+Z is not bound anywhere",
                document.getCommands().declaredBindings().chordFor(UndoCommands.UNDO));
        assertNotNull("Ctrl+Shift+Z / Ctrl+Y is not bound anywhere",
                document.getCommands().declaredBindings().chordFor(UndoCommands.REDO));
    }

    /**
     * <b>Bound on the graph, not on the document root.</b>
     *
     * <p>The defaults include bare {@code A}, {@code F}, {@code Space} and {@code Backspace}. A keymap
     * resolves from the focused element outward, so at the root those would fire while typing into any
     * file open beside the graph in a dock — and every assertion above would still pass.</p>
     */
    @Test
    public void theBareLetterKeysAreScopedToTheGraphRatherThanTheWindow() {
        GraphView graph = bareGraph();
        // Both halves, because either alone passes for the wrong reason.
        //
        // WHERE the binding lives: on the graph, put there by GraphView.bindKeys for every instance.
        // These letters are deliberately NOT declared on the commands -- a declared binding is
        // application-wide by definition, and "A frames the graph" must not be true while somebody is
        // typing in a file open beside it in a dock.
        assertNotNull("the graph's own bare letters are not bound on the graph",
                graph.keymap().chordFor(GraphCommands.FRAME_ALL));
        assertNull("a bare letter must not be bound at the document root, or it fires while typing",
                document.keymap().chordFor(GraphCommands.FRAME_ALL));

        // And WHETHER it applies: enablement is the second gate, and it answers only inside a graph.
        // FRAME_ALL rather than DELETE, which additionally needs a non-empty selection and so would be
        // disabled on a bare graph for a reason that has nothing to do with scoping.
        assertTrue("a bare letter should apply with focus inside the graph",
                document.getCommands().get(GraphCommands.FRAME_ALL)
                        .isEnabled(CommandContext.of(graph)));
        assertFalse("a bare letter must not apply at the document root",
                document.getCommands().get(GraphCommands.FRAME_ALL)
                        .isEnabled(CommandContext.of(
                                document)));
    }

    /** The assembled editor inherits all of it, because its graph is an ordinary {@link GraphView}. */
    @Test
    public void theAssembledShaderEditorGetsThemThroughItsGraph() {
        // Left EMPTY on purpose: attaching previews for real nodes starts CgPreviewRenderer, which wants
        // a GL context. Commands do not care how many nodes exist.
        ShaderGraphEditor editor = new ShaderGraphEditor();
        windowOver(editor);

        assertTrue(document.getCommands().contains(GraphCommands.DELETE));
        assertTrue(document.getCommands().contains(UndoCommands.UNDO));
        assertNotNull(document.getCommands().get(GraphCommands.DELETE));
        assertNotNull(document.getCommands().get(UndoCommands.UNDO));
    }

    /**
     * The precedent this follows, asserted so it cannot quietly stop being true.
     *
     * <p>{@code TextEditor} brings its own commands and its own chords, with no host involved. If that
     * ever moves back out to a caller, the rule this file exists to state has been broken from the other
     * end.</p>
     *
     * <p>Undo is checked through {@code Keymap.acceleratorFor} rather than the editor's own keymap,
     * because the editor deliberately does <b>not</b> rebind it: {@code UndoCommands} declares the chord,
     * so it is live inside the editor without a second copy that would then have to be kept in step.
     * What matters is that Mod+Z fires undo here, which is what this asks.</p>
     */
    @Test
    public void aTextEditorStillBringsItsOwnTheSameWay() {
        TextEditor editor = new TextEditor("hello");
        editor.layout(l -> l.width(400).height(300));
        windowOver(editor);

        assertTrue(document.getCommands().contains(EditorCommands.PREFIX + "deleteLines"));
        assertTrue(document.getCommands().contains(UndoCommands.UNDO));
        assertNotNull("Ctrl+Z does not reach undo from inside the editor",
                Keymap.acceleratorFor(editor, UndoCommands.UNDO));
        // Its OWN chords are on the element, which is what scopes them to a focused editor.
        assertNotNull("Mod+D is not bound on the text editor",
                editor.keymap().chordFor(EditorCommands.PREFIX + "addCaretAtNextOccurrence"));
    }
}
