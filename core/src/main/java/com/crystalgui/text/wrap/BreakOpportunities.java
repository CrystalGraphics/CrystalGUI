package com.crystalgui.text.wrap;

/**
 * Where a break is <em>allowed</em> to fall — the character rules, with no measurement in them.
 *
 * <p>Ported from VS Code's {@code monospaceLineBreaksComputer.ts} ({@code WrappingCharacterClassifier}
 * and {@code canBreak}), microsoft/vscode, MIT licence.</p>
 *
 * <p>Its own class because <b>two</b> computers need it — the column-based one and the pixel-measuring
 * one — and the cap logic in CrystalGraphics' stroke shaders is the standing reminder of what happens
 * when a body like this is duplicated: it was wrong three times running, every version rendered something
 * plausible, and the fourth fix landed in one copy while the other kept the bug.</p>
 */
public final class BreakOpportunities {

    public static final int NONE = 0;
    public static final int BREAK_BEFORE = 1;
    public static final int BREAK_AFTER = 2;
    public static final int BREAK_IDEOGRAPHIC = 3;

    /**
     * Characters a break may fall <em>before</em> — VS Code's {@code wordWrapBreakBeforeCharacters}.
     *
     * <p><b>ASCII subset, deliberately.</b> VS Code's default also lists CJK opening punctuation and
     * full-width currency marks. Those are omitted to keep this source ASCII rather than at the mercy of
     * the compiler's platform encoding — a mojibaked literal here would silently classify the wrong
     * characters. CJK still wraps: {@link #BREAK_IDEOGRAPHIC} covers Han, Hiragana and Katakana by
     * codepoint range, which is where the bulk of the behaviour lives.</p>
     */
    private static final String BREAK_BEFORE_CHARS = "([{";

    /** Characters a break may fall <em>after</em> — {@code wordWrapBreakAfterCharacters}, ASCII subset. */
    private static final String BREAK_AFTER_CHARS = " \t})]?|/&.,;";

    private BreakOpportunities() {
    }

    public static int classify(char c) {
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
    public static boolean canBreak(int prevClass, char c, int charClass) {
        return c != ' ' && (
                (prevClass == BREAK_AFTER && charClass != BREAK_AFTER)
                        || (prevClass != BREAK_BEFORE && charClass == BREAK_BEFORE)
                        || (prevClass == BREAK_IDEOGRAPHIC && charClass != BREAK_AFTER)
                        || (charClass == BREAK_IDEOGRAPHIC && prevClass != BREAK_BEFORE));
    }

    /** The number of leading space/tab characters — where a carried indent stops. */
    public static int leadingBlankLength(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }
}
