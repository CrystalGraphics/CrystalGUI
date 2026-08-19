package com.crystalgui.text.lang;

/**
 * What is true of a symbol beyond its kind — the small set a consumer in this repository actually reads.
 *
 * <p>Deliberately not a mirror of Java's modifier set. {@code synchronized}, {@code transient},
 * {@code volatile} and {@code strictfp} are real and nothing here would draw them differently, and a
 * constant that no consumer reads is a field somebody has to keep populated for nothing. Three consumers
 * exist or are planned — hover, completion rendering, and semantic-token styling — and between them they
 * read exactly these four.</p>
 *
 * <p>{@link #DEPRECATED} is the one with a visual contract already in place: it is drawn struck through,
 * matching {@link com.crystalgui.text.diagnostic.DiagnosticTag}'s own deprecated handling, so the two
 * paths agree about what a deprecated thing looks like.</p>
 */
public enum SymbolModifier {

    /** Reached through the type rather than an instance — decides completion's icon and its ranking. */
    STATIC,

    /** Struck through wherever it is drawn. */
    DEPRECATED,

    ABSTRACT,

    /** {@code final}, or a language's equivalent. With {@link SymbolKind#FIELD} plus {@link #STATIC} this
     * is what makes a name a constant, which is why the kind and the modifiers are both needed. */
    FINAL
}
