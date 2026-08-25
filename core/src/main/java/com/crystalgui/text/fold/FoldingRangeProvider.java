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

    /**
     * Whether {@link #compute} may run on a worker.
     *
     * <h3>False by default, and the default is the safe answer</h3>
     *
     * <p>Folding is a whole-document pass — a region can start above the viewport and end below it — so
     * it is the one part of a frame whose cost scales with the file rather than the screen. Measured on
     * a 2,020-line class: <b>{@code fold:computeRegions (IndentRangeProvider) 25.7ms}</b>, on the frame
     * that opens it.</p>
     *
     * <p>Moving that off the frame is only safe for a provider that reads <em>nothing but its arguments</em>.
     * {@link IndentRangeProvider} qualifies: a {@link Rope} is persistent, so a snapshot cannot be edited
     * underneath it, and the provider holds no state. A syntax-aware provider does <b>not</b> — a
     * tree-sitter one reads a native tree the frame thread is also querying, and answering yes there is a
     * JVM crash rather than an exception. Hence opt-in, per implementation, rather than a decision the
     * caller makes about providers it cannot see inside.</p>
     */
    default boolean computesOffThread() {
        return false;
    }

    /** A provider that finds nothing, for documents where folding is switched off. */
    static FoldingRangeProvider none() {
        return (document, tabSize) -> FoldingRegions.empty();
    }
}
