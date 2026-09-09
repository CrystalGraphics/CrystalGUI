package com.crystalgui.headless;

import java.util.List;

import org.junit.Test;

import com.crystalgui.widget.text.TableColumns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * CSS Tables 3's automatic column layout — the arithmetic, on its own.
 *
 * <p><b>In {@code headlessTest} on purpose.</b> {@link TableColumns} takes numbers and answers numbers:
 * no fonts, no boxes, no window. Whoever measures the cells is the half that needs those, and keeping
 * the two apart is what lets the decision be pinned here rather than inferred from a laid-out tree.</p>
 *
 * <p>The cases are the ones the port exists for, in the order they bite: content-sized when there is
 * room, squeezed in step when there is not, at the minimum when there is none, and a spanning cell
 * asking only for what the columns beneath it cannot already carry.</p>
 */
public class TableColumnsTest {

    private static final float EPSILON = 0.01f;

    private static TableColumns.Cell cell(int column, float min, float max) {
        return new TableColumns.Cell(column, 1, min, max);
    }

    private static float total(float[] widths) {
        float sum = 0f;
        for (float width : widths) sum += width;
        return sum;
    }

    /** Room to spare: every column takes what it wants, and the leftover stays leftover. */
    @Test
    public void aTableWithRoomIsSizedByItsContent() {
        float[] widths = TableColumns.solve(3, List.of(
                cell(0, 20f, 60f), cell(1, 20f, 50f), cell(2, 30f, 400f)), 900f);

        assertEquals("column 0 did not take its content width", 60f, widths[0], EPSILON);
        assertEquals("column 1 did not take its content width", 50f, widths[1], EPSILON);
        assertEquals("column 2 did not take its content width", 400f, widths[2], EPSILON);
        assertTrue("a table with room stretched to fill it", total(widths) < 900f);
    }

    /**
     * Not enough room: every column gives up the same FRACTION of what it asked for beyond its minimum.
     *
     * <p>Which is the whole point of the algorithm — the alternative, taking it all from the widest
     * column, is what makes a table of one long prose column and two labels collapse into a ribbon.</p>
     */
    @Test
    public void aSqueezedTableGivesUpProportionally() {
        // Minimums 20+20+30 = 70; maximums 60+50+400 = 510. At 290 the columns are halfway.
        float[] widths = TableColumns.solve(3, List.of(
                cell(0, 20f, 60f), cell(1, 20f, 50f), cell(2, 30f, 400f)), 290f);

        assertEquals("the columns do not fill the room they were given", 290f, total(widths), EPSILON);
        assertEquals(40f, widths[0], EPSILON);
        assertEquals(35f, widths[1], EPSILON);
        assertEquals(215f, widths[2], EPSILON);
    }

    /**
     * Less room than the minimum: the table overflows rather than going narrower than its own words.
     *
     * <p>Honest rather than tidy. A column narrower than the longest word in it cannot draw that word,
     * so the answer is the minimum and something outside scrolls.</p>
     */
    @Test
    public void aTableTooNarrowStopsAtItsMinimum() {
        float[] widths = TableColumns.solve(3, List.of(
                cell(0, 20f, 60f), cell(1, 20f, 50f), cell(2, 30f, 400f)), 10f);

        assertEquals(20f, widths[0], EPSILON);
        assertEquals(20f, widths[1], EPSILON);
        assertEquals(30f, widths[2], EPSILON);
        assertTrue("a table at its minimum did not overflow", total(widths) > 10f);
    }

    /** A spanning cell narrower than the columns under it asks for nothing at all. */
    @Test
    public void aSpanThatAlreadyFitsChangesNothing() {
        float[] widths = TableColumns.solve(2, List.of(
                new TableColumns.Cell(0, 2, 10f, 40f), cell(0, 20f, 100f), cell(1, 20f, 100f)), 900f);

        assertEquals(100f, widths[0], EPSILON);
        assertEquals(100f, widths[1], EPSILON);
    }

    /**
     * A spanning cell wider than them shares out only the EXCESS, in proportion to what each already
     * wants — so the ratio between the columns it covers survives.
     */
    @Test
    public void aSpanSharesOutOnlyWhatDoesNotFit() {
        // The columns want 60 and 20, eighty between them; the header wants 180, so 100 is shared out
        // three parts to one.
        float[] widths = TableColumns.solve(2, List.of(
                new TableColumns.Cell(0, 2, 30f, 180f), cell(0, 10f, 60f), cell(1, 10f, 20f)), 900f);

        assertEquals(135f, widths[0], EPSILON);
        assertEquals(45f, widths[1], EPSILON);
        assertEquals("the span did not end up as wide as it asked", 180f, total(widths), EPSILON);
    }

    /** With nothing to share in proportion TO, the excess is split evenly — the only answer available. */
    @Test
    public void aSpanOverEmptyColumnsSplitsEvenly() {
        float[] widths = TableColumns.solve(2, List.of(
                new TableColumns.Cell(0, 2, 0f, 100f)), 900f);

        assertEquals(50f, widths[0], EPSILON);
        assertEquals(50f, widths[1], EPSILON);
    }

    /** No columns, no answer — and not an exception, since an empty table is a document's business. */
    @Test
    public void anEmptyTableAnswersNothing() {
        assertEquals(0, TableColumns.solve(0, List.of(), 100f).length);
        assertEquals(0, TableColumns.solve(-1, List.of(cell(0, 1f, 2f)), 100f).length);
    }

    /** A cell placed outside the grid is ignored rather than trusted — it cannot index an array. */
    @Test
    public void aCellOutsideTheGridIsIgnored() {
        float[] widths = TableColumns.solve(1, List.of(cell(0, 10f, 40f), cell(5, 999f, 999f)), 900f);

        assertEquals(1, widths.length);
        assertEquals(40f, widths[0], EPSILON);
    }
}
