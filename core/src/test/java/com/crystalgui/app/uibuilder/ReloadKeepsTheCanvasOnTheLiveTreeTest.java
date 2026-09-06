package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.panel.HierarchyPanel;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>A reload replaces the tree, and the canvas must move with it.</b>
 *
 * <p>{@code adopt} mints a brand-new root — a reload, a revert, the file changing underneath. The
 * artboard held the old one and nothing put the new one in: {@code Artboard.resync()} existed, said in
 * its own javadoc what it was for, and had <b>no callers</b>.</p>
 *
 * <p>So the hierarchy, which reads {@code document.root()}, listed a tree the canvas was not showing.
 * Clicking one of its rows selected a node that was in no document and therefore had no box — which is
 * every symptom at once: eight handles stranded at their layer's origin, no selection outline, nothing
 * resizable, and a canvas click selecting a node with no row in the panel.</p>
 */
public class ReloadKeepsTheCanvasOnTheLiveTreeTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [ { \"kind\": \"text\", \"id\": \"title\","
            + " \"state\": { \"text\": \"bao\" } } ] }\n"
            + "}\n";

    private static final String RELOADED = SOURCE.replace("bao", "mao");

    private BuilderEditor editor;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        editor = new BuilderEditor(new UiBuilderDocument(
                SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"));
        UIElement host = new UIElement().layout(l -> l.width(800).height(500));
        host.append(editor.view());
        document.append(host);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();
    }

    /** <b>The canvas shows the tree the hierarchy lists.</b> */
    @Test
    public void afterAReloadTheCanvasHoldsTheDocumentsCurrentRoot() {
        editor.document().adopt(RELOADED.getBytes(StandardCharsets.UTF_8));
        document.update(W, H);
        frame();

        UIElement root = editor.document().root();
        assertSame("the artboard is still showing the replaced tree",
                root, editor.artboard().children().get(0));
    }

    /**
     * <b>And a node the hierarchy offers can actually be selected.</b>
     *
     * <p>The failure this reproduces: the row exists, the click selects, and the node has no box — so
     * every overlay drawn from the selection points at nothing.</p>
     */
    @Test
    public void aRowFromTheReloadedTreeSelectsANodeThatIsLaidOut() {
        editor.document().adopt(RELOADED.getBytes(StandardCharsets.UTF_8));
        document.update(W, H);
        frame();

        HierarchyPanel hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        document.update(W, H);
        frame();

        UIElement title = editor.document().root().children().get(0);
        int row = -1;
        var rows = hierarchy.tree().visibleRows();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).item() == title) row = i;
        assertTrue("the hierarchy does not even list the reloaded tree", row >= 0);

        hierarchy.tree().select(row);
        document.update(W, H);
        frame();

        assertSame(title, editor.selection().node());
        assertNotNull("selected a node that is in no document, so it has no box and nothing can be "
                + "drawn on it", title.box());
        assertSame(title, editor.handles().target());
    }

    /** A selection from the replaced tree is dropped rather than left pointing at dead elements. */
    @Test
    public void theStaleSelectionIsCleared() {
        UIElement before = editor.document().root().children().get(0);
        editor.selection().selectOnly(before);
        document.update(W, H);
        frame();

        editor.document().adopt(RELOADED.getBytes(StandardCharsets.UTF_8));
        document.update(W, H);
        frame();

        assertNull("the selection still holds an element from the replaced tree",
                editor.selection().node());
    }
}
