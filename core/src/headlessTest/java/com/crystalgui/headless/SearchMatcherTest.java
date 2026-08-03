package com.crystalgui.headless;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.3.9 — the search matcher.
 *
 * <h3>Headless on purpose, and that is an assertion rather than a convenience</h3>
 * <p>This lives in {@code headlessTest}, where CrystalGraphics core is deliberately absent. Search is a
 * general facility the create menu merely happens to be the first consumer of, so it must not reach a
 * widget, a font or a GL type — and the source set proves it: a stray dependency fails here with
 * {@code NoClassDefFoundError} rather than being discovered when someone tries to reuse it.</p>
 */
public class SearchMatcherTest {

    private static final int NAME = SearchMatch.FIELD_PRIMARY;
    private static final int ALIAS = SearchMatch.FIELD_ALIAS;
    private static final int CATEGORY = SearchMatch.FIELD_CONTEXT;

    private static SearchMatch match(String query, String candidate, int weight) {
        return SearchMatcher.match(SearchQuery.of(query), candidate, weight);
    }

    // ── The kinds ───────────────────────────────────────────────────────────

    @Test
    public void exactBeatsPrefixBeatsAcronymBeatsSubstring() {
        SearchMatch exact = match("add", "Add", NAME);
        SearchMatch prefix = match("vec", "Vector 2", NAME);
        SearchMatch acronym = match("cp", "Cross Product", NAME);
        SearchMatch substring = match("duct", "Cross Product", NAME);

        assertEquals(SearchMatch.Kind.EXACT, exact.kind());
        assertEquals(SearchMatch.Kind.PREFIX, prefix.kind());
        assertEquals(SearchMatch.Kind.ACRONYM, acronym.kind());
        assertEquals(SearchMatch.Kind.SUBSTRING, substring.kind());

        assertTrue(exact.score() > prefix.score());
        assertTrue(prefix.score() > acronym.score());
        assertTrue(acronym.score() > substring.score());
    }

    @Test
    public void matchingIsCaseInsensitiveBothWays() {
        assertNotNull(match("VECTOR", "Vector 2", NAME));
        assertNotNull(match("vector", "VECTOR 2", NAME));
        assertEquals(SearchMatch.Kind.EXACT, match("ADD", "add", NAME).kind());
    }

    @Test
    public void aQueryThatDoesNotAppearDoesNotMatch() {
        assertNull(match("zzz", "Cross Product", NAME));
        assertNull("longer than the candidate", match("vectorvector", "Vector", NAME));
    }

    /** A blank query is a decision about what to SHOW, not a zero-scoring match — see the matcher's note. */
    @Test
    public void anEmptyQueryMatchesNothingRatherThanEverything() {
        assertNull(match("", "Anything", NAME));
        assertNull(match("   ", "Anything", NAME));
        assertTrue(SearchQuery.of("   ").isEmpty());
    }

    // ── Ranges ──────────────────────────────────────────────────────────────

    /** The offsets a highlight will tint. Wrong here means the wrong characters light up. */
    @Test
    public void rangesReportExactlyWhatMatched() {
        List<SearchMatch.Range> prefix = match("vec", "Vector 2", NAME).ranges();
        assertEquals(1, prefix.size());
        assertEquals(0, prefix.get(0).start());
        assertEquals(3, prefix.get(0).end());

        List<SearchMatch.Range> substring = match("vector", "Normal Vector", NAME).ranges();
        assertEquals(1, substring.size());
        assertEquals(7, substring.get(0).start());
        assertEquals(13, substring.get(0).end());
    }

    /** An acronym marks each word start it landed on — several ranges, one character each. */
    @Test
    public void anAcronymReportsOneRangePerMatchedWordStart() {
        List<SearchMatch.Range> ranges = match("cp", "Cross Product", NAME).ranges();
        assertEquals(2, ranges.size());
        assertEquals(0, ranges.get(0).start());
        assertEquals(6, ranges.get(1).start());
    }

    @Test
    public void acronymReadsCamelCaseAndUnderscoresAsWordStarts() {
        assertEquals(SearchMatch.Kind.ACRONYM, match("rz", "rotateZ", NAME).kind());
        assertEquals(SearchMatch.Kind.ACRONYM, match("sm", "sphere_mask", NAME).kind());
    }

    /**
     * <b>Not a general fuzzy matcher.</b> An arbitrary subsequence must NOT match, or a few hundred short
     * labels produce a long tail nobody meant — the failure a search box exists to avoid.
     */
    @Test
    public void anArbitrarySubsequenceIsNotAMatch() {
        assertNull("v..t..r inside 'Vector' is a subsequence, not a word-start acronym",
                match("vtr", "Vector", NAME));
        assertNull(match("cs", "Clamp", NAME));
    }

    // ── Field dominates kind — the actual bug fix ───────────────────────────

    /**
     * <b>The regression that motivated all of this.</b>
     *
     * <p>The create menu ranked alphabetically, so {@code vec} pre-selected <b>Cross Product</b> — matched
     * only through its {@code Math/Vector} category — while {@code Vector 2} sat twelfth, and Enter
     * created the wrong node. Any hit on a NAME must outrank any hit on a category.</p>
     */
    @Test
    public void aNameMatchAlwaysOutranksACategoryMatch() {
        SearchMatch vector2 = match("vec", "Vector 2", NAME);
        SearchMatch crossProductByCategory = match("vec", "Math/Vector", CATEGORY);

        assertNotNull(vector2);
        assertNotNull(crossProductByCategory);
        assertTrue("Vector 2 must rank above a category-only hit",
                vector2.score() > crossProductByCategory.score());
    }

    /** Even the WEAKEST name match beats the STRONGEST category match — the weights cannot be crossed. */
    @Test
    public void theWeakestNameMatchStillBeatsAnExactCategoryMatch() {
        SearchMatch weakestName = match("duct", "Cross Product", NAME);      // substring, late, long
        SearchMatch exactCategory = match("vector", "Vector", CATEGORY);      // exact

        assertEquals(SearchMatch.Kind.SUBSTRING, weakestName.kind());
        assertEquals(SearchMatch.Kind.EXACT, exactCategory.kind());
        assertTrue(weakestName.score() > exactCategory.score());
    }

    /** And an alias sits between the two — a synonym is a name, just not the primary one. */
    @Test
    public void anAliasRanksBetweenANameAndACategory() {
        SearchMatch name = match("duct", "Cross Product", NAME);
        SearchMatch alias = match("plus", "plus", ALIAS);
        SearchMatch category = match("math", "Math", CATEGORY);

        assertTrue(name.score() > alias.score());
        assertTrue(alias.score() > category.score());
    }

    // ── Tie-breaking within a field ─────────────────────────────────────────

    /** {@code vec} means Vector 2 rather than the same prefix buried in a much longer label. */
    @Test
    public void aShorterCandidateWinsAmongEqualKinds() {
        SearchMatch shortOne = match("vec", "Vector 2", NAME);
        SearchMatch longOne = match("vec", "Vector Rotate About Axis", NAME);

        assertEquals(shortOne.kind(), longOne.kind());
        assertTrue(shortOne.score() > longOne.score());
    }

    /** An earlier hit wins among equal kinds — but never crosses into the kind above. */
    @Test
    public void anEarlierHitWinsButNeverOutranksAStrongerKind() {
        SearchMatch early = match("or", "Normal Vector", NAME);
        SearchMatch late = match("or", "Rotate About Axis Or Something", NAME);
        assertTrue(early.score() > late.score());

        assertTrue("no positional bonus may promote a substring above a prefix",
                match("vec", "Vector 2", NAME).score() > early.score());
    }

    // ── matchAny ────────────────────────────────────────────────────────────

    @Test
    public void matchAnyTakesTheStrongestOfSeveralCandidates() {
        SearchMatch best = SearchMatcher.matchAny(SearchQuery.of("plus"),
                List.of("sum", "plus", "addition"), ALIAS);

        assertNotNull(best);
        assertEquals("the exact synonym, not the first that merely matched",
                SearchMatch.Kind.EXACT, best.kind());
    }

    @Test
    public void matchAnyIsNullWhenNothingMatches() {
        assertNull(SearchMatcher.matchAny(SearchQuery.of("zzz"), List.of("a", "b"), ALIAS));
        assertNull(SearchMatcher.matchAny(SearchQuery.of("a"), List.of(), ALIAS));
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    /** A natural sort is already ranked, so a caller does not have to remember which way round it goes. */
    @Test
    public void sortingAResultListPutsTheBestFirst() {
        List<SearchMatch> matches = new ArrayList<>(List.of(
                match("vec", "Math/Vector", CATEGORY),
                match("vec", "Vector 2", NAME),
                match("vec", "Normal Vector", NAME)));

        matches.sort(null);

        assertEquals(SearchMatch.Kind.PREFIX, matches.get(0).kind());
        assertEquals(SearchMatch.Kind.SUBSTRING, matches.get(1).kind());
        assertEquals("the category-only hit sinks to the bottom",
                SearchMatch.FIELD_CONTEXT, matches.get(2).fieldWeight());
    }
}
