package com.crystalgui.graph.shader;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Which slot a drop lands in — the geometry half of the Blackboard's reorder.
 *
 * <h3>The bug this exists for, because it hid in the one direction nobody would question</h3>
 * <p>{@code dropIndexAt} compared {@code screenToLocal(...).y} against half the row's height, as though
 * the conversion returned a position <em>relative to that row's top-left</em>. It does not.
 * {@code UIElement.localToWorld} composes the parent chain, scroll and the element's own
 * {@code transform} — but never its layout offset — so "local" is <b>absolute logical space</b>: the
 * space {@code getRuntimeCache().getY()} is already in, which is why {@code isMouseOverElement} compares
 * against {@code getX()/getY()} rather than against zero. All it undoes is {@code uiScale} and any
 * transform.</p>
 *
 * <p>So every row measured as though it sat at the origin, nothing ever matched, and the method returned
 * {@code rows.size()} for every point on the panel. <b>Dragging a pill down therefore looked perfect</b>
 * — the end of the list is where a downward drag was headed anyway — and dragging one back up did
 * nothing whatsoever, since "move to the end" is a no-op for a row already there.</p>
 *
 * <p>Asserted through the real layout rather than with hand-fed numbers: the whole failure was a
 * coordinate space, and a test that mocked the geometry would have agreed with the broken version.</p>
 */
public class BlackboardDropSlotTest extends UiTestBase {

    private GraphDocument document;
    private BlackboardPanel board;
    private UIWindow window;

    /**
     * A physical pointer y at {@code fraction} down this row — what a real mouse would report.
     *
     * <p>Through {@code localToWorld}, which is the exact forward of the {@code worldToLocal} the code
     * under test inverts. Multiplying by a hardcoded {@code uiScale} would be a second statement of the
     * conversion, and this test exists precisely because a second statement of it was wrong.</p>
     */
    private float screenYOf(UIElement row, float fraction) {
        var cache = row.getRuntimeCache();
        float logicalY = cache.getY() + cache.getHeight() * fraction;
        return Transform2D.apply(cache.localToWorld.get(), 0f, logicalY).y();
    }

    private void mountWith(String... menuLabels) {
        document = new GraphDocument();
        board = new BlackboardPanel(document, "test", new UndoStack());

        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.addChild(board);
        window = new UIWindow(Ui.of(root));
        // The user-agent sheet is NOT installed for you, and without it the rows have no geometry at
        // all -- every box measures zero and every slot answer is accidentally right.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(600, 400);
        for (String label : menuLabels) board.addProperty(label);
        // Twice: UIText settles its measured height across passes, and a row of height zero would make
        // every midpoint identical.
        window.updateWithoutPainting();
        window.updateWithoutPainting();
    }

    /** Every row reports a distinct slot, top to bottom — the property the broken version lacked. */
    @Test
    public void eachRowOwnsItsOwnSlot() {
        mountWith("Vector 2", "Vector 4");
        assertEquals(2, board.pills().size());

        UIElement first = board.pills().get(0);
        UIElement second = board.pills().get(1);

        assertEquals("above the first row", 0, board.dropIndexAt(20f, screenYOf(first, -1f)));
        assertEquals("in the first row's top half", 0, board.dropIndexAt(20f, screenYOf(first, 0.25f)));
        assertEquals("in its bottom half", 1, board.dropIndexAt(20f, screenYOf(first, 0.75f)));
        assertEquals("in the second row's top half", 1, board.dropIndexAt(20f, screenYOf(second, 0.25f)));
        assertEquals("in its bottom half", 2, board.dropIndexAt(20f, screenYOf(second, 0.75f)));
        assertEquals("below everything", 2, board.dropIndexAt(20f, screenYOf(second, 6f)));
    }

    /**
     * <b>The reported bug, end to end: a row dragged down can be dragged back up.</b>
     *
     * <p>Down always worked, because the broken answer and the intended one coincided there. This drags
     * in both directions through the same path the drop handler uses.</p>
     */
    @Test
    public void aRowMovesBothDownAndUp() {
        mountWith("Vector 2", "Vector 4");
        String vec2 = document.properties().get(0).id();

        // Down: into the second row's bottom half, i.e. the end of the list.
        board.dropProperty(vec2, board.dropIndexAt(20f, screenYOf(board.pills().get(1), 0.75f)), "");
        window.updateWithoutPainting();
        window.updateWithoutPainting();
        assertEquals("it went down", 1, document.indexOfProperty(vec2));

        // And back up: above the first row.
        board.dropProperty(vec2, board.dropIndexAt(20f, screenYOf(board.pills().get(0), -1f)), "");
        window.updateWithoutPainting();
        assertEquals("and back up again", 0, document.indexOfProperty(vec2));
    }
}
