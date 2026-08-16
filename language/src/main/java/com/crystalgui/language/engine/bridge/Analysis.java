package com.crystalgui.language.engine.bridge;

import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import java.util.List;

/**
 * A resolved source file, living on the engine's side — <b>whatever the engine is</b>.
 *
 * <h3>Why this is its own type and not {@code SourceAnalyzer.Analysis}</h3>
 *
 * <p>The <em>request</em> for an analysis is language-shaped: Java's names a class, a classpath and a
 * release level, and a JavaScript request will name none of those. The <em>answer</em> is not — problems,
 * semantic tokens, what a name at an offset refers to, what can be done about a problem, what is in scope.
 * Every one of those is a question the editor asks in the same words of every engine, and everything that
 * consumes an answer ({@code AnalysedLanguageServices} and the providers built over it) is written once
 * against this and never against the request. Keeping the answer nested inside Java's request type would
 * have made a second engine either implement Java's request or copy the consumer.</p>
 *
 * <p>Everything returned is made of {@code com.crystalgui.text.*} types, which {@code EngineClassLoader}
 * shares with the host precisely so that no translation layer is needed. Offsets are into the <b>source
 * that was handed in</b>; if the caller wrapped the user's text in a prelude (§15.3), unwrapping is the
 * caller's job and is a subtraction.</p>
 *
 * <p>An analysis is a resource: it holds an AST with bindings, and {@link #close()} is what lets one be
 * dropped when the document moves on.</p>
 */
public interface Analysis extends AutoCloseable {

    /** The document version this describes. @see com.crystalgui.text.lang.Versioned */
    long version();

    /**
     * Every problem the engine reported.
     *
     * <p>Positions are row/column, matching {@link Diagnostic}'s own choice — see that type on why a
     * diagnostic that outlives its snapshot is better stale-by-a-row than confidently pointing at innocent
     * text.</p>
     */
    List<Diagnostic> diagnostics();

    /**
     * Whether the engine's <b>optional</b> problem analysis actually ran for this unit.
     *
     * <p>ECJ reports unused imports, unused locals and every other optional problem out of the
     * post-resolve and flow-analysis passes — and skips both entirely for a unit that failed to parse,
     * because they would be running over a tree the parser has already said it does not trust. So a file
     * with one syntax error reports that error and <em>nothing else</em>, and the warnings reappear the
     * moment it parses again. {@code javac} behaves the same way, and a JavaScript linter over a parse
     * error would too.</p>
     *
     * <p><b>Reported rather than inferred</b>, and the difference is a real bug. "The list has errors and
     * no warnings" looks like the same statement and is not: a file that parses cleanly, has one type
     * error and genuinely has no warnings produces exactly that shape, and anything treating it as
     * suppression would resurrect warnings the user has already fixed. Only the engine knows which of
     * the two happened.</p>
     *
     * <p>Defaults to true — an engine that does not distinguish the two is saying "my answer is
     * complete", which is the safe reading: it costs a feature, not correctness.</p>
     */
    default boolean optionalProblemsAnalysed() {
        return true;
    }

    /**
     * Colouring the grammar could not produce, in the §10.1 capture vocabulary.
     *
     * <p>Only what needs an engine: a parameter told apart from a local from a field, a type reference,
     * a name that does not resolve, something deprecated. Re-stating what the grammar already gets right
     * would be work whose only effect is to overwrite an identical answer.</p>
     */
    List<SyntaxToken> semanticTokens();

    /** What the name at {@code offset} refers to, or null. */
    SymbolInfo resolveAt(int offset);

    /** The type the language expects at {@code offset}, or null. */
    TypeRef expectedTypeAt(int offset);

    /**
     * What can be done about the problems overlapping {@code [from, to)}.
     *
     * <p>Answered <b>in this analysis's own coordinates</b>, and stamped by the caller with
     * {@link #version()}. That is what makes the offsets safe without any mapping: an action is only ever
     * applied to a document still at that version, so the analysis's positions and the buffer's are the
     * same positions or the action is refused. Mapping a stale edit forward is not attempted — a fix built
     * from a tree that no longer matches the text is not worth salvaging.</p>
     *
     * <p>Defaulted to nothing so an adapter that offers no fixes says so by saying nothing, which is the
     * same three-tier absence the rest of this bridge uses. A band whose engine is too old for a
     * particular correction simply does not return it.</p>
     *
     * <p>{@code context} is what the host knows and a correction cannot work out for itself — today only
     * which qualified names could satisfy an unresolved simple name. It is handed IN rather than looked up
     * here because the split is real: the syntax tree knows <em>which</em> name is unresolved and
     * <em>where</em> an import belongs, and only the host has an index of the classpath to say what that
     * name could be.</p>
     *
     * <p><b>One interface rather than one parameter per need</b>, which is the difference between this
     * signature and the {@code Function} it replaced. Every future correction that needs something
     * host-side — a fuzzy index for "did you mean", the indent for generated code — adds a method there
     * instead of an argument here, and an argument here is a contract both loaders and the oldest band
     * must agree on. @see CodeActionContext</p>
     */
    default List<CodeAction> codeActionsIn(int from, int to, CodeActionContext context) {
        return List.of();
    }

    /**
     * Everything reachable on {@code type} from {@code contextOffset} — what completion after a dot is
     * built from.
     *
     * <p>Hand back a {@link TypeRef} this analysis produced, so the engine's binding is intact and
     * generic substitution survives. {@code contextOffset} is not decoration: accessibility is a property
     * of where you are asking from, and a list that ignored it would offer members that do not compile —
     * worse than offering none, because the list looks authoritative and the error arrives after
     * acceptance.</p>
     */
    List<SymbolInfo> membersOf(TypeRef type, int contextOffset);

    /**
     * Every name usable unqualified at {@code offset} — what completion in open code is built from.
     *
     * <p>The counterpart to {@link #membersOf}, and it has to exist separately because the two questions
     * have different answers from the same place: after a dot the only sensible list is the receiver's
     * members, and in open code it is locals, then parameters, then fields, then everything in scope by
     * name. A provider that could not tell them apart would flood a member list with locals, which is
     * §18.1's stated reason for {@code TriggerKind}.</p>
     *
     * <p><b>Declaration order matters and is preserved</b>: nearest scope first. The ranking chain above
     * this re-sorts by match quality, but ties fall back to the order this returns, so an inner local
     * arriving before an outer field is a real signal rather than an accident.</p>
     *
     * <p>Only what is <em>declared</em>. Unimported types are a different question with a different
     * answer — they cost an import to accept — and they come from an index rather than from an AST.</p>
     */
    List<SymbolInfo> symbolsInScope(int offset);

    /** Releases the AST. Idempotent. */
    @Override
    void close();
}
