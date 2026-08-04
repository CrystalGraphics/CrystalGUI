package com.crystalgui.ui;

import com.crystalgui.graph.shader.ShaderGraphEditor;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.graph.GraphCommands;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A shader graph answers its own keys.</b>
 *
 * <p>{@link GraphCommands} was installed by one harness scene and by nothing else, so when the assembled
 * editor moved into {@code core/} the dock got a graph that took focus, showed a selection, and responded
 * to no key at all — Delete, Space and F alike. Nothing failed; the commands were simply never
 * registered, which looks exactly like a broken widget.</p>
 *
 * <p>That is the same class of defect {@code graph.css} already cost: a requirement every consumer has to
 * remember is a requirement that gets forgotten. Both now belong to the widget.</p>
 */
public class ShaderGraphCommandsTest extends UiTestBase {

    private UIWindow window;
    private ShaderGraphEditor editor;

    private void build() {
        editor = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.init(1600, 1000);
        // The graph is left EMPTY: attaching previews for real nodes starts CgPreviewRenderer, which
        // allocates an FBO per node and wants a GL context. The commands do not care how many nodes exist.
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    @Test(timeout = 15_000)
    public void theWidgetRegistersTheGraphCommandsItself() {
        build();
        assertTrue("graph.delete was never registered -- the graph answers no keys",
                window.getCommands().contains(GraphCommands.DELETE));
        assertTrue(window.getCommands().contains(GraphCommands.CREATE_NODE));
        assertTrue(window.getCommands().contains(GraphCommands.FRAME_ALL));
    }

    /**
     * <b>Bound on the widget, not on the window root.</b>
     *
     * <p>The defaults include bare {@code A}, {@code F}, {@code Space} and {@code Backspace}. A keymap
     * resolves from the focused element upward, so binding them at the root would make typing {@code a}
     * into any file open in the dock frame the shader graph instead — and every one of the assertions
     * above would still pass.</p>
     */
    @Test(timeout = 15_000)
    public void theBareLetterKeysAreScopedToTheGraphRatherThanTheWindow() {
        build();
        assertNotNull("Delete is not bound on the graph widget at all",
                editor.keymap().chordFor(GraphCommands.DELETE));
        assertNull("bare 'A' is bound at the WINDOW root -- it would frame the graph while typing "
                        + "into any text editor in the dock",
                window.ui.rootElement.keymap().chordFor(GraphCommands.FRAME_ALL));
    }
}
