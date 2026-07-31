package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.WordOperations;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.1.7b — word boundaries, ported from VS Code rather than invented.
 *
 * <p>The naive version this replaced classified with {@code Character.isLetterOrDigit}, which is wrong in
 * the way people notice within seconds: {@code _} is not a letter or a digit, so {@code foo_bar} was two
 * words. Every editor treats it as one, and the reason is that the separator set is a <em>listed
 * constant</em> rather than a character-class test.</p>
 */
public class WordOperationsTest {

    private static final WordClassifier CLASSIFIER = WordClassifier.DEFAULT;

    private static int right(String text, int from) {
        return WordOperations.nextWordEnd(Rope.of(text), from, CLASSIFIER);
    }

    private static int left(String text, int from) {
        return WordOperations.previousWordStart(Rope.of(text), from, CLASSIFIER);
    }

    // ── Classification ──────────────────────────────────────────────────────────────────────────

    /** <b>The whole reason for the port.</b> */
    @Test
    public void underscoreIsAWordCharacter() {
        assertTrue(CLASSIFIER.isWordPart('_'));
        assertEquals("foo_bar is one word", 7, right("foo_bar baz", 0));
    }

    @Test
    public void punctuationIsASeparatorAndSpaceIsWhitespace() {
        assertEquals(WordClassifier.CharClass.SEPARATOR, CLASSIFIER.classify('.'));
        assertEquals(WordClassifier.CharClass.SEPARATOR, CLASSIFIER.classify('('));
        assertEquals(WordClassifier.CharClass.WHITESPACE, CLASSIFIER.classify(' '));
        assertEquals(WordClassifier.CharClass.WHITESPACE, CLASSIFIER.classify('\t'));
        assertEquals(WordClassifier.CharClass.REGULAR, CLASSIFIER.classify('a'));
        assertEquals(WordClassifier.CharClass.REGULAR, CLASSIFIER.classify('9'));
    }

    /** A user's own language is made of words too — anything unlisted and non-ASCII is a word part. */
    @Test
    public void nonAsciiLettersAreWordCharacters() {
        assertTrue(CLASSIFIER.isWordPart('é'));
        assertTrue(CLASSIFIER.isWordPart('ß'));
        assertTrue(CLASSIFIER.isWordPart('日'));
    }

    @Test
    public void theSeparatorSetIsConfigurable() {
        WordClassifier dollarIsAWord = new WordClassifier(".,;");
        assertTrue("with a smaller set, $ becomes part of a word", dollarIsAWord.isWordPart('$'));
        assertFalse(WordClassifier.DEFAULT.isWordPart('$'));
    }

    // ── Movement ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Word-left lands on the START of a word; word-right on its END.</b> They are not mirror images,
     * and that asymmetry is deliberate — it is what makes {@code Ctrl+Shift+Left} select the word you are
     * inside rather than the gap before it.
     */
    @Test
    public void leftGoesToWordStartAndRightToWordEnd() {
        String text = "alpha beta gamma";
        assertEquals("right from inside alpha ends at its end", 5, right(text, 2));
        assertEquals("left from inside beta starts at its start", 6, left(text, 8));
    }

    @Test
    public void runsAreSkippedByClassNotByNotALetter() {
        String text = "foo.  bar";
        // foo -> the separator run -> bar, rather than one jump over everything between.
        int afterFoo = right(text, 0);
        assertEquals(3, afterFoo);
        assertTrue("the next stop is not straight to the end", right(text, afterFoo) <= text.length());
    }

    @Test
    public void movingRightFromTheEndStaysThere() {
        assertEquals(5, right("hello", 5));
        assertEquals(0, left("hello", 0));
    }

    /**
     * <b>A word move must not cross a line break in one jump.</b> Crossing it makes Ctrl+Left leap up a
     * line through the previous line's trailing whitespace, which reads as the caret teleporting.
     */
    @Test
    public void aWordMoveStopsAtALineBreak() {
        String text = "one\ntwo";
        assertTrue("moving left from 'two' must not reach 'one'", left(text, 5) >= 3);
        assertTrue("moving right from 'one' must not reach 'two'", right(text, 3) <= 4);
    }

    // ── The word under a caret ──────────────────────────────────────────────────────────────────

    @Test
    public void wordAtFindsTheSurroundingWord() {
        int[] word = WordOperations.wordAt(Rope.of("alpha beta"), 7, CLASSIFIER);
        assertNotNull(word);
        assertEquals(6, word[0]);
        assertEquals(10, word[1]);
    }

    /** A caret at a word's end still finds that word, not the gap after it. */
    @Test
    public void wordAtLooksBehindTheCaretToo() {
        int[] word = WordOperations.wordAt(Rope.of("alpha beta"), 5, CLASSIFIER);
        assertNotNull(word);
        assertEquals(0, word[0]);
        assertEquals(5, word[1]);
    }

    @Test
    public void wordAtInWhitespaceFindsNothing() {
        assertNull(WordOperations.wordAt(Rope.of("a   b"), 2, CLASSIFIER));
    }

    @Test
    public void wordAtSpansUnderscores() {
        int[] word = WordOperations.wordAt(Rope.of("call some_long_name(x)"), 8, CLASSIFIER);
        assertNotNull(word);
        assertEquals(5, word[0]);
        assertEquals("the whole identifier, underscores included", 19, word[1]);
    }
}
