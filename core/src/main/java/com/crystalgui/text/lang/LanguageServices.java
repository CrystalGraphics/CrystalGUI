package com.crystalgui.text.lang;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;

import javax.annotation.Nullable;

/**
 * Everything an engine offers about one document, behind one handle.
 *
 * <h3>Its absence is the feature flag, and there is no other one</h3>
 *
 * <p>{@code core/} must load and work on a dedicated server with no GL, no natives and no compiler — so
 * every consumer of this treats null as ordinary rather than as a degraded mode. An editor with no
 * services highlights from the grammar (or from the keyword lexer, if the grammar module is absent too),
 * offers no completion, and resolves nothing. That is three tiers of graceful degradation expressed
 * entirely as "is this reference null", which is why there is no {@code enableSemanticHighlighting}
 * setting anywhere: a boolean that can disagree with what is actually loaded is a second source of truth
 * about the same fact.</p>
 *
 * <h3>Per document, not per editor — and that distinction has a cost if it is got wrong</h3>
 *
 * <p>The same file open in two split panes is <b>one</b> document and must be one set of services: a
 * compile is expensive, and two editors each holding their own would double every compile, publish two
 * competing diagnostic sets into the same {@link com.crystalgui.text.diagnostic.DiagnosticSet}, and
 * disagree about what version they had reached. The lifecycle therefore follows the document — created
 * when it opens, {@linkplain #close() closed} when it closes, unaffected by how many views exist.</p>
 *
 * <h3>Implement only what the engine has</h3>
 *
 * <p>All three accessors default to their {@code NONE} constants, so a GLSL adapter that only reports
 * diagnostics implements this interface and overrides nothing. That is deliberate here and deliberately
 * <em>not</em> the rule on the platform SPI, which refuses defaults so a new platform cannot silently
 * inherit "no sound, no cursor". The difference is that these have an honest empty answer and a platform
 * service does not: "this language offers no completion" is a fact about the language, while "this
 * platform has no clipboard" is usually a fact about someone forgetting to write one.</p>
 *
 * <h3>Diagnostics are not here, because they already have a home</h3>
 *
 * <p>The obvious fourth accessor would be {@code diagnostics()}, and it would be a second place for
 * something that already exists: every document has a {@link com.crystalgui.text.diagnostic.DiagnosticSet}
 * with a per-owner model, precisely so independent producers can publish without clobbering each other.
 * An engine publishes with {@code set.changeOne(services.id(), list)} and the Problems panel, the
 * inspection widget and the status bar all read it through paths that already work. Mirroring the list
 * here would mean two copies with no rule about which is authoritative.</p>
 */
public interface LanguageServices extends AutoCloseable {

    /**
     * Builds services for one document.
     *
     * <p>Handed the buffer rather than its text: an engine subscribes to {@code buffer.onChanged} and
     * schedules its own work, which is what keeps the editor from having to know that compiling exists.
     * {@code TextBuffer.version()} is the stamp every answer carries.</p>
     *
     * <p>{@code resource} may be null — a scratch editor, a harness scene, the shader graph's emitted
     * source. An engine that needs a path to resolve against (an import, a sibling file) returns limited
     * services rather than refusing; a script that has not been saved is still worth colouring.</p>
     */
    @FunctionalInterface
    interface Factory {
        LanguageServices create(TextBuffer buffer, @Nullable Resource resource);
    }

    /**
     * Who these are, for {@link com.crystalgui.text.diagnostic.DiagnosticSet#changeOne} and for anything
     * that wants to say which engine answered — {@code "java"}, {@code "glsl"}.
     *
     * <p>Stable across the document's life, and unique per engine rather than per document: the owner key
     * exists so a second engine's diagnostics do not erase the first's, and two documents never share a
     * set.</p>
     */
    String id();

    /** Colouring the engine knows and the grammar cannot. @see SemanticTokenProvider */
    default SemanticTokenProvider semanticTokens() {
        return SemanticTokenProvider.NONE;
    }

    /** Hover, go-to-definition, and completion's type questions. @see Resolver */
    default Resolver resolver() {
        return Resolver.NONE;
    }

    /** What could go here. @see CompletionProvider */
    default CompletionProvider completion() {
        return CompletionProvider.NONE;
    }

    /**
     * Releases everything held for this document — a parse tree, a compiler, a classloader, <b>and the
     * providers handed out above</b>.
     *
     * <p><b>This is the only close on the seam, deliberately.</b> {@link SemanticTokenProvider} has a
     * {@code close()} of its own and nothing outside an implementation of this interface may call it: an
     * editor that closed a provider would be releasing something it was only lent, while the same
     * document's other view carried on using it. So an implementation releases what it owns, here, and
     * the editor calls exactly one method.</p>
     *
     * <p>Overrides {@link AutoCloseable#close()} to drop the checked exception. A service that cannot be
     * closed without failing has no caller able to do anything about it, and forcing a try/catch at the
     * one call site that matters is how a close ends up not being called at all.</p>
     */
    @Override
    default void close() {
    }
}
