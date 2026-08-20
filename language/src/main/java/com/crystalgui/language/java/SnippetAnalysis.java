package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.CodeActionContext;
import com.crystalgui.language.java.exec.ScriptPrelude;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An analysis of a wrapped snippet, answered in the <b>author's</b> coordinates.
 *
 * <h3>The asymmetry this closes</h3>
 *
 * <p>A Java script is a <em>body</em> — statements, with the host's context already in scope — and
 * {@link ScriptPrelude} is what turns one into a compilation unit. {@code ScriptHost} has always done
 * that, so a bare snippet <b>runs</b>. The editor's analysis path did not, so the same file was handed
 * to ECJ as a compilation unit and came back covered in the parser trying to read
 * {@code System.out.println(...)} as a member declaration: "insert Identifier ( to complete
 * MethodHeaderName", "RecordHeaderName expected instead". Thirty errors on a file that ran correctly.</p>
 *
 * <p>Wrapping in the analysis path is the fix, and it is only half of one: the compiler then answers
 * about a document the author cannot see, whose first line is a class header and whose every row is
 * shifted. This is the other half — every position out is translated back, and every position in is
 * translated forward.</p>
 *
 * <h3>Dropping rather than clamping</h3>
 *
 * <p>{@code Wrapped.toScriptOffset} answers {@code -1} for synthesized text and says why: a problem in
 * the prelude is a problem in code the author never wrote and cannot fix, so clamping it to offset 0
 * puts a squiggle on their first character and blames them for it. Everything here follows that — a
 * diagnostic, a token, a declaration site or a whole code action that lands in the prelude is dropped.</p>
 *
 * <p><b>A code action is dropped as a unit</b>, not edit by edit. Its {@link ChangeSet} is one atomic
 * rewrite; applying the half that maps and discarding the half that does not would corrupt the document
 * far more thoroughly than offering nothing.</p>
 */
final class SnippetAnalysis implements Analysis {

    private final Analysis unit;
    private final ScriptPrelude.Wrapped wrapped;
    private final int scriptLength;

    SnippetAnalysis(Analysis unit, ScriptPrelude.Wrapped wrapped, int scriptLength) {
        this.unit = unit;
        this.wrapped = wrapped;
        this.scriptLength = scriptLength;
    }

    @Override
    public long version() {
        return unit.version();
    }

    @Override
    public boolean optionalProblemsAnalysed() {
        return unit.optionalProblemsAnalysed();
    }

    @Override
    public List<Diagnostic> diagnostics() {
        List<Diagnostic> mapped = new ArrayList<>();
        for (Diagnostic problem : unit.diagnostics()) {
            // THE PRELUDE'S OWN DECISION, not a second one. toScriptDiagnostic returns null for a problem
            // that lands in synthesized text -- including the one worth naming, a stray closing brace the
            // compiler places on the generated closer.
            Diagnostic inScript = wrapped.toScriptDiagnostic(problem);
            if (inScript != null) mapped.add(inScript);
        }
        return mapped;
    }

    @Override
    public List<SyntaxToken> semanticTokens() {
        List<SyntaxToken> mapped = new ArrayList<>();
        for (SyntaxToken token : unit.semanticTokens()) {
            int start = wrapped.toScriptOffset(token.start());
            int end = wrapped.toScriptOffset(token.end());
            if (start < 0 || end < 0 || end <= start) continue;
            mapped.add(new SyntaxToken(start, end, token.name()));
        }
        return mapped;
    }

    @Override
    public SymbolInfo resolveAt(int offset) {
        return inScript(unit.resolveAt(wrapped.toUnitOffset(offset)));
    }

    /**
     * Forwarded <b>unchanged</b> — a name is not a position, so there is nothing to translate.
     *
     * <p>Which is exactly why it was missing. Every other method here earns its override by moving an
     * offset between the author's document and the wrapped one; this one has no offset in it and no
     * offset out, so there was nothing to write and it was not written. The bridge's
     * {@code default … { return null; }} then answered for it, and a default is an answer chosen for
     * someone who never saw the question.</p>
     *
     * <p><b>The failure was file-shaped and read as random.</b> A snippet — a bare body, which is what
     * a Java script is — gets wrapped in this class; a file with a real class declaration does not. So
     * every documentation link worked in {@code Main.java} and none worked in a one-line scratch file,
     * with the same popup, the same emitter and the same engine underneath. Three rounds went to the
     * press, the anchor and the href before the difference turned out to be which of two Analysis
     * objects was answering.</p>
     */
    @Override
    public SymbolInfo describe(String name) {
        return unit.describe(name);
    }

    @Override
    public TypeRef expectedTypeAt(int offset) {
        return unit.expectedTypeAt(wrapped.toUnitOffset(offset));
    }

    @Override
    public List<SymbolInfo> membersOf(TypeRef type, int contextOffset) {
        return unit.membersOf(type, wrapped.toUnitOffset(contextOffset));
    }

    @Override
    public List<SymbolInfo> symbolsInScope(int offset) {
        List<SymbolInfo> mapped = new ArrayList<>();
        for (SymbolInfo symbol : unit.symbolsInScope(wrapped.toUnitOffset(offset))) {
            mapped.add(inScript(symbol));
        }
        return mapped;
    }

    @Override
    public List<CodeAction> codeActionsIn(int from, int to, CodeActionContext context) {
        List<CodeAction> mapped = new ArrayList<>();
        for (CodeAction action : unit.codeActionsIn(wrapped.toUnitOffset(from),
                wrapped.toUnitOffset(to), context)) {
            CodeAction inScript = inScript(action);
            if (inScript != null) mapped.add(inScript);
        }
        return mapped;
    }

    @Override
    public void close() {
        unit.close();
    }

    /**
     * A symbol whose declaration site is in the author's text — or with none, when it is in the prelude.
     *
     * <p>The symbol itself survives either way: a binding the prelude declared is still a real name with
     * a real type, and the only thing that cannot be honoured about it is "jump to where it was written",
     * because nowhere is where it was written.</p>
     */
    @Nullable
    private SymbolInfo inScript(@Nullable SymbolInfo symbol) {
        if (symbol == null) return null;
        DeclarationSite site = symbol.declaration();
        if (site == null || !site.isSameDocument()) return symbol;
        TextPoint start = wrapped.toScriptPoint(site.start());
        TextPoint end = wrapped.toScriptPoint(site.end());
        if (start == null || end == null) return symbol.withDeclaration(null);
        return symbol.withDeclaration(DeclarationSite.here(start, end));
    }

    /** An action whose every edit is in the author's text — or null, because a partial rewrite is worse. */
    @Nullable
    private CodeAction inScript(CodeAction action) {
        ChangeSet edit = action.edit();
        if (edit == null) return action;
        List<Change> mapped = new ArrayList<>();
        for (Change change : edit.changes()) {
            int from = wrapped.toScriptOffset(change.from());
            int to = wrapped.toScriptOffset(change.to());
            if (from < 0 || to < 0) return null;
            mapped.add(new Change(from, to, change.insert()));
        }
        // REBUILT rather than withEdit-ed: CodeAction is a record with no wither for its edit, and
        // adding one to core's seam for this would be a nine-component constructor call at every other
        // caller's expense. Every other component travels unchanged.
        return new CodeAction(action.id(), action.title(), action.kind(),
                ChangeSet.of(scriptLength, mapped), action.commandId(), action.arguments(),
                action.preferred(), action.version(), action.description());
    }
}
