package com.crystalgui.headless;

import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.text.diff.ThreeWayMerge.Side;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ThreeWayMerge#mapLine} — what keeps three panes looking at the same part of a merge.
 *
 * <p>The three texts have different line counts, so scrolling them to the same pixel offset, or even to
 * the same line number, drifts them apart the moment anything is inserted or deleted above. This is the
 * translation that stops that, and it is pure arithmetic over the regions — so it is testable without a
 * window, which is the whole reason it lives on the merge rather than in the view.</p>
 */
public class LineMappingTest {

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }

    /** One insertion on the left: everything below it is one line further down there. */
    private static ThreeWayMerge withAnInsertionInMine() {
        return ThreeWayMerge.of(
                lines("a", "b", "c", "d"),
                lines("a", "INSERTED", "b", "c", "d"),
                lines("a", "b", "c", "d"));
    }

    @Test
    public void linesAboveAChangeMapToThemselves() {
        ThreeWayMerge merge = withAnInsertionInMine();

        assertEquals(0, merge.mapLine(0, Side.MINE, Side.BASE));
        assertEquals(0, merge.mapLine(0, Side.BASE, Side.MINE));
    }

    /** <b>The point of the whole thing.</b> Below an insertion the two numberings differ by one. */
    @Test
    public void linesBelowAnInsertionAreOffsetByIt() {
        ThreeWayMerge merge = withAnInsertionInMine();

        // base "c" is line 2; in mine it has been pushed down to line 3.
        assertEquals(3, merge.mapLine(2, Side.BASE, Side.MINE));
        assertEquals(2, merge.mapLine(3, Side.MINE, Side.BASE));
        assertEquals("and theirs never moved", 2, merge.mapLine(3, Side.MINE, Side.THEIRS));
    }

    /** Mapping to the same side is the identity, however odd the regions are. */
    @Test
    public void mappingASideToItselfChangesNothing() {
        ThreeWayMerge merge = withAnInsertionInMine();
        for (int line = 0; line < 6; line++) {
            assertEquals(line, merge.mapLine(line, Side.MINE, Side.MINE));
            assertEquals(line, merge.mapLine(line, Side.BASE, Side.BASE));
        }
    }

    /**
     * A line inside a region maps to that region's start.
     *
     * <p>Honest rather than convenient: a region is where the texts disagree, so there is no finer
     * correspondence to offer. It is also why a merge view cannot line up perfectly <em>through</em> a
     * conflict however it scrolls — and pretending otherwise would put the panes a line or two out and
     * make it look like a rounding bug.</p>
     */
    @Test
    public void aLineInsideARegionMapsToTheRegionsStart() {
        ThreeWayMerge merge = ThreeWayMerge.of(
                lines("head", "a", "b", "tail"),
                lines("head", "MINE1", "MINE2", "MINE3", "tail"),
                lines("head", "THEIRS", "tail"));

        int regionStartInTheirs = merge.regions().get(0).theirsFrom();
        assertEquals(regionStartInTheirs, merge.mapLine(2, Side.MINE, Side.THEIRS));
        assertEquals(regionStartInTheirs, merge.mapLine(3, Side.MINE, Side.THEIRS));
    }

    /** A line past the end of everything still maps somewhere legal rather than going negative. */
    @Test
    public void mappingPastTheEndStaysInRange() {
        ThreeWayMerge merge = withAnInsertionInMine();

        assertTrue(merge.mapLine(99, Side.MINE, Side.THEIRS) >= 0);
        assertTrue(merge.mapLine(99, Side.THEIRS, Side.MINE) >= 0);
        assertTrue(merge.mapLine(0, Side.THEIRS, Side.MINE) >= 0);
    }

    /**
     * Round-tripping through the base is stable outside regions.
     *
     * <p>Which is what makes the scroll sync settle: a pane that mapped to another and back to a
     * different line would creep a row every frame the ticker ran.</p>
     */
    @Test
    public void mappingThereAndBackIsStableOutsideRegions() {
        ThreeWayMerge merge = withAnInsertionInMine();

        for (int mineLine : new int[] {0, 2, 3, 4}) {
            int base = merge.mapLine(mineLine, Side.MINE, Side.BASE);
            int back = merge.mapLine(base, Side.BASE, Side.MINE);
            assertEquals("line " + mineLine + " must not creep", mineLine, back);
        }
    }
}
