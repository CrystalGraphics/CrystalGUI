package com.crystalgui.app.shadergraph.blackboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UINode;
import org.junit.Test;

/**
 * Which slot a drop lands in — the geometry half of the Blackboard's reorder, on the new engine.
 *
 * <h3>The same bug twice, from opposite directions</h3>
 *
 * <p>The old engine's copy of this test exists because {@code dropIndexAt} compared
 * {@code screenToLocal(...).y} against a bare half-height, as though the conversion returned a
 * position relative to the row's own top-left. It did not: that method undid {@code uiScale} and any
 * transform but never the element's layout offset, so "local" was absolute logical space and the
 * comparison had to be against {@code box().y() + height / 2}.</p>
 *
 * <p><b>M6.1 moved that origin, and the fixed comparison survived the port unchanged.</b>
 * {@code UINode.toLocal} now DOES put the box's own origin at zero, so adding {@code box().y()} to the
 * threshold adds the row's offset within the list to it — and every row past the first reads as "the
 * pointer is above my midpoint". A dragged pill lands at the top of the list wherever it is dropped.
 * The old engine's failure was the mirror image: every row measured as though it sat at the origin,
 * nothing matched, and a pill always landed at the END.</p>
 *
 * <p>Asserted through the real layout rather than with hand-fed numbers, for the reason the old test
 * gives: the whole failure is a coordinate space, and a test that mocked the geometry would agree with
 * whichever version wrote it.</p>
 */
public class BlackboardDropSlotTest extends UiDocumentTestBase {

    private BlackboardPanel board;

    private void mountWith(String... menuLabels) {
        withDefaultStyles();
        board = new BlackboardPanel(new GraphDocument(), "test", new UndoStack());
        layout(board, l -> l.width(280f).height(400f));
        document.append(board);
        for (String label : menuLabels) board.addProperty(label);
        // Twice: a text row settles its measured height across passes, and rows of height zero make
        // every midpoint identical — which is a fixture that agrees with any implementation at all.
        frame();
        frame();
    }

    /** A surface Y at {@code fraction} of the way down {@code row}'s box. */
    private static float screenYOf(UINode row, float fraction) {
        Box box = row.box();
        assertNotNull("the row has no box — the fixture never laid out", box);
        assertTrue("the row measured zero-high, so every slot answer is accidentally equal",
                box.height() > 0f);
        return box.worldY() + box.height() * fraction;
    }

    /**
     * Every row owns its own slot, top to bottom — the property BOTH broken versions lacked.
     *
     * <p>The whole sequence in one test on purpose: each engine's bug collapses the answer onto a
     * single end of the range, so any single assertion in the middle passes against one of them.</p>
     */
    @Test
    public void eachRowOwnsItsOwnSlot() {
        mountWith("Vector 2", "Vector 4");
        assertEquals("the fixture did not build two rows", 2, board.pills().size());

        UINode first = board.pills().get(0);
        UINode second = board.pills().get(1);

        assertEquals("above the first row", 0, board.dropIndexAt(20f, screenYOf(first, -1f)));
        assertEquals("in the first row's top half", 0, board.dropIndexAt(20f, screenYOf(first, 0.25f)));
        assertEquals("in its bottom half", 1, board.dropIndexAt(20f, screenYOf(first, 0.75f)));
        assertEquals("in the second row's top half", 1, board.dropIndexAt(20f, screenYOf(second, 0.25f)));
        assertEquals("in its bottom half", 2, board.dropIndexAt(20f, screenYOf(second, 0.75f)));
        assertEquals("below everything", 2, board.dropIndexAt(20f, screenYOf(second, 6f)));
    }

    /**
     * <b>Three rows, because two cannot tell the two failures apart at the bottom.</b>
     *
     * <p>With two rows, "always the last slot" and "always index 1" agree on the second row's bottom
     * half. A third row separates them, and it is the middle one that the ported bug gets wrong.</p>
     */
    @Test
    public void theMiddleRowIsReachable() {
        mountWith("Vector 2", "Vector 4", "Color");
        assertEquals(3, board.pills().size());

        UINode middle = board.pills().get(1);
        assertEquals("the middle row's top half is slot 1",
                1, board.dropIndexAt(20f, screenYOf(middle, 0.25f)));
        assertEquals("its bottom half is slot 2",
                2, board.dropIndexAt(20f, screenYOf(middle, 0.75f)));
    }
}
