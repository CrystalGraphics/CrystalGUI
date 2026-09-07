package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.panel.HierarchyPanel;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>The Design panel keeps working after it leaves the tree and comes back.</b>
 *
 * <p>It subscribed to all three of its signals in the CONSTRUCTOR while {@code disconnected} drops every
 * connection it holds — which it must, since one outliving its node is what the engine's ownership rule
 * exists to prevent. So the first detach killed the lot and nothing remade them: rows stopped selecting
 * anything, the tree stopped following the canvas, and it stopped rebuilding when the document changed.
 * Reported as the panel "just dying" and staying dead until the harness restarted, which is exactly what
 * a subscription nothing re-registers looks like.</p>
 *
 * <p>The dock takes a tool window out for ordinary reasons — hiding it, rebuilding a layout, replacing
 * the panel behind a tab — which is why it read as random.</p>
 */
public class DesignPanelSurvivesADetachTest extends UiDocumentTestBase {

    @Test
    public void aRowStillSelectsAfterADetach() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:x.cgui");
        UIElement first = new UIElement().layout(l -> l.width(40).height(20));
        UIElement second = new UIElement().layout(l -> l.width(40).height(20));
        model.root().append(first, second);

        BuilderEditor editor = new BuilderEditor(model);
        UIElement host = new UIElement().layout(l -> l.width(400).height(300));
        host.append(editor.view());
        document.append(host);

        HierarchyPanel panel = new HierarchyPanel(editor.surface());
        UIElement side = new UIElement().layout(l -> l.width(200).height(300));
        side.append(panel);
        document.append(side);
        document.update(W, H);

        assertTrue("the tree never listed the document", panel.tree().visibleRows().size() >= 2);
        chooseRow(panel, indexOf(panel, second));
        assertSame("a row never selected anything at all", second, editor.selection().node());

        // OUT OF THE TREE AND BACK, which is what a dock does to a tool window it stops showing.
        panel.removeSelf();
        document.update(W, H);
        side.append(panel);
        document.update(W, H);

        chooseRow(panel, indexOf(panel, first));
        assertSame("the panel stopped selecting after a detach", first, editor.selection().node());

        // AND IT STILL FOLLOWS THE CANVAS, the other half that died with it.
        editor.selection().selectOnly(second);
        document.update(W, H);
        assertTrue("the panel stopped following the canvas",
                panel.tree().getSelectedIndices().contains(indexOf(panel, second)));
    }

    private static int indexOf(HierarchyPanel panel, UIElement node) {
        var rows = panel.tree().visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() == node) return i;
        }
        throw new AssertionError("no row for " + node);
    }

    private void chooseRow(HierarchyPanel panel, int index) {
        panel.tree().onSelectionChanged.emit(Set.of(index));
        document.update(W, H);
    }
}
