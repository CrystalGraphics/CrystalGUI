package com.crystalgui.language.js;

import com.crystalgui.language.engine.bridge.JsSourceAnalyzer;
import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
import com.crystalgui.language.engine.bridge.MemberNameMapper;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.language.engine.bridge.CodeActionContext;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import javax.annotation.Nullable;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ErrorCollector;
import org.mozilla.javascript.ast.IfStatement;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.ParseProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
 * <h3>What is here, and what is not</h3>
 *
 * <p>The parse, the problems ({@link RhinoProblemPolicy}), the scopes ({@link RhinoScopes}), the colours
 * they justify ({@link RhinoSemanticTokens}), the four resolution tiers ({@link RhinoResolution}) and the
 * fix catalog ({@link JsQuickFixes}) — every question {@link com.crystalgui.language.engine.bridge.Analysis}
 * asks, answered from the one parse this holds. What crosses the bridge is the answer; the tree never
 * does.</p>
 */
public final class RhinoSourceAnalyzer implements JsSourceAnalyzer {

    /** Public no-argument, because {@code EngineHost.adapter} instantiates this reflectively. */
    public RhinoSourceAnalyzer() {
    }

    /**
     * The Java engine behind the interop tier, or null when this build has none.
     *
     * <p>One per process, like the analyser itself: the probe cache is keyed by class name and a Java
     * class means the same thing in every open document, so sharing it is what makes the second file to
     * mention {@code java.util.ArrayList} free.</p>
     */
    private volatile InteropResolver interop;

    /**
     * Measured against this band's own parser, once. @see JsKeywords
     *
     * <p>Through {@link RhinoThread} like every other entry, and through the same {@code CompilerEnvirons}
     * an ordinary analysis uses — a keyword that parses under different settings than the editor's would be
     * offered and then reported as an error on the line it was inserted into.</p>
     */
    @Override
    public List<String> keywords() {
        // THE OFFERABLE SET, not the measured one: `template` is measured because an intention must not
        // rewrite code into a form the band refuses, and it is not a keyword anybody can accept from a
        // popup. @see JsKeywords#NOT_OFFERED
        return JsKeywords.offerableBy(RhinoSourceAnalyzer::parses);
    }

    /** Whether this engine parses {@code snippet} with no errors at all. */
    private static boolean parses(String snippet) {
        return RhinoThread.with(() -> {
            ErrorCollector problems = new ErrorCollector();
            try {
                new Parser(environs(), problems).parse(snippet, "keyword-probe.js", 1);
            } catch (RuntimeException refused) {
                return Boolean.FALSE;
            }
            return problems.getErrors().isEmpty();
        });
    }

    /** The engine's own global names — @see JsSourceAnalyzer#globals */
    @Override
    public List<String> globals() {
        return List.copyOf(RhinoGlobals.names());
    }

    /** What the host puts in scope, by name and declared type. @see JsSourceAnalyzer#useHostBindings */
    @Override
    public void useHostBindings(Map<String, String> nameToTypeName) {
        hostBindings = nameToTypeName == null || nameToTypeName.isEmpty()
                ? Map.of() : Map.copyOf(nameToTypeName);
    }

    private volatile Map<String, String> hostBindings = Map.of();

    @Override
    public void useJavaEngine(SourceAnalyzer java, List<String> classpath, int releaseLevel) {
        InteropResolver previous = interop;
        InteropResolver opened = new InteropResolver(java, classpath, releaseLevel);
        opened.restrictTo(allowsClass);
        opened.useMemberNames(memberNames);
        interop = opened;
        if (previous != null) previous.close();
    }

    /** What a script may reach, or null for everything. @see JsSourceAnalyzer#restrictTo */
    private volatile Predicate<String> allowsClass;

    /** How member names are shown. @see JsSourceAnalyzer#useMemberNames */
    private volatile MemberNameMapper memberNames = MemberNameMapper.IDENTITY;

    @Override
    public void useMemberNames(MemberNameMapper mapper) {
        memberNames = mapper == null ? MemberNameMapper.IDENTITY : mapper;
        InteropResolver current = interop;
        if (current != null) current.useMemberNames(memberNames);
    }

    @Override
    public void restrictTo(Predicate<String> policy) {
        this.allowsClass = policy;
        InteropResolver current = interop;
        if (current != null) current.restrictTo(policy);
    }

    @Override
    public Analysis analyze(String sourceName, String source, long version) {
        return analyze(sourceName, source, version, LiveScopeSnapshot.EMPTY);
    }

    @Override
    public Analysis analyze(String sourceName, String source, long version,
                            LiveScopeSnapshot liveScope) {
        // THE ENGINE LOADER, ON THE THREAD, BEFORE ANY RHINO CLASS IS TOUCHED. This is not the class
        // that evaluates regular expressions and it is still the one that usually initialises `Context`
        // first -- an editor analyses on every keystroke and runs a script rarely -- so whichever
        // adapter gets there first decides whether regexes work for the life of the loader.
        // @see RhinoThread, which is where that whole argument lives.
        return RhinoThread.with(() -> parse(sourceName, source, version,
                liveScope == null ? LiveScopeSnapshot.EMPTY : liveScope));
    }

    private Analysis parse(String sourceName, String source, long version,
                           LiveScopeSnapshot liveScope) {
        String text = source == null ? "" : source;
        String name = sourceName == null || sourceName.isEmpty() ? "script.js" : sourceName;

        CompilerEnvirons environs = environs();

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

        LineIndex lines = new LineIndex(text);
        RhinoScopes scopes = RhinoScopes.of(root);
        List<Diagnostic> reported = policy().apply(problems.getErrors(), text, lines);

        // UNUSED NAMES ARE THE ANALYSER'S, NOT THE PARSER'S. Rhino reports nothing about them -- it has
        // no reason to -- so this is the first problem in the file that came from having resolved the
        // scopes rather than from having read the syntax. It is also the one warning a JavaScript author
        // gets before running anything, which in a language with no compiler is worth a good deal.
        //
        // ONLY WHEN THE FILE PARSED. A broken file has half a tree, so a name whose only use is inside
        // the part that failed to parse looks unused -- and a warning that appears while you are
        // mid-edit and vanishes when you finish is worse than no warning. Same rule the Java engine
        // states as `optionalProblemsAnalysed`, arrived at from the same direction.
        boolean parsed = root != null && !hasError(reported);
        if (parsed) {
            reported = withUnusedWarnings(reported, scopes, lines);
            reported = withConstantConditions(reported, root, text, lines);
        }

        Map<String, String> bindings = hostBindings;
        RhinoResolution resolution = new RhinoResolution(root, scopes, text, lines, liveScope, interop,
                name, JsKeywords.measuredBy(RhinoSourceAnalyzer::parses), bindings);
        return new ParsedScript(version, text, root, scopes, reported, parsed, resolution,
                new JsQuickFixes(root, scopes, new JsRewrites(text, version), resolution),
                bindings.keySet());
    }

    /**
     * The parser settings — one definition, so the keyword probe and the editor cannot disagree.
     *
     * <p>Built per call rather than shared: {@code CompilerEnvirons} is mutable and Rhino writes to it
     * during a parse, so one instance handed to two parsers is a race with no symptom worth naming.</p>
     */
    private static CompilerEnvirons environs() {
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
        return environs;
    }

    /**
     * One policy for the process: it holds only the engine's name, which cannot change.
     *
     * <p>Built lazily because the name has to be <em>asked</em> of a live context —
     * {@code getImplementationVersion} is an instance method, which is Rhino saying the version belongs
     * to a context rather than to the jar. Naming the band's pinned version from our own build file
     * instead would be a second source of truth for the one fact a user is most likely to quote back
     * when something does not work.</p>
     */
    private static volatile RhinoProblemPolicy policy;

    private static RhinoProblemPolicy policy() {
        RhinoProblemPolicy cached = policy;
        if (cached != null) return cached;
        synchronized (RhinoSourceAnalyzer.class) {
            if (policy == null) policy = RhinoProblemPolicy.of(engineName());
            return policy;
        }
    }

    private static String engineName() {
        return RhinoThread.with(() -> {
            Context cx = Context.enter();
            try {
                String version = cx.getImplementationVersion();
                return version == null || version.isEmpty() ? "this engine" : version;
            } catch (RuntimeException unavailable) {
                return "this engine";
            } finally {
                Context.exit();
            }
        });
    }

    private static boolean hasError(List<Diagnostic> problems) {
        for (Diagnostic problem : problems) {
            if (problem.severity() == DiagnosticSeverity.ERROR) return true;
        }
        return false;
    }

    /**
     * Declared and never mentioned again.
     *
     * <p>Two exclusions, and neither is laziness.</p>
     *
     * <p><b>Parameters</b>, because a callback's signature is fixed by whoever calls it:
     * {@code function (err, data)} that ignores {@code err} is idiomatic rather than wrong, and marking
     * it would put a warning on most event handlers ever written. Both reference editors default to the
     * same exclusion for the same reason.</p>
     *
     * <p><b>Top-level declarations</b>, because a script's top level <em>is</em> its surface. Nothing in
     * the file needs to use {@code main} for the host to call it, and a binding a mod reads is written
     * once and referenced never. Warning there put "'main' is declared but never used" on the entry
     * point of the fixture this milestone is traced in — which is the clearest possible demonstration
     * that the rule was wrong. A declaration inside a function has no such excuse: nothing outside can
     * see it, so unused means unused.</p>
     */
    private static List<Diagnostic> withUnusedWarnings(List<Diagnostic> problems, RhinoScopes scopes,
                                                       LineIndex lines) {
        List<Diagnostic> out = new ArrayList<>(problems);
        for (RhinoScopes.Declaration declared : scopes.declarations()) {
            // SAID ONCE, on the declaration, because the fix catalog offers "Remove 'x'" against exactly
            // this set. @see RhinoScopes.Declaration#isReportableUnused for the two exclusions.
            if (!declared.isReportableUnused()) continue;
            out.add(new Diagnostic(lines.pointAt(declared.offset),
                    lines.pointAt(declared.offset + declared.length),
                    DiagnosticSeverity.WARNING,
                    "'" + declared.name + "' is declared but never used",
                    RhinoProblemPolicy.OWNER, null));
        }
        return out;
    }

    /**
     * {@code if (false)} and {@code while (true)} — a branch whose condition cannot vary.
     *
     * <p>The JavaScript half of "unreachable code, dead branch": Rhino reports nothing about it, because a
     * parser has no reason to. It is a small walk over literal conditions and it catches the one shape
     * that is nearly always a mistake rather than a choice — a condition left behind after debugging.</p>
     *
     * <p><b>{@code while (true)} is exempt</b>, and that is the whole difficulty of the check: it is the
     * idiomatic spelling of a deliberate loop in every language that has it, and warning about it would put
     * a mark on correct code in every event loop ever written. {@code if (true)} is not exempt, because
     * nobody writes one on purpose.</p>
     */
    private static List<Diagnostic> withConstantConditions(List<Diagnostic> problems, AstRoot root,
                                                           String source, LineIndex lines) {
        List<Diagnostic> out = new ArrayList<>(problems);
        root.visit(node -> {
            AstNode condition = constantConditionOf(node);
            if (condition == null) return true;
            String literal = RhinoTokens.keywordOf(condition);
            int at = condition.getAbsolutePosition();
            out.add(new Diagnostic(lines.pointAt(at),
                    lines.pointAt(Math.min(source.length(), at + Math.max(1, condition.getLength()))),
                    DiagnosticSeverity.WARNING,
                    "'" + literal + "' is constant, so this branch is decided before it runs",
                    RhinoProblemPolicy.OWNER, null));
            return true;
        });
        return out;
    }

    /** The condition of an {@code if} whose test is a boolean literal, or null. */
    @Nullable
    private static AstNode constantConditionOf(AstNode node) {
        if (!(node instanceof IfStatement)) return null;
        AstNode condition = ((IfStatement) node).getCondition();
        if (!(condition instanceof KeywordLiteral)) return null;
        String literal = RhinoTokens.keywordOf(condition);
        return "true".equals(literal) || "false".equals(literal) ? condition : null;
    }

    private static String messageOf(RuntimeException thrown) {
        String message = thrown.getMessage();
        return message == null || message.isEmpty() ? thrown.toString() : message;
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
        private final boolean parsed;
        private AstRoot root;
        private RhinoScopes scopes;
        private List<SyntaxToken> tokens;
        private RhinoResolution resolution;
        private JsQuickFixes fixes;
        private final Set<String> hostBindings;

        ParsedScript(long version, String source, AstRoot root, RhinoScopes scopes,
                     List<Diagnostic> diagnostics, boolean parsed, RhinoResolution resolution,
                     JsQuickFixes fixes, Set<String> hostBindings) {
            this.version = version;
            this.source = source;
            this.root = root;
            this.scopes = scopes;
            this.diagnostics = diagnostics;
            this.parsed = parsed;
            this.resolution = resolution;
            this.fixes = fixes;
            this.hostBindings = hostBindings;
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
            return parsed;
        }

        /**
         * Built on first ask and kept, not built during the parse.
         *
         * <p>An analysis is scheduled on every keystroke and its tokens are read only when a view asks
         * — so a document nobody is looking at, or one whose editor was closed while the job was in
         * flight, pays a walk it never uses. The list is small and the walk is over a tree already in
         * memory, so holding it is cheaper than recomputing per viewport query, which is the shape the
         * editor's per-row cache expects.</p>
         */
        @Override
        public List<SyntaxToken> semanticTokens() {
            if (tokens == null) {
                // WITH THE HOST'S BINDINGS, which is what `variable.global` is for. It was always handed
                // the empty set, so the capture was dead and every binding a mod offers was drawn as an
                // unresolved name -- in the one editor whose own runtime provides it.
                tokens = root == null ? List.of()
                        : RhinoSemanticTokens.of(root, scopes, hostBindings);
            }
            return tokens;
        }

        @Override
        public SymbolInfo resolveAt(int offset) {
            return resolution == null ? null : resolution.resolveAt(offset);
        }

        @Override
        public TypeRef expectedTypeAt(int offset) {
            return resolution == null ? null : resolution.expectedTypeAt(offset);
        }

        @Override
        public List<SymbolInfo> membersOf(TypeRef type, int contextOffset) {
            return resolution == null ? List.of() : resolution.membersOf(type, contextOffset);
        }

        @Override
        public List<SymbolInfo> symbolsInScope(int offset) {
            return resolution == null ? List.of() : resolution.symbolsInScope(offset);
        }

        /**
         * What Alt+Enter offers here.
         *
         * <p>Answered on this side of the bridge, like every other question about the tree — a host-side
         * catalog would need a node-by-node view of the AST and a crossing per node walked. The
         * {@code CodeAction}s that come back are parent-first records carrying a {@code ChangeSet}, so the
         * answer crosses and the tree never does. @see JsQuickFixes</p>
         */
        @Override
        public List<CodeAction> codeActionsIn(int from, int to, CodeActionContext context) {
            return fixes == null ? List.of() : fixes.actionsIn(from, to);
        }

        @Override
        public void close() {
            // The tree is ordinary heap, so dropping the reference is the whole of it -- there is no
            // native handle here, unlike the grammar tier's parse trees. Idempotent by construction.
            root = null;
            scopes = null;
            tokens = null;
            // THE RESOLUTION GOES, THE INTEROP CACHE STAYS. That cache belongs to the analyser and is
            // shared by every open document -- closing it here would empty it whenever any one file was
            // edited, which is a re-analysis of every Java class the next keystroke mentions.
            resolution = null;
            fixes = null;
        }
    }
}
