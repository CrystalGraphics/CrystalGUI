package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>The handles still follow the selection after the editor leaves the tree and comes back.</b>
 *
 * <p>{@code ResizeHandles} subscribed to the selection in its CONSTRUCTOR and dropped every connection
 * it holds in {@code disconnected} — which it must, since a subscription outliving its node is what the
 * engine's ownership rule exists to prevent. So the subscription went on the first detach and was never
 * remade: the handles followed nothing from then on, for the life of the editor.</p>
 *
 * <p>The selection outline kept working throughout, because it reads the selection in
 * {@code paintContent} and subscribes to nothing. The canvas therefore outlined a node it would not put
 * handles on, which reads as the handles being broken rather than as a lost subscription.</p>
 *
 * <p>Written against a detach directly rather than through a dock: whether any particular tab switch
 * detaches an editor is the dock's business and changes with its layout, while "this layer is taken out
 * and put back" is the thing that has to keep working.</p>
 */
public class HandlesSurviveADetachTest extends UiDocumentTestBase {

    @Test
    public void aDetachAndReattachKeepsTheSelectionSubscription() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:x.cgui");
        BuilderEditor editor = new BuilderEditor(model);
        UIElement first = new UIElement().layout(l -> l.width(40).height(20));
        UIElement second = new UIElement().layout(l -> l.width(40).height(20));
        model.root().append(first, second);

        UIElement host = new UIElement().layout(l -> l.width(400).height(300));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);

        editor.selection().selectOnly(first);
        document.update(W, H);
        assertSame("the handles never followed the selection at all",
                first, editor.handles().target());

        // OUT OF THE TREE AND BACK IN, which is what a dock does to an editor it stops showing.
        editor.view().removeSelf();
        document.update(W, H);
        host.append(editor.view());
        document.update(W, H);

        editor.selection().selectOnly(second);
        document.update(W, H);
        assertSame("the handles stopped following the selection after a detach",
                second, editor.handles().target());

        editor.selection().selectOnly(null);
        document.update(W, H);
        assertNull("...and stopped hearing about a cleared one too", editor.handles().target());
    }
}
