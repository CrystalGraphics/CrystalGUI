package com.crystalgui.language.js;

import com.crystalgui.language.engine.bridge.JsSourceAnalyzer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ErrorCollector;
import org.mozilla.javascript.ast.ParseProblem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rhino's parser, driven — the JavaScript engine's side of the analysis bridge.
 *
 * <h3>IDE mode, and what each of its four switches buys</h3>
 *
 * <p>{@link CompilerEnvirons#setRecoverFromErrors} plus an {@link ErrorCollector} is what makes this an
 * <em>editor's</em> parser rather than a compiler's: every problem is collected and a tree comes back
 * anyway, where the default throws on the first. {@code setIdeMode} keeps the positions and the
 * structure a tool needs and the compiler discards. {@code setRecordingComments} and
 * {@code setRecordingLocalJsDocComments} attach each doc comment to the declaration it precedes, which
 * is where JavaScript keeps the only type information it has.</p>
 *
 * <p>All four matter because <b>a file is broken most of the time somebody is typing in it</b>. An
 * analyser that answered only for well-formed input would answer exactly when it is not needed — the
 * same argument {@code Resolver} makes about ECJ's binding recovery, and the reason both engines are
 * driven this way.</p>
 *
 * <h3>What is here at M10.2, and what is not</h3>
 *
 * <p>The parse, the problems, and the tree held for later. Semantic tokens, scopes and resolution are
 * M10.3 and M10.4 and answer empty until then — which is not a stub in the pejorative sense: it is
 * exactly what {@link com.crystalgui.language.engine.bridge.Analysis} promises an engine may say, and
 * every consumer above already treats an empty answer as ordinary rather than as a failure.</p>
 */
public final class RhinoSourceAnalyzer implements JsSourceAnalyzer {

    /** Public no-argument, because {@code EngineHost.adapter} instantiates this reflectively. */
    public RhinoSourceAnalyzer() {
    }

    @Override
    public Analysis analyze(String sourceName, String source, long version) {
        // THE ENGINE LOADER, ON THE THREAD, BEFORE ANY RHINO CLASS IS TOUCHED. This is not the class
        // that evaluates regular expressions and it is still the one that usually initialises `Context`
        // first -- an editor analyses on every keystroke and runs a script rarely -- so whichever
        // adapter gets there first decides whether regexes work for the life of the loader.
        // @see RhinoThread, which is where that whole argument lives.
        return RhinoThread.with(() -> parse(sourceName, source, version));
    }

    private Analysis parse(String sourceName, String source, long version) {
        String text = source == null ? "" : source;
        String name = sourceName == null || sourceName.isEmpty() ? "script.js" : sourceName;

        CompilerEnvirons environs = new CompilerEnvirons();
        environs.setLanguageVersion(Context.VERSION_ES6);
        environs.setRecoverFromErrors(true);
        environs.setIdeMode(true);
        environs.setRecordingComments(true);
        environs.setRecordingLocalJsDocComments(true);
        // NEVER STRICT-BY-DEFAULT. Rhino's strict mode turns a pile of style opinions into warnings --
        // trailing commas, missing semicolons, `==` against null -- and which of those are worth showing
        // is `RhinoProblemPolicy`'s decision at M10.3, made per message id. Turning them all on here
        // would decide it by accident, in the wrong file.
        environs.setStrictMode(false);

        ErrorCollector problems = new ErrorCollector();
        AstRoot root = null;
        try {
            root = new Parser(environs, problems).parse(text, name, 1);
        } catch (RuntimeException fatal) {
            // RECOVERY IS NOT TOTAL. `setRecoverFromErrors` covers the errors the parser has a rule for;
            // a few shapes still unwind (a runaway string, some malformed regexes). The collected
            // problems are still the useful answer, so this keeps them and reports the throw as one more
            // -- losing the lot would blank the editor's squiggles for exactly the file that has most.
            problems.getErrors().add(new ParseProblem(ParseProblem.Type.Error,
                    messageOf(fatal), name, 0, Math.max(0, text.length())));
        }
        return new ParsedScript(version, text, root, diagnosticsOf(problems.getErrors(), text));
    }

    private static String messageOf(RuntimeException thrown) {
        String message = thrown.getMessage();
        return message == null || message.isEmpty() ? thrown.toString() : message;
    }

    /**
     * Rhino's problems as the editor's, converted once.
     *
     * <h4>Offsets in, row/column out — and the conversion belongs here</h4>
     *
     * <p>{@link ParseProblem} reports an absolute file offset and a length, which is better than what
     * JDT gives and worse than what {@link Diagnostic} takes: a diagnostic names a <b>row and column</b>,
     * deliberately, because that is what survives an edit somewhere else in the file. Converting needs
     * the exact text the parse saw, and this is the only place that still has it — the buffer will have
     * moved on by the time anything downstream looks.</p>
     */
    private static List<Diagnostic> diagnosticsOf(List<ParseProblem> problems, String text) {
        if (problems.isEmpty()) return Collections.emptyList();
        LineIndex lines = new LineIndex(text);
        List<Diagnostic> out = new ArrayList<>(problems.size());
        for (ParseProblem problem : problems) {
            int from = Math.max(0, Math.min(problem.getFileOffset(), text.length()));
            // A ZERO-LENGTH PROBLEM IS REAL AND IS WIDENED BY ONE. "missing ; before statement" points
            // BETWEEN two characters, and a mark with no width cannot be seen -- the same rule the
            // editor's own diagnostic lane already applies, stated here so the range arrives usable.
            int length = Math.max(1, problem.getLength());
            int to = Math.min(text.length(), from + length);
            out.add(new Diagnostic(lines.pointAt(from), lines.pointAt(to),
                    problem.getType() == ParseProblem.Type.Error
                            ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                    problem.getMessage(), OWNER, null));
        }
        return out;
    }

    /** Where a problem says it came from, in the Problems panel's source column. */
    private static final String OWNER = "rhino";

    /**
     * Offset → row/column over one immutable snapshot.
     *
     * <p>Built once per analysis and thrown away with it. A binary search over line starts rather than a
     * scan per problem: a file with two hundred problems is exactly the file being typed in, and the
     * scan would be quadratic in the thing that is already slowest.</p>
     */
    private static final class LineIndex {

        private final int[] lineStarts;

        LineIndex(String text) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') starts.add(i + 1);
            }
            this.lineStarts = new int[starts.size()];
            for (int i = 0; i < starts.size(); i++) lineStarts[i] = starts.get(i);
        }

        TextPoint pointAt(int offset) {
            int low = 0;
            int high = lineStarts.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (lineStarts[mid] <= offset) low = mid;
                else high = mid - 1;
            }
            return new TextPoint(low, offset - lineStarts[low]);
        }
    }

    /**
     * One parse, held so the questions above the bridge can be asked of it.
     *
     * <p>The tree is retained rather than discarded after the diagnostics are read, because it is what
     * M10.4's scopes and M10.6's resolution answer from — and re-parsing per question is the shape this
     * bridge exists to avoid. {@link #close()} is what lets it go.</p>
     */
    private static final class ParsedScript implements Analysis {

        private final long version;
        private final String source;
        private final List<Diagnostic> diagnostics;
        private AstRoot root;

        ParsedScript(long version, String source, AstRoot root, List<Diagnostic> diagnostics) {
            this.version = version;
            this.source = source;
            this.root = root;
            this.diagnostics = diagnostics;
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        /**
         * Whether the optional pass ran — {@code false} when the parse failed.
         *
         * <p>Same contract as the Java side, and it means the same thing: a unit the parser does not
         * trust gets no style or flow analysis, so a file with one syntax error reports that error and
         * <em>nothing else</em>. Reported rather than inferred, because "errors and no warnings" is
         * equally the shape of a file that parses fine and genuinely has none.</p>
         */
        @Override
        public boolean optionalProblemsAnalysed() {
            if (root == null) return false;
            for (Diagnostic problem : diagnostics) {
                if (problem.severity() == DiagnosticSeverity.ERROR) return false;
            }
            return true;
        }

        @Override
        public List<SyntaxToken> semanticTokens() {
            return Collections.emptyList();
        }

        @Override
        public SymbolInfo resolveAt(int offset) {
            return null;
        }

        @Override
        public TypeRef expectedTypeAt(int offset) {
            return null;
        }

        @Override
        public List<SymbolInfo> membersOf(TypeRef type, int contextOffset) {
            return Collections.emptyList();
        }

        @Override
        public List<SymbolInfo> symbolsInScope(int offset) {
            return Collections.emptyList();
        }

        @Override
        public void close() {
            // The tree is ordinary heap, so dropping the reference is the whole of it -- there is no
            // native handle here, unlike the grammar tier's parse trees. Idempotent by construction.
            root = null;
        }

        /** What was parsed. For the questions above that need the text as the parse saw it. */
        String source() {
            return source;
        }
    }
}
