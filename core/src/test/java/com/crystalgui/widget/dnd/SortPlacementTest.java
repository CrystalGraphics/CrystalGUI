package com.crystalgui.widget.dnd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import com.crystalgui.widget.dnd.SortPlacement.Cell;
import com.crystalgui.widget.dnd.SortPlacement.Found;
import com.crystalgui.widget.dnd.SortPlacement.Side;
import com.crystalgui.widget.surface.EdgePan;

/**
 * <b>Where a drop lands among laid-out children</b> — GrapesJS's {@code findPosition}, and the drop area that
 * decides into or beside.
 */
public class SortPlacementTest {

    /** Three 100x20 rows stacked with no gap, child indices 0..2. */
    private static List<Cell> column() {
        return List.of(new Cell(0, 0, 100, 20, true, 0),
                new Cell(0, 20, 100, 20, true, 1),
                new Cell(0, 40, 100, 20, true, 2));
    }

    @Test
    public void aColumnPlacesByEachChildsVerticalCentre() {
        assertEquals(0, SortPlacement.find(column(), 50, 5).index(false));
        assertEquals(1, SortPlacement.find(column(), 50, 15).index(false));
        assertEquals(2, SortPlacement.find(column(), 50, 35).index(false));
    }

    /** The far end is the one that gets lost: past the last child is an append, not a replacement. */
    @Test
    public void pastTheLastChildAppends() {
        Found found = SortPlacement.find(column(), 50, 200);
        assertEquals(Side.AFTER, found.side());
        assertEquals(3, found.index(false));
    }

    @Test
    public void aRowPlacesByEachChildsHorizontalCentre() {
        List<Cell> row = List.of(new Cell(0, 0, 40, 20, false, 0), new Cell(40, 0, 40, 20, false, 1));
        assertEquals(0, SortPlacement.find(row, 10, 10).index(false));
        assertEquals(1, SortPlacement.find(row, 50, 10).index(false));
        assertEquals(2, SortPlacement.find(row, 75, 10).index(false));
    }

    /** A wrapping row finds the pointer's LINE first, so the end of line one is not the answer for line two. */
    @Test
    public void aWrappingRowStaysOnThePointersLine() {
        List<Cell> wrapped = List.of(
                new Cell(0, 0, 40, 20, false, 0), new Cell(40, 0, 40, 20, false, 1),
                new Cell(0, 20, 40, 20, false, 2), new Cell(40, 20, 40, 20, false, 3));
        assertEquals("left half of the second line's first child", 2,
                SortPlacement.find(wrapped, 5, 30).index(false));
        assertEquals("right of the first line's last child", 2,
                SortPlacement.find(wrapped, 75, 10).index(false));
        assertEquals("right of the second line's last child", 4,
                SortPlacement.find(wrapped, 75, 30).index(false));
    }

    /** A reversed row draws its children backwards, so a point on the left lands AFTER in child order. */
    @Test
    public void aReversedFlowSwapsTheSideBackIntoChildOrder() {
        // Child 1 drawn first (left), child 0 second -- visual order.
        List<Cell> reversed = List.of(new Cell(0, 0, 40, 20, false, 1), new Cell(40, 0, 40, 20, false, 0));
        assertEquals("left of the leftmost -- the last child -- is the end of the list", 2,
                SortPlacement.find(reversed, 5, 10).index(true));
        assertEquals("right of the rightmost -- the first child -- is the start", 0,
                SortPlacement.find(reversed, 75, 10).index(true));
    }

    /**
     * A limit at coordinate 0 still limits. The original tested each limit for truth, so a first line
     * ending at y = 0 set no line limit and a point on that line was answered with the second line's child.
     */
    @Test
    public void aLimitAtTheOriginIsStillALimit() {
        List<Cell> wrapped = List.of(
                new Cell(0, -20, 40, 20, false, 0), new Cell(40, -20, 40, 20, false, 1),
                new Cell(0, 0, 40, 20, false, 2));
        assertEquals(0, SortPlacement.find(wrapped, 5, -10).index(false));
    }

    @Test
    public void anEmptyListHasNoSideToFind() {
        assertThrows(IllegalArgumentException.class, () -> SortPlacement.find(List.of(), 0, 0));
    }

    /** The band is a tenth of the size each side, clamped to 1..15. */
    @Test
    public void theDropAreaLeavesAClampedBand() {
        assertArrayEquals(new float[] {10, 5, 80, 40}, SortPlacement.dropArea(0, 0, 100, 50), 0.001f);
        assertArrayEquals("never thicker than 15", new float[] {15, 15, 970, 970},
                SortPlacement.dropArea(0, 0, 1000, 1000), 0.001f);
        assertArrayEquals("never thinner than 1", new float[] {1, 1, 3, 3},
                SortPlacement.dropArea(0, 0, 5, 5), 0.001f);
    }

    /** A pointer held in the band pans by how deep it is, capped; outside it, nothing. */
    @Test
    public void edgePanStepsByDepthIntoTheBand() {
        assertArrayEquals(new float[] {0, 0}, EdgePan.step(200, 150, 400, 300, 24), 0.001f);
        assertArrayEquals("near the right edge the plane moves left", new float[] {-14, 0},
                EdgePan.step(390, 150, 400, 300, 24), 0.001f);
        assertArrayEquals("near the top it moves down", new float[] {0, 20},
                EdgePan.step(200, 4, 400, 300, 24), 0.001f);
        assertArrayEquals("carried off the viewport, capped at the band", new float[] {24, 0},
                EdgePan.step(-500, 150, 400, 300, 24), 0.001f);
    }
}
