package com.crystalgui.text.lang;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * "Which types on the classpath are called something like this?" — the seam behind Go to Class.
 *
 * <h3>Why this exists at all rather than the picker asking the index</h3>
 *
 * <p>The answer already exists, fully built and paid for: {@code TypeIndex} in {@code language/} scans the
 * classpath once for completion and holds up to sixty thousand entries. But <b>{@code core/} must never
 * depend on {@code language/}</b> — that is what keeps tree-sitter's platform natives and ECJ's fifteen
 * megabytes off a dedicated server — so the picker cannot name it. This is the inversion, and it is the
 * same shape as {@link com.crystalgui.fs.ResourceContentProvider}: {@code core/} declares the question,
 * an engine answers it from the other side.</p>
 *
 * <h3>What a provider owes, and what it does not</h3>
 *
 * <p>It owes <b>candidates</b>, narrowed however it likes and bounded by {@code limit}. It does <b>not</b>
 * owe ranking or match ranges, and deliberately: this codebase has one matcher, and the rule recorded
 * against it is that a list ranked by one rule and highlighted by another reads as a highlighting bug when
 * it is really a disagreement about matching. So {@link com.crystalgui.core.search.SearchMatcher} runs
 * once, in the picker, over whatever comes back — and a second engine contributing types cannot bring a
 * second notion of "better match" with it.</p>
 *
 * <p>The cost is that a provider's own narrowing happens first, so the picker re-ranks a pre-filtered set
 * rather than the whole classpath. That is the correct trade at this size — sixty thousand entries is not
 * a list to hand across a seam per keystroke — but it means {@link Results#truncated} is load-bearing.</p>
 *
 * @see TypeSearchRegistry
 */
@FunctionalInterface
public interface TypeSearch {

    /**
     * Types whose <b>simple name</b> is a plausible answer to {@code query}.
     *
     * @param query never null, never empty — an empty query lists nothing, because "every type on the
     *              classpath" is not a list anybody wants and is not one this can bound usefully
     * @param limit the most results wanted; a provider may return fewer, never more
     */
    Results search(String query, int limit);

    /** One type: enough to draw a row and to open it. */
    record Result(String simpleName, String packageName, @Nullable String container,
                  @Nullable SymbolKind kind, boolean isAbstract) {

        public Result {
            if (simpleName == null) throw new IllegalArgumentException("simpleName");
            packageName = packageName == null ? "" : packageName;
        }

        /**
         * The binary name, which is <b>also the identity</b> — what a picker hands back and what a
         * {@code library:} resource is addressed by. Derived rather than stored: two spellings of one
         * name is how they come to disagree.
         */
        public String qualifiedName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }
    }

    /**
     * What a search found, and whether there was more of it.
     *
     * <p>The second half is not bookkeeping. Every index here is bounded — {@code TypeIndex} caps at sixty
     * thousand entries and forty results per bucket — and <b>a truncated list is indistinguishable from a
     * complete one</b> unless it says so. A type that exists but fell past the cap then looks exactly like
     * a type that does not exist, which is the worst answer a search can give: it is wrong, and it is
     * wrong in the direction that stops the user looking.</p>
     */
    record Results(List<Result> results, boolean truncated) {

        public Results {
            results = results == null ? Collections.emptyList() : List.copyOf(results);
        }

        public static final Results EMPTY = new Results(Collections.emptyList(), false);

        public boolean isEmpty() {
            return results.isEmpty();
        }
    }
}
