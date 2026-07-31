package com.crystalgui.text.wrap;

/**
 * Where a line breaks, measured in columns.
 *
 * <p><b>Ported from VS Code's {@code MonospaceLineBreaksComputer}</b> —
 * {@code src/vs/editor/common/viewModel/monospaceLineBreaksComputer.ts}, microsoft/vscode, MIT licence.
 * The scan loop in {@link #project} is that file's; its character rules live in
 * {@link BreakOpportunities}, shared with {@link ShapedLineBreaks}.</p>
 *
 * <h3>Why a column-based computer exists alongside a font-accurate one</h3>
 * <p>It is exact for a monospaced font, which is what code is read in, and it needs <b>no font, no GL
 * context and no shaper</b> — so it runs in {@code headlessTest}, which is where every rule below is
 * pinned. The shaped computer measures glyphs and is what the widget actually uses; this one is the
 * reference the shaped one is checked against, and the fallback when no font has resolved yet.</p>
 *
 * <h3>The rule that makes wrapping look right</h3>
 * <p>Breaking is <b>opportunistic with a forced fallback</b>. The scan remembers the last offset it
 * <em>could</em> have broken at and only uses it when the column limit is actually exceeded; if there was
 * no opportunity, or taking it would leave the next line still over the limit, it breaks mid-word
 * instead. Both halves matter — opportunity-only wrapping cannot break a 300-character URL at all, and
 * always-break makes prose wrap mid-word.</p>
 */
public final class MonospaceLineBreaks implements LineBreaksComputer {

    private final int wrapColumn;
    private final int tabSize;
    private final WrapIndent indentMode;

    public MonospaceLineBreaks(int wrapColumn, int tabSize, WrapIndent indentMode) {
        if (wrapColumn < 1) throw new IllegalArgumentException("Wrap column must be at least 1, was " + wrapColumn);
        this.wrapColumn = wrapColumn;
        this.tabSize = Math.max(1, tabSize);
        this.indentMode = indentMode;
    }

    private int charWidth(char c, int visibleColumn) {
        if (c == '\t') return tabSize - (visibleColumn % tabSize);
        return 1;
    }

    @Override
    public LineProjection project(String line) {
        int length = line.length();
        if (length == 0) return LineProjection.unwrapped(0);

        int wrappedIndent = indentMode.columnsFor(line, tabSize, wrapColumn);
        int wrappedWrapColumn = wrapColumn - wrappedIndent;

        int[] breaks = new int[4];
        int breakCount = 0;

        int breakOffset = 0;
        int breakOffsetVisibleColumn = 0;
        int breakingColumn = wrapColumn;

        char prev = line.charAt(0);
        int prevClass = BreakOpportunities.classify(prev);
        int visibleColumn = charWidth(prev, 0);

        for (int i = 1; i < length; i++) {
            char c = line.charAt(i);
            int charClass;
            int width;

            if (Character.isHighSurrogate(c) && i + 1 < length) {
                // A surrogate pair is one unit and is never broken through the middle.
                i++;
                charClass = BreakOpportunities.NONE;
                width = 2;
            } else {
                charClass = BreakOpportunities.classify(c);
                width = charWidth(c, visibleColumn);
            }

            if (BreakOpportunities.canBreak(prevClass, c, charClass)) {
                breakOffset = i;
                breakOffsetVisibleColumn = visibleColumn;
            }

            visibleColumn += width;

            if (visibleColumn > breakingColumn) {
                // Over the limit. Take the remembered opportunity -- unless there was none, or unless
                // taking it would leave this character still past the limit on the next line, in which
                // case break right here and split the word.
                if (breakOffset == 0 || visibleColumn - breakOffsetVisibleColumn > wrappedWrapColumn) {
                    breakOffset = i;
                    breakOffsetVisibleColumn = visibleColumn - width;
                }
                if (breakCount == breaks.length) {
                    int[] grown = new int[breaks.length * 2];
                    System.arraycopy(breaks, 0, grown, 0, breaks.length);
                    breaks = grown;
                }
                breaks[breakCount++] = breakOffset;
                breakingColumn = breakOffsetVisibleColumn + wrappedWrapColumn;
                breakOffset = 0;
            }

            prev = c;
            prevClass = charClass;
        }

        if (breakCount == 0) return LineProjection.unwrapped(length);

        int[] offsets = new int[breakCount + 1];
        System.arraycopy(breaks, 0, offsets, 0, breakCount);
        // The last entry is the row's length, not a break -- see LineProjection.
        offsets[breakCount] = length;
        return new LineProjection(offsets, wrappedIndent);
    }
}
