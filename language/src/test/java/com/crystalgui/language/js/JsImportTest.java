package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.js.rhino.JsImports;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>{@code import a.b.C;} in a JavaScript script — blanked, then bound.</b>
 *
 * <p>Rhino <em>reserves</em> {@code import} and does not implement it, so a script carrying one dies in
 * the parser with "identifier is a reserved word" before anything of ours runs; and neither
 * {@code importClass} nor {@code importPackage} exists, because those come from {@code ImporterTopLevel}
 * and this engine builds its scope with a plain {@code initStandardObjects}. All measured against the
 * running band rather than assumed.</p>
 *
 * <p>So the statement is replaced with spaces of its own length and the name bound separately. The
 * length is the whole design: a rewriting preprocessor buys one keyword and pays for it with every
 * offset after the import, and this editor's diagnostics, squiggles, completion and go-to are all
 * offsets. {@code ScriptPrelude.blankImports} does exactly this on the Java side.</p>
 */
public class JsImportTest {

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue(EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue(JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @After
    public void clearThePolicy() {
        JsLanguage.restrictTo(null);
    }

    // ── The scan ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void blankingIsLengthPreserving() {
        String source = "import java.util.ArrayList;\nvar a = new ArrayList();\n";
        JsImports.Scanned scanned = JsImports.scan(source);

        assertEquals("every offset after an import must be the offset it already was",
                source.length(), scanned.source().length());
        assertEquals("and every row", source.split("\n", -1).length,
                scanned.source().split("\n", -1).length);
        assertTrue("the statement is still there", scanned.source().startsWith("     "));
        assertEquals(Map.of("ArrayList", "java.util.ArrayList"), scanned.imported());
    }

    /**
     * <b>A missing semicolon is not an error.</b>
     *
     * <p>JavaScript has automatic semicolon insertion and authors use it. Requiring the {@code ;} left
     * the line unblanked, so what the author saw was Rhino's own <em>"'import': ES modules are not
     * supported by Rhino 1.9.1"</em> — a message naming a feature they had not asked for, on a line whose
     * only fault was punctuation.</p>
     */
    @Test
    public void anImportWithoutASemicolonIsStillAnImport() {
        JsImports.Scanned scanned = JsImports.scan("import java.util.ArrayList\nvar a = 1;\n");
        assertEquals(Map.of("ArrayList", "java.util.ArrayList"), scanned.imported());
        assertEquals("blanking must not eat the newline, or every row below shifts",
                2, scanned.source().split("\n", -1).length - 1);
    }

    /**
     * <b>The import line is coloured like a Java one</b> — {@code module} per package segment, then
     * {@code type}.
     *
     * <p>Only the semantic pass can do it: the statement is blanked before the parser sees it, so there
     * is no node to colour from, and tree-sitter — still reading the raw text — parses {@code import
     * a.b.C;} as a broken ES module declaration and colours the line out of its error recovery.</p>
     */
    @Test
    public void anImportLineIsColouredLikeAJavaImport() {
        // BOTH TERMINATIONS. JavaScript inserts semicolons and authors rely on it, and the two shapes are
        // not equivalent to the grammar underneath -- without the terminator its error recovery reaches
        // into the body, which is what made `var` two rows below report as a variable. The colours have
        // to be the same either way; @see ImportSourceFilterTest for the half that makes them so.
        for (String terminator : List.of(";", "")) {
            String source = "import java.util.ArrayList" + terminator + "\nvar a = new ArrayList();\n";
            List<com.crystalgui.text.syntax.SyntaxToken> tokens =
                    JsLanguage.analyzer().analyze("Probe.js", source, 8).semanticTokens();

            assertEquals("java should be a package segment", "module",
                    captureOver(tokens, source, "java"));
            assertEquals("util should be a package segment", "module",
                    captureOver(tokens, source, "util"));
            assertEquals("and the last segment is the type", "type",
                    captureOver(tokens, source, "ArrayList"));
        }
    }

    /** The capture covering the first occurrence of {@code text}, or null. */
    private static String captureOver(List<com.crystalgui.text.syntax.SyntaxToken> tokens,
                                      String source, String text) {
        int at = source.indexOf(text);
        for (com.crystalgui.text.syntax.SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + text.length()) return token.name();
        }
        return null;
    }

    /** A commented-out import is not one — the pattern is anchored past leading whitespace only. */
    @Test
    public void aCommentedImportIsNotScanned() {
        assertTrue(JsImports.scan("// import java.util.ArrayList;\n").isEmpty());
    }

    /** A wildcard cannot be bound, so it is left for the parser to complain about rather than swallowed. */
    @Test
    public void aWildcardImportIsNotSilentlyAccepted() {
        JsImports.Scanned scanned = JsImports.scan("import java.util.*;\n");
        assertTrue("a wildcard was collected as though it bound something", scanned.isEmpty());
        assertEquals("and it was blanked, which would hide the error",
                "import java.util.*;\n", scanned.source());
    }

    // ── The runtime ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void anImportedClassIsInScopeAtRunTime() throws Throwable {
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            Object answer = host.run(host.compileScript("Probe.js",
                    "import java.util.ArrayList;\n"
                            + "var list = new ArrayList();\n"
                            + "list.add('one');\n"
                            + "list.size();\n", Map.of()), Map.of());
            assertEquals(1, ((Number) answer).intValue());
        } finally {
            host.close();
        }
    }

    /** The statics, not an instance — an import binds the CLASS, exactly as {@code Java.type} does. */
    @Test
    public void anImportedNameIsTheClassObject() throws Throwable {
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            Object answer = host.run(host.compileScript("Probe.js",
                    "import java.util.Collections;\n"
                            + "Collections.emptyList().size();\n", Map.of()), Map.of());
            assertEquals(0, ((Number) answer).intValue());
        } finally {
            host.close();
        }
    }

    /**
     * <b>An import is a reach, and the class filter sees it.</b>
     *
     * <p>A filter a script could walk past by writing the reach differently is not a filter — this is the
     * same gate {@code Java.type} goes through, on the same policy.</p>
     */
    @Test
    public void anImportIsSubjectToTheClassFilter() throws Throwable {
        JsLanguage.restrictTo(ScriptPolicy.denying(List.of("java.io")));
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            host.run(host.compileScript("Probe.js",
                    "import java.io.File;\nnew File('.').getName();\n", Map.of()), Map.of());
            fail("a refused class was reachable through an import");
        } catch (Throwable expected) {
            // The point of the test.
        } finally {
            host.close();
        }
    }

    // ── The editor ──────────────────────────────────────────────────────────────────────────────

    /** The half that makes it usable: no "reserved word" error, and the name is not unresolved. */
    @Test
    public void anImportAnalysesCleanlyAndTheNameResolves() {
        String source = "import java.util.ArrayList;\nvar list = new ArrayList();\n";
        Analysis analysis = JsLanguage.analyzer().analyze("Probe.js", source, 8);

        for (Diagnostic problem : analysis.diagnostics()) {
            assertTrue("an import was reported as a problem: " + problem.message(),
                    problem.severity() != DiagnosticSeverity.ERROR);
        }

        SymbolInfo imported = analysis.resolveAt(source.indexOf("new ArrayList") + 4);
        assertNotNull("the imported name did not resolve -- the editor and the runtime disagree",
                imported);
        assertEquals("ArrayList", imported.name());
    }

    /**
     * And the offsets are the author's.
     *
     * <p>The reason blanking is length-preserving: a rewrite would shift everything after the import, so
     * a diagnostic on the line below would point somewhere else entirely.
     */
    @Test
    public void anOffsetAfterAnImportIsUnmoved() {
        String source = "import java.util.ArrayList;\nvar list = new ArrayList();\n";
        Analysis analysis = JsLanguage.analyzer().analyze("Probe.js", source, 8);

        SymbolInfo declared = analysis.resolveAt(source.indexOf("var list") + 4);
        assertNotNull("the declaration after an import did not resolve at its own offset", declared);
        assertEquals("list", declared.name());
    }
}
