package com.crystalgui.text.lang;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.Diagnostic;

import java.util.List;
import java.util.function.Consumer;

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
 * <h3>Diagnostics are PUSHED, not held — they already have a home</h3>
 *
 * <p>The obvious fourth accessor would be {@code diagnostics()}, and it would be a second place for
 * something that already exists: every document has a {@link com.crystalgui.text.diagnostic.DiagnosticSet}
 * with a per-owner model, precisely so independent producers can publish without clobbering each other.
 * Mirroring the list here would mean two copies with no rule about which is authoritative. So
 * {@link #onDiagnostics} is a signal rather than a getter: the engine announces, the document's owner
 * writes it into the set under {@link #id()}, and the Problems panel, the inspection widget and the
 * status bar all keep reading the path they already read.</p>
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

    /** What can be done about the problems in a range. @see CodeActionProvider */
    default CodeActionProvider codeActions() {
        return CodeActionProvider.NONE;
    }

    /**
     * Told when the engine has a new set of problems for this document.
     *
     * <h3>Why a signal and not a {@code diagnostics()} accessor</h3>
     *
     * <p>Every document already has a {@link com.crystalgui.text.diagnostic.DiagnosticSet} with a
     * per-owner model, built so independent producers can publish without erasing each other. An
     * accessor here would be a <em>second</em> home for the same list, with no rule about which is
     * authoritative — so instead the engine <b>pushes</b> and whoever owns the set writes it there with
     * {@code set.changeOne(services.id(), list)}. One list, one owner, and the Problems panel, the
     * inspection widget and the status bar all keep reading the path they already read.</p>
     *
     * <p>The list <b>replaces</b> the previous one for this engine and is always complete. A producer
     * that emitted deltas would need the consumer to keep a shadow copy, which is the same second home
     * arriving by a different route.</p>
     *
     * <h3>{@link Versioned}, and why this one is gated where semantic tokens are not</h3>
     *
     * <p>A {@link Diagnostic} names a <b>row and column</b>, which is what every compiler reports and what
     * survives an edit elsewhere in the file. Turning that into the offsets a squiggle is drawn from is a
     * question about a <em>specific</em> document, so it can only be asked of the text the analysis actually
     * saw. Announce a list without its version and the consumer converts row/column against whatever the
     * document is now — silently, and wrongly in exactly the amount the user typed while the compile ran.</p>
     *
     * <p>So a stale list is <b>discarded</b> here, which is the opposite of the keep-per-line policy
     * {@link SemanticTokenProvider} uses, and both are right: a colour on an untouched line is still correct
     * when the document moves on, and an <em>offset</em> never is. Discarding does not starve the view —
     * the job is debounced and keyed, so a list only lands after a pause, and a keystroke during a compile
     * queues the next one. This is the choice {@link Versioned} exists to let a consumer make.</p>
     *
     * <p>Invoked on the <b>UI thread</b>. The default subscribes nothing and returns a connection that
     * is already disconnected, which is the honest answer for an engine that never reports problems.</p>
     */
    default Connection onDiagnostics(Consumer<Versioned<List<Diagnostic>>> listener) {
        return Connection.DISCONNECTED;
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
