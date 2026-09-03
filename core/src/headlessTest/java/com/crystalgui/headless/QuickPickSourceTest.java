package com.crystalgui.headless;

import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.collection.pick.QuickPickEntry;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import com.crystalgui.text.TextRange;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ranking and highlighting for the static {@link QuickPickSource}.
 *
 * <p>Headless because none of it needs pixels — the source is a pure function from a query to an ordered
 * list, which is precisely the part worth pinning. What the palette <em>does</em> with that list is the
 * widget test's problem.</p>
 */
public class QuickPickSourceTest {

    private static QuickPickSource sourceOf(QuickPickItem... items) {
        return QuickPickSource.of(List.of(items));
    }

    /** The best row a source pushes for {@code query}. */
    private static QuickPickEntry firstOf(QuickPickSource source, SearchQuery query) {
        return QuickPickSource.drain(source, query, 1000).entries().get(0);
    }

    private static List<String> idsFor(QuickPickSource source, String query) {
        List<String> ids = new ArrayList<>();
        // DRAINED THROUGH THE REAL SINK, so this exercises the contract a source actually implements
        // rather than a convenience shape that only tests use.
        for (QuickPickEntry entry
                : QuickPickSource.drain(source, SearchQuery.of(query), 1000).entries()) {
            ids.add(entry.item().id());
        }
        return ids;
    }

    // ── Empty query ─────────────────────────────────────────────────────────────────────────────

    /**
     * Everything, alphabetically by category then label.
     *
     * <p>Not registry order. A palette opens on this list and pre-selects its first row, so an arbitrary
     * order means Enter on an untouched palette does an arbitrary thing — and the order would change
     * whenever an unrelated command was registered.</p>
     */
    @Test
    public void anEmptyQueryListsEverythingAlphabeticallyWithinCategory() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("z", "Zoom In", "View", null),
                new QuickPickItem("a", "Apply", "File", null),
                new QuickPickItem("s", "Save", "File", null));

        assertEquals(List.of("a", "s", "z"), idsFor(source, ""));
    }

    @Test
    public void anEmptyQueryHighlightsNothing() {
        QuickPickSource source = sourceOf(new QuickPickItem("a", "Apply", "File", null));
        QuickPickEntry entry = firstOf(source, SearchQuery.EMPTY);
        assertTrue(entry.labelRanges().isEmpty());
        assertTrue(entry.categoryRanges().isEmpty());
    }

    // ── Ranking ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A label hit outranks a category hit.</b>
     *
     * <p>The bug this whole field-weighting scheme exists for, restated at the palette level. Querying
     * {@code dock} must surface the command <em>called</em> "Dock…" above every command that merely lives
     * in the Dock category — otherwise Enter runs the wrong one, which is exactly how the node create menu
     * created a Cross Product when asked for {@code vec}.</p>
     */
    @Test
    public void aLabelHitOutranksACategoryHit() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("split", "Split Right", "Dock", null),
                new QuickPickItem("float", "Dock Floating Panel", "View", null));

        assertEquals(List.of("float", "split"), idsFor(source, "dock"));
    }

    @Test
    public void aNonMatchIsAbsentRatherThanScoredZero() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("a", "Apply", "File", null),
                new QuickPickItem("z", "Zoom In", "View", null));

        assertEquals(List.of("a"), idsFor(source, "appl"));
    }

    @Test
    public void anExactPrefixBeatsALaterSubstring() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("late", "Unsplit Editor", "Dock", null),
                new QuickPickItem("early", "Split Editor", "Dock", null));

        assertEquals("early", idsFor(source, "split").get(0));
    }

    // ── Highlighting ────────────────────────────────────────────────────────────────────────────

    /**
     * Ranges are reported against the field they matched, with no offset for the rendered
     * {@code "Category: Label"} form.
     *
     * <p>That is the whole reason the row renders category and label as two elements. A single glued
     * string would need every label range shifted by {@code category.length() + 2}, recomputed at every
     * bind, and wrong the moment a category is absent.</p>
     */
    @Test
    public void labelRangesAreRelativeToTheLabelAlone() {
        QuickPickSource source = sourceOf(new QuickPickItem("s", "Split Right", "Dock", null));

        QuickPickEntry entry = firstOf(source, SearchQuery.of("split"));
        assertEquals(List.of(TextRange.of(0, 5)), entry.labelRanges());
        assertTrue("a label hit must not also light up the category", entry.categoryRanges().isEmpty());
    }

    /**
     * Only the winning field is highlighted.
     *
     * <p>Lighting up both would claim the category contributed to the ranking when a label hit outranks it
     * outright — the row would look like it placed there for a reason it did not.</p>
     */
    @Test
    public void aCategoryOnlyHitHighlightsTheCategoryAndNotTheLabel() {
        QuickPickSource source = sourceOf(new QuickPickItem("s", "Split Right", "Dock", null));

        QuickPickEntry entry = firstOf(source, SearchQuery.of("dock"));
        assertFalse(entry.categoryRanges().isEmpty());
        assertTrue(entry.labelRanges().isEmpty());
    }

    @Test
    public void anItemWithNoCategoryStillMatchesOnItsLabel() {
        QuickPickSource source = sourceOf(QuickPickItem.of("a", "Apply"));
        assertEquals(List.of("a"), idsFor(source, "app"));
    }

    @Test
    public void displayTextOmitsTheSeparatorWhenThereIsNoCategory() {
        assertEquals("Apply", QuickPickItem.of("a", "Apply").displayText());
        assertEquals("File: Apply", QuickPickItem.of("a", "Apply", "File").displayText());
    }

    // ── Streaming, and saying when there was more ───────────────────────────────────────

    /** A source that pushes {@code count} rows and stops when told to. */
    private static QuickPickSource pushing(int count) {
        return (query, sink) -> {
            for (int i = 0; i < count; i++) {
                if (!sink.accept(QuickPickEntry.plain(QuickPickItem.of("id" + i, "Row " + i)))) return;
            }
        };
    }

    /**
     * <b>A source is stopped by the sink, not by being asked for a number.</b>
     *
     * <p>The whole reason the contract pushes. A source over sixty thousand entries must be able to give
     * up as soon as the consumer has enough — which it cannot do if its job is to return a finished list.
     * Asserted by counting what the source was <em>able</em> to push, not what came back.</p>
     */
    @Test
    public void aSourceStopsAsSoonAsTheSinkHasEnough() {
        int[] pushed = { 0 };
        QuickPickSource counting = (query, sink) -> {
            for (int i = 0; i < 500; i++) {
                pushed[0]++;
                if (!sink.accept(QuickPickEntry.plain(QuickPickItem.of("id" + i, "Row " + i)))) return;
            }
        };

        QuickPickSource.Batch batch = QuickPickSource.drain(counting, SearchQuery.EMPTY, 5);

        assertEquals(5, batch.entries().size());
        assertTrue("the source was left running past the cap: " + pushed[0] + " pushed", pushed[0] <= 6);
    }

    /**
     * <b>Hitting the cap is reported, whether or not the source noticed.</b>
     *
     * <p>The single most important thing on the sink, and the easiest to leave out. A list that silently
     * stops is indistinguishable from a complete one, so a row past the cap looks exactly like a row that
     * does not exist — wrong, and wrong in the direction that stops the user looking. The source here says
     * nothing about truncation; the sink works it out from the cap having bitten.</p>
     */
    @Test
    public void reachingTheCapIsReportedWithoutTheSourceSayingSo() {
        assertTrue("the cap bit and nothing said so",
                QuickPickSource.drain(pushing(20), SearchQuery.EMPTY, 5).truncated());
    }

    /** <b>...and a list that fits is not reported as cut.</b> The other half, and the noisier failure. */
    @Test
    public void aListThatFitsIsNotReportedAsTruncated() {
        QuickPickSource.Batch batch = QuickPickSource.drain(pushing(3), SearchQuery.EMPTY, 5);
        assertEquals(3, batch.entries().size());
        assertFalse("a complete list claimed to be truncated", batch.truncated());
    }

    /**
     * <b>A source may report truncation of its own.</b>
     *
     * <p>The case the cap cannot see: an index that narrowed before it answered knows it left rows behind
     * even when everything it pushed fits comfortably. {@code TypeSearch.Results.truncated} is exactly
     * that, and it has to survive the journey to the header.</p>
     */
    @Test
    public void aSourceCanReportTruncationTheCapCannotSee() {
        QuickPickSource narrowed = (query, sink) -> {
            sink.accept(QuickPickEntry.plain(QuickPickItem.of("a", "Alpha")));
            sink.markTruncated();
        };

        QuickPickSource.Batch batch = QuickPickSource.drain(narrowed, SearchQuery.EMPTY, 100);
        assertEquals(1, batch.entries().size());
        assertTrue("the source said there was more and it was lost", batch.truncated());
    }

    // ── Availability and context ────────────────────────────────────────────────────────────────

    /**
     * <b>A row you can choose beats one you cannot, when they matched equally well.</b>
     *
     * <p>The fixture is the reported case in miniature: {@code Redo} is six characters shorter than
     * {@code Reset Zoom}, and brevity is part of {@code SearchMatch.score()} — so sorting on the score
     * alone put an unavailable row above an available one and there was nothing in the ranking able to
     * say otherwise. Both are prefix hits on the label, so nothing about the <em>match</em> separates
     * them.</p>
     */
    @Test
    public void anAvailableRowOutranksAnUnavailableOneThatMatchedNoBetter() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("redo", "Redo", "Edit", null, false),
                new QuickPickItem("reset", "Reset Zoom", "Editor", null, true));

        assertEquals("brevity still outranks being usable", List.of("reset", "redo"), idsFor(source, "re"));
    }

    /**
     * <b>...and a better match still wins outright, unavailable or not.</b>
     *
     * <p>The counter-assertion, and it is not a formality: a ranking written as "every enabled row first"
     * satisfies the test above and makes searching for a command by its full name stop finding it. An
     * exact hit sits a whole tier above a prefix one, and a tier is not something availability may
     * cross.</p>
     */
    @Test
    public void aBetterMatchStillWinsOverAvailability() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("exact", "Redo", "Edit", null, false),
                new QuickPickItem("prefix", "Redo All", "Edit", null, true));

        assertEquals(List.of("exact", "prefix"), idsFor(source, "redo"));
    }

    /**
     * <b>Of the rows that work, the ones that work because of where you are come first.</b>
     *
     * <p>{@code Reload} is the shorter label, so brevity favours it and the ordering below can only come
     * from the contextual flag. @see QuickPickItem#contextual</p>
     */
    @Test
    public void aContextualRowOutranksAGlobalOneThatMatchedNoBetter() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("global", "Reload", "Explorer", null, true),
                new QuickPickItem("here", "Reset Zoom", "Editor", null, true).withContextual(true));

        assertEquals(List.of("here", "global"), idsFor(source, "re"));
    }

    /**
     * The untouched palette obeys the same rule.
     *
     * <p>One ordering rule rather than two: a list whose principle changed the moment you typed a
     * character would be the harder of the two to learn. Alphabetical remains, as the tiebreak it always
     * was — {@code Apply} would otherwise lead.</p>
     */
    @Test
    public void anEmptyQueryPutsAvailableRowsFirstToo() {
        QuickPickSource source = sourceOf(
                new QuickPickItem("a", "Apply", "File", null, false),
                new QuickPickItem("z", "Zoom In", "View", null, true));

        assertEquals(List.of("z", "a"), idsFor(source, ""));
    }
}
