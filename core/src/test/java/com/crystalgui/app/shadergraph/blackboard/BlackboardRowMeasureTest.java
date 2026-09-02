package com.crystalgui.app.shadergraph.blackboard;

import com.crystalgui.widget.text.UIText;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A pill must measure the same however the rebuild that made it was triggered.
 *
 * <h3>The fault, which is an engine one and only surfaced here</h3>
 * <p>{@code UIDocument.advanceFrame} ran {@code calculateStyle → tickAnimations → calculateLayout}, then
 * interleaved style and layout until clean. That trailing loop fixes anything a ticker <em>changed</em>
 * — a class set on an element that already exists — but it cannot undo a decision taken from the first
 * layout pass, and one of those is permanent <b>by design</b>.</p>
 *
 * <p>{@code UIText.selfSizesWidth} is settled <b>exactly once</b>, on the first {@code recompute()}
 * after attachment, because re-deriving it every pass provably oscillates (its javadoc carries the
 * argument). Give that one pass an <em>unstyled</em> parent and it settles the wrong way for good:
 * Taffy's default {@code flex-direction} is {@code COLUMN}, whose cross axis stretches a child to the
 * parent's width — so the label is handed a real width, concludes it does not size itself, and then
 * contributes <b>zero</b> width forever, including after the real {@code flex-direction: row} arrives.</p>
 *
 * <p>The Blackboard rebuilds its list from a ticker, and must: rebuilding during a drop detaches the
 * drag source out from under {@code endDrag}. So every pill came back with its capsule shrunk to just
 * its padding and the label spilling out the side onto the panel behind it. Nothing was wrong with the
 * CSS, the widget, or the text — the rows had been measured once before they had a style.</p>
 */
public class BlackboardRowMeasureTest extends UiDocumentTestBase {

    private GraphDocument graphDocument;
    private BlackboardPanel board;

    private void mount() {
        graphDocument = new GraphDocument();
        board = new BlackboardPanel(graphDocument, "test", new UndoStack());
        UINode root = new UINode().layout(l -> l.width(600).height(400));
        root.append(board);
        document.append(root);
        // Without the user-agent sheet the rows have no geometry at all, and every width below would be
        // zero — agreeing with the broken behaviour for the wrong reason.
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
    }

    /** Straight into the graphDocument, so no rename opens over the capsule and hides it. */
    private void declare(String name, String typeId) {
        graphDocument.addProperty(GraphProperty.of(name, typeId, "(0,0)"), graphDocument.propertyCount());
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    private float capsuleWidthOf(int row) {
        PropertyPill pill = board.pills().get(row);
        for (UINode child : pill.children()) {
            if (child.hasClass(PropertyPill.CAPSULE_CLASS)) {
                return child.box().width();
            }
        }
        fail("no capsule on row " + row);
        return -1f;
    }

    /**
     * <b>A rebuild driven from a frame ticker measures exactly as one driven between frames.</b>
     *
     * <p>Compared against the between-frames answer rather than a number: the claim is that <em>when</em>
     * the rebuild happened cannot change how a row measures, and a hard-coded width would turn a font
     * change into a failure here.</p>
     */
    @Test
    public void aTickerDrivenRebuildMeasuresLikeAnyOther() {
        mount();
        declare("Vector 2", "vec2");
        settle();
        float betweenFrames = capsuleWidthOf(0);
        assertTrue("the capsule must wrap its label, not just its padding; got " + betweenFrames,
                betweenFrames > 40f);

        // A graphDocument change from INSIDE a ticker, which is the deferred refresh's real path: the panel
        // rebuilds from a ticker so that a drop cannot detach the drag source mid-flight.
        document.animation().every(document, delta -> {
            declare("Vector 4", "vec4");
            return false;
        });
        settle();

        assertEquals("both rows are rebuilt, and the first must not have changed size",
                betweenFrames, capsuleWidthOf(0), 0.01f);
        assertTrue("and the row the ticker added measures its own label too; got " + capsuleWidthOf(1),
                capsuleWidthOf(1) > 40f);
    }
}
