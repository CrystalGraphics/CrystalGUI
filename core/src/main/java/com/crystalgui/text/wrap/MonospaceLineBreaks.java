package com.crystalgui.text.wrap;

/**
 * Where a line breaks, measured in columns.
 *
 * <p><b>Ported from VS Code's {@code MonospaceLineBreaksComputer}</b> —
 * {@code src/vs/editor/common/viewModel/monospaceLineBreaksComputer.ts}, microsoft/vscode, MIT licence.
 * The character classes, {@link #canBreak}, and the scan loop in {@link #project} are that file's.</p>
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

    private static final int NONE = 0;
    private static final int BREAK_BEFORE = 1;
    private static final int BREAK_AFTER = 2;
    private static final int BREAK_IDEOGRAPHIC = 3;

    /**
     * Characters a break may fall <em>before</em> — VS Code's {@code wordWrapBreakBeforeCharacters}.
     *
     * <p><b>ASCII subset, deliberately.</b> VS Code's default also lists CJK opening punctuation
     * ({@code 〈《「『【〔（［｛｢}) and full-width currency marks. Those are omitted to keep this source
     * ASCII rather than at the mercy of the compiler's platform encoding — a mojibaked literal here would
     * silently classify the wrong characters. CJK still wraps: {@link #BREAK_IDEOGRAPHIC} covers Han,
     * Hiragana and Katakana by codepoint range, which is where the bulk of the behaviour lives.</p>
     */
    private static final String BREAK_BEFORE_CHARS = "([{";

    /** Characters a break may fall <em>after</em> — VS Code's {@code wordWrapBreakAfterCharacters}, ASCII subset. */
    private static final String BREAK_AFTER_CHARS = " \t})]?|/&.,;";

    private final int wrapColumn;
    private final int tabSize;
    private final WrapIndent indentMode;

    public MonospaceLineBreaks(int wrapColumn, int tabSize, WrapIndent indentMode) {
        if (wrapColumn < 1) throw new IllegalArgumentException("Wrap column must be at least 1, was " + wrapColumn);
        this.wrapColumn = wrapColumn;
        this.tabSize = Math.max(1, tabSize);
        this.indentMode = indentMode;
    }

    private static int classify(char c) {
        if (BREAK_BEFORE_CHARS.indexOf(c) >= 0) return BREAK_BEFORE;
        if (BREAK_AFTER_CHARS.indexOf(c) >= 0) return BREAK_AFTER;
        // Han, Hiragana and Katakana break between any two characters -- there are no spaces to break at.
        if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)) {
            return BREAK_IDEOGRAPHIC;
        }
        return NONE;
    }

    /**
     * Whether a break may fall between two characters.
     *
     * <p>Ported verbatim. The two clauses that look redundant are not: breaking at the <em>end</em> of a
     * run of {@code BREAK_AFTER} and at the <em>start</em> of a run of {@code BREAK_BEFORE} is what keeps
     * {@code "foo... bar"} from breaking between the dots and {@code "((("} from breaking inside itself.</p>
     */
    private static boolean canBreak(int prevClass, char c, int charClass) {
        return c != ' ' && (
                (prevClass == BREAK_AFTER && charClass != BREAK_AFTER)
                        || (prevClass != BREAK_BEFORE && charClass == BREAK_BEFORE)
                        || (prevClass == BREAK_IDEOGRAPHIC && charClass != BREAK_AFTER)
                        || (charClass == BREAK_IDEOGRAPHIC && prevClass != BREAK_BEFORE));
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
        int prevClass = classify(prev);
        int visibleColumn = charWidth(prev, 0);

        for (int i = 1; i < length; i++) {
            char c = line.charAt(i);
            int charClass;
            int width;

            if (Character.isHighSurrogate(c) && i + 1 < length) {
                // A surrogate pair is one unit and is never broken through the middle.
                i++;
                charClass = NONE;
                width = 2;
            } else {
                charClass = classify(c);
                width = charWidth(c, visibleColumn);
            }

            if (canBreak(prevClass, c, charClass)) {
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
