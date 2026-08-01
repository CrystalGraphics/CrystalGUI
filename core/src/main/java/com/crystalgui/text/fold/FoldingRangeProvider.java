package com.crystalgui.text.fold;

import com.crystalgui.text.Rope;

/**
 * Where foldable regions come from.
 *
 * <p><b>VS Code's {@code RangeProvider}</b> ({@code folding.ts}), reduced to the one method that matters
 * here — there is no cancellation token because this engine computes folds synchronously on the frame that
 * needs them, and no {@code dispose} because no implementation owns a resource.</p>
 *
 * <h3>The seam is the point</h3>
 * <p>{@link IndentRangeProvider} is the default and needs nothing but the text. A syntax-aware provider —
 * tree-sitter, once a grammar is loaded — implements this same interface and returns better regions for
 * the languages it knows, with the indent provider still answering for everything else. That is exactly
 * how VS Code layers {@code SyntaxRangeProvider} over {@code IndentRangeProvider}, and it is the same
 * arrangement {@code SyntaxTokenizer} already has in this package's neighbour.</p>
 *
 * <p>Regions must come back <b>sorted by start row and strictly nested</b>. {@link FoldingRegions} binary
 * searches them and packs parent indices on that assumption; a provider that returns them out of order
 * produces wrong answers rather than an exception.</p>
 */
@FunctionalInterface
public interface FoldingRangeProvider {

    /**
     * @param tabSize columns a tab advances — folding is indentation-sensitive, so this is not cosmetic
     */
    FoldingRegions compute(Rope document, int tabSize);

    /** A provider that finds nothing, for documents where folding is switched off. */
    static FoldingRangeProvider none() {
        return (document, tabSize) -> FoldingRegions.empty();
    }
}
