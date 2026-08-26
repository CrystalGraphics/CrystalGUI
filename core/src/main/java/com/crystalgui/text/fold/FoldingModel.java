package com.crystalgui.text.fold;

import com.crystalgui.text.Rope;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * A document's folding state — which regions exist, which are collapsed, and therefore which rows are
 * hidden.
 *
 * <p><b>Ported from VS Code's {@code FoldingModel}</b>
 * ({@code src/vs/editor/contrib/folding/browser/foldingModel.ts}) together with the hidden-range
 * derivation from {@code HiddenRangeModel.updateHiddenRanges}
 * ({@code hiddenRangeModel.ts}), microsoft/vscode, MIT licence.</p>
 *
 * <h3>This is document state, and it is deliberately not undoable</h3>
 * <p>Folding is <b>view state</b> by the same boundary the engine already draws for undo: it is how you
 * are looking at the document, not what the document says. Reloading a file gives back its text, not which
 * blocks you had closed. So collapsing goes nowhere near {@code UndoStack} and Ctrl+Z will never unfold —
 * which is what VS Code, IntelliJ and every other editor do, and the same rule that keeps scroll position
 * and selection out of the history.</p>
 *
 * <h3>Regions are recomputed wholesale, collapse state is carried across</h3>
 * <p>An edit invalidates every region below it, so {@link #update} recomputes the whole set and then
 * reinstates collapse state onto the new regions by start row. VS Code does this with tracked decorations
 * that move with the text, which lets it survive a region's rows shifting; this port matches on start row
 * and length, which survives everything except an edit that moves a collapsed block's first line. The
 * consequence of the reduction is a block that silently reopens, never one that hides the wrong rows.</p>
 */
public final class FoldingModel {

    /** An inclusive run of document rows. */
    public record RowRange(int startRow, int endRow) {
        public boolean contains(int row) {
            return row >= startRow && row <= endRow;
        }
    }

    private FoldingRegions regions = FoldingRegions.empty();

    public FoldingRegions regions() {
        return regions;
    }

    /**
     * Recomputes every region and carries collapse state onto the new set.
     *
     * @return whether anything a viewer can see changed — the set of hidden rows
     */
    public boolean update(Rope document, FoldingRangeProvider provider, int tabSize) {
        return install(provider.compute(document, tabSize));
    }

    /**
     * Adopts a freshly computed region set — the cheap half of {@link #update}.
     *
     * <p>Split out so the expensive half can run on a worker: {@code provider.compute} is a whole-document
     * pass measured at <b>25.7ms</b> on a 2,020-line class, while this is a merge over two sorted lists
     * and a comparison. Only a provider that says {@link FoldingRangeProvider#computesOffThread} may be
     * split this way; this half always runs where the model is read.</p>
     *
     * @return whether the set of hidden rows changed, so a caller can drop what it has realised
     */
    public boolean install(FoldingRegions next) {
        List<RowRange> before = hiddenRows();
        carryCollapseState(regions, next);
        regions = next;
        return !before.equals(hiddenRows());
    }

    /**
     * Reinstates collapse state from the previous region set onto a freshly computed one.
     *
     * <p>Both sets are sorted by start row, so this is a merge rather than a lookup per region.</p>
     *
     * <p>A region <b>keeps its collapse only if its extent is unchanged</b>. VS Code's rule, and the reason
     * is that a block whose end moved is a block whose content changed: reinstating the fold there would
     * hide rows the user has never seen, which is the one failure mode that makes folding feel unsafe.</p>
     */
    private static void carryCollapseState(FoldingRegions previous, FoldingRegions next) {
        int i = 0;
        int j = 0;
        while (i < previous.length() && j < next.length()) {
            int previousStart = previous.getStartLineNumber(i);
            int nextStart = next.getStartLineNumber(j);
            if (previousStart < nextStart) {
                i++;
            } else if (previousStart > nextStart) {
                j++;
            } else {
                if (previous.isCollapsed(i)
                        && previous.getEndLineNumber(i) == next.getEndLineNumber(j)) {
                    next.setCollapsed(j, true);
                }
                i++;
                j++;
            }
        }
    }

    /**
     * The rows hidden by the current collapse state.
     *
     * <p>Port of {@code HiddenRangeModel.updateHiddenRanges}. Two rules carry the whole behaviour:</p>
     * <ul>
     *   <li><b>The first row of a collapsed region stays visible</b> ({@code startRow + 1}). It is the row
     *       showing that something is folded there; hiding it would make a collapsed block unreachable.</li>
     *   <li><b>A collapsed region inside an already-collapsed one contributes nothing.</b> Its rows are
     *       already gone, and emitting them again would produce overlapping ranges that the view's
     *       visibility pass would have to reconcile.</li>
     * </ul>
     *
     * <p>Regions are sorted by start row and strictly nested, which is what lets one pass with a single
     * "last collapsed" pair do the containment test instead of a stack.</p>
     */
    public List<RowRange> hiddenRows() {
        List<RowRange> hidden = new ArrayList<>();
        int lastCollapsedStart = Integer.MAX_VALUE;
        int lastCollapsedEnd = -1;

        for (int i = 0; i < regions.length(); i++) {
            if (!regions.isCollapsed(i)) continue;

            int startLineNumber = regions.getStartLineNumber(i) + 1; // the first line is not hidden
            int endLineNumber = regions.getEndLineNumber(i);
            if (lastCollapsedStart <= startLineNumber && endLineNumber <= lastCollapsedEnd) {
                continue; // ignore ranges contained in collapsed regions
            }
            if (startLineNumber <= endLineNumber) {
                hidden.add(new RowRange(startLineNumber, endLineNumber));
            }
            lastCollapsedStart = startLineNumber;
            lastCollapsedEnd = endLineNumber;
        }
        return hidden;
    }

    /** Whether any region is collapsed at all — the fast path for a document nobody has folded. */
    public boolean hasCollapsedRegions() {
        for (int i = 0; i < regions.length(); i++) {
            if (regions.isCollapsed(i)) return true;
        }
        return false;
    }

    /** The innermost region containing {@code row}, or {@code null}. */
    public FoldingRegions.Region getRegionAtLine(int row) {
        int index = regions.findRange(row);
        return index >= 0 ? regions.toRegion(index) : null;
    }

    /** The region whose <b>first row</b> is {@code row} — what a gutter chevron on that row toggles. */
    public FoldingRegions.Region getRegionStartingAt(int row) {
        int index = regions.findRange(row);
        while (index >= 0) {
            if (regions.getStartLineNumber(index) == row) return regions.toRegion(index);
            index = regions.getParentIndex(index);
        }
        return null;
    }

    /**
     * Every region containing {@code row}, innermost first.
     *
     * <p>{@code level} counts outwards from 1, so a filter of {@code level <= 2} means "this block and its
     * parent".</p>
     */
    public List<FoldingRegions.Region> getAllRegionsAtLine(int row,
                                                           BiPredicate<FoldingRegions.Region, Integer> filter) {
        List<FoldingRegions.Region> result = new ArrayList<>();
        int index = regions.findRange(row);
        int level = 1;
        while (index >= 0) {
            FoldingRegions.Region current = regions.toRegion(index);
            if (filter == null || filter.test(current, level)) result.add(current);
            level++;
            index = current.parentIndex();
        }
        return result;
    }

    /**
     * Every region inside {@code region}, or the whole document when it is {@code null}.
     *
     * <p>The level-aware form keeps a stack of enclosing regions so {@code level} is the nesting depth
     * <em>relative to the starting point</em>, which is what "fold level 2" means.</p>
     */
    public List<FoldingRegions.Region> getRegionsInside(FoldingRegions.Region region,
                                                        BiPredicate<FoldingRegions.Region, Integer> filter) {
        List<FoldingRegions.Region> result = new ArrayList<>();
        int index = region != null ? region.regionIndex() + 1 : 0;
        int endLineNumber = region != null ? region.endLineNumber() : Integer.MAX_VALUE;

        List<FoldingRegions.Region> levelStack = new ArrayList<>();
        for (int i = index, len = regions.length(); i < len; i++) {
            FoldingRegions.Region current = regions.toRegion(i);
            if (regions.getStartLineNumber(i) >= endLineNumber) break;
            while (!levelStack.isEmpty() && !current.containedBy(levelStack.get(levelStack.size() - 1))) {
                levelStack.remove(levelStack.size() - 1);
            }
            levelStack.add(current);
            if (filter == null || filter.test(current, levelStack.size())) result.add(current);
        }
        return result;
    }

    /** Flips each region's collapse state. */
    public void toggleCollapseState(List<FoldingRegions.Region> toToggle) {
        for (FoldingRegions.Region region : toToggle) {
            region.setCollapsed(!region.isCollapsed());
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The command-level operations. VS Code has these as free functions beside the model; Java puts them
    // here, but they are that file's and are kept as thin compositions of the queries above rather than
    // reaching into the region arrays themselves.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Collapse or expand the region at each row, and optionally what is inside it.
     *
     * @param levels 1 to affect only the region itself, {@link Integer#MAX_VALUE} for everything within
     */
    public void toggleCollapseState(int levels, int... rows) {
        List<FoldingRegions.Region> toToggle = new ArrayList<>();
        for (int row : rows) {
            FoldingRegions.Region region = getRegionAtLine(row);
            if (region == null) continue;
            boolean doCollapse = !region.isCollapsed();
            toToggle.add(region);
            if (levels > 1) {
                toToggle.addAll(getRegionsInside(region,
                        (r, level) -> r.isCollapsed() != doCollapse && level < levels));
            }
        }
        toggleCollapseState(toToggle);
    }

    /** Collapse or expand the region at each row and its ancestors, out to {@code levels} deep. */
    public void setCollapseStateLevelsUp(boolean doCollapse, int levels, int... rows) {
        List<FoldingRegions.Region> toToggle = new ArrayList<>();
        for (int row : rows) {
            toToggle.addAll(getAllRegionsAtLine(row,
                    (region, level) -> region.isCollapsed() != doCollapse && level <= levels));
        }
        toggleCollapseState(toToggle);
    }

    /**
     * Collapse or expand the region at each row, <b>stepping outwards</b> when it is already in that state.
     *
     * <p>This is what makes repeated Ctrl+Shift+[ walk out of a nesting instead of doing nothing on the
     * second press: the innermost region that is not already collapsed is the one that moves.</p>
     */
    public void setCollapseStateUp(boolean doCollapse, int... rows) {
        List<FoldingRegions.Region> toToggle = new ArrayList<>();
        for (int row : rows) {
            List<FoldingRegions.Region> found =
                    getAllRegionsAtLine(row, (region, level) -> region.isCollapsed() != doCollapse);
            if (!found.isEmpty()) toToggle.add(found.get(0));
        }
        toggleCollapseState(toToggle);
    }

    /**
     * Collapse or expand every region at exactly {@code foldLevel}, except those containing a blocked row.
     *
     * <p>The blocked rows are the carets: "fold level 2" must not close the block you are editing in.</p>
     */
    public void setCollapseStateAtLevel(int foldLevel, boolean doCollapse, int... blockedRows) {
        List<FoldingRegions.Region> toToggle = getRegionsInside(null, (region, level) -> {
            if (level != foldLevel || region.isCollapsed() == doCollapse) return false;
            for (int row : blockedRows) {
                if (region.containsLine(row)) return false;
            }
            return true;
        });
        toggleCollapseState(toToggle);
    }

    /** Collapse or expand every region in the document, except those around a blocked row. */
    public void setCollapseStateForRest(boolean doCollapse, int... blockedRows) {
        List<FoldingRegions.Region> filtered = new ArrayList<>();
        for (int row : blockedRows) {
            List<FoldingRegions.Region> found = getAllRegionsAtLine(row, null);
            if (!found.isEmpty()) filtered.add(found.get(0));
        }
        Predicate<FoldingRegions.Region> keep = region -> {
            for (FoldingRegions.Region other : filtered) {
                if (other.containedBy(region) || region.containedBy(other)) return false;
            }
            return region.isCollapsed() != doCollapse;
        };
        toggleCollapseState(getRegionsInside(null, (region, level) -> keep.test(region)));
    }

    /**
     * Collapses everything <b>except a sole outermost region</b>, which would hide the whole document.
     *
     * <p>What IntelliJ's Collapse All does, and the reason is that folding the one region that contains
     * every other one leaves literally nothing to look at — the file becomes a single line and the editor
     * reads as though it emptied itself. In a Java file that region is the class body, so this leaves the
     * declaration and its members visible with each method collapsed, which is the familiar result.</p>
     *
     * <p><b>Only when it is the sole top-level region.</b> A file of several top-level functions has no
     * single container, nothing is hidden wholesale by collapsing them, and folding every one of them is
     * exactly what was asked for — so the carve-out does not apply and must not, or Collapse All would do
     * nothing at all in that very common shape.</p>
     */
    public void collapseAllKeepingDocumentVisible(int rowCount) {
        for (int i = 0; i < regions.length(); i++) {
            regions.setCollapsed(i, !hidesMostOfTheDocument(i, rowCount));
        }
    }

    /**
     * Whether collapsing region {@code i} would take the majority of the document with it.
     *
     * <p>The test is the SPAN, not the nesting depth. An earlier attempt skipped the sole top-level region,
     * which sounds equivalent and is not: a file whose class is preceded by a licence block comment has
     * two top-level regions, so the guard never fired and the class collapsed anyway. Counting containers
     * asks about structure; the thing that actually matters is how much disappears.</p>
     */
    private boolean hidesMostOfTheDocument(int index, int rowCount) {
        if (rowCount <= 0) return false;
        // TOP-LEVEL as well as large, and both halves are load-bearing.
        //
        // Span alone spares any big region, and in a short file a method covers most of it -- Collapse All
        // then collapsed nothing at all. Depth alone spares the sole top-level region, which misses the
        // shape the bug was reported on: a licence block comment above a class gives two top-level regions,
        // so the guard never fired and the class collapsed anyway. Only a region that both contains no
        // other AND holds the bulk of the file is the one whose collapse leaves nothing to look at.
        if (regions.getParentIndex(index) != -1) return false;
        int span = regions.getEndLineNumber(index) - regions.getStartLineNumber(index) + 1;
        return span * 2 >= rowCount;
    }

    /** Collapse or expand everything, unconditionally. */
    public void setCollapseStateForAll(boolean doCollapse) {
        List<FoldingRegions.Region> toToggle = new ArrayList<>();
        for (int i = 0; i < regions.length(); i++) {
            if (regions.isCollapsed(i) != doCollapse) toToggle.add(regions.toRegion(i));
        }
        toggleCollapseState(toToggle);
    }
}
