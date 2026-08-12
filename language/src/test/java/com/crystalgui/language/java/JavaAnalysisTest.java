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
