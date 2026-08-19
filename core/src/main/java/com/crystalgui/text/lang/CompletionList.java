package com.crystalgui.text.lang;

import java.util.List;

/**
 * A completion result, and whether it is the whole answer.
 *
 * <h3>{@code isIncomplete} is the only reason this is not just a {@code List}</h3>
 *
 * <p>A completion session survives typing: the popup opens once and each subsequent keystroke normally
 * re-filters the list it already has, which is what makes it feel instant. That is only correct when the
 * list is <em>complete</em> — when it contains everything that could ever match, so narrowing it locally
 * can never miss something.</p>
 *
 * <p>An unimported-type list is not complete: a modpack's classpath is tens of thousands of types, so a
 * provider returns the best few hundred for what has been typed so far and says so. Filtering that locally
 * as more characters arrive silently drops every type that was ranked out of the first answer, and the
 * symptom is the worst kind — completion that works for common names and quietly fails for the one you are
 * actually looking for. So {@code isIncomplete} means <b>re-query on the next keystroke</b>, and it is a
 * property of the answer because only the provider knows.</p>
 *
 * <p>LSP's field, VS Code's behaviour, and the same reasoning IntelliJ's second-basic-completion pass
 * rests on.</p>
 *
 * @param items      the rows, already in the provider's preferred order; ranking is applied on top
 * @param incomplete whether typing more must re-query rather than re-filter
 */
public record CompletionList(List<CompletionItem> items, boolean incomplete) {

    /** No completions here — and a complete answer, so the session may filter it away and stay shut. */
    public static final CompletionList EMPTY = new CompletionList(List.of(), false);

    public CompletionList {
        items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    /** Everything there is — safe to narrow locally as the user types. */
    public static CompletionList complete(List<CompletionItem> items) {
        return new CompletionList(items, false);
    }

    /** The best available so far — the next keystroke must ask again. */
    public static CompletionList partial(List<CompletionItem> items) {
        return new CompletionList(items, true);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }
}
