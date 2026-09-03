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
 * same shape as {@code fs.client.ContentProvider}: {@code core/} declares the question,
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

    /**
     * One type: enough to draw a row and to open it.
     *
     * @param packageName for a NESTED type this is the enclosing type rather than a package, so
     *                    {@link #qualifiedName} spells the name an author writes
     * @param binaryName  what the class file is called — {@code Outer$Inner}, and equal to
     *                    {@link #qualifiedName} for everything else
     */
    record Result(String simpleName, String packageName, @Nullable String container,
                  @Nullable SymbolKind kind, boolean isAbstract, String binaryName) {

        public Result {
            if (simpleName == null) throw new IllegalArgumentException("simpleName");
            packageName = packageName == null ? "" : packageName;
            if (binaryName == null || binaryName.isEmpty()) {
                binaryName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            }
        }

        /** A top-level type, whose two spellings are the same. */
        public Result(String simpleName, String packageName, @Nullable String container,
                      @Nullable SymbolKind kind, boolean isAbstract) {
            this(simpleName, packageName, container, kind, isAbstract, null);
        }

        /**
         * The name an author writes — {@code java.util.Map.Entry}.
         *
         * <h3>Why this is no longer the identity too</h3>
         *
         * <p>It used to be both, deliberately: <i>"two spellings of one name is how they come to
         * disagree"</i>. That holds while every type is top-level and stops holding the moment a nested
         * one is indexed — no class file is called {@code WorldSettings.GameType}, so addressing a
         * {@code library:} resource by this name opened an empty document. The spellings are now both
         * carried rather than one being derived from the other, which is the only way to stop them
         * disagreeing when they genuinely differ. @see #binaryName
         */
        public String qualifiedName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }

        /**
         * The TOP-LEVEL class holding this type — itself, unless it is nested.
         *
         * <p>What a {@code library:} resource must be addressed by: a member type has no class file and
         * no document of its own, so opening one means opening the file it lives in.</p>
         */
        public String topLevelName() {
            int nested = binaryName.indexOf('$');
            return nested < 0 ? binaryName : binaryName.substring(0, nested);
        }

        /** Whether this type is declared inside another. */
        public boolean isNested() {
            return binaryName.indexOf('$') >= 0;
        }

        /**
         * The package alone.
         *
         * <p>Not {@link #packageName}, which for a nested type names the ENCLOSING TYPE so that
         * {@code qualifiedName} spells something importable. A row that wants to say "in Outer of
         * package" needs the two apart.</p>
         */
        public String packageOnly() {
            String top = topLevelName();
            int dot = top.lastIndexOf('.');
            return dot < 0 ? "" : top.substring(0, dot);
        }

        /**
         * The enclosing type chain in source form — {@code WorldSettings}, or {@code Outer.Inner} for one
         * nested two deep. Empty for a top-level type.
         */
        public String enclosingName() {
            int nested = binaryName.lastIndexOf('$');
            if (nested < 0) return "";
            String chain = binaryName.substring(0, nested);
            int dot = topLevelName().lastIndexOf('.');
            return (dot < 0 ? chain : chain.substring(dot + 1)).replace('$', '.');
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
