package com.crystalgui.core.collection.pick;

import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import com.crystalgui.ui.text.TextRange;

import java.util.Collections;
import java.util.List;

/**
 * One item as a {@link QuickPickSource} decided to present it — the item, plus which characters matched.
 *
 * <h3>Why the ranges travel with the result rather than being recomputed by the widget</h3>
 *
 * <p>Because the widget does not know how the source matched. A static list matches with
 * {@link com.crystalgui.core.search.SearchMatcher}; a server-backed file picker matches on the far side of
 * an RPC and returns ranges it computed there. If {@link QuickPick} re-derived the highlight itself it
 * would have to reimplement every source's matching rule, and would silently disagree with the ranking it
 * is displaying — rows sorted by one rule and lit up by another.</p>
 *
 * <p>The two range lists are separate rather than one list plus a discriminator because the row renders
 * category and label as two elements, so each list is applied to its own {@code UIText} with no offset
 * arithmetic anywhere. Both may be empty — an empty query matches nothing and highlights nothing.</p>
 *
 * @param item             the row
 * @param labelRanges      characters to highlight within {@link QuickPickItem#label()}
 * @param categoryRanges   characters to highlight within {@link QuickPickItem#category()}
 */
public record QuickPickEntry(QuickPickItem item, List<TextRange> labelRanges,
                             List<TextRange> categoryRanges) {

    public QuickPickEntry {
        if (item == null) throw new IllegalArgumentException("QuickPickEntry item must not be null");
        labelRanges = labelRanges == null ? Collections.emptyList() : List.copyOf(labelRanges);
        categoryRanges = categoryRanges == null ? Collections.emptyList() : List.copyOf(categoryRanges);
    }

    /** An unhighlighted row — what an empty query produces, and what a source with no ranges to report
     * should return rather than fabricating one. */
    public static QuickPickEntry plain(QuickPickItem item) {
        return new QuickPickEntry(item, Collections.emptyList(), Collections.emptyList());
    }
}
