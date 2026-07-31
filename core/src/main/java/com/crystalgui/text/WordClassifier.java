package com.crystalgui.text;

/**
 * Which characters make up a word — ported from VS Code's {@code WordCharacterClassifier}.
 *
 * <p><b>Ported, not invented.</b> Word boundaries are a convention with decades of accumulated
 * decisions behind them, and the naive version (letters and digits are word characters, everything else
 * is not) is wrong in the way people notice immediately: {@code foo_bar} becomes two words, because
 * {@code _} is not a letter or a digit. It is a <em>word character</em> in every editor, and the way that
 * falls out is that the separator set is a listed constant rather than a character-class test.</p>
 *
 * <p>Source: {@code src/vs/editor/common/core/wordCharacterClassifier.ts} and the
 * {@code wordSeparators} editor option, microsoft/vscode, MIT licence.</p>
 */
public final class WordClassifier {

    /** VS Code's {@code USUAL_WORD_SEPARATORS}. Note the absence of {@code _}. */
    public static final String DEFAULT_SEPARATORS = "`~!@#$%^&*()-=+[{]}\\|;:'\",.<>/?";

    /** The three classes every word operation branches on. */
    public enum CharClass {
        /** Part of a word — letters, digits, underscore, and anything not listed as a separator. */
        REGULAR,
        /** Space and tab. Deliberately distinct from a separator: skipping runs treats them apart. */
        WHITESPACE,
        /** Punctuation that ends a word without being part of one. */
        SEPARATOR
    }

    public static final WordClassifier DEFAULT = new WordClassifier(DEFAULT_SEPARATORS);

    private final boolean[] ascii = new boolean[128];
    private final String separators;

    public WordClassifier(String separators) {
        this.separators = separators == null ? DEFAULT_SEPARATORS : separators;
        for (int i = 0; i < this.separators.length(); i++) {
            char c = this.separators.charAt(i);
            if (c < ascii.length) ascii[c] = true;
        }
    }

    public CharClass classify(char c) {
        if (c == ' ' || c == '\t') return CharClass.WHITESPACE;
        // A line break is whitespace for the purposes of word movement -- it ends a run without being
        // punctuation, which is what stops a word jump from swallowing the newline into a separator run.
        if (c == '\n' || c == '\r') return CharClass.WHITESPACE;
        if (c < ascii.length) return ascii[c] ? CharClass.SEPARATOR : CharClass.REGULAR;
        // Anything outside ASCII is a word character unless it is listed. Accented letters, CJK and
        // emoji all end up REGULAR, which is what a user means by "a word" in their own language.
        return separators.indexOf(c) >= 0 ? CharClass.SEPARATOR : CharClass.REGULAR;
    }

    public boolean isWordPart(char c) {
        return classify(c) == CharClass.REGULAR;
    }
}
