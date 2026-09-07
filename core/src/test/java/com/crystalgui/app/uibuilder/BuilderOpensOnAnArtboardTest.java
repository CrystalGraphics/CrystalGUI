package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.app.uibuilder.canvas.TreeSelectTool;
import com.crystalgui.widget.surface.mode.SelectExtension;

/**
 * <b>L2.10 — a {@code .cgui} opens on an artboard, on the shared surface, with Select.</b>
 *
 * <p>The builder's half of L2's acceptance: the engine extracted from the graph is what the builder
 * opens on, it answers {@link BuilderContext} so a feature can be written against it, and it enables
 * exactly the one extension a bare surface has.</p>
 */
public class BuilderOpensOnAnArtboardTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\" }\n"
            + "}\n";

    private BuilderEditor open() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument model =
                new UiBuilderDocument(SOURCE.getBytes(StandardCharsets.UTF_8), "test:page");
        BuilderEditor editor = new BuilderEditor(model);
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor.view());
        document.append(root);
        document.update(W, H);
        return editor;
    }

    @Test
    public void itOpensOnTheSharedSurfaceAndAnswersTheBuildersContext() {
        BuilderEditor editor = open();

        assertTrue("the builder's plane IS the shared surface's context",
                editor.surface() instanceof BuilderContext);
        BuilderContext builder = editor.surface();
        assertSame("and it answers with the document it was opened on",
                editor.document(), builder.getDocument());
        assertSame("and with the artboard the tree is laid out on",
                editor.artboard(), builder.artboard());
    }

    @Test
    public void theArtboardIsOnThePlaneAndSelectIsTheOnlyTool() {
        BuilderEditor editor = open();

        assertTrue("the artboard is an item on the plane, not a painted rectangle",
                editor.surface().surface().items().contains(editor.artboard()));
        // TWO TOOLS NOW, and the builder's is the current one: it composes the engine's Select and adds
        // the one thing a tree needs -- a press on a POSITIONED node is a move, not a selection.
        assertTrue("the engine's Select is still registered",
                editor.surface().tools().stream()
                        .anyMatch(kind -> SelectExtension.TOOL.equals(kind.id())));
        assertTrue("and the builder's own is too",
                editor.surface().tools().stream()
                        .anyMatch(kind -> TreeSelectTool.ID.equals(kind.id())));
        assertTrue("and nothing the builder has not written yet",
                editor.surface().insertSources().isEmpty());
    }
}
