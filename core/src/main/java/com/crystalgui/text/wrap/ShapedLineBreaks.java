package com.crystalgui.text.wrap;

/**
 * Where a line breaks, measured in <b>pixels</b> against the font it will actually be drawn in.
 *
 * <p>The counterpart to {@link MonospaceLineBreaks}, and VS Code's {@code DOMLineBreaksComputer} in
 * intent: that one lays the text out in a hidden element and reads the real break positions back, because
 * a column count is only correct when every glyph is the same width.</p>
 *
 * <h3>Why this is not optional</h3>
 * <p>The first version of soft wrap used the column computer with a viewport width divided by the advance
 * of a <b>space</b>. In a monospaced font that is exact. In the theme's font — IBM Plex Sans, which is
 * proportional — a space is far narrower than an average glyph, so the column budget came out much too
 * large and wrapped lines still ran off the right-hand edge and were clipped. It looked like wrapping was
 * broken; it was measuring that was.</p>
 *
 * <p>Nothing here duplicates the column computer: the <em>where may it break</em> rules are shared through
 * {@link BreakOpportunities}, and only the <em>has it run out of room</em> test differs.</p>
 */
public final class ShapedLineBreaks implements LineBreaksComputer {

    /**
     * The measured x of every character column of a line.
     *
     * <p>An interface so this class stays free of fonts and GL — the editor supplies its own already-
     * cached row measurements, and a test supplies a fixed width per character. That is the same seam
     * {@link LineBreaksComputer} draws, one level down.</p>
     */
    @FunctionalInterface
    public interface LineMetrics {
        /** {@code result[c]} is the x of column {@code c}; length is {@code line.length() + 1}. */
        float[] columnOffsets(String line);
    }

    private final float wrapWidth;
    private final int tabSize;
    private final WrapIndent indentMode;
    private final LineMetrics metrics;

    public ShapedLineBreaks(float wrapWidth, int tabSize, WrapIndent indentMode, LineMetrics metrics) {
        if (!(wrapWidth > 0f)) throw new IllegalArgumentException("Wrap width must be positive");
        this.wrapWidth = wrapWidth;
        this.tabSize = Math.max(1, tabSize);
        this.indentMode = indentMode == null ? WrapIndent.NONE : indentMode;
        this.metrics = metrics;
    }

    @Override
    public LineProjection project(String line) {
        int length = line.length();
        if (length == 0) return LineProjection.unwrapped(0);

        float[] x = metrics.columnOffsets(line);
        if (x == null || x.length < length + 1) return LineProjection.unwrapped(length);
        // A line that already fits is the overwhelmingly common case and costs one comparison.
        if (x[length] <= wrapWidth) return LineProjection.unwrapped(length);

        int indentColumns = indentMode.columnsFor(line, tabSize, Integer.MAX_VALUE);
        float indentPx = indentPixels(line, x, indentColumns);
        // The same guard the column computer carries, in the unit this one works in: an indent that
        // leaves no room for text cannot terminate the scan.
        if (indentPx > wrapWidth / 2f) {
            indentColumns = 0;
            indentPx = 0f;
        }

        int[] breaks = new int[4];
        int breakCount = 0;

        int lineStart = 0;
        float budget = wrapWidth;
        int breakAt = 0;

        char prev = line.charAt(0);
        int prevClass = BreakOpportunities.classify(prev);

        for (int i = 1; i < length; i++) {
            char c = line.charAt(i);
            int charClass = BreakOpportunities.classify(c);

            if (BreakOpportunities.canBreak(prevClass, c, charClass)) breakAt = i;

            // Width of everything from this view line's start up to and including character i.
            if (x[i + 1] - x[lineStart] > budget) {
                // Take the remembered opportunity, unless there was none or unless taking it would leave
                // this character still past the edge -- then split the token. Both halves matter: without
                // the opportunity, prose breaks mid-word; without the fallback, a 300-character URL
                // cannot break at all.
                int at = (breakAt <= lineStart || x[i + 1] - x[breakAt] > budget) ? i : breakAt;
                if (at <= lineStart) at = Math.min(length, lineStart + 1);

                if (breakCount == breaks.length) {
                    int[] grown = new int[breaks.length * 2];
                    System.arraycopy(breaks, 0, grown, 0, breaks.length);
                    breaks = grown;
                }
                breaks[breakCount++] = at;
                lineStart = at;
                breakAt = at;
                budget = wrapWidth - indentPx;
            }

            prev = c;
            prevClass = charClass;
        }

        if (breakCount == 0) return LineProjection.unwrapped(length);

        int[] offsets = new int[breakCount + 1];
        System.arraycopy(breaks, 0, offsets, 0, breakCount);
        // The last entry is the row's length, not a break -- see LineProjection.
        offsets[breakCount] = length;
        return new LineProjection(offsets, indentColumns);
    }

    /**
     * The pixel width a continuation line is inset by.
     *
     * <p>The row's own leading whitespace is <b>measured</b> rather than multiplied out, so a continuation
     * lines up with the text above it in whatever font resolved. Any further levels {@link WrapIndent}
     * asks for are added at the width of one space, since there is no measured run to read them from.</p>
     */
    private float indentPixels(String line, float[] x, int indentColumns) {
        if (indentColumns <= 0) return 0f;
        int blank = Math.min(BreakOpportunities.leadingBlankLength(line), x.length - 1);
        float measured = x[blank];
        int extraColumns = Math.max(0, indentColumns - blank);
        if (extraColumns == 0) return measured;
        float spaceWidth = x.length > 1 ? Math.max(1f, x[1] - x[0]) : 1f;
        return measured + extraColumns * spaceWidth;
    }
}
