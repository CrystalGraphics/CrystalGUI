package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Two panes onto one {@code .cgui} edit one document.</b> Each draws its own copy of the tree, laid out and
 * selectable on its own; an edit made through either — naming the document's node or the pane's drawing of it —
 * is one edit to the document, and both panes follow it.
 */
public class BuilderSplitTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private UIBuilderView left;
    private UIBuilderView right;
    private UIElement node;

    @Before
    public void twoPanes() {
        UIElementRegistry.bootstrap();
        model = new UiBuilderDocument(UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:split.cgui");
        node = new UIElement().layout(l -> l.width(100).height(40));
        model.apply(new BuilderEdit.Insert(model.root(), node, 0));
        left = new UIBuilderView(model);
        right = new UIBuilderView(model);
        UIElement host = new UIElement().layout(l -> l.width(W).height(H));
        host.append(left.view(), right.view());
        document.append(host);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
    }

    @Test
    public void eachPaneDrawsItsOwnCopy() {
        assertNotSame("both panes were handed one tree, which one would take from the other",
                left.shownTree().shown(node), right.shownTree().shown(node));
        assertTrue(left.shownTree().shown(node).box() != null);
        assertTrue(right.shownTree().shown(node).box() != null);
    }

    @Test
    public void anEditNamingOnePanesDrawingIsOneDocumentEditBothPanesFollow() {
        UIElement drawnLeft = left.shownTree().shown(node);
        model.apply(new BuilderEdit.SetId(drawnLeft, "", "title"));
        assertEquals("the edit reached the document's node, not only the drawing", "title", node.id());
        assertEquals("title", right.shownTree().shown(node).id());

        model.history().undo();
        assertEquals("", node.id());
        assertEquals("", left.shownTree().shown(node).id());
        assertEquals("", right.shownTree().shown(node).id());
    }

    @Test
    public void eachPaneSelectsOnItsOwn() {
        left.selection().selectOnly(node);
        assertSame(node, left.selection().node());
        assertNull("a selection in one pane is not the other pane's", right.selection().node());
        assertSame(left.shownTree().shown(node), left.handles().target());
    }

    @Test
    public void closingOnePaneLeavesTheOtherFollowing() {
        left.disposeView();
        model.apply(new BuilderEdit.SetId(node, "", "after"));
        assertEquals("after", right.shownTree().shown(node).id());
    }
}
