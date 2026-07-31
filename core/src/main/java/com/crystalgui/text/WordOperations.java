package com.crystalgui.text;

/**
 * Word-wise movement and deletion — ported from VS Code's {@code WordOperations}.
 *
 * <p>Source: {@code src/vs/editor/common/cursor/cursorWordOperations.ts}, microsoft/vscode, MIT licence.
 * Reduced to the cases this editor exposes (word left, word right, and the delete pair that must agree
 * with them), because the original also serves accessibility navigation and word-part motion this widget
 * does not offer.</p>
 *
 * <h3>Two things worth stating, both of which the naive version gets wrong</h3>
 * <ul>
 *   <li><b>Word-left stops at the START of the word</b> and word-right stops at its <b>END</b>. They are
 *       not mirror images walking the same boundaries: pressing Left then Right from inside a word does
 *       not return you to where you began, and that asymmetry is correct — it is what every editor does,
 *       and what makes Ctrl+Shift+Left select the word you are inside rather than the gap before it.</li>
 *   <li><b>Runs are skipped by class, not by "not-a-letter".</b> Whitespace and punctuation are separate
 *       classes, so {@code foo.  bar} steps foo → . → bar rather than jumping the lot.</li>
 * </ul>
 *
 * <p>Operating on the {@link Rope} directly rather than on a materialised string, because the naive
 * version called {@code toString()} on the whole document for every Ctrl+Left — O(document) per
 * keystroke, invisible in a test and very visible in a large file.</p>
 */
public final class WordOperations {

    private WordOperations() {
    }

    /**
     * The offset a word-left move lands on: the start of the word before {@code offset}.
     *
     * <p>Skips any run of whitespace or separators first, then walks back over the word itself.</p>
     */
    public static int previousWordStart(Rope document, int offset, WordClassifier classifier) {
        int at = Math.max(0, Math.min(offset, document.length()));
        if (at == 0) return 0;

        // Step back one first: standing at the start of a word, Ctrl+Left goes to the PREVIOUS word,
        // not to where the caret already is.
        at--;
        while (at > 0 && classifier.classify(document.charAt(at)) != WordClassifier.CharClass.REGULAR) {
            // A line break stops the walk. Crossing it would make Ctrl+Left jump up a line through the
            // trailing whitespace of the line above, which reads as the caret leaping.
            if (document.charAt(at) == '\n') return at;
            at--;
        }
        while (at > 0 && classifier.classify(document.charAt(at - 1)) == WordClassifier.CharClass.REGULAR) {
            at--;
        }
        return at;
    }

    /**
     * The offset a word-right move lands on: the end of the word at or after {@code offset}.
     */
    public static int nextWordEnd(Rope document, int offset, WordClassifier classifier) {
        int length = document.length();
        int at = Math.max(0, Math.min(offset, length));
        if (at >= length) return length;

        while (at < length && classifier.classify(document.charAt(at)) != WordClassifier.CharClass.REGULAR) {
            if (document.charAt(at) == '\n') return at == offset ? at + 1 : at;
            at++;
        }
        while (at < length && classifier.classify(document.charAt(at)) == WordClassifier.CharClass.REGULAR) {
            at++;
        }
        return at;
    }

    /**
     * The word surrounding {@code offset}, as {@code {start, end}}, or {@code null} when there is none.
     *
     * <p>What double-click selects and what {@code Ctrl+D} takes as its search term. Looks at the
     * character <em>before</em> the offset as well as the one at it, so a caret sitting at the end of a
     * word still finds that word rather than the gap after it.</p>
     */
    public static int[] wordAt(Rope document, int offset, WordClassifier classifier) {
        int length = document.length();
        int at = Math.max(0, Math.min(offset, length));

        boolean onWord = at < length && classifier.isWordPart(document.charAt(at));
        boolean afterWord = at > 0 && classifier.isWordPart(document.charAt(at - 1));
        if (!onWord && !afterWord) return null;

        int start = at;
        while (start > 0 && classifier.isWordPart(document.charAt(start - 1))) start--;
        int end = at;
        while (end < length && classifier.isWordPart(document.charAt(end))) end++;
        return end > start ? new int[] { start, end } : null;
    }
}
