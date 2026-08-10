package com.crystalgui.search;

import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.ui.text.TextRange;
import com.crystalgui.text.search.SearchResults;
import com.crystalgui.text.search.TextSearch;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The document half of find and replace, without a frame.
 *
 * <p>This is the point of moving it off {@code TextEditor}: whole-word boundaries, overlapping matches,
 * zero-width patterns, the cursor's wrapping and the exclusion bookkeeping are all decidable on a
 * {@code CharSequence}, and none of them should need a {@code UIWindow}, fonts and an input handler to
 * assert. The same argument the view-part extraction already made for this widget.</p>
 */
public class TextSearchTest {

    private static final SearchQuery.Options WORDS = SearchQuery.Options.DEFAULT.withWholeWords(true);
    private static final SearchQuery.Options REGEX = SearchQuery.Options.DEFAULT.withRegex(true);

    private static List<TextRange> find(String text, String query, SearchQuery.Options options) {
        return TextSearch.findAll(text, SearchQuery.of(query, options));
    }

    // ── Finding ─────────────────────────────────────────────────────────────────────────────────

    /** Overlapping matches count separately, which is what every editor reports. */
    @Test
    public void overlappingMatchesAreCountedSeparately() {
        assertEquals(2, find("aaa", "aa", SearchQuery.Options.DEFAULT).size());
    }

    @Test
    public void caseIsIgnoredByDefaultAndHonouredWhenAsked() {
        assertEquals(2, find("Foo foo", "foo", SearchQuery.Options.DEFAULT).size());
        assertEquals(1, find("Foo foo", "foo",
                SearchQuery.Options.DEFAULT.withMatchCase(true)).size());
    }

    @Test
    public void wholeWordsRefusesAMatchInsideAWord() {
        assertEquals(2, find("cat concatenate", "cat", SearchQuery.Options.DEFAULT).size());
        assertEquals(1, find("cat concatenate", "cat", WORDS).size());
    }

    @Test
    public void regexFindsEveryMatchInOrder() {
        List<TextRange> matches = find("a1 b2 c3", "[a-z]\\d", REGEX);
        assertEquals(3, matches.size());
        assertEquals(0, matches.get(0).start());
        assertEquals(6, matches.get(2).start());
    }

    /** An uncompilable pattern finds nothing rather than throwing — it is recompiled on every keystroke. */
    @Test
    public void anInvalidPatternFindsNothing() {
        assertTrue(find("anything", "(unclosed", REGEX).isEmpty());
    }

    /** A zero-width match is skipped and the scan continues past it. */
    @Test
    public void zeroWidthMatchesAreSkippedNotTaken() {
        assertTrue(find("no letters", "x*", REGEX).isEmpty());
        assertEquals(1, find("the box", "x*", REGEX).size());
    }

    /** Whole words applies to a regex match too, as it does in both references. */
    @Test
    public void wholeWordsComposesWithRegex() {
        assertTrue(find("new.shadergraph", "gr.ph", REGEX.withWholeWords(true)).isEmpty());
        assertEquals(1, find("a graph here", "gr.ph", REGEX.withWholeWords(true)).size());
    }

    // ── The cursor ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void theCursorWrapsBothWays() {
        SearchResults results = SearchResults.of(find("a a a", "a", SearchQuery.Options.DEFAULT));
        assertEquals(3, results.size());

        assertTrue(results.next());
        assertEquals(1, results.currentNumber());
        results.next();
        results.next();
        assertEquals(3, results.currentNumber());
        results.next();
        assertEquals("next from the last should wrap to the first", 1, results.currentNumber());
        results.previous();
        assertEquals("and previous from the first back to the last", 3, results.currentNumber());
    }

    /** Typing lands on the match nearest the caret, not at the top of the file. */
    @Test
    public void theCursorLandsOnTheFirstMatchAtOrAfterAnOffset() {
        SearchResults results = SearchResults.of(find("a a a", "a", SearchQuery.Options.DEFAULT));
        assertTrue(results.moveToFirstAtOrAfter(2));
        assertEquals(2, results.currentNumber());
        assertTrue("past the last match it wraps to the first", results.moveToFirstAtOrAfter(99));
        assertEquals(1, results.currentNumber());
    }

    @Test
    public void anEmptyResultHasNoCursor() {
        SearchResults results = SearchResults.of(List.of());
        assertFalse(results.next());
        assertFalse(results.previous());
        assertEquals(0, results.currentNumber());
        assertNull(results.currentMatch());
    }

    // ── Exclusions ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Exclude takes a match out of Replace All and leaves it in the list.</b>
     *
     * <p>IntelliJ's behaviour, and why exclusions live with the occurrences: the editor has to keep drawing
     * the span — struck through — and Replace All has to skip it.</p>
     */
    @Test
    public void anExcludedMatchStaysVisibleAndIsNotReplaced() {
        SearchResults results = SearchResults.of(find("a a a", "a", SearchQuery.Options.DEFAULT));
        results.next();
        assertTrue(results.toggleExcludeCurrent());

        assertEquals("it is still one of the matches", 3, results.size());
        assertEquals("but not one Replace All should touch", 2, results.included().size());
        assertEquals(1, results.excludedRanges().size());
        assertTrue(results.isCurrentExcluded());

        results.toggleExcludeCurrent();
        assertEquals("and putting it back restores it", 3, results.included().size());
    }

    /**
     * <b>Exclusions are kept by RANGE, so a re-query does not move them.</b>
     *
     * <p>Held by index they migrate: one more keystroke renumbers the list and Replace All skips whichever
     * match inherited the number. Anything whose range is gone drops out on its own.</p>
     */
    @Test
    public void exclusionsSurviveARequeryAndDoNotMigrate() {
        String text = "a a a";
        SearchResults results = SearchResults.of(find(text, "a", SearchQuery.Options.DEFAULT));
        results.next();
        results.next();                                  // the second match, at offset 2
        results.toggleExcludeCurrent();
        assertEquals(2, results.excludedRanges().get(0).start());

        SearchResults requeried = results.withMatches(find(text, "a", SearchQuery.Options.DEFAULT));
        assertEquals("the exclusion was lost on re-query", 1, requeried.excludedRanges().size());
        assertEquals("and it moved to a different match", 2, requeried.excludedRanges().get(0).start());

        SearchResults narrowed = results.withMatches(List.of(TextRange.of(0, 1)));
        assertTrue("an exclusion whose match is gone must drop out",
                narrowed.excludedRanges().isEmpty());
    }

    // ── Preserve case ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>Three shapes, and no more.</b>
     *
     * <p>All-upper takes an all-upper replacement, Capitalised takes a Capitalised one, everything else is
     * left alone. A general case-mapper is not attempted: {@code getHTMLElement} has no "case" to preserve
     * and guessing produces a rename nobody asked for.</p>
     */
    @Test
    public void preserveCaseHandlesUpperAndCapitalisedAndLeavesTheRestAlone() {
        assertEquals("BAR", TextSearch.preserveCase("FOO", "bar"));
        assertEquals("Bar", TextSearch.preserveCase("Foo", "bar"));
        assertEquals("bar", TextSearch.preserveCase("foo", "bar"));
        assertEquals("a mixed name has no case to preserve",
                "bar", TextSearch.preserveCase("getHTMLElement", "bar"));
    }

    /** A match with no letters at all is not "upper case" — it has no case. */
    @Test
    public void punctuationIsNotUpperCase() {
        assertEquals("bar", TextSearch.preserveCase("---", "bar"));
    }
}
