package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>The semantic layer answers what a grammar cannot.</b>
 *
 * <p>Every assertion here is deliberately something tree-sitter is structurally unable to produce. A
 * grammar sees shape: it knows {@code count} is an identifier and stops. Whether that identifier is a
 * parameter, a local or a field is a question about <em>resolution</em>, and it is most of what makes
 * IntelliJ's colouring look richer than a lexer's (§14.2).</p>
 *
 * <p>Runs through the real {@link EngineClassLoader} over real band jars, so it is also a standing
 * check that the bridge carries `com.crystalgui.text.*` types with one identity — the day that stops
 * being true, these fail with `SyntaxToken cannot be cast to SyntaxToken`.</p>
 */
public class JavaAnalysisTest {

    private JavaEngine engine;
    private SourceAnalyzer analyzer;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());

        // THROUGH JavaEngine, not by building a loader here. An earlier version of this test assembled
        // its own and pointed it at the TEST output directory, so the child could not find the adapter,
        // fell back to the parent, and the adapter loaded somewhere ECJ does not exist -- surfacing as
        // NoClassDefFoundError for a class the file plainly imports. JavaEngine.instantiate now refuses
        // that outright, and there is one place that knows how to open an engine.
        engine = JavaEngine.open(band, source);
        analyzer = engine.analyzer();
    }

    @After
    public void closeEngine() throws IOException {
        if (engine != null) engine.close();
    }

    private SourceAnalyzer.Analysis analyze(String source) {
        return analyzer.analyze("Script", source, List.of(), 8, 42L);
    }

    /** The capture over an exact span — for a name that appears more than once in the fixture. */
    private static String captureAtIndex(List<SyntaxToken> tokens, int at, int length) {
        for (SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + length) return token.name();
        }
        return null;
    }

    /** The capture at the first occurrence of {@code needle}, or null. */
    private static String captureAt(List<SyntaxToken> tokens, String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) throw new IllegalArgumentException("no '" + needle + "' in the fixture");
        for (SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + needle.length()) return token.name();
        }
        return null;
    }

    // ── Semantic tokens: the four kinds of identifier a grammar sees as one ──────────────────────

    /**
     * A method <b>declaration</b>, a <b>static</b> call and an instance call are three colours.
     *
     * <p>This layer used to return {@code function.method} for every {@code IMethodBinding}, which made
     * every call on screen blue — and quietly undid {@code Queries.splitMethodDeclarationsFromCalls},
     * which exists so the grammar can tell the two apart. Semantic tokens <em>replace</em> grammar tokens,
     * so the coarser answer from the layer that is supposed to know more won every time, and the split
     * looked broken in the query rather than in the engine.</p>
     *
     * <p>{@code DEFAULT_FUNCTION_CALL} carries {@code baseAttributes="DEFAULT_IDENTIFIER"} in the exported
     * scheme — a call is deliberately <b>not</b> tinted — and {@code DEFAULT_STATIC_METHOD} differs from
     * the instance one by a slant alone.</p>
     */
    @Test
    public void aDeclarationAStaticCallAndAnInstanceCallAreThreeDifferentColours() {
        String source = ""
                + "public class Script {\n"
                + "    static int twice(int n) { return n * 2; }\n"
                + "    int run(String text) {\n"
                + "        return twice(text.length());\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();

            assertEquals("the declaration is the blue one", "function.method",
                    captureAtIndex(tokens, source.indexOf("twice"), 5));
            assertEquals("a static call takes the slant", "function.static",
                    captureAtIndex(tokens, source.lastIndexOf("twice"), 5));
            assertEquals("and an instance call is not tinted at all", "function.call",
                    captureAt(tokens, source, "length"));
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>A record component is a FIELD, and JDT calls it neither field nor parameter.</b>
     *
     * <p>So it fell through both tests to the local-variable catch-all, and a record's header drew its
     * components in the colour of a temporary inside a method body — the one thing they are not. They
     * are state the object carries, named once, readable from anywhere the object is.</p>
     *
     * <p>Decided from the tree rather than from {@code isRecordComponent()}, which arrived with Java 14:
     * this adapter is loaded by the OLDEST band's classloader, where calling it throws
     * {@code NoSuchMethodError} — a failure no test on a modern JVM can see.</p>
     */
    @Test
    public void aRecordComponentIsColouredAsAFieldRatherThanALocal() {
        String source = ""
                + "public class Script {\n"
                + "    record Message(String text, long id) {\n"
                + "        String shout() { return text; }\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 17, 1L);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            Assume.assumeTrue("this band's JDT does not parse records; skipping",
                    captureAtIndex(tokens, source.indexOf("Message"), 7) != null);

            assertEquals("the component's declaration", "variable.member",
                    captureAtIndex(tokens, source.indexOf("text"), 4));
            assertEquals("and reading it in the body, which already resolved to a field",
                    "variable.member", captureAtIndex(tokens, source.lastIndexOf("text"), 4));
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>A whole import path is one colour, and the last segment is the type it names.</b>
     *
     * <p>Three separate answers had to line up and one of them was missing. {@code util} resolves to a
     * package binding; {@code List} resolves to a type; and {@code java} — the leftmost segment — is a
     * bare qualifier that JDT gives <em>no</em> binding at all. A binding-only rule therefore coloured
     * two of the three and left the first as body text, so every import in the file read in two
     * colours. Positional, and only as a fallback, so the final segment keeps its real kind.</p>
     *
     * <p>{@code module} is tree-sitter's own name for this. The grammar cannot supply it: its rule for a
     * scoped identifier is a CAPITALISATION heuristic, blind to {@code com.crystalgui} by construction
     * and wrong on {@code Foo.bar}.</p>
     */
    @Test
    public void everySegmentOfAnImportPathIsColouredAndTheLastIsItsType() {
        String source = ""
                + "import java.util.List;\n"
                + "package_placeholder\n".replace("package_placeholder\n", "")
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            assertEquals("the leftmost segment has no binding and was left uncoloured", "module",
                    captureAtIndex(tokens, source.indexOf("java"), 4));
            assertEquals("and the one that does resolve must agree with it", "module",
                    captureAtIndex(tokens, source.indexOf("util"), 4));
            assertEquals("while the last segment is the type being imported", "type.interface",
                    captureAtIndex(tokens, source.indexOf("List"), 4));
        } finally {
            analysis.close();
        }
    }

    /**
     * An annotation's name is <b>metadata</b>, not a type reference.
     *
     * <p>{@code @SuppressWarnings} resolves to the annotation's type binding, so it came back as
     * {@code type} and took the default foreground — overwriting the grammar's own {@code @attribute}
     * capture. It was yellow in the documentation popup the entire time, because {@code JavaSignatures}
     * knows the name came from an annotation and says so; only the editor was wrong.</p>
     *
     * <p>The declaration is still a type, which is why this cannot be decided from the binding alone.</p>
     */
    @Test
    public void anAnnotationUseIsMetadataWhileItsDeclarationIsAType() {
        String source = ""
                + "public class Script {\n"
                + "    @SuppressWarnings(\"unused\")\n"
                + "    void hidden() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            // THE `@` INCLUDED. A SimpleName starts one character in, so marking the name alone left the
            // marker in the default colour beside a yellow name -- and the `@` is precisely what makes
            // the name metadata rather than a type reference.
            assertEquals("attribute", captureAt(tokens, source, "@SuppressWarnings"));
        } finally {
            analysis.close();
        }
    }

    /**
     * A local that is <b>written to again</b> is drawn differently from one that is not.
     *
     * <p>{@code DEFAULT_REASSIGNED_LOCAL_VARIABLE} keeps the kind's colour and adds an underline, because
     * what changed is not <em>what the name is</em> but that its value does not stay put. Decided by a
     * syntactic scan — the name on the left of an assignment, or under a {@code ++}/{@code --} — not by
     * dataflow.</p>
     */
    @Test
    public void aReassignedLocalIsMarkedAndASettledOneIsNot() {
        String source = ""
                + "public class Script {\n"
                + "    int run() {\n"
                + "        int settled = 1;\n"
                + "        int counter = 0;\n"
                + "        counter++;\n"
                + "        return settled + counter;\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            assertEquals("variable.reassigned", captureAt(tokens, source, "counter"));
            assertEquals("a local assigned once is an ordinary one", "variable",
                    captureAt(tokens, source, "settled"));
        } finally {
            analysis.close();
        }
    }

    /**
     * A local reached from <b>inside a lambda</b> is captured, and IntelliJ draws it as such.
     *
     * <p>{@code IMPLICIT_ANONYMOUS_CLASS_PARAMETER_ATTRIBUTES} gives it the field colour and an underline,
     * and that pairing is not arbitrary: a captured local is effectively final by language rule and
     * outlives the frame that declared it, so it behaves far more like a field than like a local.</p>
     *
     * <p>Positional, like the annotation case — the same variable is ordinary outside the lambda and
     * captured inside it, so no property of the binding can decide it.</p>
     */
    @Test
    public void aLocalReachedFromInsideALambdaIsCaptured() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    void run(List<String> items) {\n"
                + "        StringBuilder out = new StringBuilder();\n"
                + "        out.append(\"outside\");\n"
                + "        items.forEach(item -> out.append(item));\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            assertEquals("outside the lambda it is an ordinary local", "variable",
                    captureAtIndex(tokens, source.indexOf("out.append(\"outside\")"), 3));
            assertEquals("inside it, it was captured", "variable.captured",
                    captureAtIndex(tokens, source.indexOf("out.append(item)"), 3));
        } finally {
            analysis.close();
        }
    }

    /** A type parameter is not a type — {@code <E>} is a placeholder, and both references colour it. */
    @Test
    public void aTypeParameterIsNotAClass() {
        String source = ""
                + "public class Script<E> {\n"
                + "    E first(java.util.List<E> items) { return items.get(0); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            assertEquals("type.parameter", captureAtIndex(tokens, source.indexOf("<E>") + 1, 1));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aParameterALocalAndAFieldAreThreeDifferentColours() {
        String source = ""
                + "public class Script {\n"
                + "    int fieldName = 1;\n"
                + "    int method(int paramName) {\n"
                + "        int localName = paramName + fieldName;\n"
                + "        return localName;\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();

            // THE HEADLINE. Three identifiers, identical in every way a parser can see, three colours.
            assertEquals("variable.parameter", captureAt(tokens, source, "paramName"));
            assertEquals("variable", captureAt(tokens, source, "localName"));
            assertEquals("variable.member", captureAt(tokens, source, "fieldName"));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aStaticFinalFieldIsAConstantAndAPlainStaticFieldIsNot() {
        String source = ""
                + "public class Script {\n"
                + "    static final int MAX = 10;\n"
                + "    static int counter = 0;\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            assertEquals("constant", captureAt(tokens, source, "MAX"));
            assertEquals("a mutable static field is not a constant",
                    "variable.member", captureAt(tokens, source, "counter"));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void anEnumConstantIsAConstant() {
        String source = ""
                + "public class Script {\n"
                + "    enum Colour { CRIMSON }\n"
                + "    Colour pick() { return Colour.CRIMSON; }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            assertEquals("constant", captureAt(analysis.semanticTokens(), source, "CRIMSON"));
        } finally {
            analysis.close();
        }
    }

    /** Every capture emitted over the exact span of {@code needle}. */
    private static List<String> capturesAt(List<SyntaxToken> tokens, String source, String needle) {
        int at = source.indexOf(needle);
        List<String> names = new ArrayList<>();
        for (SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + needle.length()) names.add(token.name());
        }
        return names;
    }

    @Test
    public void anInstantiationIsCapturedAsTheCallItIs() {
        // `new ArrayList<>()` is a CALL, and both references draw it as one. The grammar cannot: its
        // `@constructor` capture covers a class name in `new X()` AND in `class X`, so the one
        // distinction that matters here -- declaration versus use -- is the one it has folded away.
        //
        // The popup had already been corrected to ask the ClassInstanceCreation while the highlighter
        // still asked the name, so the colour said "class" over a popup describing the constructor.
        String source = ""
                + "public class Script {\n"
                + "    Object run() { return new java.util.ArrayList<>(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            assertEquals("function.call", captureAt(tokens, source, "ArrayList"));
            // AND ONLY THE RIGHTMOST SEGMENT. The constructor is what the type's own name refers to; the
            // qualifiers in front of it are still the package they were in an import line.
            assertEquals("module", captureAt(tokens, source, "util"));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void instantiatingARecordIsACallToo() {
        // A record's canonical constructor is IMPLICIT -- nobody wrote it -- which is the shape that
        // already caught findDeclaringNode out once. `new ArrayList<>()` passing proves nothing about
        // it: that constructor is declared in a file, and this one exists only as a binding.
        String source = ""
                + "public class Script {\n"
                + "    record Circle(double radius) { }\n"
                + "    Object run() { return new Circle(1.5d); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 17, 42L);
        try {
            int use = source.indexOf("new Circle") + 4;
            String capture = captureAtIndex(analysis.semanticTokens(), use, "Circle".length());
            Assume.assumeTrue("this band's JDT does not parse records; skipping", capture != null);
            assertEquals("function.call", capture);
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aConstructorDeclarationIsStillADeclaration() {
        // The other half of the split above, and the reason the constructor case can be deleted from
        // captureFor rather than merely re-pointed: a constructor has a declaration form and a use form
        // exactly as any other method does, so the test methodCapture already applies is the right one.
        String source = ""
                + "public class Script {\n"
                + "    Script(int seed) { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            int declaration = source.indexOf("Script(int");
            assertEquals("function.method",
                    captureAtIndex(analysis.semanticTokens(), declaration, "Script".length()));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void anArgumentOfAnInstantiationIsNotTheConstructor() {
        // `new Message(text, Severity.INFO, 0L)` -- the argument sits inside the creation, and a walk
        // that climbs any qualified name reaches it. Everything downstream was then individually right
        // and collectively wrong: the constructor's kind and container, under the hovered word's name.
        String source = ""
                + "public class Script {\n"
                + "    enum Severity { INFO }\n"
                + "    static class Message { Message(String t, Severity s) { } }\n"
                + "    Object run() { return new Message(\"x\", Severity.INFO); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            int use = source.indexOf("Severity.INFO");
            assertEquals("type.enum", captureAtIndex(tokens, use, "Severity".length()));
            assertEquals("constant", captureAtIndex(tokens, use + 9, "INFO".length()));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void anUnresolvedNameIsMarkedAsSuch() {
        // The exit criterion, and the inline half of what the diagnostic also says. Underlined rather
        // than recoloured by the sheet, so the name keeps whatever colour said WHAT it is.
        String source = ""
                + "public class Script {\n"
                + "    int run() { return whoKnows + 1; }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            assertTrue("nothing marked the unresolvable name",
                    capturesAt(analysis.semanticTokens(), source, "whoKnows").contains("unresolved"));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aResolvableNameIsNotMarkedUnresolved() {
        // The direction that matters more: a false underline is a red mark on working code, while a
        // missed one is invisible. Package and import segments have no binding and are legitimately
        // unresolvable, so they must not be marked either.
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "    int size() { return names.size(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<SyntaxToken> tokens = analysis.semanticTokens();
            for (SyntaxToken token : tokens) {
                assertFalse("marked '" + source.substring(token.start(), token.end())
                        + "' unresolved in a file that compiles", "unresolved".equals(token.name()));
            }
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aDeprecatedNameIsMarkedALONGSIDEItsKindRatherThanInsteadOfIt() {
        // TWO TRUE THINGS ABOUT ONE RANGE. `old` is a method AND it is deprecated; the scheme draws
        // the first as a colour and the second as a strike-through, so emitting only one of them
        // throws away the piece of information the highlighter actually had.
        String source = ""
                + "public class Script {\n"
                + "    @Deprecated int old() { return 1; }\n"
                + "    int use() { return old(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            int callSite = source.indexOf("return old()") + 7;
            List<String> captures = new ArrayList<>();
            for (SyntaxToken token : analysis.semanticTokens()) {
                if (token.start() == callSite) captures.add(token.name());
            }
            assertTrue("no deprecation marker: " + captures, captures.contains("deprecated"));
            // `function.call`, not `function.method`: this is the CALL site, and a call is deliberately
            // not tinted -- DEFAULT_FUNCTION_CALL inherits DEFAULT_IDENTIFIER. What this test is about is
            // that a kind is emitted AT ALL beside the marker, not which kind it happens to be.
            assertTrue("the kind was lost: " + captures, captures.contains("function.call"));
        } finally {
            analysis.close();
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void anUnknownMethodIsReportedWithARangeAndASeverity() {
        String source = ""
                + "public class Script {\n"
                + "    int run() { return missingMethod(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<Diagnostic> problems = analysis.diagnostics();
            assertFalse("nothing reported for a call to a method that does not exist",
                    problems.isEmpty());

            Diagnostic first = problems.get(0);
            assertEquals(DiagnosticSeverity.ERROR, first.severity());
            assertEquals("java", first.source());
            assertNotNull("a diagnostic with no code cannot be suppressed or grouped", first.code());
            assertTrue(first.message(), first.message().contains("missingMethod"));

            // ROW 1, because the offending call is on the second line and TextPoint counts from 0.
            // JDT counts lines from 1, and getting that conversion wrong puts every squiggle one line
            // off -- which looks like the parser being confused rather than an arithmetic slip.
            assertEquals(1, first.start().row());
            assertTrue("the range is empty, so it would paint as nothing at all",
                    first.end().column() > first.start().column());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void wellFormedSourceReportsNothing() {
        SourceAnalyzer.Analysis analysis = analyze(
                "public class Script { int run() { return 1; } }\n");
        try {
            assertTrue(String.valueOf(analysis.diagnostics()), analysis.diagnostics().isEmpty());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void brokenSourceStillColoursTheNamesAroundTheBreak() {
        // §15.1's whole point, asserted rather than assumed. A script under the caret is nearly always
        // incomplete; an analyser that only answers for well-formed input answers exactly when it is
        // not needed. Without setBindingsRecovery every name in the file would lose its colour on the
        // keystroke that broke it -- which reads as flickering, not as invalidity.
        String source = ""
                + "public class Script {\n"
                + "    int fieldName = 1;\n"
                + "    int method(int paramName) {\n"
                + "        int localName = paramName.\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            assertFalse("a syntax error should be reported", analysis.diagnostics().isEmpty());

            List<SyntaxToken> tokens = analysis.semanticTokens();
            assertEquals("variable.member", captureAt(tokens, source, "fieldName"));
            assertEquals("variable.parameter", captureAt(tokens, source, "paramName"));
        } finally {
            analysis.close();
        }
    }

    // ── Resolution ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void resolveAtDescribesTheNameUnderTheCaret() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "    int size() { return names.size(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            // INSIDE the word, not at its first character: a caret sits within an identifier far more
            // often than before it, which is why the lookup asks for the covering node.
            int inside = source.indexOf("names.size()") + 2;
            SymbolInfo symbol = analysis.resolveAt(inside);

            assertNotNull("nothing resolved under the caret", symbol);
            assertEquals("names", symbol.name());
            assertEquals(SymbolKind.FIELD, symbol.kind());
            assertNotNull(symbol.type());
            // THE GENERIC ARGUMENT SURVIVES. If this came back as bare `List` the binding resolved
            // against something, but not against a real class library -- and completion after a dot
            // would then offer `E get(int)` instead of `String get(int)`.
            assertEquals("List<String>", symbol.type().displayName());
            assertEquals("java.util.List", symbol.type().qualifiedName());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void resolveAtFindsADeclarationInThisFile() {
        String source = ""
                + "public class Script {\n"
                + "    int counter = 0;\n"
                + "    int read() { return counter; }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo symbol = analysis.resolveAt(source.indexOf("return counter") + 8);
            assertNotNull(symbol);
            assertNotNull("no declaration site — go-to-definition would have nowhere to go",
                    symbol.declaration());
            assertTrue(symbol.declaration().isSameDocument());
            assertEquals("the declaration is on line 2", 1, symbol.declaration().start().row());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aMemberOfACompiledClassResolvesWithNoDeclarationSite() {
        // The ordinary case, not a failure: a class on the classpath has no source attached, and
        // inventing a location for it would be worse than saying nothing.
        SourceAnalyzer.Analysis analysis = analyze(
                "public class Script { int n() { return \"abc\".length(); } }\n");
        try {
            SymbolInfo symbol = analysis.resolveAt(
                    "public class Script { int n() { return \"abc\".".length() + 2);
            assertNotNull(symbol);
            assertEquals(SymbolKind.METHOD, symbol.kind());
            assertEquals("length", symbol.name());
            assertEquals("java.lang.String", symbol.container());
            assertNull(symbol.declaration());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aDeprecatedMemberIsMarkedAsSuch() {
        String source = ""
                + "public class Script {\n"
                + "    @Deprecated int old() { return 1; }\n"
                + "    int use() { return old(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo symbol = analysis.resolveAt(source.indexOf("return old()") + 8);
            assertNotNull(symbol);
            assertTrue("deprecation has a drawing contract and nothing set it",
                    symbol.is(SymbolModifier.DEPRECATED));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void resolveAtAnswersNothingWhereThereIsNoName() {
        SourceAnalyzer.Analysis analysis = analyze(
                "public class Script { int n() { return 1; } }\n");
        try {
            assertNull(analysis.resolveAt(0));
        } finally {
            analysis.close();
        }
    }

    // ── expectedTypeAt: the query completion ranking is built on ────────────────────────────────

    @Test
    public void theExpectedTypeOfAnAssignmentIsTheLeftHandSide() {
        String source = ""
                + "public class Script {\n"
                + "    void run() {\n"
                + "        String text;\n"
                + "        text = \"value\";\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            TypeRef expected = analysis.expectedTypeAt(source.indexOf("\"value\"") + 1);
            assertNotNull(expected);
            assertEquals("java.lang.String", expected.qualifiedName());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void theExpectedTypeOfAnArgumentIsTheParameterItFills() {
        // The one that makes a completion list feel intelligent: offering the right type FIRST inside
        // a call is IntelliJ's single largest quality difference against sorting by name.
        String source = ""
                + "public class Script {\n"
                + "    void take(int count, String label) { }\n"
                + "    void run() { take(1, \"x\"); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            TypeRef second = analysis.expectedTypeAt(source.indexOf("\"x\"") + 1);
            assertNotNull(second);
            assertEquals("java.lang.String", second.qualifiedName());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void theExpectedTypeOfAReturnIsTheMethodsReturnType() {
        String source = ""
                + "public class Script {\n"
                + "    String make() { return \"x\"; }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            TypeRef expected = analysis.expectedTypeAt(source.indexOf("\"x\"") + 1);
            assertNotNull(expected);
            assertEquals("java.lang.String", expected.qualifiedName());
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>A syntax error suppresses every optional problem in the file — this is ECJ, not us.</b>
     *
     * <p>Reported as a panel bug: the Problems view shows errors <em>or</em> warnings and never both, and
     * fixing one syntax error reveals four warnings that were "hidden by it". They were not hidden. They
     * were never reported: this fixture has an unused import <em>and</em> an unused local <em>and</em> a
     * syntax error, and ECJ answers with the two syntax errors and nothing else.</p>
     *
     * <p>The mechanism is structural rather than configurable. A unit that fails to parse has
     * {@code ignoreFurtherInvestigation} set, which skips {@code analyseCode()} — and unused locals come
     * out of flow analysis while unused imports come out of the same post-resolve pass. There is no
     * compiler option that turns this back on, because the analysis it would re-enable is being run over
     * a tree the parser has already said it does not trust.</p>
     *
     * <p>Both {@code javac} and ECJ take that view; IntelliJ appears not to only because its inspections
     * are a separate engine from its compiler, and it keeps the previous pass's results on screen while
     * the file is broken. Matching it here is a <b>product</b> decision — retain the last clean warnings
     * and track them through the edits, which {@code DecorationSet} could already do — and not a bug fix.
     * Recorded as a test so the day ECJ changes its mind, or we add a second pass, is a failing
     * assertion rather than a surprise.</p>
     */
    @Test
    public void aSyntaxErrorSuppressesEveryOptionalProblemInTheFile() {
        String source = ""
                + "import java.util.List;\n"          // never used -> would be a warning
                + "public class Script {\n"
                + "    void run() {\n"
                + "        int unusedLocal = 1;\n"     // never read -> would be a warning
                + "        String s = null;\n"
                + "        s.\n"                       // the syntax error
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<Diagnostic> found = analysis.diagnostics();
            long errors = found.stream().filter(d -> d.severity() == DiagnosticSeverity.ERROR).count();
            long lesser = found.stream().filter(d -> d.severity() != DiagnosticSeverity.ERROR).count();

            assertTrue("the fixture is meant to be broken: " + found, errors > 0);
            assertEquals("ECJ has started reporting optional problems for a unit that does not parse."
                            + " That is a behaviour change worth knowing about, not a broken test: " + found,
                    0, lesser);
        } finally {
            analysis.close();
        }
    }

    /** ...and the control: with the syntax error gone, the same file reports both of them. */
    @Test
    public void aFileThatParsesReportsItsOptionalProblems() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    void run() {\n"
                + "        int unusedLocal = 1;\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            List<Diagnostic> found = analysis.diagnostics();
            long lesser = found.stream().filter(d -> d.severity() != DiagnosticSeverity.ERROR).count();
            assertTrue("the warnings the broken fixture never got: " + found, lesser > 0);
        } finally {
            analysis.close();
        }
    }

    @Test
    public void theAnalysisCarriesTheVersionItDescribes() {
        SourceAnalyzer.Analysis analysis = analyze("public class Script { }\n");
        try {
            assertEquals(42L, analysis.version());
        } finally {
            analysis.close();
        }
    }
}
