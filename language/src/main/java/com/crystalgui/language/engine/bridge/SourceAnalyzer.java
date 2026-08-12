package com.crystalgui.language.engine.bridge;

import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import java.util.List;


/**
 * What an engine can say about a source file — the analysis half of the bridge.
 *
 * <h3>Why this returns an {@link Analysis} rather than a pile of lists</h3>
 *
 * <p>Resolving a name needs the whole resolved AST, and an AST cannot cross the classloader boundary:
 * it is made of engine types the host must never see. Materialising everything a caller <em>might</em>
 * ask — every symbol at every offset — is absurd. So the analysis stays on the engine's side, behind an
 * interface the host can hold: {@link Analysis} is declared here (shared package, one identity) and
 * implemented over there (holding the AST). Each query is a call across the gap that answers from a
 * tree the host never sees.</p>
 *
 * <p>That also gives disposal somewhere to live: an AST with bindings is not small, and
 * {@link Analysis#close()} is what lets one be dropped when the document moves on.</p>
 */
public interface SourceAnalyzer {

    /**
     * Parses and resolves one source file.
     *
     * @param className    the fully qualified name the source declares
     * @param source       the source text, exactly as the engine should see it
     * @param classpath    what to resolve against
     * @param releaseLevel the Java level to analyse at
     * @param version      the document version this describes, carried through to every answer
     */
    Analysis analyze(String className, String source, List<String> classpath, int releaseLevel,
                     long version);

    /**
     * A resolved source file, living on the engine's side.
     *
     * <p>Everything returned is made of {@code com.crystalgui.text.*} types, which
     * {@code EngineClassLoader} shares with the host precisely so that no translation layer is needed.
     * Offsets are into the <b>source that was handed in</b>; if the caller wrapped the user's text in a
     * prelude (§15.3), unwrapping is the caller's job and is a subtraction.</p>
     */
    interface Analysis extends AutoCloseable {

        /** The document version this describes. @see com.crystalgui.text.lang.Versioned */
        long version();

        /**
         * Every problem the compiler reported.
         *
         * <p>Positions are row/column, matching {@link Diagnostic}'s own choice — see that type on why
         * a diagnostic that outlives its snapshot is better stale-by-a-row than confidently pointing at
         * innocent text.</p>
         */
        List<Diagnostic> diagnostics();

        /**
         * Colouring the grammar could not produce, in the §10.1 capture vocabulary.
         *
         * <p>Only what needs an engine: a parameter told apart from a local from a field, a type
         * reference, a name that does not resolve, something deprecated. Re-stating what the grammar
         * already gets right would be work whose only effect is to overwrite an identical answer.</p>
         */
        List<SyntaxToken> semanticTokens();

        /** What the name at {@code offset} refers to, or null. */

        SymbolInfo resolveAt(int offset);

        /** The type the language expects at {@code offset}, or null. */

        TypeRef expectedTypeAt(int offset);

        /**
         * Everything reachable on {@code type} from {@code contextOffset} — what completion after a
         * dot is built from.
         *
         * <p>Hand back a {@link TypeRef} this analysis produced, so the engine's binding is intact
         * and generic substitution survives. {@code contextOffset} is not decoration: accessibility
         * is a property of where you are asking from, and a list that ignored it would offer members
         * that do not compile — worse than offering none, because the list looks authoritative and
         * the error arrives after acceptance.</p>
         */
        List<SymbolInfo> membersOf(TypeRef type, int contextOffset);

        /** Releases the AST. Idempotent. */
        @Override
        void close();
    }
}
