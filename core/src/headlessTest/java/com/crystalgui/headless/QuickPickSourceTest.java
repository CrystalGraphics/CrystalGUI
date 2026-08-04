package com.crystalgui.headless;

import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.ui.elements.chrome.QuickPickEntry;
import com.crystalgui.ui.elements.chrome.QuickPickItem;
import com.crystalgui.ui.elements.chrome.QuickPickSource;
import com.crystalgui.ui.text.TextRange;
import org.junit.Test;

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

    private static List<String> idsFor(QuickPickSource source, String query) {
        return source.query(SearchQuery.of(query)).stream().map(e -> e.item().id()).toList();
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
        QuickPickEntry entry = source.query(SearchQuery.EMPTY).get(0);
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

        QuickPickEntry entry = source.query(SearchQuery.of("split")).get(0);
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

        QuickPickEntry entry = source.query(SearchQuery.of("dock")).get(0);
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
}
