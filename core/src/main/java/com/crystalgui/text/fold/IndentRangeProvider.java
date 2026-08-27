package com.crystalgui.text.fold;

import com.crystalgui.text.Rope;
import com.crystalgui.text.view.IndentLevels;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foldable regions derived from <b>indentation</b>, which is what an editor folds by when nothing better
 * is available.
 *
 * <p><b>Ported from VS Code's {@code IndentRangeProvider}</b> —
 * {@code src/vs/editor/contrib/folding/browser/indentRangeProvider.ts}, microsoft/vscode, MIT licence.
 * {@link #computeRanges} and {@link RangesCollector} are that file's, kept statement-for-statement.</p>
 *
 * <h3>Why indentation and not brackets</h3>
 * <p>This was the open design question, and Monaco answers it outright: indentation is the <b>default</b>
 * provider, the one every document gets before any language contributes anything. Brackets are never used
 * for folding at all.</p>
 *
 * <p>The reason is that indentation needs no grammar, no tokenizer and no language configuration, so it
 * works on the first frame of an unknown file — and it is <b>immune to the failure that sinks bracket
 * scanning</b>, which is a brace inside a string or a comment. A bracket counter has to be told what a
 * string is before it can be trusted, i.e. it needs the very thing it was supposed to avoid needing.
 * Indentation reads a prefix of whitespace and cannot be fooled by content.</p>
 *
 * <p>It also folds things brackets cannot see: a Markdown section, a YAML block, a run of comment lines, a
 * Python suite. That is why {@link FoldingRangeProvider} exists as a seam — a syntax-aware provider layers
 * <em>over</em> this one for languages that have a grammar, exactly as it does in VS Code, rather than
 * replacing it.</p>
 *
 * <h3>The algorithm reads the document backwards</h3>
 * <p>Bottom to top, keeping a stack of open regions. A row whose indent is <b>less</b> than the stack top
 * closes every region indented further than it, and the closed region runs from this row down to the row
 * above where that indent was last seen. Going forwards would mean discovering a region's start only after
 * already needing it.</p>
 */
public final class IndentRangeProvider implements FoldingRangeProvider {

    /** VS Code's {@code MAX_FOLDING_REGIONS_FOR_INDENT_DEFAULT}. */
    public static final int DEFAULT_LIMIT = 5000;

    /**
     * A pair of patterns marking an explicitly folded region — {@code #region} / {@code #endregion}.
     *
     * <p>Ported because it is part of the loop rather than a layer on top of it: a marker region uses the
     * {@code -2} indent sentinel and short-circuits the indent arithmetic entirely, so leaving it out would
     * change the shape of the function rather than just omit a feature.</p>
     */
    public record Markers(Pattern start, Pattern end) {
    }

    private final boolean offSide;
    private final Markers markers;
    private final int limit;
    private final boolean includeClosingRow;

    public IndentRangeProvider(boolean offSide, Markers markers, int limit, boolean includeClosingRow) {
        this.offSide = offSide;
        this.markers = markers;
        this.limit = limit;
        this.includeClosingRow = includeClosingRow;
    }

    /** The provider every document gets: no markers, no off-side rule, the default limit. */
    public static IndentRangeProvider plain() {
        return new IndentRangeProvider(false, null, DEFAULT_LIMIT, true);
    }

    /**
     * Yes — this reads nothing but its arguments.
     *
     * <p>Every field here is configuration fixed at construction, and a {@link Rope} is persistent, so
     * the snapshot handed to a worker cannot be edited underneath it. @see FoldingRangeProvider#computesOffThread
     */
    @Override
    public boolean computesOffThread() {
        return true;
    }

    @Override
    public FoldingRegions compute(Rope document, int tabSize) {
        FoldingRegions regions = computeRanges(document, offSide, markers, tabSize, limit);
        return includeClosingRow ? extendToClosingRows(regions, document, tabSize) : regions;
    }

    /**
     * Whether a row does nothing but close brackets.
     *
     * <p><b>The whole row, not just its first character.</b> Checking only the first char accepts
     * {@code } else {}, which is a closer AND an opener at the same indent — so folding the {@code if}
     * swallowed the {@code else} header and hid a branch whose existence the reader could no longer see.
     * Caught by a test written for exactly that shape.</p>
     *
     * <p>A trailing {@code ;} or {@code ,} is allowed, so {@code });} and {@code },} still count.</p>
     */
    private static boolean isClosingRow(String trimmed) {
        if (trimmed.isEmpty()) return false;
        int i = 0;
        while (i < trimmed.length()) {
            char c = trimmed.charAt(i);
            if (c != '}' && c != ')' && c != ']') break;
            i++;
        }
        if (i == 0) return false; // did not start with a closer
        if (i < trimmed.length()) {
            char tail = trimmed.charAt(i);
            if (tail != ';' && tail != ',') return false;
            i++;
        }
        return i == trimmed.length();
    }

    /**
     * Grows each region by one row when the row below it merely closes the block.
     *
     * <p><b>A deliberate divergence from VS Code, and the one place this port does not follow it.</b> Pure
     * indent folding ends a region at its last <em>indented</em> row, so a closing brace — which sits at
     * the OUTER indent — is left behind and a collapsed block reads as:</p>
     * <pre>
     * void f() {...
     * }
     * </pre>
     * <p>which is what VS Code actually renders. IntelliJ instead swallows the closing row and shows
     * {@code void f() {...}} on one line, and that is what was asked for. The rule is narrow on purpose:
     * the row must consist of a closing bracket <em>and</em> sit at exactly the region's own starting
     * indent, so an {@code else} clause or a statement following the block is never absorbed.</p>
     *
     * <p><b>Nesting cannot break.</b> Extending past the parent's end would need the row after the child to
     * lie outside the parent — and such a row is at or below the parent's starting indent, which is
     * strictly less than the child's, so the equality test fails first. A child therefore extends at most
     * onto the parent's own last row.</p>
     */
    public static FoldingRegions extendToClosingRows(FoldingRegions regions, Rope document, int tabSize) {
        int count = regions.length();
        if (count == 0) return regions;

        int[] starts = new int[count];
        int[] ends = new int[count];
        int rows = document.lineCount();
        for (int i = 0; i < count; i++) {
            int start = regions.getStartLineNumber(i);
            int end = regions.getEndLineNumber(i);
            int below = end + 1;
            if (below < rows) {
                String text = document.line(below);
                int startIndent = IndentLevels.computeIndentLevel(document.line(start), tabSize);
                if (isClosingRow(text.trim())
                        && IndentLevels.computeIndentLevel(text, tabSize) == startIndent) {
                    end = below;
                }
            }
            starts[i] = start;
            ends[i] = end;
        }
        return new FoldingRegions(starts, ends);
    }

    /** Mutable state for one open region while the document is scanned upwards. */
    private static final class PreviousRegion {
        /** The region's indent, or {@code -2} when it is an end-marker awaiting its start. */
        int indent;
        /** The row above which this region's content stops. */
        int endAbove;
        /** The region's start row. Only meaningful for marker regions. */
        int line;

        PreviousRegion(int indent, int endAbove, int line) {
            this.indent = indent;
            this.endAbove = endAbove;
            this.line = line;
        }
    }

    /**
     * Collects regions bottom-up, then reverses them.
     *
     * <p>The scan produces regions in descending start order and {@link FoldingRegions} requires ascending,
     * so the reversal in {@link #toIndentRanges} is not tidying — the binary search depends on it.</p>
     */
    public static final class RangesCollector {
        private final int[] startIndexes;
        private final int[] endIndexes;
        private final int[] indentOccurrences = new int[1000];
        private int length;
        private final int foldingRangesLimit;

        public RangesCollector(int foldingRangesLimit, int capacity) {
            this.foldingRangesLimit = foldingRangesLimit;
            this.startIndexes = new int[capacity];
            this.endIndexes = new int[capacity];
        }

        public void insertFirst(int startLineNumber, int endLineNumber, int indent) {
            if (startLineNumber > FoldingRegions.MAX_LINE_NUMBER
                    || endLineNumber > FoldingRegions.MAX_LINE_NUMBER) {
                return;
            }
            if (length >= startIndexes.length) return;
            int index = length;
            startIndexes[index] = startLineNumber;
            endIndexes[index] = endLineNumber;
            length++;
            if (indent < 1000) {
                indentOccurrences[indent]++;
            }
        }

        public FoldingRegions toIndentRanges(Rope document, int tabSize) {
            if (length <= foldingRangesLimit) {
                // Reverse into arrays of the exact length.
                int[] starts = new int[length];
                int[] ends = new int[length];
                for (int i = length - 1, k = 0; i >= 0; i--, k++) {
                    starts[k] = startIndexes[i];
                    ends[k] = endIndexes[i];
                }
                return new FoldingRegions(starts, ends);
            }

            // Over the limit. Drop the MOST DEEPLY INDENTED regions rather than the last ones found: the
            // outermost regions are the ones a reader actually folds, and truncating by position would
            // discard whole top-level blocks from the bottom of the file.
            int entries = 0;
            int maxIndent = indentOccurrences.length;
            for (int i = 0; i < indentOccurrences.length; i++) {
                int n = indentOccurrences[i];
                if (n != 0) {
                    if (n + entries > foldingRangesLimit) {
                        maxIndent = i;
                        break;
                    }
                    entries += n;
                }
            }
            int[] starts = new int[foldingRangesLimit];
            int[] ends = new int[foldingRangesLimit];
            for (int i = length - 1, k = 0; i >= 0; i--) {
                int startIndex = startIndexes[i];
                int indent = IndentLevels.computeIndentLevel(document.line(startIndex), tabSize);
                if (indent < maxIndent || (indent == maxIndent && entries++ < foldingRangesLimit)) {
                    if (k >= foldingRangesLimit) break;
                    starts[k] = startIndex;
                    ends[k] = endIndexes[i];
                    k++;
                }
            }
            return new FoldingRegions(starts, ends);
        }
    }

    /**
     * The whole algorithm.
     *
     * <p>Rows are 0-based here where VS Code's are 1-based, so the sentinel is {@code rowCount} rather than
     * {@code lineCount + 1} and the loop runs {@code rowCount - 1} down to {@code 0}. Nothing else shifts:
     * {@code endAbove - 1} and the {@code >= 1} minimum size are row <em>differences</em>, which the change
     * of origin leaves alone.</p>
     */
    public static FoldingRegions computeRanges(Rope document, boolean offSide, Markers markers,
                                               int tabSize, int foldingRangesLimit) {
        int rowCount = document.lineCount();
        RangesCollector result = new RangesCollector(foldingRangesLimit, rowCount + 1);

        // One combined pattern, so a line is scanned once: group 1 matching means it was the START marker,
        // and a match without it was the end. VS Code keeps a two-pattern fallback for when the start and
        // end regexes carry DIFFERENT FLAGS, which cannot be expressed in one JS regex -- Java puts flags
        // in the pattern via (?i) and friends, where combining is always legal, so the fallback has no
        // case to handle here and is left out rather than ported as dead code.
        Pattern pattern = markers == null ? null
                : Pattern.compile("(" + markers.start().pattern() + ")|(?:" + markers.end().pattern() + ")");

        PreviousRegion[] previousRegions = new PreviousRegion[rowCount + 2];
        int stackSize = 0;
        // The sentinel guarantees the stack is never empty, so the pops below need no bounds test.
        previousRegions[stackSize++] = new PreviousRegion(-1, rowCount, rowCount);

        for (int line = rowCount - 1; line >= 0; line--) {
            String lineContent = document.line(line);
            int indent = IndentLevels.computeIndentLevel(lineContent, tabSize);
            PreviousRegion previous = previousRegions[stackSize - 1];

            if (indent == -1) {
                if (offSide) {
                    // For off-side languages an empty line belongs to the block ABOVE it. The block below
                    // has already been emitted, so this can only move the end of the one before.
                    previous.endAbove = line;
                }
                continue; // only whitespace
            }

            boolean isStartMatch = false;
            boolean isEndMatch = false;
            if (pattern != null) {
                Matcher m = pattern.matcher(lineContent);
                if (m.find()) {
                    isStartMatch = m.group(1) != null;
                    isEndMatch = !isStartMatch;
                }
            }

            if (isStartMatch || isEndMatch) {
                if (isStartMatch) {
                    // Discard every region opened since the matching end marker -- a marker region overrides
                    // whatever indentation implied inside it.
                    int i = stackSize - 1;
                    while (i > 0 && previousRegions[i].indent != -2) i--;
                    if (i > 0) {
                        stackSize = i + 1;
                        previous = previousRegions[i];

                        // A marker region INCLUDES its end line, unlike an indent region.
                        result.insertFirst(line, previous.line, indent);
                        previous.line = line;
                        previous.indent = indent;
                        previous.endAbove = line;
                        continue;
                    }
                    // No end marker above: fall through and treat it as an ordinary line.
                } else {
                    previousRegions[stackSize++] = new PreviousRegion(-2, line, line);
                    continue;
                }
            }

            if (previous.indent > indent) {
                // This row closes everything indented further than it.
                do {
                    stackSize--;
                    previous = previousRegions[stackSize - 1];
                } while (previous.indent > indent);

                int endLineNumber = previous.endAbove - 1;
                if (endLineNumber - line >= 1) { // needs at least size 1
                    result.insertFirst(line, endLineNumber, indent);
                }
            }
            if (previous.indent == indent) {
                previous.endAbove = line;
            } else { // previous.indent < indent
                previousRegions[stackSize++] = new PreviousRegion(indent, line, line);
            }
        }
        return result.toIndentRanges(document, tabSize);
    }
}
