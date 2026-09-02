package com.crystalgui.widget.canvas;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>Resizing the document must not make the graph disappear.</b>
 *
 * <p>Reported from the harness: the shader graph's pane went blank on a document resize and stayed blank.
 * The canvas culls with {@code opacity: 0} against {@link
 * com.crystalgui.widget.canvas.CanvasView#visibleWorldRect()}, so anything that leaves that rect
 * wrong after a resize hides every node while the layout underneath stays perfectly correct — which is
 * why this is asserted on the cull set rather than on geometry.</p>
 *
 * <p>The canvas here is sized by its <b>parent</b>, not by a declared width and height. That is the shape
 * a dock pane produces and it is the one the existing canvas tests do not cover: they all give the canvas
 * a definite box, where a resize changes nothing it reads.</p>
 */
public class CanvasResizeTest extends UiDocumentTestBase {

    private GraphView graph;
    private GraphNode near;

    @Before
    public void setUp() {
        graph = new GraphView();
        // The dock-pane shape: fills a column it does not know the size of.
        graph.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));

        UINode root = new UINode().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.append(graph);

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));

        near = new GraphNode("near");
        graph.addNode(near, 20f, 30f);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) {
            frame();
        frame();
        }
    }

    /** The precondition. If this fails the rest of the file is testing nothing. */
    @Test
    public void aNodeAtTheOriginIsVisibleToBeginWith() {
        assertFalse("the node is culled before any resize -- the fixture is wrong",
                graph.isCulled(near));
    }

    /** Growing the document can only reveal more world, so nothing may become hidden. */
    @Test
    public void growingTheWindowKeepsTheNodeVisible() {
        settle();
        assertFalse("the node vanished when the document grew", graph.isCulled(near));
    }

    /** And shrinking, as long as the node is still inside the smaller viewport. */
    @Test
    public void shrinkingTheWindowKeepsANodeThatIsStillInViewVisible() {
        settle();
        assertFalse("the node vanished when the document shrank, though it is still in view",
                graph.isCulled(near));
    }

    /** Several resizes in a row — the harness produces a stream of them while a drag is in progress. */
    @Test
    public void aStreamOfResizesLeavesTheNodeVisible() {
        for (int width = 500; width <= 1600; width += 137) {
            settle();
        }
        assertFalse("the node vanished somewhere in a run of resizes", graph.isCulled(near));
    }

    /** Culling still does its job — a node far off the plane stays hidden, so the tests above are not
     * passing because culling quietly stopped working. */
    @Test
    public void aNodeFarOffScreenIsStillCulled() {
        GraphNode far = new GraphNode("far");
        graph.addNode(far, 40_000f, 40_000f);
        settle();
        assertTrue("nothing is being culled at all -- the assertions above prove nothing",
                graph.isCulled(far));
    }
}
