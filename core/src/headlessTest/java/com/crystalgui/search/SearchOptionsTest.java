package com.crystalgui.search;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * IntelliJ's Cc / W / .* toggles, at the layer they belong to.
 *
 * <p>Headless on purpose: the options are a property of matching, not of any widget, so all three are
 * decidable without a window, a font or a frame. Every UI step above them is presentation.</p>
 */
public class SearchOptionsTest {

    private static final SearchQuery.Options CASE =
            SearchQuery.Options.DEFAULT.withMatchCase(true);
    private static final SearchQuery.Options WORDS =
            SearchQuery.Options.DEFAULT.withWholeWords(true);
    private static final SearchQuery.Options REGEX =
            SearchQuery.Options.DEFAULT.withRegex(true);

    private static SearchMatch match(String query, SearchQuery.Options options, String candidate) {
        return SearchMatcher.match(SearchQuery.of(query, options), candidate, 0);
    }

    // ── Match case ──────────────────────────────────────────────────────────────────────────────

    /** The default is unchanged — every existing caller keeps case-insensitive matching. */
    @Test
    public void theDefaultIsStillCaseInsensitive() {
        assertNotNull(match("main", SearchQuery.Options.DEFAULT, "Main.java"));
    }

    /**
     * <b>Match case makes the two agree at both ends.</b>
     *
     * <p>The matcher used to lower-case the candidate unconditionally, which is what made this
     * unimplementable — the query is normalised to the same case now, so they agree by construction.</p>
     */
    @Test
    public void matchCaseRejectsTheWrongCase() {
        assertNull("lower-case query matched an upper-case name", match("main", CASE, "Main.java"));
        assertNotNull(match("Main", CASE, "Main.java"));
    }

    // ── Whole words ─────────────────────────────────────────────────────────────────────────────

    /** {@code cat} is in "concatenate" and is not a word of it. */
    @Test
    public void wholeWordsRefusesAMatchInsideAWord() {
        assertNotNull("the default should still find it", match("cat", SearchQuery.Options.DEFAULT,
                "concatenate"));
        assertNull(match("cat", WORDS, "concatenate"));
    }

    /** And accepts one with a boundary on each side, including the ends of the string. */
    @Test
    public void wholeWordsAcceptsARealWord() {
        assertNotNull(match("cat", WORDS, "the cat sat"));
        assertNotNull("a word at the start", match("cat", WORDS, "cat sat"));
        assertNotNull("a word at the end", match("sat", WORDS, "the cat sat"));
        assertNotNull("punctuation is a boundary", match("cat", WORDS, "a.cat.file"));
    }

    /** An underscore is a word character, which is what a reader of code expects. */
    @Test
    public void anUnderscoreIsPartOfTheWord() {
        assertNull(match("cat", WORDS, "my_cat_name"));
    }

    /** It composes with match case rather than replacing it. */
    @Test
    public void wholeWordsComposesWithMatchCase() {
        SearchQuery.Options both = WORDS.withMatchCase(true);
        assertNull(match("cat", both, "the Cat sat"));
        assertNotNull(match("Cat", both, "the Cat sat"));
    }

    // ── Regex ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void regexMatchesAndReportsItsSpan() {
        SearchMatch match = match("c.t", REGEX, "the cat sat");
        assertNotNull(match);
        assertEquals(1, match.ranges().size());
        assertEquals(4, match.ranges().get(0).start());
        assertEquals(7, match.ranges().get(0).end());
    }

    /**
     * <b>An invalid pattern is a state, not an exception.</b>
     *
     * <p>It is compiled inside a keystroke handler, so throwing would take the frame down over a
     * half-typed {@code (}. IntelliJ reds the field and reports no results.</p>
     */
    @Test
    public void anInvalidPatternMatchesNothingAndDoesNotThrow() {
        SearchQuery query = SearchQuery.of("(unclosed", REGEX);
        assertTrue("the field needs to know, to say so", query.isInvalidPattern());
        assertNull(SearchMatcher.match(query, "unclosed", 0));
    }

    /**
     * <b>A zero-width match is not a match.</b>
     *
     * <p>{@code a*} matches the empty string at position 0 of everything, which would report every
     * candidate as a hit and paint a zero-width band on each.</p>
     */
    @Test
    public void aZeroWidthPatternMatchesNothing() {
        assertNull(match("x*", REGEX, "no ex in here"));
    }

    /** Case sensitivity reaches the pattern too, rather than being a separate literal-only idea. */
    @Test
    public void regexHonoursMatchCase() {
        assertNotNull(match("c.t", REGEX, "the CAT sat"));
        assertNull(match("c.t", REGEX.withMatchCase(true), "the CAT sat"));
    }

    /**
     * <b>A pattern scores as the weakest kind.</b>
     *
     * <p>It has no meaningful place on the EXACT/PREFIX/ACRONYM ladder, and inventing a fourth ordering
     * would change how {@code QuickPick} ranks everything else.</p>
     */
    @Test
    public void aPatternScoresAsASubstring() {
        SearchMatch match = match("Main", REGEX, "Main.java");
        assertNotNull(match);
        assertEquals(SearchMatch.Kind.SUBSTRING, match.kind());
    }

    /** Compiled once per query, so the pattern is available to anything that wants to reuse it. */
    @Test
    public void thePatternIsCompiledOnceOnTheQuery() {
        SearchQuery query = SearchQuery.of("c.t", REGEX);
        assertNotNull(query.pattern());
        assertEquals("a second read must not recompile", query.pattern(), query.pattern());
    }
}
