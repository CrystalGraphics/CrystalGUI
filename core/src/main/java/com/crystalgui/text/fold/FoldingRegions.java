package com.crystalgui.text.fold;

import java.util.BitSet;

/**
 * Every foldable region in a document, stored as parallel arrays rather than objects.
 *
 * <p><b>Ported from VS Code's {@code FoldingRegions}</b> —
 * {@code src/vs/editor/contrib/folding/browser/foldingRanges.ts}, microsoft/vscode, MIT licence. The
 * parent-index bit packing, {@link #findIndex}, {@link #findRange} and the collapse-state bookkeeping are
 * that file's, kept operation-for-operation.</p>
 *
 * <h3>Why arrays and not a tree</h3>
 * <p>Folding regions are produced <b>already sorted by start row</b> and are strictly nested — one region
 * either contains another entirely or is disjoint from it, never partially overlapping. That is exactly
 * the shape a flat sorted array indexes better than a tree: the parent of a region is found by a binary
 * search plus a walk, and a whole document's regions cost two {@code int[]}s. A tree would allocate a node
 * per region for a structure that is rebuilt wholesale on every edit.</p>
 *
 * <h3>The parent index lives in the top byte of each row number</h3>
 * <p>{@link #ensureParentIndices} packs a region's parent index into the <b>unused high bits of the start
 * and end row</b> — the low byte into {@code startIndexes}, the high byte into {@code endIndexes} — so a
 * 16-bit parent index costs no third array. A row number is masked to {@link #MAX_LINE_NUMBER} (24 bits)
 * on the way out, which caps a foldable document at ~16.7M rows and the region count at
 * {@link #MAX_FOLDING_REGIONS}.</p>
 *
 * <p><b>This is bit-exact in Java.</b> TypeScript's arrays are {@code Uint32Array} and Java's are signed
 * {@code int[]}, so a packed value with the top bit set reads back <em>negative</em> here and positive
 * there. It does not matter: every read goes through {@code & MAX_LINE_NUMBER} or {@code >>>}, and Java's
 * {@code >>>} is precisely TypeScript's. Sign is never consulted. Using {@code long[]} to "fix" the
 * signedness would break the {@code >>> 24} arithmetic instead.</p>
 *
 * <h3>Rows are 0-based here</h3>
 * <p>VS Code's line numbers start at 1, this engine's rows start at 0 — the same shift
 * {@code LineProjection} documents. Every {@code +1}/{@code -1} in the original that only existed to cross
 * that boundary is absent by design rather than by oversight. The one place it is load-bearing is
 * {@link FoldingModel#hiddenRows()}, where the start row of a collapsed region stays visible.</p>
 */
public final class FoldingRegions {

    /** Where a region came from, which decides who wins when two sets of regions are merged. */
    public enum FoldSource {
        /** Computed by a {@link FoldingRangeProvider} — the ordinary case. */
        PROVIDER,
        /** Created by the user selecting lines and folding them; survives a recompute. */
        USER_DEFINED,
        /** A provider region that was collapsed, went away on a recompute, and was reinstated. */
        RECOVERED
    }

    public static final int MAX_FOLDING_REGIONS = 0xFFFF;
    public static final int MAX_LINE_NUMBER = 0xFFFFFF;

    private static final int MASK_INDENT = 0xFF000000;

    private final int[] startIndexes;
    private final int[] endIndexes;

    // VS Code hand-rolls a BitField over a Uint32Array here. BitSet is the same structure with the same
    // word-at-a-time layout, so the port is the standard library rather than a copy of it.
    private final BitSet collapseStates;
    private final BitSet userDefinedStates;
    private final BitSet recoveredStates;

    private boolean parentsComputed;

    public FoldingRegions(int[] startIndexes, int[] endIndexes) {
        if (startIndexes.length != endIndexes.length || startIndexes.length > MAX_FOLDING_REGIONS) {
            throw new IllegalArgumentException("invalid startIndexes or endIndexes size");
        }
        this.startIndexes = startIndexes;
        this.endIndexes = endIndexes;
        this.collapseStates = new BitSet(startIndexes.length);
        this.userDefinedStates = new BitSet(startIndexes.length);
        this.recoveredStates = new BitSet(startIndexes.length);
        this.parentsComputed = false;
    }

    /** The empty set — what a document with no foldable structure has, and the safe initial value. */
    public static FoldingRegions empty() {
        return new FoldingRegions(new int[0], new int[0]);
    }

    /**
     * Packs each region's parent index into the spare high bits of its row numbers.
     *
     * <p>Computed once, lazily, because a region set is often built and thrown away without anyone asking
     * for a parent — and because it <b>mutates the arrays in place</b>, which is only safe exactly once.</p>
     */
    private void ensureParentIndices() {
        if (parentsComputed) return;
        parentsComputed = true;

        int[] parentIndexes = new int[startIndexes.length];
        int stackSize = 0;

        for (int i = 0, len = startIndexes.length; i < len; i++) {
            int startLineNumber = startIndexes[i];
            int endLineNumber = endIndexes[i];
            if (startLineNumber > MAX_LINE_NUMBER || endLineNumber > MAX_LINE_NUMBER) {
                throw new IllegalStateException(
                        "startLineNumber or endLineNumber must not exceed " + MAX_LINE_NUMBER);
            }
            // Pop until the top of the stack is a region that actually contains this one. Because the
            // regions are sorted by start row and strictly nested, whatever survives is the parent.
            while (stackSize > 0 && !isInsideLast(parentIndexes[stackSize - 1], startLineNumber, endLineNumber)) {
                stackSize--;
            }
            int parentIndex = stackSize > 0 ? parentIndexes[stackSize - 1] : -1;
            parentIndexes[stackSize++] = i;
            startIndexes[i] = startLineNumber + ((parentIndex & 0xFF) << 24);
            endIndexes[i] = endLineNumber + ((parentIndex & 0xFF00) << 16);
        }
    }

    private boolean isInsideLast(int index, int startLineNumber, int endLineNumber) {
        return getStartLineNumber(index) <= startLineNumber && getEndLineNumber(index) >= endLineNumber;
    }

    public int length() {
        return startIndexes.length;
    }

    public int getStartLineNumber(int index) {
        return startIndexes[index] & MAX_LINE_NUMBER;
    }

    public int getEndLineNumber(int index) {
        return endIndexes[index] & MAX_LINE_NUMBER;
    }

    public boolean isCollapsed(int index) {
        return collapseStates.get(index);
    }

    public void setCollapsed(int index, boolean newState) {
        collapseStates.set(index, newState);
    }

    public FoldSource getSource(int index) {
        if (userDefinedStates.get(index)) return FoldSource.USER_DEFINED;
        if (recoveredStates.get(index)) return FoldSource.RECOVERED;
        return FoldSource.PROVIDER;
    }

    public void setSource(int index, FoldSource source) {
        userDefinedStates.set(index, source == FoldSource.USER_DEFINED);
        recoveredStates.set(index, source == FoldSource.RECOVERED);
    }

    /**
     * The index of the region containing this one, or {@code -1} at the top level.
     *
     * <p>The sentinel arrives for free from the packing: a parent of {@code -1} stores {@code 0xFF} and
     * {@code 0xFF00}, which recombine to {@link #MAX_FOLDING_REGIONS} — a value no real index can take,
     * since the constructor refuses that many regions.</p>
     */
    public int getParentIndex(int index) {
        ensureParentIndices();
        int parent = ((startIndexes[index] & MASK_INDENT) >>> 24) + ((endIndexes[index] & MASK_INDENT) >>> 16);
        return parent == MAX_FOLDING_REGIONS ? -1 : parent;
    }

    public boolean contains(int index, int line) {
        return getStartLineNumber(index) <= line && getEndLineNumber(index) >= line;
    }

    /** The last region starting at or before {@code line} — not necessarily one that contains it. */
    private int findIndex(int line) {
        int low = 0;
        int high = startIndexes.length;
        if (high == 0) return -1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (line < getStartLineNumber(mid)) high = mid;
            else low = mid + 1;
        }
        return low - 1;
    }

    /**
     * The <b>innermost</b> region containing {@code line}, or {@code -1}.
     *
     * <p>Two steps, and the second is what makes it correct. The binary search finds the last region that
     * <em>starts</em> at or before the line, which is the innermost candidate — but that region may well
     * have ended before the line, in which case the answer is one of its ancestors rather than nothing.
     * Walking the parent chain is what finds it. Returning {@code -1} on the first miss is the obvious
     * shortcut and silently reports "not foldable" for every line that sits after a nested block.</p>
     */
    public int findRange(int line) {
        int index = findIndex(line);
        if (index >= 0) {
            if (getEndLineNumber(index) >= line) return index;
            index = getParentIndex(index);
            while (index != -1) {
                if (contains(index, line)) return index;
                index = getParentIndex(index);
            }
        }
        return -1;
    }

    public Region toRegion(int index) {
        return new Region(this, index);
    }

    /**
     * One region, as an index plus its owner.
     *
     * <p>Deliberately a <b>view</b> and not a copy: collapse state lives in the {@link FoldingRegions} and
     * is mutated through it, so a region handed to a caller keeps reporting the truth after a fold.</p>
     */
    public static final class Region {
        private final FoldingRegions ranges;
        private final int index;

        Region(FoldingRegions ranges, int index) {
            this.ranges = ranges;
            this.index = index;
        }

        public int startLineNumber() {
            return ranges.getStartLineNumber(index);
        }

        public int endLineNumber() {
            return ranges.getEndLineNumber(index);
        }

        public int regionIndex() {
            return index;
        }

        public int parentIndex() {
            return ranges.getParentIndex(index);
        }

        public boolean isCollapsed() {
            return ranges.isCollapsed(index);
        }

        public void setCollapsed(boolean collapsed) {
            ranges.setCollapsed(index, collapsed);
        }

        public boolean containedBy(int outerStart, int outerEnd) {
            return outerStart <= startLineNumber() && outerEnd >= endLineNumber();
        }

        public boolean containedBy(Region outer) {
            return containedBy(outer.startLineNumber(), outer.endLineNumber());
        }

        public boolean containsLine(int lineNumber) {
            return startLineNumber() <= lineNumber && lineNumber <= endLineNumber();
        }

        /**
         * Whether collapsing this region would hide {@code lineNumber}.
         *
         * <p>Strictly narrower than {@link #containsLine}: the region's <b>first row stays visible</b> when
         * it is collapsed — it is the row carrying the fold indicator, and hiding it would make a collapsed
         * region impossible to find, let alone reopen.</p>
         */
        public boolean hidesLine(int lineNumber) {
            return startLineNumber() < lineNumber && lineNumber <= endLineNumber();
        }
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length(); i++) {
            if (i > 0) out.append(", ");
            out.append('[').append(isCollapsed(i) ? '+' : '-').append("] ")
                    .append(getStartLineNumber(i)).append('/').append(getEndLineNumber(i));
        }
        return out.toString();
    }
}
