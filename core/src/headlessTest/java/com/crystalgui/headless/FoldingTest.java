package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import com.crystalgui.text.fold.FoldingModel;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.fold.FoldingRegions;
import com.crystalgui.text.fold.IndentRangeProvider;
import com.crystalgui.text.wrap.LineBreaksComputer;
import com.crystalgui.text.wrap.ProjectedLines;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.2.7 — folding's model layer.
 *
 * <h3>Why headless</h3>
 * <p>Folding needs no font. <b>Which rows are foldable</b> is a scan over indentation, <b>which are
 * hidden</b> is arithmetic over collapse flags, and <b>what that does to coordinates</b> is the projection
 * index reporting zero view lines for a row. None of the three can ask a glyph anything, so all three are
 * asserted without a window — and they are where the bugs live, because a wrong answer here shows up as
 * the caret landing inside a block that is not on screen.</p>
 */
public class FoldingTest {

    private static final String NL = "\n";

    private static Rope rope(String... lines) {
        return Rope.of(String.join(NL, lines));
    }

    private static FoldingRegions regionsOf(Rope document) {
        return IndentRangeProvider.plain().compute(document, 4);
    }

    /** {@code start/end} pairs, so a whole region set reads as one string in a failure message. */
    private static String describe(FoldingRegions regions) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < regions.length(); i++) {
            if (i > 0) out.append(' ');
            out.append(regions.getStartLineNumber(i)).append('-').append(regions.getEndLineNumber(i));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------
    // Region discovery
    // ---------------------------------------------------------------------------------------------------

    /**
     * <b>A region runs from the row that opens the indent to the last row still inside it.</b>
     *
     * <p>The closing brace is NOT part of the region here, which is the visible consequence of folding by
     * indentation rather than by brackets: {@code }} sits at the outer indent, so it belongs to the block
     * outside. That is what makes a collapsed function read as {@code void f() { ⋯ }} with the brace still
     * shown, exactly as VS Code renders it.</p>
     */
    @Test
    public void aBlockFoldsFromItsHeaderToItsLastIndentedRow() {
        Rope document = rope(
                "void f() {",     // 0
                "    a();",       // 1
                "    b();",       // 2
                "}",              // 3
                "void g() {}");   // 4

        // Including the closing brace -- a deliberate divergence from VS Code, see
        // IndentRangeProvider.extendToClosingRows. Pure indent folding stops at the last INDENTED row and
        // leaves `}` behind on a line of its own; swallowing it is what makes a collapsed function read as
        // `void f() {...}`, which is IntelliJ's form and what was asked for.
        assertEquals("0-3", describe(regionsOf(document)));
    }

    /**
     * <b>Only a bare closer at the region's own indent is swallowed.</b>
     *
     * <p>The narrowness is the point. A statement following the block sits at the same indent, and so does
     * an {@code else} — absorbing either would fold away code the user can still see the start of, which is
     * far worse than leaving a brace behind.</p>
     */
    @Test
    public void onlyABareCloserIsSwallowed() {
        assertEquals("a following statement is not part of the block",
                "0-1", describe(regionsOf(rope("if (x) {", "    y();", "next();"))));
        // A closer AND an opener on one row. Swallowing it folds the else branch out of sight.
        assertEquals("nor is an else",
                "0-1 2-3", describe(regionsOf(rope("if (x) {", "    y();", "} else {", "    z();"))));
    }

    /** Nested blocks produce nested regions, sorted outermost-first by start row. */
    @Test
    public void nestedBlocksNest() {
        Rope document = rope(
                "class A {",       // 0
                "    void f() {",  // 1
                "        a();",    // 2
                "        b();",    // 3
                "    }",           // 4
                "}");              // 5

        FoldingRegions regions = regionsOf(document);
        assertEquals("0-5 1-4", describe(regions));
        assertEquals("the inner block's parent is the outer one", 0, regions.getParentIndex(1));
        assertEquals("and the outer one has none", -1, regions.getParentIndex(0));
    }

    /**
     * <b>A region must hide at least one row to exist.</b>
     *
     * <p>{@code endLineNumber - line >= 1} in the original — a region spans the header plus at least one
     * row beneath it. Since the header always stays visible, a region that did not clear that bar would be
     * a chevron that hides nothing when clicked.</p>
     *
     * <p>So a one-row body <em>is</em> foldable: it hides exactly that row. It is a flat file — one with no
     * indent increase anywhere — that yields nothing.</p>
     */
    @Test
    public void aRegionMustHideAtLeastOneRow() {
        assertEquals("a header plus one body row hides that row",
                "0-1", describe(regionsOf(rope("if (x)", "    y();", "z();"))));
        assertEquals("nothing indents, so nothing folds",
                "", describe(regionsOf(rope("a();", "b();", "c();"))));
    }

    /** Blank rows do not break a block, and do not extend one past its content either. */
    @Test
    public void blankRowsDoNotSplitABlock() {
        Rope document = rope(
                "void f() {",  // 0
                "    a();",    // 1
                "",            // 2
                "    b();",    // 3
                "}",           // 4
                "",            // 5
                "");           // 6

        assertEquals("0-4", describe(regionsOf(document)));
    }

    /**
     * <b>Indentation cannot be fooled by a brace in a string.</b>
     *
     * <p>The reason this provider is the default rather than a bracket counter. A brace inside a literal
     * would open a phantom region for a scanner that does not first know what a string is — i.e. one that
     * needs the very grammar the indent provider exists to avoid needing.</p>
     */
    @Test
    public void aBraceInsideAStringChangesNothing() {
        Rope withBrace = rope("void f() {", "    s = \"{\";", "    t = 1;", "}");
        Rope without = rope("void f() {", "    s = 1;", "    t = 1;", "}");

        assertEquals(describe(regionsOf(without)), describe(regionsOf(withBrace)));
    }

    /** Tabs and spaces measure the same, so a tab-indented file folds identically. */
    @Test
    public void tabsIndentAsFarAsTheTabSize() {
        Rope tabbed = Rope.of("void f() {" + NL + "\ta();" + NL + "\tb();" + NL + "}");
        assertEquals("0-3", describe(IndentRangeProvider.plain().compute(tabbed, 4)));
    }

    // ---------------------------------------------------------------------------------------------------
    // findRange -- the query the gutter and every command go through
    // ---------------------------------------------------------------------------------------------------

    /**
     * <b>The innermost region containing a row is found even when the row sits after a nested block.</b>
     *
     * <p>This is what the parent walk in {@code findRange} is for. The binary search lands on the last
     * region that <em>starts</em> at or before the row — which for row 4 below is the inner block, already
     * ended. Returning nothing at that point is the obvious shortcut and reports "not foldable" for every
     * row following a nested block, which is most of a real file.</p>
     */
    @Test
    public void aRowAfterANestedBlockStillFindsItsParent() {
        Rope document = rope(
                "class A {",       // 0
                "    void f() {",  // 1
                "        a();",    // 2
                "    }",           // 3
                "    int x;",      // 4  <- after the inner block, still inside the outer one
                "}");              // 5

        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);

        FoldingRegions.Region at4 = model.getRegionAtLine(4);
        assertNotNull("row 4 is inside the class body", at4);
        assertEquals(0, at4.startLineNumber());

        FoldingRegions.Region at2 = model.getRegionAtLine(2);
        assertNotNull(at2);
        assertEquals("and row 2 finds the INNER block, not the class", 1, at2.startLineNumber());
    }

    /** A row outside every region reports nothing rather than the nearest one. */
    @Test
    public void aRowOutsideEveryRegionFindsNothing() {
        FoldingModel model = new FoldingModel();
        model.update(rope("a();", "void f() {", "    x();", "    y();", "}"), IndentRangeProvider.plain(), 4);

        assertNull(model.getRegionAtLine(0));
        assertNotNull(model.getRegionAtLine(2));
    }

    // ---------------------------------------------------------------------------------------------------
    // Hidden rows
    // ---------------------------------------------------------------------------------------------------

    private static FoldingModel folded(Rope document, int... rowsToCollapse) {
        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);
        for (int row : rowsToCollapse) {
            FoldingRegions.Region region = model.getRegionStartingAt(row);
            assertNotNull("no region starts at row " + row, region);
            region.setCollapsed(true);
        }
        return model;
    }

    /**
     * <b>The first row of a collapsed region stays visible.</b>
     *
     * <p>{@code getStartLineNumber(i) + 1} in the original, and the single most load-bearing {@code +1} in
     * the feature: that row is what shows something is folded there and is the only thing left to click to
     * reopen it. Hiding it makes a collapsed block unreachable — the rows are gone and so is the handle.</p>
     */
    @Test
    public void theHeaderOfACollapsedRegionIsNotHidden() {
        FoldingModel model = folded(rope("void f() {", "    a();", "    b();", "}"), 0);

        List<FoldingModel.RowRange> hidden = model.hiddenRows();
        assertEquals(1, hidden.size());
        assertEquals("hiding starts BELOW the header", 1, hidden.get(0).startRow());
        assertEquals("and runs through the closing row", 3, hidden.get(0).endRow());
        assertFalse("the header itself is visible", hidden.get(0).contains(0));
    }

    /**
     * <b>A collapsed region inside an already-collapsed one contributes nothing.</b>
     *
     * <p>Its rows are already gone. Emitting them again produces overlapping ranges, and the visibility
     * pass would then have to reconcile two claims about the same row.</p>
     */
    @Test
    public void aCollapsedRegionInsideACollapsedOneAddsNoRange() {
        Rope document = rope(
                "class A {",       // 0
                "    void f() {",  // 1
                "        a();",    // 2
                "        b();",    // 3
                "    }",           // 4
                "}");              // 5

        FoldingModel model = folded(document, 1, 0); // inner first, then the outer one over it

        List<FoldingModel.RowRange> hidden = model.hiddenRows();
        assertEquals("one range, not two", 1, hidden.size());
        assertEquals(1, hidden.get(0).startRow());
        assertEquals(5, hidden.get(0).endRow());
    }

    /** Two sibling blocks folded independently give two ranges. */
    @Test
    public void siblingBlocksGiveSeparateRanges() {
        Rope document = rope(
                "void f() {", "    a();", "    b();", "}",   // 0-3
                "void g() {", "    c();", "    d();", "}");  // 4-7

        FoldingModel model = folded(document, 0, 4);
        List<FoldingModel.RowRange> hidden = model.hiddenRows();

        assertEquals(2, hidden.size());
        assertEquals(1, hidden.get(0).startRow());
        assertEquals(3, hidden.get(0).endRow());
        assertEquals(5, hidden.get(1).startRow());
        assertEquals(7, hidden.get(1).endRow());
    }

    // ---------------------------------------------------------------------------------------------------
    // The projection index -- where folding becomes visible
    // ---------------------------------------------------------------------------------------------------

    private static ProjectedLines projected(Rope document, FoldingModel model) {
        ProjectedLines lines = new ProjectedLines(LineBreaksComputer.none());
        lines.rebuild(document);
        List<FoldingModel.RowRange> hidden = model.hiddenRows();
        int[][] ranges = new int[hidden.size()][];
        for (int i = 0; i < hidden.size(); i++) {
            ranges[i] = new int[] { hidden.get(i).startRow(), hidden.get(i).endRow() };
        }
        lines.setHiddenAreas(ranges);
        return lines;
    }

    /** <b>A hidden row occupies zero view lines</b> — the whole of folding, in the view model. */
    @Test
    public void foldingRemovesViewLines() {
        Rope document = rope("void f() {", "    a();", "    b();", "}");
        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);

        ProjectedLines open = projected(document, model);
        assertEquals(4, open.viewLineCount());

        model.getRegionStartingAt(0).setCollapsed(true);
        ProjectedLines closed = projected(document, model);
        assertEquals("body and closing row hidden", 1, closed.viewLineCount());
    }

    /**
     * <b>A view line never resolves to a hidden row.</b>
     *
     * <p>The reason {@code modelAt} cannot use {@code Arrays.binarySearch}. A hidden row makes two adjacent
     * prefix-sum entries equal, and the JDK's search over duplicates may return either — landing on a row
     * that is not on screen. Every view line below must name a row that is genuinely visible.</p>
     */
    @Test
    public void noViewLineResolvesToAHiddenRow() {
        Rope document = rope(
                "void f() {",  // 0
                "    a();",    // 1  hidden
                "    b();",    // 2  hidden
                "}",           // 3
                "tail();");    // 4

        FoldingModel model = folded(document, 0);
        ProjectedLines lines = projected(document, model);

        assertEquals(2, lines.viewLineCount());
        int[] expected = { 0, 4 };
        for (int viewLine = 0; viewLine < lines.viewLineCount(); viewLine++) {
            assertEquals("view line " + viewLine, expected[viewLine], lines.modelAt(viewLine).row());
        }
    }

    /**
     * <b>An offset inside a folded block resolves to the nearest visible row above it.</b>
     *
     * <p>VS Code's {@code convertModelPositionToViewPosition}, which walks off a hidden row rather than
     * trusting its recorded first view line. That recorded line is where the fold RESUMES — one past the
     * last real one — so without the walk an offset inside a collapsed block resolves past the end of the
     * view entirely. Reachable from search, from Ctrl+End, and from any caret already there when the block
     * closed.</p>
     */
    @Test
    public void anOffsetInsideAFoldResolvesToTheNearestVisibleRow() {
        Rope document = rope("void f() {", "    a();", "    b();", "}", "tail();");
        FoldingModel model = folded(document, 0);
        ProjectedLines lines = projected(document, model);
        assertEquals(2, lines.viewLineCount());

        int insideTheFold = document.lineStartOffset(2) + 4;
        ProjectedLines.ViewPosition view = lines.toViewPosition(document, insideTheFold,
                com.crystalgui.text.wrap.LineProjection.Affinity.NONE);

        assertTrue("must land on a real view line", view.viewLine() >= 0);
        assertTrue("and within the visible ones", view.viewLine() < lines.viewLineCount());
        assertEquals("specifically the header, the nearest visible row above", 0, view.viewLine());
    }

    /**
     * <b>Everything hidden is refused.</b>
     *
     * <p>VS Code throws here. A document with no visible row has nowhere to put the caret and no fold
     * indicator to reopen anything, so it is unrecoverable from the user's side — but taking down the frame
     * over a view-state bug is worse than showing one row.</p>
     */
    @Test
    public void theDocumentNeverGoesFullyBlank() {
        Rope document = rope("a", "b", "c");
        ProjectedLines lines = new ProjectedLines(LineBreaksComputer.none());
        lines.rebuild(document);

        lines.setHiddenAreas(new int[][] { { 0, 2 } });

        assertTrue("at least one row survives", lines.viewLineCount() >= 1);
        assertTrue(lines.isVisible(0));
    }

    /**
     * <b>A reprojection at the same row count keeps visibility.</b>
     *
     * <p>What a resize is. Isolated here rather than in the widget, where it cannot be observed: the editor
     * reapplies its hidden rows every frame, so the picture is right either way and only a caller reading
     * the index BETWEEN a reprojection and the next frame can tell the difference.</p>
     */
    @Test
    public void reprojectingAtTheSameRowCountKeepsVisibility() {
        Rope document = rope("void f() {", "    a();", "    b();", "}");
        ProjectedLines lines = new ProjectedLines(LineBreaksComputer.none());
        lines.rebuild(document);
        lines.setHiddenAreas(new int[][] { { 1, 2 } });
        assertEquals(2, lines.viewLineCount());

        lines.rebuild(document); // a resize

        assertEquals("still folded", 2, lines.viewLineCount());
        assertFalse(lines.isVisible(1));
    }

    /** An edit that changes the row count resets it, and the folding model reapplies from scratch. */
    @Test
    public void reprojectingAtADifferentRowCountResetsVisibility() {
        Rope document = rope("void f() {", "    a();", "    b();", "}");
        ProjectedLines lines = new ProjectedLines(LineBreaksComputer.none());
        lines.rebuild(document);
        lines.setHiddenAreas(new int[][] { { 1, 2 } });

        lines.rebuild(rope("void f() {", "    a();", "    b();", "}", "extra();"));

        assertEquals("every row visible again", 5, lines.viewLineCount());
    }

    /** Unfolding is expressed by a row no longer being in the hidden set — there is no "unhide" call. */
    @Test
    public void unfoldingRestoresEveryRow() {
        Rope document = rope("void f() {", "    a();", "    b();", "}");
        FoldingModel model = folded(document, 0);
        ProjectedLines lines = projected(document, model);
        assertEquals(1, lines.viewLineCount());

        model.getRegionStartingAt(0).setCollapsed(false);
        assertEquals(4, projected(document, model).viewLineCount());
    }

    // ---------------------------------------------------------------------------------------------------
    // Collapse state across an edit
    // ---------------------------------------------------------------------------------------------------

    /** A fold elsewhere in the file survives an edit that does not touch it. */
    @Test
    public void aFoldSurvivesAnUnrelatedEdit() {
        Rope before = rope("void f() {", "    a();", "    b();", "}", "int x;");
        FoldingModel model = folded(before, 0);
        assertEquals(1, model.hiddenRows().size());

        Rope after = rope("void f() {", "    a();", "    b();", "}", "int x;", "int y;");
        model.update(after, IndentRangeProvider.plain(), 4);

        assertEquals("still folded", 1, model.hiddenRows().size());
        assertEquals(1, model.hiddenRows().get(0).startRow());
    }

    /**
     * <b>A fold whose block changed size reopens rather than hiding rows nobody has seen.</b>
     *
     * <p>The deliberate conservative half of carrying collapse state across a recompute. Reinstating a fold
     * over content that grew would hide text the user just added.</p>
     */
    @Test
    public void aFoldWhoseBlockGrewReopens() {
        Rope before = rope("void f() {", "    a();", "    b();", "}");
        FoldingModel model = folded(before, 0);
        assertTrue(model.hasCollapsedRegions());

        Rope after = rope("void f() {", "    a();", "    b();", "    c();", "}");
        model.update(after, IndentRangeProvider.plain(), 4);

        assertFalse("the block is no longer the one that was folded", model.hasCollapsedRegions());
    }

    // ---------------------------------------------------------------------------------------------------
    // The command-level operations
    // ---------------------------------------------------------------------------------------------------

    /** Folding at a level closes every block of that depth and leaves the others alone. */
    @Test
    public void foldingAtLevelClosesOnlyThatDepth() {
        Rope document = rope(
                "class A {",       // 0
                "    void f() {",  // 1
                "        a();",    // 2
                "        b();",    // 3
                "    }",           // 4
                "}");              // 5

        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);

        model.setCollapseStateAtLevel(2, true);
        assertTrue("the inner block closed", model.getRegionStartingAt(1).isCollapsed());
        assertFalse("the outer one did not", model.getRegionStartingAt(0).isCollapsed());
    }

    /** A caret inside a block blocks it from being folded by a level command. */
    @Test
    public void aBlockedRowIsNotFoldedByLevel() {
        Rope document = rope("class A {", "    void f() {", "        a();", "        b();", "    }", "}");
        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);

        model.setCollapseStateAtLevel(2, true, 2);
        assertFalse("the caret is in it", model.getRegionStartingAt(1).isCollapsed());
    }

    /**
     * <b>Repeated "fold" steps outwards instead of doing nothing.</b>
     *
     * <p>{@code setCollapseStateUp} picks the innermost region that is not <em>already</em> in the target
     * state, so pressing the key twice folds the block and then its parent. Picking the innermost region
     * unconditionally makes the second press a no-op, which reads as the key having stopped working.</p>
     */
    @Test
    public void foldingRepeatedlyWalksOutwards() {
        Rope document = rope("class A {", "    void f() {", "        a();", "        b();", "    }", "}");
        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);

        model.setCollapseStateUp(true, 2);
        assertTrue(model.getRegionStartingAt(1).isCollapsed());
        assertFalse(model.getRegionStartingAt(0).isCollapsed());

        model.setCollapseStateUp(true, 2);
        assertTrue("the second press reached the parent", model.getRegionStartingAt(0).isCollapsed());
    }

    /** Fold-all and unfold-all reach every region. */
    @Test
    public void foldAllAndUnfoldAllReachEverything() {
        Rope document = rope("class A {", "    void f() {", "        a();", "        b();", "    }", "}");
        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);

        model.setCollapseStateForAll(true);
        assertTrue(model.getRegionStartingAt(0).isCollapsed());
        assertTrue(model.getRegionStartingAt(1).isCollapsed());

        model.setCollapseStateForAll(false);
        assertFalse(model.hasCollapsedRegions());
    }

    /**
     * <b>Fold-all leaves open anything that would hide most of the file.</b>
     *
     * <p>Collapsing the region that holds the file's whole body leaves literally nothing to look at — the
     * document becomes a line or two and the editor reads as though it emptied itself. IntelliJ keeps the
     * class open and collapses the methods inside it, which is what makes Collapse All useful rather than
     * destructive.</p>
     *
     * <p><b>The test is the span, not the nesting depth.</b> An earlier version skipped the <em>sole</em>
     * top-level region, which sounds equivalent and is not: this file has a licence-style block comment
     * above the class, so there are two top-level regions, the guard never fired, and the class collapsed
     * anyway. That is the shape the bug was reported on.</p>
     */
    @Test
    public void foldAllLeavesOpenWhatWouldHideMostOfTheFile() {
        Rope document = rope(
                "/* a block comment",   // 0
                "   spanning lines */", // 1
                "class A {",            // 2
                "    void f() {",       // 3
                "        a();",         // 4
                "    }",                // 5
                "    void g() {",       // 6
                "        b();",         // 7
                "    }",                // 8
                "}");                   // 9

        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);
        model.collapseAllKeepingDocumentVisible(document.lineCount());

        assertFalse("the class holds the file, so it stays open",
                model.getRegionStartingAt(2).isCollapsed());
        assertTrue("its methods collapse", model.getRegionStartingAt(3).isCollapsed());
        assertTrue(model.getRegionStartingAt(6).isCollapsed());
    }

    /**
     * <b>Several ordinary top-level regions all collapse.</b>
     *
     * <p>None of them hides the majority of the file, so nothing is spared. A rule that skipped top-level
     * regions by depth would make Collapse All do nothing at all in this very common shape.</p>
     */
    @Test
    public void severalTopLevelRegionsAllCollapse() {
        Rope document = rope(
                "void f() {", "    a();", "}",   // 0-2
                "void g() {", "    b();", "}",   // 3-5
                "void h() {", "    c();", "}");  // 6-8

        FoldingModel model = new FoldingModel();
        model.update(document, IndentRangeProvider.plain(), 4);
        model.collapseAllKeepingDocumentVisible(document.lineCount());

        assertTrue(model.getRegionStartingAt(0).isCollapsed());
        assertTrue(model.getRegionStartingAt(3).isCollapsed());
        assertTrue(model.getRegionStartingAt(6).isCollapsed());
    }

    /** An empty provider means nothing folds, and nothing throws. */
    @Test
    public void aDocumentWithNoRegionsIsHarmless() {
        FoldingModel model = new FoldingModel();
        model.update(rope("a", "b", "c"), FoldingRangeProvider.none(), 4);

        assertEquals(0, model.regions().length());
        assertTrue(model.hiddenRows().isEmpty());
        assertNull(model.getRegionAtLine(1));
        model.setCollapseStateForAll(true); // must not throw
    }
}
