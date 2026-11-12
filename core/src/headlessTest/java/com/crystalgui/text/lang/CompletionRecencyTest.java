package com.crystalgui.text.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The recency service: what was accepted lately ranks higher, read as a snapshot that later accepts do not move. */
public class CompletionRecencyTest {

    private static CompletionItem item(String label) {
        return CompletionItem.of(label, SymbolKind.METHOD);
    }

    @Test
    public void theLatestAcceptedRanksHighestAndNeverAcceptedIsZero() {
        CompletionRecency recency = new CompletionRecency();
        recency.note(item("out"));
        recency.note(item("err"));

        CompletionRecency.Snapshot ranks = recency.snapshot();
        assertTrue(ranks.rankOf(item("err")) > ranks.rankOf(item("out")));
        assertEquals(0, ranks.rankOf(item("in")));
    }

    @Test
    public void aSnapshotIsNotMovedByWhatIsAcceptedAfterIt() {
        CompletionRecency recency = new CompletionRecency();
        recency.note(item("out"));
        CompletionRecency.Snapshot before = recency.snapshot();

        recency.note(item("err"));

        assertEquals("a sort in progress keeps one ordering", 0, before.rankOf(item("err")));
        assertEquals(2, recency.snapshot().rankOf(item("err")));
    }

    @Test
    public void twoRecenciesKeepSeparateHistories() {
        CompletionRecency mine = new CompletionRecency();
        CompletionRecency yours = new CompletionRecency();
        mine.note(item("out"));

        assertEquals(0, yours.snapshot().rankOf(item("out")));
    }
}
