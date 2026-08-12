package com.crystalgui.text.lang;

import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;

import java.util.List;

/**
 * Colouring an engine knows and a grammar cannot — laid over the grammar's, in the same vocabulary.
 *
 * <h3>What this adds that {@link SyntaxTokenizer} cannot</h3>
 *
 * <p>A grammar sees shape. It can tell a declaration from a call because they are different nodes, and it
 * stops there: every plain identifier is one capture, so a parameter, a local, a field and a static import
 * are one colour and no scheme can separate them. An engine has resolved them, so it can. That difference
 * is most of what makes IntelliJ's colouring look richer than a lexer's, and it is the only thing this
 * interface is for.</p>
 *
 * <h3>It speaks §10.1's capture names, deliberately</h3>
 *
 * <p>Tokens come back as {@link SyntaxToken} — the same type, the same names ({@code variable.parameter},
 * {@code variable.member}, {@code type}) that the grammars produce and the schemes colour.
 * {@link SymbolKind#captureName()} is the mapping an engine should use rather than spelling names itself.
 * A parallel semantic vocabulary would need its own scheme tokens and its own governance test, and the
 * mapping between the two would be a table nobody keeps current — LSP has exactly that problem and solves
 * it with a per-server legend, which is a cost a protocol has to pay and this does not.</p>
 *
 * <h3>Push, not request — and that is the opposite of {@link Resolver}</h3>
 *
 * <p>Both are asynchronous and they are asynchronous in different shapes, because they are different kinds
 * of work:</p>
 *
 * <ul>
 *   <li><b>This is continuous background analysis.</b> Nobody asked for it; a compile runs because the
 *       document changed, and its tokens become available whenever it finishes. So the editor is
 *       <em>told</em> ({@link #setInvalidationListener}) and then pulls synchronously per row from
 *       whatever the provider is holding. That is the shape {@link SyntaxTokenizer} already has, and the
 *       per-row cache built against it works unchanged.</li>
 *   <li><b>{@link Resolver} is a user-initiated question.</b> Hover and go-to-definition have one asker,
 *       one answer, and a caret position that stops being interesting the moment it moves — so they take
 *       a callback and can be discarded on staleness.</li>
 * </ul>
 *
 * <p>LSP draws the same line for the same reason: {@code publishDiagnostics} is a server-to-client
 * notification, {@code hover} is a request.</p>
 *
 * <h3>Stale answers are the normal case, and must not be hidden</h3>
 *
 * <p>{@link #version()} is what the answers describe. The editor's policy is <b>keep, per line</b>
 * ({@link Versioned}): a line the edit did not touch still has correct semantic colours, so discarding
 * everything on each keystroke would drop the file back to lexer colouring on every key and restore it a
 * few hundred milliseconds later — a flicker that is worse than a slightly stale colour, and precisely
 * what a stamp lets a consumer choose to avoid.</p>
 */
public interface SemanticTokenProvider {

    /** Adds nothing to the grammar's answer — what a language with no engine has. */
    SemanticTokenProvider NONE = new SemanticTokenProvider() {

        @Override
        public List<SyntaxToken> tokensIn(int fromOffset, int toOffset) {
            return List.of();
        }

        @Override
        public long version() {
            return 0;
        }
    };

    /**
     * Every semantic token overlapping {@code [from, to)}, in UTF-16 offsets into the whole document.
     *
     * <p><b>Synchronous, and answers from what the provider already holds</b> — this is called during a
     * paint and must never compile, block, or reach a network. An engine that has not finished returns
     * what it had, or nothing.</p>
     *
     * <p>The document is deliberately not a parameter, unlike {@link SyntaxTokenizer#tokenize}. A provider
     * that could read the live text would be tempted to, and its answers describe an older one — handing
     * it the current document is handing it the means to produce a confidently wrong result. What it holds
     * and what {@link #version()} says are the same snapshot; nothing else is available.</p>
     */
    List<SyntaxToken> tokensIn(int fromOffset, int toOffset);

    /** The document version {@link #tokensIn} currently describes. @see Versioned */
    long version();

    /**
     * Told when new answers have landed, so a per-row cache can drop exactly the rows that changed.
     *
     * <p>Same contract as {@link SyntaxTokenizer#setInvalidationListener}, including the range: a listener
     * that re-queried everything would put a viewport-sized query back on the frame at the rate compiles
     * land, which is the cost the row cache exists to remove. Invoked on the <b>UI thread</b>.</p>
     */
    default void setInvalidationListener(SyntaxTokenizer.InvalidationListener listener) {
        // A provider that never works in the background never calls it.
    }

    /** Releases whatever the engine is holding for this document. */
    default void close() {
    }
}
